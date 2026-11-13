/* getkeys_probe_drv.c: does repeated getKeys disturb gesture state? Replays a sensor-space corpus
 * through TouchStart/Move/End (one point per call, as the JNI shim does) with N getKeys-equivalent
 * reads between stages, and reports stored sample count, decoder recall, top-1 against a decoy pool
 * and an FNV-1a digest per arm. Tests the state hypothesis only, not main-thread timing.
 * Usage: getkeys_probe_drv <corpus> <kdb.xml> <wordlist> --n=<N> [--drop=K] [--verbose]
 * Driven by test/getkeys_probe.sh (N = 0, 1, 20, plus the --drop negative control). */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <time.h>

#define POOL_MAX   400
#define MAX_PTS    4096
#define MAX_WORDS  40000

/* Big enough for GetKeyPositions' ctx+0xfc30..36 reads; zeroed = the PKB configuration. */
#define CTX_SZ 0x10000

static unsigned char* g_ctx;
static int g_verbose;

/* The probe: the blob's JNI getKeys shim at 0x20fc0, minus the JNI object churn. */
#define SHIM_CAPACITY 0x50
#define SHIM_STRIDE   0x30

/* volatile so the seven per-key reads (SetIntField on device) are not optimized away */
static volatile unsigned g_sink;

static void getkeys_probe(void) {
    static unsigned char buf[SHIM_CAPACITY * SHIM_STRIDE];
    ET9U32 count = 0;
    /* 0x21008: bl ET9KDB_GetKeyPositions(x0=ctx, x1=sp+0x18, w2=0x50, x3=sp+0x14) */
    ET9STATUS st = xt9kdb_GetKeyPositions((ET9KDBInfoPtr)g_ctx, buf, SHIM_CAPACITY, &count);
    if (st != ET9STATUS_NONE) return;            /* 0x2100c: cbz w0, non-zero returns NULL */
    for (ET9U16 i = 0; i < count; i++) {
        const unsigned char* rec = buf + (size_t)i * SHIM_STRIDE;
        g_sink += *(const ET9U16*)(rec + 0x0a);  /* keyCode  0x21070 ldurh */
        g_sink += *(const ET9U32*)(rec + 0x20);  /* centerX  0x21090 ldur  */
        g_sink += *(const ET9U32*)(rec + 0x24);  /* centerY  0x210ac ldur  */
        g_sink += *(const ET9U16*)(rec + 0x28);  /* left     0x210c8 ldurh */
        g_sink += *(const ET9U16*)(rec + 0x2a);  /* top      0x210e4 ldurh */
        g_sink += *(const ET9U16*)(rec + 0x2c);  /* right    0x21100 ldurh */
        g_sink += *(const ET9U16*)(rec + 0x2e);  /* bottom   0x2111c ldrh, post-inc 0x30 */
    }
}

static void probe_n(int n) { for (int i = 0; i < n; i++) getkeys_probe(); }

static void tstart(float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    (void)xt9kdb_TouchStart((ET9KDBInfoPtr)g_ctx, 1, xs, ys, ts, 1, 0);
}
static void tmove(float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    (void)xt9kdb_TouchMove((ET9KDBInfoPtr)g_ctx, 1, xs, ys, ts, 1, 0);
}
static void tend(float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    (void)xt9kdb_TouchEnd((ET9KDBInfoPtr)g_ctx, 1, xs, ys, ts, 1);
}

static unsigned char* read_file(const char* path, long* len) {
    FILE* fp = fopen(path, "rb");
    if (!fp) { fprintf(stderr, "cannot open %s\n", path); exit(2); }
    fseek(fp, 0, SEEK_END); *len = ftell(fp); fseek(fp, 0, SEEK_SET);
    unsigned char* b = (unsigned char*)malloc((size_t)*len + 1);
    if (fread(b, 1, (size_t)*len, fp) != (size_t)*len) { fprintf(stderr, "short read %s\n", path); exit(2); }
    b[*len] = 0; fclose(fp);
    return b;
}

