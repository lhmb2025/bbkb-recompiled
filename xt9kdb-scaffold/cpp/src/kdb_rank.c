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
#define RANK_N 32                     /* resample points */

typedef struct { float x, y; } RPt;

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

/* resample a polyline to RANK_N points at uniform arc length */
static int resample(const RPt* in, int n, RPt* out) {
    if (n <= 0) return 0;
    if (n == 1) { for (int i = 0; i < RANK_N; i++) out[i] = in[0]; return RANK_N; }
    float total = 0;
    for (int i = 1; i < n; i++) {
        float dx = in[i].x - in[i-1].x, dy = in[i].y - in[i-1].y;
        total += sqrtf(dx*dx + dy*dy);
    }
    if (total <= 0.0f) { for (int i = 0; i < RANK_N; i++) out[i] = in[0]; return RANK_N; }
    float step = total / (RANK_N - 1), acc = 0.0f;
    int seg = 1;
    RPt cur = in[0];
    out[0] = in[0];
    for (int k = 1; k < RANK_N; k++) {
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
        (void)acc;
    }
    return RANK_N;
}

/* the ideal polyline for a word: its letters' key centres over the LOADED model (case-folded;
 * non-letter chars — apostrophes etc. — are skipped, matching how one swipes "he'll") */
static int word_ideal(const ET9KdbLoaded* m, const char* word, RPt* out, int max) {
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

/* Score one candidate word against the raw path. Lower = better; HUGE if unscorable. */
float xt9kdb_rank_score(const ET9KdbLoaded* m, const Xt9DecParams* p,
                        const Xt9DecPt* path, ET9U32 pathN,
                        const char* word, int listIndex) {
    static RPt ideal[64], rp[RANK_N], ri[RANK_N];
    static RPt praw[2503];
    if (!m || !p || !path || !pathN || !word || !word[0]) return 1e9f;
    int in = word_ideal(m, word, ideal, 64);
    if (in == 0) return 1e9f;
    ET9U32 pn = pathN > 2503 ? 2503 : pathN;
    for (ET9U32 i = 0; i < pn; i++) { praw[i].x = path[i].x; praw[i].y = path[i].y; }
    resample(praw, (int)pn, rp);
    resample(ideal, in, ri);
    float shape = 0.0f;
    for (int i = 0; i < RANK_N; i++)
        shape += kdist(p, rp[i].x, rp[i].y, ri[i].x, ri[i].y);
    shape /= RANK_N;
    float anchors = kdist(p, praw[0].x, praw[0].y, ideal[0].x, ideal[0].y)
                  + kdist(p, praw[pn-1].x, praw[pn-1].y, ideal[in-1].x, ideal[in-1].y);
    /* arc lengths in key units for the length-mismatch term */
    float plen = 0.0f, ilen = 0.0f;
    for (ET9U32 i = 1; i < pn; i++)
        plen += kdist(p, praw[i].x, praw[i].y, praw[i-1].x, praw[i-1].y);
    for (int i = 1; i < in; i++)
        ilen += kdist(p, ideal[i].x, ideal[i].y, ideal[i-1].x, ideal[i-1].y);
    float lenMis = (ilen > 0.25f) ? fabsf(plen - ilen) / ilen
                                  : fabsf(plen - ilen);   /* near-zero ideal (single key): absolute */
    return shape + XT9_RANK_W_ANCHOR * anchors + XT9_RANK_W_LEN * lenMis
                 + XT9_RANK_W_LIST * (float)listIndex;
}
