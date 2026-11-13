/* kdb_decode.c: owned gesture decoder (ET9KDB_ProcessStoredTouch, spec ../TRACE_RESULT_REGION.md)
 * plus per-row vertical thresholds. The blob derives every threshold from one RADY (home-row height),
 * which no value satisfies on 90/180/180 bands (180 misses short hooks, 108 over-segments transits).
 *   cluster width cut RADY/0.9, height cut RADY/2 [0xc74c4]; centroid per cluster [0xc799c]
 *   key = bbox containment + nearest centre [0xc857c]; path order, no sort [0xc9c00+]; 1-8 symbols [0xc8f90]
 * Global mode (per_row=0) must match the blob's segmentation (test/parity_drv.c). */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <math.h>
#include <string.h>

/* Cos of the turn between adjacent centroid legs below which a cluster is an inflection; stands in
 * for the blob's (RADY/3)*pi arc term. Parity is insensitive across 0.3-0.7. */
#ifndef XT9_DEC_TURN_COS
#define XT9_DEC_TURN_COS 0.5f
#endif

/* Start position = mean of samples within this arc (px) of touchdown, not the first cluster's
 * centroid, which can grow a key wide and drift onto the neighbour (pool -> "oll"). 0 disables. */
#ifndef XT9_DEC_START_ARC
#define XT9_DEC_START_ARC 45.0f
#endif

/* Waypoint threshold in key units (max perpendicular deviation of gap samples from the chord).
 * Conservative: lower values recover shallow detours (pool's o) but break hello/done. */
#ifndef XT9_DEC_WAYPOINT_DEV
#define XT9_DEC_WAYPOINT_DEV 0.65f
#endif

/* Min samples in a tight kept cluster to read it as a dwell (double letter); 0 disables.
 * Off by default: it over-fires on slow pass-through clusters. */
#ifndef XT9_DEC_DWELL_MIN
#define XT9_DEC_DWELL_MIN 0
#endif

/* Key-crossing recovery: min samples in a distinct key cell inside a minimal-feed gap to emit
 * that key. 0 disables (debug.et9.owncross). */
#ifndef XT9_DEC_CROSS_MIN
#define XT9_DEC_CROSS_MIN 0
#endif

void xt9kdb_decode_params_from_model(const ET9KdbLoaded* m, Xt9DecParams* p, int per_row) {
    memset(p, 0, sizeof(*p));
    p->per_row = per_row;
    p->wp_dev = XT9_DEC_WAYPOINT_DEV;
    int maxW = 0;
    /* distinct row bands by key top, ascending (as in kdb_nkl.c) */
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        int kw = (m->keys[i].right - m->keys[i].left) + 1;
        if (kw > maxW) maxW = kw;
        int t = m->keys[i].top, hh = (m->keys[i].bottom - m->keys[i].top) + 1, seen = 0;
        for (int r = 0; r < p->nrows; r++) if ((int)p->row_top[r] == t) { seen = 1; break; }
        if (!seen && p->nrows < 8) { p->row_top[p->nrows] = (float)t; p->row_h[p->nrows] = (float)hh; p->nrows++; }
    }
    for (int a = 0; a < p->nrows; a++) for (int b = a + 1; b < p->nrows; b++)
        if (p->row_top[b] < p->row_top[a]) {
            float t = p->row_top[a]; p->row_top[a] = p->row_top[b]; p->row_top[b] = t;
            float h = p->row_h[a];  p->row_h[a]  = p->row_h[b];  p->row_h[b]  = h;
        }
    p->radx = (float)(maxW > 0 ? maxW : 108);
    /* global RADY = home-row height */
    p->rady = p->nrows > 1 ? p->row_h[1] : (p->nrows ? p->row_h[0] : 108.0f);
    p->dwell_min = XT9_DEC_DWELL_MIN;
    p->cross_min = XT9_DEC_CROSS_MIN;
}

/* Vertical scale at y: containing band's height in per-row mode (nearest band outside), else RADY. */
static float rady_at(const Xt9DecParams* p, float y) {
    if (!p->per_row || p->nrows == 0) return p->rady;
    for (int r = p->nrows - 1; r >= 0; r--)
        if (y >= p->row_top[r]) return p->row_h[r];
    return p->row_h[0];
}

/* Where per-row reads its vertical scale during cluster growth:
 *   START    band of the cluster's first point
 *   POINT    band of the point being tested
 *   MINSPAN  smallest band the growing cluster has touched (most conservative) */
