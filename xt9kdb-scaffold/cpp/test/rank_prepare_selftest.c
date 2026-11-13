/* rank_prepare_selftest.c: the prepared ranker (xt9kdb_rank_prepare + letter table) must score
 * bit-identically to the per-candidate reference scorer kept below as the oracle; rankings, the
 * decline guard and the deposit pick compare these floats. Covers real corpora, a strided slice of
 * the recall table, case/apostrophe/missing-letter words, over-cap, one-point and zero-length paths,
 * duplicate letter keys (first wins), plus a negative control. Run by run_tests.sh from cpp/. */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <math.h>
#include "../src/kdb_freq_en.h"

/* The oracle: the reference one-candidate scorer, verbatim apart from names. */
#define REF_W_ANCHOR 1.5f
#define REF_W_LIST   0.005f
#define REF_W_LEN    0.6f
#define REF_N 32
typedef struct { float x, y; } RefPt;
static float ref_row_h_at(const Xt9DecParams* p, float y) {
    if (p->nrows == 0) return p->rady > 0 ? p->rady : 108.0f;
    for (int r = p->nrows - 1; r >= 0; r--)
        if (y >= p->row_top[r]) return p->row_h[r];
    return p->row_h[0];
}
static float ref_kdist(const Xt9DecParams* p, float ax, float ay, float bx, float by) {
    float dx = (ax - bx) / (p->radx > 0 ? p->radx : 108.0f);
    float rh = ref_row_h_at(p, 0.5f * (ay + by));
    float dy = (ay - by) / (rh > 0 ? rh : 108.0f);
    return sqrtf(dx * dx + dy * dy);
}
static int ref_resample(const RefPt* in, int n, RefPt* out) {
    if (n <= 0) return 0;
    if (n == 1) { for (int i = 0; i < REF_N; i++) out[i] = in[0]; return REF_N; }
    float total = 0;
    for (int i = 1; i < n; i++) {
        float dx = in[i].x - in[i-1].x, dy = in[i].y - in[i-1].y;
        total += sqrtf(dx*dx + dy*dy);
    }
    if (total <= 0.0f) { for (int i = 0; i < REF_N; i++) out[i] = in[0]; return REF_N; }
    float step = total / (REF_N - 1);
    int seg = 1;
    RefPt cur = in[0];
    out[0] = in[0];
    for (int k = 1; k < REF_N; k++) {
        float need = step;
        while (seg < n) {
            float dx = in[seg].x - cur.x, dy = in[seg].y - cur.y;
            float len = sqrtf(dx*dx + dy*dy);
            if (len >= need && len > 0.0f) {
                float f = need / len;
                cur.x += dx * f; cur.y += dy * f;
                break;
            }
            need -= len; cur = in[seg]; seg++;
        }
        out[k] = cur;
    }
    return REF_N;
}
static int ref_word_ideal(const ET9KdbLoaded* m, const char* word, RefPt* out, int max) {
    int n = 0;
    for (const char* c = word; *c && n < max; c++) {
        char lc = (char)tolower((unsigned char)*c);
        if (lc < 'a' || lc > 'z') continue;
        for (ET9U16 i = 0; i < m->keyCount; i++) {
            if (m->keys[i].keyCode == (ET9U16)lc) {
                out[n].x = (float)m->keys[i].cx; out[n].y = (float)m->keys[i].cy;
                n++;
                break;
            }
        }
    }
    return n;
}
static float ref_rank_score(const ET9KdbLoaded* m, const Xt9DecParams* p,
                            const Xt9DecPt* path, ET9U32 pathN, const char* word, int listIndex) {
    static RefPt ideal[64], rp[REF_N], ri[REF_N];
    static RefPt praw[2503];
    if (!m || !p || !path || !pathN || !word || !word[0]) return 1e9f;
    int in = ref_word_ideal(m, word, ideal, 64);
    if (in == 0) return 1e9f;
    ET9U32 pn = pathN > 2503 ? 2503 : pathN;
    for (ET9U32 i = 0; i < pn; i++) { praw[i].x = path[i].x; praw[i].y = path[i].y; }
    ref_resample(praw, (int)pn, rp);
    ref_resample(ideal, in, ri);
    float shape = 0.0f;
    for (int i = 0; i < REF_N; i++)
        shape += ref_kdist(p, rp[i].x, rp[i].y, ri[i].x, ri[i].y);
    shape /= REF_N;
    float anchors = ref_kdist(p, praw[0].x, praw[0].y, ideal[0].x, ideal[0].y)
                  + ref_kdist(p, praw[pn-1].x, praw[pn-1].y, ideal[in-1].x, ideal[in-1].y);
    float plen = 0.0f, ilen = 0.0f;
    for (ET9U32 i = 1; i < pn; i++)
        plen += ref_kdist(p, praw[i].x, praw[i].y, praw[i-1].x, praw[i-1].y);
    for (int i = 1; i < in; i++)
        ilen += ref_kdist(p, ideal[i].x, ideal[i].y, ideal[i-1].x, ideal[i-1].y);
    float lenMis = (ilen > 0.25f) ? fabsf(plen - ilen) / ilen : fabsf(plen - ilen);
    return shape + REF_W_ANCHOR * anchors + REF_W_LEN * lenMis + REF_W_LIST * (float)listIndex;
}