static char g_words[MAX_WORDS][16];
static int  g_nwords;

static void add_word(const char* tok) {
    size_t l = strlen(tok);
    if (l < 2 || l > 14 || g_nwords >= MAX_WORDS) return;
    char tmp[16];
    for (size_t i = 0; i < l; i++) {
        char c = (char)tolower((unsigned char)tok[i]);
        if (c < 'a' || c > 'z') return;
        tmp[i] = c;
    }
    tmp[l] = 0;
    for (int i = 0; i < g_nwords; i++) if (!strcmp(g_words[i], tmp)) return;   /* dedupe */
    memcpy(g_words[g_nwords++], tmp, l + 1);
}

/* Decoy vocabulary; w24_words.txt is one whitespace-separated line, so split on any whitespace. */
static void load_wordlist(const char* path) {
    long len; char* buf = (char*)read_file(path, &len);
    char* saveptr = 0;
    for (char* tok = strtok_r(buf, " \t\r\n", &saveptr); tok; tok = strtok_r(0, " \t\r\n", &saveptr))
        add_word(tok);
    free(buf);
}

/* Is the letter in this position's ambiguous set? (case-insensitive) */
static int in_set(const Xt9DecPos* p, char c) {
    char lc = c | 0x20;
    for (int i = 0; i < p->nsymb; i++)
        if ((p->symbs[i] | 0x20) == (ET9SYMB)lc) return 1;
    return 0;
}

/* Can the word be spelled by one symbol from each of an ordered subsequence of positions? The
 * recall floor (as in eval_drv); more sensitive to sample count than the resampling ranker. */
static int reachable(const char* want, const Xt9DecPos* pos, int np) {
    int wl = 0; while (want[wl]) wl++;
    static char dp[66];
    for (int i = 0; i <= wl; i++) dp[i] = 0;
    dp[0] = 1;
    for (int j = 0; j < np; j++)
        for (int i = wl; i >= 1; i--)
            if (dp[i-1] && in_set(&pos[j], want[i-1])) dp[i] = 1;
    return dp[wl];
}

static const ET9KdbKey* key_of(const ET9KdbLoaded* m, char lc) {
    for (ET9U16 i = 0; i < m->keyCount; i++)
        if (m->keys[i].keyCode == (ET9U16)lc) return &m->keys[i];
    return 0;
}

