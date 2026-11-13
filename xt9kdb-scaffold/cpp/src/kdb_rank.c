/* kdb_rank.c: owned gesture ranker. Scores a candidate against the swipe by comparing the path to
 * the polyline through its letters' key centres; recall stays with the blob's tap matcher. Distances
 * are in key units (x / RADX, y / local row-band height), so tolerance is per row. Lower is better:
 *   shape    mean distance between N arc-length-resampled points of path vs ideal polyline
 *   anchors  start-to-first-key + end-to-last-key distance (swipes begin and end deliberately)
 *   lenMis   relative path-length mismatch;  list  tiny bonus for the generator's order
 * total = shape + W_ANCHOR*anchors + W_LEN*lenMis + W_LIST*listIndex */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <math.h>
#include <string.h>
#include <ctype.h>

#ifndef XT9_RANK_W_ANCHOR
#define XT9_RANK_W_ANCHOR 1.5f
#endif
/* List-order bias: a tiebreak only. The order embeds tap ranking, which this ranker overrules;
 * 0.02 already flipped hello -> help. */
#ifndef XT9_RANK_W_LIST
#define XT9_RANK_W_LIST 0.005f
#endif
/* Path-length mismatch |len(swipe) - len(ideal)| / len(ideal), key units. Resampling normalizes
 * length away, so without it a word paralleling a longer word's prefix (help vs hello) can tie. */
#ifndef XT9_RANK_W_LEN
#define XT9_RANK_W_LEN 0.6f
#endif
#define RANK_N XT9_RANK_N           /* resample points */
#define RANK_PATH_CAP 2503            /* max path points read (record capacity) */

typedef Xt9RankPt RPt;

static float row_h_at(const Xt9DecParams* p, float y) {
    if (p->nrows == 0) return p->rady > 0 ? p->rady : 108.0f;
    for (int r = p->nrows - 1; r >= 0; r--)
        if (y >= p->row_top[r]) return p->row_h[r];
    return p->row_h[0];
}

/* key-unit distance between two points (x by pitch, y by the local band height at their mid-y) */
static float kdist(const Xt9DecParams* p, float ax, float ay, float bx, float by) {
    float dx = (ax - bx) / (p->radx > 0 ? p->radx : 108.0f);
    float rh = row_h_at(p, 0.5f * (ay + by));
    float dy = (ay - by) / (rh > 0 ? rh : 108.0f);
    return sqrtf(dx * dx + dy * dy);
}

/* Point i of a polyline whose elements start with {float x, y} (RPt and Xt9DecPt), so the raw
 * path is resampled in place without a copy. */
#define PT_X(b, st, i) (((const float*)((const char*)(b) + (size_t)(i) * (st)))[0])
#define PT_Y(b, st, i) (((const float*)((const char*)(b) + (size_t)(i) * (st)))[1])

/* resample a polyline to RANK_N points at uniform arc length */
static int resample(const void* in, size_t stride, int n, RPt* out) {
    if (n <= 0) return 0;
    RPt in0 = { PT_X(in, stride, 0), PT_Y(in, stride, 0) };
    if (n == 1) { for (int i = 0; i < RANK_N; i++) out[i] = in0; return RANK_N; }
    float total = 0;
    for (int i = 1; i < n; i++) {
        float dx = PT_X(in, stride, i) - PT_X(in, stride, i-1), dy = PT_Y(in, stride, i) - PT_Y(in, stride, i-1);
        total += sqrtf(dx*dx + dy*dy);
    }
    if (total <= 0.0f) { for (int i = 0; i < RANK_N; i++) out[i] = in0; return RANK_N; }
    float step = total / (RANK_N - 1);
    int seg = 1;
    RPt cur = in0;
    out[0] = in0;
    for (int k = 1; k < RANK_N; k++) {
        float need = step;
        while (seg < n) {
            float dx = PT_X(in, stride, seg) - cur.x, dy = PT_Y(in, stride, seg) - cur.y;
            float len = sqrtf(dx*dx + dy*dy);
            if (len >= need && len > 0.0f) {
                float f = need / len;
                cur.x += dx * f; cur.y += dy * f;
                break;
            }
            need -= len; cur.x = PT_X(in, stride, seg); cur.y = PT_Y(in, stride, seg); seg++;
        }
        out[k] = cur;
    }
    return RANK_N;
}

