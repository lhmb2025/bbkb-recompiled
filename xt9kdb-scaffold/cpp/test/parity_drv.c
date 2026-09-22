/* parity_drv.c — replay REAL captured gesture traces through the owned decoder and compare its
 * per-trace position count against the blob's observed WSI count (the parity gate for
 * kdb_decode.c; remediation log §8.7 step 1).
 *
 * Corpus format (built from bbkb-captures by the extraction script; one file, N traces):
 *   TRACE src=<session> word=<blobWord> wsi=<blobPosCount> radx=<r> rady=<r> n=<samples>
 *   x y t          (n lines)
 * Sessions 3 and 5 ran the FLAT root layout (pre-scaled Y); everything else ran the athena
 * variant. The blob decoded each trace with the recorded RADY (probe overrides included), so
 * parity mode replays with exactly that value.
 *
 * Usage: parity_drv <corpus.txt> <athena_qwerty_pkb.xml> <root_qwerty_pkb.xml> [--per-row]
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char* read_file(const char* path, long* len) {
    FILE* fp = fopen(path, "rb");
    if (!fp) { fprintf(stderr, "cannot open %s\n", path); exit(2); }
    fseek(fp, 0, SEEK_END); *len = ftell(fp); fseek(fp, 0, SEEK_SET);
    unsigned char* b = (unsigned char*)malloc((size_t)*len + 1);
    fread(b, 1, (size_t)*len, fp); b[*len] = 0; fclose(fp);
    return b;
}

/* Keep a private copy of each loaded model: Load_XmlKDB publishes into one static g_model, so a
 * second load would overwrite the first. */
static ET9KdbLoaded g_models[2];
static ET9KdbKey    g_keycopy[2][64];   /* == kdb_load_xml.c XT9_MAX_KEYS */
static void load_model(int slot, const char* path) {
    long len; unsigned char* xml = read_file(path, &len);
    int dummy = 1;
    if (xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)len) != ET9STATUS_NONE) {
        fprintf(stderr, "load failed: %s\n", path); exit(2);
    }
    g_models[slot] = *g_kdb_model;
    memcpy(g_keycopy[slot], g_kdb_model->keys, sizeof(ET9KdbKey) * g_kdb_model->keyCount);
    g_models[slot].keys = g_keycopy[slot];
    free(xml);
    printf("model[%d] %s: %ux%u keys=%u\n", slot, path,
           g_models[slot].authoredWidth, g_models[slot].authoredHeight, g_models[slot].keyCount);
}

int main(int argc, char** argv) {
    if (argc < 4) { fprintf(stderr, "usage: parity_drv corpus athena.xml root.xml [--per-row]\n"); return 2; }
    int per_row = (argc > 4 && !strcmp(argv[4], "--per-row"));
    load_model(0, argv[2]);   /* athena variant */
    load_model(1, argv[3]);   /* flat root      */

    FILE* fp = fopen(argv[1], "r");
    if (!fp) { fprintf(stderr, "cannot open corpus\n"); return 2; }

    static Xt9DecPt pts[4096];
    static Xt9DecPos pos[64];
    char line[256], src[64], word[64];
    int n_tr = 0, n_exact = 0, sum_abs = 0;
    int hist[11] = {0};                      /* count-delta histogram, clamped to [-5..+5] */
    while (fgets(line, sizeof(line), fp)) {
        int wsi, radx, rady, n;
        if (sscanf(line, "TRACE src=%63s word=%63s wsi=%d radx=%d rady=%d n=%d",
                   src, word, &wsi, &radx, &rady, &n) != 6) continue;
        for (int i = 0; i < n; i++) {
            float x, y; unsigned t;
            if (!fgets(line, sizeof(line), fp) || sscanf(line, "%f %f %u", &x, &y, &t) != 3) {
                fprintf(stderr, "corpus truncated\n"); return 2;
            }
            pts[i].x = x; pts[i].y = y; pts[i].t = t;
        }
        const ET9KdbLoaded* m = (!strcmp(src, "session3") || !strcmp(src, "session5")) ? &g_models[1] : &g_models[0];
        Xt9DecParams p;
        xt9kdb_decode_params_from_model(m, &p, per_row);
        if (!per_row) p.rady = (float)rady;   /* parity: replay with the blob's live RADY */
        int np = xt9kdb_decode_gesture(pts, (ET9U32)n, m, &p, pos, 64);
        char spell[65]; int sp = 0;
        for (int i = 0; i < np && sp < 64; i++) {
            ET9SYMB s = pos[i].nsymb ? pos[i].symbs[0] : '?';
            spell[sp++] = (s >= 32 && s < 127) ? (char)s : '?';
        }
        spell[sp] = 0;
        int d = np - wsi;
        int hd = d < -5 ? -5 : d > 5 ? 5 : d;
        hist[hd + 5]++;
        if (d == 0) n_exact++;
        sum_abs += d < 0 ? -d : d;
        n_tr++;
        printf("  %-11s %-8s rady=%-3d blobWSI=%d own=%d %+d  spell='%s'\n",
               src, word, rady, wsi, np, d, spell);
    }
    fclose(fp);
    printf("\n==== parity: %d/%d exact-count, mean |delta| = %.2f ====\n",
           n_exact, n_tr, n_tr ? (double)sum_abs / n_tr : 0.0);
    printf("delta histogram [-5..+5]: ");
    for (int i = 0; i < 11; i++) printf("%d ", hist[i]);
    printf("\n");
    return 0;
}