static unsigned long long g_digest = 1469598103934665603ULL;   /* FNV-1a 64 */
static void digest(const void* p, size_t n) {
    const unsigned char* b = (const unsigned char*)p;
    for (size_t i = 0; i < n; i++) { g_digest ^= b[i]; g_digest *= 1099511628211ULL; }
}

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: getkeys_probe_drv <corpus> <kdb.xml> <wordlist> --n=<N> [--verbose]\n");
        return 2;
    }
    int probes = 0, drop = 0;
    for (int i = 4; i < argc; i++) {
        if (!strncmp(argv[i], "--n=", 4)) probes = atoi(argv[i] + 4);
        else if (!strncmp(argv[i], "--drop=", 7)) drop = atoi(argv[i] + 7);
        else if (!strcmp(argv[i], "--verbose")) g_verbose = 1;
    }
    if (probes < 0) { fprintf(stderr, "--n must be >= 0\n"); return 2; }
    if (drop < 0) { fprintf(stderr, "--drop must be >= 0\n"); return 2; }

    g_ctx = (unsigned char*)calloc(1, CTX_SZ);
    if (!g_ctx) { fprintf(stderr, "ctx alloc failed\n"); return 2; }

    long len; unsigned char* xml = read_file(argv[2], &len);
    if (xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)g_ctx, xml, (ET9U32)len) != ET9STATUS_NONE) {
        fprintf(stderr, "load failed\n"); return 2;
    }
    const ET9KdbLoaded* m = g_kdb_model;
    load_wordlist(argv[3]);

    /* fold the corpus labels into the vocabulary so every intended word is rankable */
    {
        FILE* lp = fopen(argv[1], "r");
        if (!lp) { fprintf(stderr, "cannot open corpus\n"); return 2; }
        char line[256], src[64], want[64], got[64];
        int wsi, rady, n;
        while (fgets(line, sizeof(line), lp))
            if (sscanf(line, "TRACE src=%63s want=%63s got=%63s wsi=%d rady=%d n=%d",
                       src, want, got, &wsi, &rady, &n) == 6) add_word(want);
        fclose(lp);
    }

    printf("model %ux%u keys=%u  vocab=%d  probes-per-stage N=%d  drop-every=%d\n",
           m->authoredWidth, m->authoredHeight, m->keyCount, g_nwords, probes, drop);

    FILE* fp = fopen(argv[1], "r");
    if (!fp) { fprintf(stderr, "cannot open corpus\n"); return 2; }

    static Xt9DecPt raw[MAX_PTS];      /* corpus points, sensor space */
    static Xt9DecPt sp[MAX_PTS];       /* stored path, authored space */
    static Xt9DecPos pos[64];
    char line[256], src[64], want[64], got[64];

    Xt9DecParams p;
    xt9kdb_decode_params_from_model(m, &p, 1);   /* per-row, the shipped default (ownprow=1) */
    p.feed_minimal = 1;                          /* ownfeedmin default */
    p.cross_min    = 6;                          /* owncross default */

    int n_tr = 0, top1 = 0, top3 = 0, n_reach = 0;
    long long sum_stored = 0, sum_raw = 0;
    ET9U32 min_stored = 0xffffffffu, max_stored = 0;
    double sum_ms = 0.0;

    while (fgets(line, sizeof(line), fp)) {
        int wsi, rady, n;
        if (sscanf(line, "TRACE src=%63s want=%63s got=%63s wsi=%d rady=%d n=%d",
                   src, want, got, &wsi, &rady, &n) != 6) continue;
        if (n <= 0 || n > MAX_PTS) { fprintf(stderr, "bad point count %d\n", n); return 2; }
        for (int i = 0; i < n; i++) {
            float x, y; unsigned t;
            if (!fgets(line, sizeof(line), fp) || sscanf(line, "%f %f %u", &x, &y, &t) != 3) {
                fprintf(stderr, "truncated corpus\n"); return 2;
            }
            raw[i].x = x; raw[i].y = y; raw[i].t = t;
        }

        /* replay through the real ABI, probing between every stage */
        struct timespec t0, t1;
        clock_gettime(CLOCK_MONOTONIC, &t0);
        tstart(raw[0].x, raw[0].y, raw[0].t);
        probe_n(probes);
        for (int i = 1; i < n - 1; i++) {
            /* negative control (--drop=K): skip every Kth TouchMove, proving the rig can see
             * dropped samples */
            if (drop && (i % drop) == 0) continue;
            tmove(raw[i].x, raw[i].y, raw[i].t);
            probe_n(probes);
        }
        if (n > 1) tend(raw[n-1].x, raw[n-1].y, raw[n-1].t);
        else       tend(raw[0].x,   raw[0].y,   raw[0].t);
        clock_gettime(CLOCK_MONOTONIC, &t1);
        sum_ms += (double)(t1.tv_sec - t0.tv_sec) * 1e3 + (double)(t1.tv_nsec - t0.tv_nsec) / 1e6;

        /* decode the stored path as owned_pst_v2 does */
        ET9U32 gn = 0;
        const ET9KdbSample* stored = xt9kdb_stored_path(&gn);
        if (gn > MAX_PTS) gn = MAX_PTS;
        for (ET9U32 i = 0; i < gn; i++) {
            float fx = stored[i].x < 0.0f ? 0.0f : stored[i].x;
            float fy = stored[i].y < 0.0f ? 0.0f : stored[i].y;
            float ax, ay;
            xt9kdb_sensor_to_authored((float)(ET9U32)fx, (float)(ET9U32)fy, &ax, &ay);
            sp[i].x = ax; sp[i].y = ay; sp[i].t = stored[i].timestamp;
        }
        int np = xt9kdb_decode_gesture(sp, gn, m, &p, pos, 64);

        char spell[65]; int spn = 0;
        for (int i = 0; i < np && spn < 64; i++) {
            ET9SYMB s = pos[i].nsymb ? pos[i].symbs[0] : '?';
            spell[spn++] = (s >= 32 && s < 127) ? (char)s : '?';
        }
        spell[spn] = 0;

        /* rank the intended word against a decoy pool */
        float sx = sp[0].x, sy = sp[0].y, ex = sp[gn ? gn-1 : 0].x, ey = sp[gn ? gn-1 : 0].y;
        int pool = 0, better = 0;
        float wantScore = xt9kdb_rank_score(m, &p, sp, gn, want, 0);
        float bestScore = wantScore; const char* best = want;
        for (int w = 0; w < g_nwords && pool < POOL_MAX; w++) {
            const char* cw = g_words[w];
            if (!strcmp(cw, want)) continue;
            const ET9KdbKey* kf = key_of(m, cw[0]);
            const ET9KdbKey* kl = key_of(m, cw[strlen(cw) - 1]);
            if (!kf || !kl) continue;
            float dfx = (sx - (float)kf->cx) / 108.0f, dfy = (sy - (float)kf->cy) / 150.0f;
            float dlx = (ex - (float)kl->cx) / 108.0f, dly = (ey - (float)kl->cy) / 150.0f;
            if (dfx*dfx + dfy*dfy > 2.25f) continue;   /* start within ~1.5 keys */
            if (dlx*dlx + dly*dly > 2.25f) continue;   /* end within ~1.5 keys   */
            pool++;
            float s = xt9kdb_rank_score(m, &p, sp, gn, cw, 0);
            if (s < wantScore) { better++; if (s < bestScore) { bestScore = s; best = cw; } }
        }
        int rank = better + 1;
        int reach = reachable(want, pos, np);

        n_tr++;
        n_reach += reach;
        sum_raw += n; sum_stored += gn;
        if (gn < min_stored) min_stored = gn;
        if (gn > max_stored) max_stored = gn;
        if (rank == 1) top1++;
        if (rank <= 3) top3++;

        {
            ET9U32 g = gn; int r = rank, ps = pool, npv = np, rc = reach;
            digest(want, strlen(want)); digest(&g, sizeof g); digest(spell, strlen(spell));
            digest(&npv, sizeof npv); digest(&ps, sizeof ps); digest(&r, sizeof r);
            digest(&rc, sizeof rc);
        }
        if (g_verbose)
            printf("  %-12s raw=%-4d stored=%-4u np=%-3d rank=%-3d pool=%-3d reach=%d top='%s' best='%s'\n",
                   want, n, gn, np, rank, pool, reach, spell, best);
    }
    fclose(fp);

    printf("\n==== N=%d drop=%d  traces=%d  top1=%d/%d (%.2f%%)  top3=%d/%d (%.2f%%)\n",
           probes, drop, n_tr, top1, n_tr, n_tr ? 100.0 * top1 / n_tr : 0.0,
           top3, n_tr, n_tr ? 100.0 * top3 / n_tr : 0.0);
    printf("==== decoder recall (intended word reachable): %d/%d (%.2f%%)\n",
           n_reach, n_tr, n_tr ? 100.0 * n_reach / n_tr : 0.0);
    printf("==== stored samples: mean %.4f  min %u  max %u  total %lld  (raw corpus total %lld)\n",
           n_tr ? (double)sum_stored / n_tr : 0.0, min_stored, max_stored, sum_stored, sum_raw);
    printf("==== getKeys probes issued: %lld   replay wall %.1f ms\n",
           (long long)probes * (sum_raw - n_tr), sum_ms);
    printf("==== digest %016llx\n", g_digest);
    return 0;
}
