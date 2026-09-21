/* eval_drv.c — run the owned decoder on a LABELED collection corpus (intended words known) and
 * score toward the INTENDED word, not the blob's count. The offline proxy for "would AW recover
 * this?": after collapsing consecutive duplicate top-keys, is the intended word a SUBSEQUENCE of
 * the decoded spell? That fails exactly on the catastrophic under-segmentation (pool->pl drops
 * both o's) and wrong-endpoint cases, which are what break recovery; it tolerates the benign
 * over-segmentation AW's ambiguous-set matching absorbs.
 *
 * Reports, per mode (global / per-row): subsequence-preserved count, mean |countDelta| vs blob,
 * and the per-trace decoded spell for eyeballing. All these traces ran the athena variant.
 *
 * Usage: eval_drv <collection_corpus.txt> <athena_qwerty_pkb.xml> [--per-row] [--verbose]
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

/* collapse consecutive duplicate chars */
static void dedup(const char* in, char* out) {
    int w = 0; char last = 0;
    for (const char* p = in; *p; p++) { if (*p != last) { out[w++] = *p; last = *p; } }
    out[w] = 0;
}

/* Is intended letter c in this position's ambiguous set? (case-insensitive) */
static int in_set(const Xt9DecPos* p, char c) {
    char lc = c | 0x20;
    for (int i = 0; i < p->nsymb; i++)
        if ((p->symbs[i] | 0x20) == (ET9SYMB)lc) return 1;
    return 0;
}

/* Reachability: can the intended word be spelled by choosing one symbol from each of an ORDERED
 * subsequence of positions (positions skippable for over-segmentation)? This is the necessary
 * condition for AW's ambiguous-set matcher to recover the word — the offline recovery floor.
 * dp[j] after processing want[0..i): reachable-prefix lengths. Simple O(len*np) sweep. */
static int reachable(const char* want, const Xt9DecPos* pos, int np) {
    int wl = 0; while (want[wl]) wl++;
    /* dp[i] = can we have matched exactly i intended letters using positions seen so far */
    static char dp[66];
    for (int i = 0; i <= wl; i++) dp[i] = 0;
    dp[0] = 1;
    for (int j = 0; j < np; j++)
        for (int i = wl; i >= 1; i--)          /* reverse so each position matches at most one letter */
            if (dp[i-1] && in_set(&pos[j], want[i-1])) dp[i] = 1;
    return dp[wl];
}

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: eval_drv corpus athena.xml [--per-row] [--verbose]\n"); return 2; }
    int per_row = 0, verbose = 0, minimal = 0, dwell = 0;
    for (int i = 3; i < argc; i++) {
        if (!strcmp(argv[i], "--per-row")) per_row = 1;
        if (!strcmp(argv[i], "--verbose")) verbose = 1;
        if (!strcmp(argv[i], "--minimal")) minimal = 1;
        if (!strcmp(argv[i], "--dwell")) dwell = 7;
    }
    long len; unsigned char* xml = read_file(argv[2], &len);
    int dummy = 1;
    if (xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)len) != ET9STATUS_NONE) {
        fprintf(stderr, "load failed\n"); return 2;
    }
    const ET9KdbLoaded* m = g_kdb_model;
    printf("model %ux%u keys=%u  mode=%s\n", m->authoredWidth, m->authoredHeight, m->keyCount,
           per_row ? "PER-ROW" : "global");

    FILE* fp = fopen(argv[1], "r");
    if (!fp) { fprintf(stderr, "cannot open corpus\n"); return 2; }

    static Xt9DecPt pts[4096];
    static Xt9DecPos pos[64];
    char line[256], src[64], want[64], got[64];
    int n_tr = 0, n_subseq = 0, sum_abs = 0;
    while (fgets(line, sizeof(line), fp)) {
        int wsi, rady, n;
        if (sscanf(line, "TRACE src=%63s want=%63s got=%63s wsi=%d rady=%d n=%d",
                   src, want, got, &wsi, &rady, &n) != 6) continue;
        for (int i = 0; i < n; i++) {
            float x, y; unsigned t;
            if (!fgets(line, sizeof(line), fp) || sscanf(line, "%f %f %u", &x, &y, &t) != 3) {
                fprintf(stderr, "truncated\n"); return 2;
            }
            pts[i].x = x; pts[i].y = y; pts[i].t = t;
        }
        Xt9DecParams p;
        xt9kdb_decode_params_from_model(m, &p, per_row);
        p.feed_minimal = minimal;
        if (dwell) p.dwell_min = dwell;
        if (!per_row) p.rady = (float)rady;
        int np = xt9kdb_decode_gesture(pts, (ET9U32)n, m, &p, pos, 64);
        char spell[65]; int sp = 0;
        for (int i = 0; i < np && sp < 64; i++) {
            ET9SYMB s = pos[i].nsymb ? pos[i].symbs[0] : '?';
            spell[sp++] = (s >= 32 && s < 127) ? (char)s : '?';
        }
        spell[sp] = 0;
        char dd[65]; dedup(spell, dd);
        int keep = reachable(want, pos, np);
        int d = np - wsi;
        n_subseq += keep; sum_abs += d < 0 ? -d : d; n_tr++;
        if (verbose || !keep)
            printf("  %-10s got=%-10s blobN=%d own=%d top='%s' %s\n",
                   want, got, wsi, np, dd, keep ? "" : "<-- UNREACHABLE");
    }
    fclose(fp);
    printf("\n==== eval: %d/%d intended-preserved (subseq), mean |countDelta|=%.2f ====\n",
           n_subseq, n_tr, n_tr ? (double)sum_abs / n_tr : 0.0);
    return 0;
}