/* ideal polyline: the word's letters' key centres, case-folded; non-letters skipped ("he'll") */
static int word_ideal(const Xt9RankPath* rp, const char* word, RPt* out, int max) {
    int n = 0;
    for (const char* c = word; *c && n < max; c++) {
        char lc = (char)tolower((unsigned char)*c);
        if (lc < 'a' || lc > 'z') continue;
        int k = rp->letterKey[lc - 'a'];
        if (k < 0) continue;
        out[n].x = (float)rp->m->keys[k].cx; out[n].y = (float)rp->m->keys[k].cy;
        n++;
    }
    return n;
}

/* Precomputes the gesture-only parts of a score (one swipe meets up to ~1,560 candidates). The
 * letter->key table is rebuilt from m each time so it cannot outlive an in-place KDB reload. */
void xt9kdb_rank_prepare(Xt9RankPath* rp, const ET9KdbLoaded* m, const Xt9DecParams* p,
                         const Xt9DecPt* path, ET9U32 pathN) {
    memset(rp, 0, sizeof *rp);
    if (!m || !p || !path || !pathN) return;
    rp->m = m; rp->p = p;
    for (int c = 0; c < 26; c++) rp->letterKey[c] = -1;
    for (ET9U16 i = 0; i < m->keyCount; i++) {   /* first key per letter wins */
        ET9U16 kc = m->keys[i].keyCode;
        if (kc >= 'a' && kc <= 'z' && rp->letterKey[kc - 'a'] < 0) rp->letterKey[kc - 'a'] = (ET9S16)i;
    }
    ET9U32 pn = pathN > RANK_PATH_CAP ? RANK_PATH_CAP : pathN;
    resample(path, sizeof *path, (int)pn, rp->rp);
    rp->first.x = path[0].x;    rp->first.y = path[0].y;
    rp->last.x  = path[pn-1].x; rp->last.y  = path[pn-1].y;
    float plen = 0.0f;
    for (ET9U32 i = 1; i < pn; i++)
        plen += kdist(p, path[i].x, path[i].y, path[i-1].x, path[i-1].y);
    rp->plen = plen;
    rp->ok = 1;
}

/* Scores one candidate against a prepared gesture. Lower is better; 1e9 if unscorable. */
float xt9kdb_rank_score_prepared(const Xt9RankPath* rp, const char* word, int listIndex) {
    RPt ideal[64], ri[RANK_N];
    if (!rp || !rp->ok || !word || !word[0]) return 1e9f;
    const Xt9DecParams* p = rp->p;
    int in = word_ideal(rp, word, ideal, 64);
    if (in == 0) return 1e9f;
    resample(ideal, sizeof *ideal, in, ri);
    float shape = 0.0f;
    for (int i = 0; i < RANK_N; i++)
        shape += kdist(p, rp->rp[i].x, rp->rp[i].y, ri[i].x, ri[i].y);
    shape /= RANK_N;
    float anchors = kdist(p, rp->first.x, rp->first.y, ideal[0].x, ideal[0].y)
                  + kdist(p, rp->last.x, rp->last.y, ideal[in-1].x, ideal[in-1].y);
    float plen = rp->plen, ilen = 0.0f;
    for (int i = 1; i < in; i++)
        ilen += kdist(p, ideal[i].x, ideal[i].y, ideal[i-1].x, ideal[i-1].y);
    float lenMis = (ilen > 0.25f) ? fabsf(plen - ilen) / ilen
                                  : fabsf(plen - ilen);   /* near-zero ideal (single key): absolute */
    return shape + XT9_RANK_W_ANCHOR * anchors + XT9_RANK_W_LEN * lenMis
                 + XT9_RANK_W_LIST * (float)listIndex;
}

/* One-shot form for scoring a few words per gesture. */
float xt9kdb_rank_score(const ET9KdbLoaded* m, const Xt9DecParams* p,
                        const Xt9DecPt* path, ET9U32 pathN,
                        const char* word, int listIndex) {
    if (!m || !p || !path || !pathN || !word || !word[0]) return 1e9f;
    Xt9RankPath rp;
    xt9kdb_rank_prepare(&rp, m, p, path, pathN);
    return xt9kdb_rank_score_prepared(&rp, word, listIndex);
}