static int g_fail = 0;
static long g_compared = 0, g_mismatch = 0;
#define WORD_STRIDE 97   /* ~620 of the 59,878 table words per trace */
static const char* kExtra[] = { "He'll", "HELLO", "a", "", "don't", "x9z", "qqqqqqqq",
    "supercalifragilisticexpialidocioussupercalifragilisticexpialidocious" /* > 64 letters */ };

static int same_bits(float a, float b) { return memcmp(&a, &b, sizeof a) == 0; }

static void check_word(const ET9KdbLoaded* m, const Xt9DecParams* p, const Xt9RankPath* rp,
                       const Xt9DecPt* pts, ET9U32 n, const char* w, int li) {
    float ref = ref_rank_score(m, p, pts, n, w, li);
    float one = xt9kdb_rank_score(m, p, pts, n, w, li);
    float pre = xt9kdb_rank_score_prepared(rp, w, li);
    g_compared++;
    if (!same_bits(ref, one) || !same_bits(ref, pre)) {
        if (g_mismatch < 5)
            printf("  MISMATCH '%s' n=%u li=%d: ref=%a oneshot=%a prepared=%a\n",
                   w, (unsigned)n, li, (double)ref, (double)one, (double)pre);
        g_mismatch++;
    }
}

static void check_path(const ET9KdbLoaded* m, const Xt9DecParams* p, const Xt9DecPt* pts, ET9U32 n, int salt) {
    Xt9RankPath rp;
    xt9kdb_rank_prepare(&rp, m, p, pts, n);
    for (int w = salt % WORD_STRIDE; w < kFreqCountEn; w += WORD_STRIDE)
        check_word(m, p, &rp, pts, n, kFreqWordEn(w), w & 63);
    for (size_t i = 0; i < sizeof kExtra / sizeof kExtra[0]; i++)
        check_word(m, p, &rp, pts, n, kExtra[i], (int)i);
}

static unsigned char* read_file(const char* path, long* len) {
    FILE* fp = fopen(path, "rb");
    if (!fp) return 0;
    fseek(fp, 0, SEEK_END); *len = ftell(fp); fseek(fp, 0, SEEK_SET);
    unsigned char* b = (unsigned char*)malloc((size_t)*len + 1);
    if (b && fread(b, 1, (size_t)*len, fp) != (size_t)*len) { free(b); b = 0; }
    if (b) b[*len] = 0;
    fclose(fp);
    return b;
}

static Xt9DecPt g_pts[4096], g_prev[4096];
static ET9U32   g_prev_n;

/* Replays one corpus on one KDB; returns traces checked (-1 on a setup failure). */
static int run_corpus(const char* kdb, const char* corpus, int* negHits) {
    long len; unsigned char* xml = read_file(kdb, &len);
    static unsigned char ctx[0x10000];
    if (!xml) { printf("  FAIL: cannot read %s\n", kdb); return -1; }
    ET9STATUS st = XT9KDB(Load_XmlKDB)((ET9KDBInfoPtr)ctx, xml, (ET9U32)len);
    free(xml);
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx((ET9KDBInfoPtr)ctx);
    if (st != ET9STATUS_NONE || !m) { printf("  FAIL: %s did not load (0x%x)\n", kdb, (unsigned)st); return -1; }
    Xt9DecParams p;
    xt9kdb_decode_params_from_model(m, &p, 1);
    FILE* fp = fopen(corpus, "r");
    if (!fp) { printf("  FAIL: cannot open %s\n", corpus); return -1; }
    char line[512];
    int traces = 0;
    g_prev_n = 0;
    while (fgets(line, sizeof line, fp)) {
        int n; const char* q = strstr(line, " n=");
        if (strncmp(line, "TRACE", 5) || !q || sscanf(q, " n=%d", &n) != 1 || n <= 0 || n > 4096) continue;
        int ok = 1;
        for (int i = 0; i < n && ok; i++) {
            float x, y; unsigned t;
            if (!fgets(line, sizeof line, fp) || sscanf(line, "%f %f %u", &x, &y, &t) != 3) ok = 0;
            else { g_pts[i].x = x; g_pts[i].y = y; g_pts[i].t = t; }
        }
        if (!ok) { printf("  FAIL: %s truncated\n", corpus); fclose(fp); return -1; }
        check_path(m, &p, g_pts, (ET9U32)n, traces);
        /* negative control: this trace's prepared path vs the previous trace's reference */
        if (g_prev_n) {
            Xt9RankPath rp;
            xt9kdb_rank_prepare(&rp, m, &p, g_pts, (ET9U32)n);
            const char* w = kFreqWordEn((traces * 31) % kFreqCountEn);
            if (!same_bits(xt9kdb_rank_score_prepared(&rp, w, 0), ref_rank_score(m, &p, g_prev, g_prev_n, w, 0)))
                (*negHits)++;
        }
        memcpy(g_prev, g_pts, sizeof(Xt9DecPt) * (size_t)n); g_prev_n = (ET9U32)n;
        traces++;
    }
    fclose(fp);
    /* synthetic edges: over-cap path (only the first 2503 points are read), one point, zero length */
    static Xt9DecPt big[3000];
    for (int i = 0; i < 3000; i++) { big[i].x = 40.0f + (float)(i % 1000); big[i].y = 30.0f + (float)((i * 7) % 300); big[i].t = (ET9U32)i; }
    check_path(m, &p, big, 3000, 1);
    check_path(m, &p, big, 1, 2);
    Xt9DecPt still[5];
    for (int i = 0; i < 5; i++) still[i] = big[0];
    check_path(m, &p, still, 5, 3);
    printf("  %-28s %-24s %4d traces\n", strrchr(kdb, '/') + 1, strrchr(corpus, '/') + 1, traces);
    return traces;
}