#ifndef XT9_DEC_ROWBAND
#define XT9_DEC_ROWBAND POINT
#endif
#define ROWBAND_START   0
#define ROWBAND_POINT   1
#define ROWBAND_MINSPAN 2
#define ROWBAND_SEL(x)  ROWBAND_##x
#define XT9_ROWBAND     ROWBAND_SEL(XT9_DEC_ROWBAND)

int xt9kdb_decode_gesture(const Xt9DecPt* pts, ET9U32 n, const ET9KdbLoaded* m,
                          const Xt9DecParams* p, Xt9DecPos* out, int max_out) {
    if (!pts || !n || !m || !p || !out || max_out <= 0) return 0;

    /* [0xc6e04] drop out-of-bounds samples before clustering */
    enum { MAXPTS = 2503 };  /* record capacity, 0x7568/12 */
    static Xt9DecPt f[MAXPTS];
    ET9U32 fn = 0;
    float W = (float)m->authoredWidth, H = (float)m->authoredHeight;
    for (ET9U32 i = 0; i < n && fn < MAXPTS; i++) {
        if (pts[i].x < 0.0f || pts[i].x >= W) continue;
        if (pts[i].y < 0.0f || pts[i].y >= H) continue;
        f[fn++] = pts[i];
    }
    if (!fn) return 0;

    /* [0xc74c4] grow clusters while the bbox stays within RADY/0.9 x RADY/2; the bursting point
     * starts the next cluster */
    enum { MAXCL = 256 };
    static struct { float cx, cy; ET9U32 first, last; } cl[MAXCL];
    int ncl = 0;
    ET9U32 i = 0;
    while (i < fn && ncl < MAXCL) {
        float ry_start = rady_at(p, f[i].y);
        float minx = f[i].x, maxx = f[i].x, miny = f[i].y, maxy = f[i].y;
        float ry_min = ry_start;
        ET9U32 j = i + 1;
        while (j < fn) {
            float ry;
            if (!p->per_row) ry = p->rady;
#if XT9_ROWBAND == ROWBAND_POINT
            else ry = rady_at(p, f[j].y);
#elif XT9_ROWBAND == ROWBAND_MINSPAN
            else { float rj = rady_at(p, f[j].y); ry_min = rj < ry_min ? rj : ry_min; ry = ry_min; }
#else /* START */
            else ry = ry_start;
#endif
            float wcut = ry / 0.9f, hcut = ry * 0.5f;
            float nminx = f[j].x < minx ? f[j].x : minx, nmaxx = f[j].x > maxx ? f[j].x : maxx;
            float nminy = f[j].y < miny ? f[j].y : miny, nmaxy = f[j].y > maxy ? f[j].y : maxy;
            if (nmaxx - nminx > wcut || nmaxy - nminy > hcut) break;
            minx = nminx; maxx = nmaxx; miny = nminy; maxy = nmaxy;
            j++;
        }
        (void)ry_min;
        float sx = 0.0f, sy = 0.0f;                       /* [0xc799c] centroid */
        for (ET9U32 k = i; k < j; k++) { sx += f[k].x; sy += f[k].y; }
        cl[ncl].cx = sx / (float)(j - i); cl[ncl].cy = sy / (float)(j - i);
        cl[ncl].first = i; cl[ncl].last = j - 1;
        ncl++;
        i = j;
    }
    if (!ncl) return 0;
    long g_start_window_end = -1;   /* set by the START_ARC block; first-gap scan origin */

    /* [0xc76cc] only inflections emit keys: endpoints always, interior clusters where the path
     * turns. Straight travel emits interpolated crossings every 2*min(RADX,RADY) px (the cause of
     * the blob's "very" -> 6 positions over-segmentation). */
    static unsigned char keep[MAXCL];
    keep[0] = 1; keep[ncl - 1] = 1;
    for (int c = 1; c + 1 < ncl; c++) {
        float ix = cl[c].cx - cl[c-1].cx, iy = cl[c].cy - cl[c-1].cy;
        float ox = cl[c+1].cx - cl[c].cx, oy = cl[c+1].cy - cl[c].cy;
        float li = sqrtf(ix*ix + iy*iy), lo = sqrtf(ox*ox + oy*oy);
        keep[c] = (li > 1.0f && lo > 1.0f &&
                   (ix*ox + iy*oy) / (li*lo) < XT9_DEC_TURN_COS) ? 1 : 0;
    }

    float arc_cut = 2.0f * (p->radx < p->rady ? p->radx : p->rady);
    int npos = 0, prev_kept = 0;
    for (int c = 0; c < ncl && npos < max_out; c++) {
        if (!keep[c]) continue;
        if (npos > 0 && p->feed_minimal) {
            /* Waypoints absorbed into a neighbour cluster: one level of Douglas-Peucker over the
             * gap's raw samples (deepest point off the chord between kept positions). */
            ET9U32 g0 = (ET9U32)cl[prev_kept].last, g1 = cl[c].first;
            if (npos == 1 && g_start_window_end >= 0 && (ET9U32)g_start_window_end < g0)
                g0 = (ET9U32)g_start_window_end;   /* first gap: scan from the start-window end */
            /* Key-crossing recovery: emit each distinct key cell dwelt in for >= cross_min samples,
             * skipping the flanking keys. Catches collinear letters (coffee's f) the deviation pass
             * cannot see, so it replaces that pass when active. */
            if (p->cross_min > 0 && g1 > g0 + 1) {
                ET9SYMB nextSym = 0;
                { ET9SYMB s8[8]; ET9U8 fq8[8];
                  int ns = xt9kdb_point_to_candidates(m, cl[c].cx, cl[c].cy, s8, fq8, 1);
                  if (ns > 0) nextSym = s8[0]; }
                ET9SYMB curSym = 0; ET9U32 runLen = 0; float sx = 0.0f, sy = 0.0f; ET9U32 rf = g0 + 1;
                for (ET9U32 k = g0 + 1; k <= g1 && npos < max_out; k++) {
                    ET9SYMB sym = 0xFFFE;   /* sentinel at k==g1 flushes the final run */
                    if (k < g1) { ET9SYMB s8[8]; ET9U8 fq8[8];
                        int ns = xt9kdb_point_to_candidates(m, f[k].x, f[k].y, s8, fq8, 1);
                        sym = (ns > 0) ? s8[0] : 0; }
                    if (runLen > 0 && sym == curSym) { runLen++; sx += f[k].x; sy += f[k].y; continue; }
                    if (runLen >= (ET9U32)p->cross_min && curSym != 0 && curSym != 0xFFFE) {
                        ET9SYMB lastSym = (npos > 0 && out[npos-1].nsymb) ? out[npos-1].symbs[0] : 0;
                        if (curSym != lastSym && curSym != nextSym) {
                            Xt9DecPos* o = &out[npos]; memset(o, 0, sizeof(*o));
                            o->cx = sx / (float)runLen; o->cy = sy / (float)runLen;
                            o->first = (int)rf; o->count = (int)runLen;
                            int ns2 = xt9kdb_point_to_candidates(m, o->cx, o->cy, o->symbs, o->freqs, 8);
                            o->nsymb = (ET9U8)(ns2 > 0 ? ns2 : 0);
                            if (o->nsymb) npos++;
                        }
                    }
                    curSym = sym;
                    if (k < g1) { runLen = 1; sx = f[k].x; sy = f[k].y; rf = k; }
                    else runLen = 0;
                }
            }
            if (p->cross_min <= 0 && g1 > g0 + 1) {
                float ax = f[g0].x / p->radx, bx = f[g1].x / p->radx;
                float rha = rady_at(p, f[g0].y), rhb = rady_at(p, f[g1].y);
                float ay = f[g0].y / (rha > 0 ? rha : 108.0f);
                float by = f[g1].y / (rhb > 0 ? rhb : 108.0f);
                float vx = bx - ax, vy = by - ay;
                float len2 = vx * vx + vy * vy;
                int bi = -1; float bd = 0.0f;
                for (ET9U32 k = g0 + 1; k < g1; k++) {
                    float rhq = rady_at(p, f[k].y);
                    float qx = f[k].x / p->radx, qy = f[k].y / (rhq > 0 ? rhq : 108.0f);
                    float dev;
                    if (len2 < 1e-6f) {
                        float dx = qx - ax, dy = qy - ay;
                        dev = sqrtf(dx * dx + dy * dy);
                    } else {
                        float t = ((qx - ax) * vx + (qy - ay) * vy) / len2;
                        if (t < 0.0f) t = 0.0f; else if (t > 1.0f) t = 1.0f;
                        float dx = qx - (ax + t * vx), dy = qy - (ay + t * vy);
                        dev = sqrtf(dx * dx + dy * dy);
                    }
                    if (dev > bd) { bd = dev; bi = (int)k; }
                }
                if (bi >= 0 && bd > (p->wp_dev > 0 ? p->wp_dev : XT9_DEC_WAYPOINT_DEV) && npos < max_out) {
                    Xt9DecPos* o = &out[npos];
                    memset(o, 0, sizeof(*o));
                    o->cx = f[bi].x; o->cy = f[bi].y;
                    o->first = bi; o->count = 1;
                    int ns = xt9kdb_point_to_candidates(m, o->cx, o->cy, o->symbs, o->freqs, 8);
                    o->nsymb = (ET9U8)(ns > 0 ? ns : 0);
                    if (o->nsymb) npos++;
                }
            }
        }
        if (npos > 0 && !p->feed_minimal) {
            /* interpolate crossings on the straight leg from the previous kept cluster */
            float acc = 0.0f;
            for (ET9U32 k = cl[prev_kept].last; k < cl[c].first && npos < max_out; k++) {
                float dx = f[k+1].x - f[k].x, dy = f[k+1].y - f[k].y;
                acc += sqrtf(dx*dx + dy*dy);
                if (acc >= arc_cut) {
                    acc = 0.0f;
                    Xt9DecPos* o = &out[npos];
                    memset(o, 0, sizeof(*o));
                    o->cx = f[k+1].x; o->cy = f[k+1].y;
                    o->first = (int)(k + 1); o->count = 1;
                    int ns = xt9kdb_point_to_candidates(m, o->cx, o->cy, o->symbs, o->freqs, 8);
                    o->nsymb = (ET9U8)(ns > 0 ? ns : 0);
                    /* a crossing still over the previous position's key adds nothing (real
                     * inflections such as hello's double l are not suppressed) */
                    if (o->nsymb && !(npos > 0 && out[npos-1].nsymb &&
                                      o->symbs[0] == out[npos-1].symbs[0]))
                        npos++;
                }
            }
        }
        if (npos >= max_out) break;
        Xt9DecPos* o = &out[npos];
        memset(o, 0, sizeof(*o));
        o->cx = cl[c].cx; o->cy = cl[c].cy;
        if (npos == 0 && XT9_DEC_START_ARC > 0.0f) {
            /* START position: touchdown-window mean, not the full first-cluster centroid */
            float sx = f[cl[c].first].x, sy = f[cl[c].first].y, acc = 0.0f;
            ET9U32 cnt = 1;
            for (ET9U32 k = cl[c].first + 1; k <= cl[c].last; k++) {
                float dx = f[k].x - f[k-1].x, dy = f[k].y - f[k-1].y;
                acc += sqrtf(dx*dx + dy*dy);
                if (acc > XT9_DEC_START_ARC) break;
                sx += f[k].x; sy += f[k].y; cnt++;
            }
            o->cx = sx / (float)cnt; o->cy = sy / (float)cnt;
            /* first waypoint gap starts at the start window's end: an over-wide start cluster
             * can swallow an adjacent key */
            g_start_window_end = cl[c].first + cnt - 1;
        }
        o->first = (int)cl[c].first; o->count = (int)(cl[c].last - cl[c].first + 1);
        /* [0xc857c] key assignment via the smart-touch candidate set (symbs[0] = location winner) */
        int ns = xt9kdb_point_to_candidates(m, o->cx, o->cy, o->symbs, o->freqs, 8);
        o->nsymb = (ET9U8)(ns > 0 ? ns : 0);
        if (o->nsymb) {
            npos++;
            /* Dwell split: a double letter is a pause at one key. A kept cluster with >= dwell_min
             * samples in a bbox under half a key emits its position twice. */
            int span = o->count;
            float bw = 0.0f, bh = 0.0f;
            for (int k = o->first; k <= (int)cl[c].last; k++) {
                float ddx = f[k].x - o->cx, ddy = f[k].y - o->cy;
                if (ddx < 0) ddx = -ddx; if (ddy < 0) ddy = -ddy;
                if (ddx > bw) bw = ddx; if (ddy > bh) bh = ddy;
            }
            if (p->dwell_min > 0 && span >= p->dwell_min
                && bw < p->radx * 0.5f && bh < p->rady * 0.35f && npos < max_out) {
                out[npos] = *o;            /* same key, second position -> the doubled letter */
                npos++;
            }
        }
        prev_kept = c;
    }
    return npos;   /* path order; no sort */
}
