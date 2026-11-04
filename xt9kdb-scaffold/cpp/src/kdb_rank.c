/* kdb_rank.c — the OWNED gesture ranker (remediation log §8.7.6).
 *
 * Scores a candidate word against the raw swipe path by comparing the path to the word's IDEAL
 * key-path (the polyline through its letters' key centres) over the loaded model's real geometry.
 * This is the precision stage of the owned gesture pipeline; candidate recall stays with the
 * blob's tap-matcher over our fed sets (option-b evidence: the intended word is in the list,
 * mis-ranked — tap scoring has no notion of path shape).
 *
 * Cost model (lower = better), all distances in KEY UNITS so tolerance is per-row by
 * construction — x normalized by the column pitch (RADX), y by the LOCAL row-band height at the
 * sample. This is where the uneven-band insight (§8.6-8.7) finally lives: a 40px y-error inside a
 * 90px row costs what a 80px error costs inside a 180px row.
 *
 *   shape    mean distance between N arc-length-resampled points of path vs ideal polyline
 *   anchors  start-to-first-key + end-to-last-key distance (heavily weighted: gesture typing's
 *            strongest invariant is that swipes begin and end deliberately)
 *   listBias small monotone bonus for the generator's own order (carries LDB frequency + DLM)
 *
 * total = shape + XT9_RANK_W_ANCHOR * anchors + XT9_RANK_W_LIST * listIndex
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <math.h>
#include <string.h>
#include <ctype.h>

#ifndef XT9_RANK_W_ANCHOR
#define XT9_RANK_W_ANCHOR 1.5f
#endif
/* List-order bias: SMALL. The generator's order embeds TAP-ranking, which is exactly what the
 * owned ranker exists to overrule — at 0.02 it charged "hello" +0.34 for sitting deep in a
 * tap-ordered pool and flipped hello->help (§8.7.8). Kept tiny as a same-score tiebreak only. */
#ifndef XT9_RANK_W_LIST
#define XT9_RANK_W_LIST 0.005f
#endif
/* Path-length mismatch term: |len(swipe) - len(ideal)| / len(ideal), lengths in key units.
 * The 32-point resample NORMALIZES total length away, so without this term a short word whose
 * shape parallels a longer word's prefix (help vs hello: e->l->p vs e->l->l->o) can tie on
 * shape+anchors when the tail is o/p-ambiguous. The swipe's physical length still tells them
 * apart — hello's double-l + hook is a longer journey. (Live evidence: gate-rank2, hello->help
 * on 2/5 traces.) */
#ifndef XT9_RANK_W_LEN
#define XT9_RANK_W_LEN 0.6f
#endif
#define RANK_N XT9_RANK_N           /* resample points */
#define RANK_PATH_CAP 2503            /* path points the score has always read (praw[] cap) */

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

/* Point i of a polyline whose elements start with {float x, y} — both RPt and Xt9DecPt do — so
 * the raw path is resampled in place instead of being copied into a scratch buffer first. */
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

/* the ideal polyline for a word: its letters' key centres over the prepared model (case-folded;
 * non-letter chars — apostrophes etc. — are skipped, matching how one swipes "he'll") */
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

/* Everything in a score that depends on the gesture and not on the word, computed once: one
 * swipe is scored against up to ~1,560 candidates (64 pool + 1,500 recall), and the copy,
 * resample and arc length used to be redone for each (about 58% of a call at 182 points).
 * The letter->key table replaces word_ideal's per-letter key scan; it is rebuilt here, from the
 * model passed in, so it can never outlive an in-place KDB reload. */
void xt9kdb_rank_prepare(Xt9RankPath* rp, const ET9KdbLoaded* m, const Xt9DecParams* p,
                         const Xt9DecPt* path, ET9U32 pathN) {
    memset(rp, 0, sizeof *rp);
    if (!m || !p || !path || !pathN) return;
    rp->m = m; rp->p = p;
    for (int c = 0; c < 26; c++) rp->letterKey[c] = -1;
    for (ET9U16 i = 0; i < m->keyCount; i++) {   /* FIRST key per letter, as the old scan found */
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

/* Score one candidate against a prepared gesture. Lower = better; HUGE if unscorable. */
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
    /* arc lengths in key units for the length-mismatch term */
    float plen = rp->plen, ilen = 0.0f;
    for (int i = 1; i < in; i++)
        ilen += kdist(p, ideal[i].x, ideal[i].y, ideal[i-1].x, ideal[i-1].y);
    float lenMis = (ilen > 0.25f) ? fabsf(plen - ilen) / ilen
                                  : fabsf(plen - ilen);   /* near-zero ideal (single key): absolute */
    return shape + XT9_RANK_W_ANCHOR * anchors + XT9_RANK_W_LEN * lenMis
                 + XT9_RANK_W_LIST * (float)listIndex;
}

/* One-shot form for callers that score a single word (or a handful) per gesture. */
float xt9kdb_rank_score(const ET9KdbLoaded* m, const Xt9DecParams* p,
                        const Xt9DecPt* path, ET9U32 pathN,
                        const char* word, int listIndex) {
    if (!m || !p || !path || !pathN || !word || !word[0]) return 1e9f;
    Xt9RankPath rp;
    xt9kdb_rank_prepare(&rp, m, p, path, pathN);
    return xt9kdb_rank_score_prepared(&rp, word, listIndex);
}