/* Two keys carry 'e' (one carries nothing): the first wins, and a letter with no key is skipped. */
static void run_duplicate_key_model(void) {
    static ET9KdbKey keys[4];
    memset(keys, 0, sizeof keys);
    const struct { ET9U16 code; ET9S16 cx, cy; } spec[4] = { {'h', 100, 50}, {'e', 300, 60}, {'e', 700, 200}, {'l', 500, 150} };
    for (int i = 0; i < 4; i++) {
        keys[i].keyCode = spec[i].code; keys[i].type = 1;
        keys[i].cx = spec[i].cx; keys[i].cy = spec[i].cy;
        keys[i].left = (ET9S16)(spec[i].cx - 50); keys[i].right = (ET9S16)(spec[i].cx + 49);
        keys[i].top = (ET9S16)(spec[i].cy - 40); keys[i].bottom = (ET9S16)(spec[i].cy + 39);
    }
    ET9KdbLoaded m;
    memset(&m, 0, sizeof m);
    m.authoredWidth = 1080; m.authoredHeight = 300; m.keyCount = 4; m.keys = keys;
    Xt9DecParams p;
    xt9kdb_decode_params_from_model(&m, &p, 1);
    Xt9DecPt path[6] = { {100, 50, 0}, {200, 55, 10}, {300, 60, 20}, {400, 110, 30}, {500, 150, 40}, {505, 152, 50} };
    Xt9RankPath rp;
    xt9kdb_rank_prepare(&rp, &m, &p, path, 6);
    const char* words[] = { "hel", "hello", "eel", "help", "lee", "zzz" };
    for (int i = 0; i < 6; i++) check_word(&m, &p, &rp, path, 6, words[i], i);
    if (rp.letterKey['e' - 'a'] != 1) { printf("  FAIL: duplicate 'e' resolved to key %d, want 1\n", rp.letterKey['e' - 'a']); g_fail++; }
    printf("  duplicate-letter model        synthetic                   6 words\n");
}

int main(void) {
    printf("== prepared ranker == one-shot ranker == the original scorer, bit for bit (P-11) ==\n");
    const char* A = "../../app/src/main/assets/kdb/";
    char athena[256], athena_t[256], vkb[256];
    snprintf(athena, sizeof athena, "%skeypad_pkb_athena.xml", A);
    snprintf(athena_t, sizeof athena_t, "%skeypad_pkb_athena_test.xml", A);
    snprintf(vkb, sizeof vkb, "%sqwerty_vkb.xml", A);
    const struct { const char* kdb; const char* corpus; } runs[] = {
        { "test/qwerty_pkb_even.xml", "test/data/corpus_v2.txt" },
        { athena,   "test/data/parity_corpus.txt" },
        { athena_t, "test/data/w6_s1_corpus.txt" },
        { vkb,      "test/data/vkb_s2_corpus.txt" },
    };
    int traces = 0, neg = 0;
    for (size_t i = 0; i < sizeof runs / sizeof runs[0]; i++) {
        int t = run_corpus(runs[i].kdb, runs[i].corpus, &neg);
        if (t < 0) g_fail++; else traces += t;
    }
    run_duplicate_key_model();
    if (g_mismatch) { printf("  FAIL: %ld of %ld scores differ from the original scorer\n", g_mismatch, g_compared); g_fail++; }
    else printf("  OK: %ld scores over %d traces bit-identical to the original scorer\n", g_compared, traces);
    if (neg == 0) { printf("  FAIL: negative control never disagreed: the comparison cannot fail\n"); g_fail++; }
    else printf("  OK: negative control disagreed on %d of %d trace pairs\n", neg, traces - 4);
    printf("==== rank prepare self-test: %s ====\n", g_fail ? "FAILED" : "all passed");
    return g_fail ? 1 : 0;
}
