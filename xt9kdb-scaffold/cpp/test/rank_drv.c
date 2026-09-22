/* rank_drv.c — offline eval for the owned gesture ranker (kdb_rank.c, §8.7.6).
 *
 * For each labeled trace (collection corpus): build a decoy candidate pool from a wordlist,
 * filtered the way the blob's generator behaves (first letter within ~1.5 keys of the path
 * start, last letter within ~1.5 keys of the path end, length 2..14), capped at POOL_MAX; the
 * intended word is inserted (it is what the generator produced in the option-b test). Rank all
 * candidates with xt9kdb_rank_score and report the intended word's rank.
 *
 * Metric: top-1 / top-3 rate over the corpus. This is the precision stage evaluated with recall
 * held ideal; the on-device generator's real recall is measured separately.
 *
 * Usage: rank_drv <collection_corpus.txt> <athena.xml> <wordlist> [--verbose]
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>

#define POOL_MAX 400

static unsigned char* read_file(const char* path, long* len) {
    FILE* fp = fopen(path, "rb");
    if (!fp) { fprintf(stderr, "cannot open %s\n", path); exit(2); }
    fseek(fp, 0, SEEK_END); *len = ftell(fp); fseek(fp, 0, SEEK_SET);
    unsigned char* b = (unsigned char*)malloc((size_t)*len + 1);
    fread(b, 1, (size_t)*len, fp); b[*len] = 0; fclose(fp);
    return b;
}

static const ET9KdbKey* key_of(const ET9KdbLoaded* m, char lc) {
    for (ET9U16 i = 0; i < m->keyCount; i++)
        if (m->keys[i].keyCode == (ET9U16)lc) return &m->keys[i];
    return 0;
}

/* wordlist storage */
static char  g_words[140000][16];
static int   g_nwords;

static void load_wordlist(const char* path) {
    long len; char* buf = (char*)read_file(path, &len);
    char* saveptr = 0;
    for (char* tok = strtok_r(buf, "\r\n", &saveptr); tok && g_nwords < 140000;
         tok = strtok_r(0, "\r\n", &saveptr)) {
        size_t l = strlen(tok);
        if (l < 2 || l > 14) continue;
        int ok = 1;
        for (size_t i = 0; i < l; i++) {
            char c = (char)tolower((unsigned char)tok[i]);
            if (c < 'a' || c > 'z') { ok = 0; break; }
            g_words[g_nwords][i] = c;
        }
        if (!ok) continue;
        g_words[g_nwords][l] = 0;
        g_nwords++;
    }
    free(buf);
}

int main(int argc, char** argv) {
    if (argc < 4) { fprintf(stderr, "usage: rank_drv corpus athena.xml wordlist [--verbose]\n"); return 2; }
    int verbose = argc > 4 && !strcmp(argv[4], "--verbose");
    long len; unsigned char* xml = read_file(argv[2], &len);
    int dummy = 1;
    if (xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)len) != ET9STATUS_NONE) {
        fprintf(stderr, "load failed\n"); return 2;
    }
    const ET9KdbLoaded* m = g_kdb_model;
    load_wordlist(argv[3]);
    printf("model %ux%u, wordlist %d words\n", m->authoredWidth, m->authoredHeight, g_nwords);

    FILE* fp = fopen(argv[1], "r");
    if (!fp) { fprintf(stderr, "cannot open corpus\n"); return 2; }

    static Xt9DecPt pts[4096];
    char line[256], src[64], want[64], got[64];
    int n_tr = 0, top1 = 0, top3 = 0;
    Xt9DecParams p;
    xt9kdb_decode_params_from_model(m, &p, 1);

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
        /* decoy pool: first/last letters near the path endpoints (generator-like) */
        float sx = pts[0].x, sy = pts[0].y, ex = pts[n-1].x, ey = pts[n-1].y;
        const char* pool[POOL_MAX + 1];
        int np = 0;
        for (int w = 0; w < g_nwords && np < POOL_MAX; w++) {
            const char* cw = g_words[w];
            if (!strcmp(cw, want)) continue;
            const ET9KdbKey* kf = key_of(m, cw[0]);
            const ET9KdbKey* kl = key_of(m, cw[strlen(cw) - 1]);
            if (!kf || !kl) continue;
            float dfx = (sx - kf->cx) / 108.0f, dfy = (sy - kf->cy) / 150.0f;
            float dlx = (ex - kl->cx) / 108.0f, dly = (ey - kl->cy) / 150.0f;
            if (dfx*dfx + dfy*dfy > 2.25f) continue;   /* start within ~1.5 keys */
            if (dlx*dlx + dly*dly > 2.25f) continue;   /* end within ~1.5 keys   */
            pool[np++] = cw;
        }
        pool[np++] = want;   /* recall held ideal */
        /* rank */
        float wantScore = xt9kdb_rank_score(m, &p, pts, (ET9U32)n, want, 0);
        int better = 0;
        const char* best = want; float bestScore = wantScore;
        for (int c = 0; c < np - 1; c++) {
            float s = xt9kdb_rank_score(m, &p, pts, (ET9U32)n, pool[c], 0);
            if (s < wantScore) { better++; if (s < bestScore) { bestScore = s; best = pool[c]; } }
        }
        int rank = better + 1;
        n_tr++;
        if (rank == 1) top1++;
        if (rank <= 3) top3++;
        if (verbose || rank > 3)
            printf("  %-10s rank=%-3d pool=%-3d best='%s'\n", want, rank, np, best);
    }
    fclose(fp);
    printf("\n==== rank eval: top1 %d/%d  top3 %d/%d ====\n", top1, n_tr, top3, n_tr);
    return 0;
}
