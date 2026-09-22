/* kdb_geometry.c — key rectangles, readback, point->key resolution.
 *
 * The key model is VERIFIED on-device (GetKeyPositions / KeyInfo): English qwerty_pkb is
 * 30 keys on a 1080x324 grid. The fixture below is that exact layout — it is both the
 * bring-up default model and the golden data for the differential harness. point->key
 * (containment, else nearest center) is the core both ProcessTrace and ProcessTap use.
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stddef.h>
#include <string.h>
#include <math.h>

/* --- verified English qwerty_pkb (captured from the live engine) ------------- */
/* trailing: bias 0,0 then the symbol list — one code per key, the code itself (L13). */
#define K(code,ty,l,t,r,b,cx,cy) { (ET9U16)(code), ty, l,t,r,b, cx,cy, 0,0, 1, { (ET9SYMB)(code) } }
/* 128px rows (authored 1080x384). INCLUSIVE rects (matching the blob): right=left+w-1,
 * bottom=top+h-1, center=(left+(w-1)/2, top+(h-1)/2). 108w x 128h keys -> right=left+107,
 * bottom=top+127, cx=left+53, cy=top+63. KEY ORDER = the blob's (device-confirmed): all
 * regional/nonRegional keys first in document order, then the function keys (type 5)
 * appended — backspace/alt/enter land at 27/28/29. Load_XmlKDB's stable partition emits
 * this same order, so test/qwerty_pkb_even.xml round-trips this fixture index-for-index.
 * Types match the NKL (+0x04): 1=regional, 2=nonRegional ($), 5=function. */
static const ET9KdbKey kQwertyPkbEn[30] = {
    K('q', 1,   0,  0, 107,127,   53, 63), K('w', 1, 108,  0, 215,127,  161, 63),
    K('e', 1, 216,  0, 323,127,  269, 63), K('r', 1, 324,  0, 431,127,  377, 63),
    K('t', 1, 432,  0, 539,127,  485, 63), K('y', 1, 540,  0, 647,127,  593, 63),
    K('u', 1, 648,  0, 755,127,  701, 63), K('i', 1, 756,  0, 863,127,  809, 63),
    K('o', 1, 864,  0, 971,127,  917, 63), K('p', 1, 972,  0,1079,127, 1025, 63),
    K('a', 1,   0,128, 107,255,   53,191), K('s', 1, 108,128, 215,255,  161,191),
    K('d', 1, 216,128, 323,255,  269,191), K('f', 1, 324,128, 431,255,  377,191),
    K('g', 1, 432,128, 539,255,  485,191), K('h', 1, 540,128, 647,255,  593,191),
    K('j', 1, 648,128, 755,255,  701,191), K('k', 1, 756,128, 863,255,  809,191),
    K('l', 1, 864,128, 971,255,  917,191),
    K('z', 1, 108,256, 215,383,  161,319), K('x', 1, 216,256, 323,383,  269,319),
    K('c', 1, 324,256, 431,383,  377,319), K('v', 1, 432,256, 539,383,  485,319),
    K('b', 1, 540,256, 647,383,  593,319), K('n', 1, 648,256, 755,383,  701,319),
    K('m', 1, 756,256, 863,383,  809,319), K('$', 2, 864,256, 971,383,  917,319),
    K(8,   5, 972,128,1079,255, 1025,191),                                  /* backspace */
    K(4070,5,   0,256, 107,383,   53,319),                                  /* fn/shift  */
    K(13,  5, 972,256,1079,383, 1025,319),                                  /* enter     */
};
#undef K
const ET9KdbLoaded XT9_QWERTY_PKB_EN = {
    /*primaryId*/9, /*secondaryId*/6,
    ET9_KDB_AUTHORED_W, ET9_KDB_AUTHORED_H, /*active*/0,0,
    30, /*authoredRadiusY*/0, (ET9KdbKey*)kQwertyPkbEn
};

const ET9KdbLoaded* g_kdb_model = &XT9_QWERTY_PKB_EN;   /* bring-up default */
void xt9kdb_set_model(const ET9KdbLoaded* m) { if (m) g_kdb_model = m; }

/* --- sensor/view -> authored mapping (RE-CONFIRMED from ET9KDB_GetKeyPositionByTap @0xc02d8) -----
 * The blob's exact transform (per axis):
 *     authored = (view - offset)                         when scale == 0   (pure translation)
 *     authored = (view - offset) * authoredDim / scale   when scale != 0   (scaled)
 * where offset = ctx+0xfc30/0xfc32  (set by SetKeyboardOffset),
 *       scale  = ctx+0xfc34/0xfc36  (set by SetKeyboardSize; 0 => no scaling),
 *       authoredDim = NKL+0x1a/0x1c (the KDB's authored width/height).
 * The inverse forward map (authored -> view, getKeys @0xb9200) is view = offset + round(scale*authored/dim).
 * We keep all four params so the owned module matches the blob in BOTH the (0,0)=no-scale config the
 * IME currently uses for the PKB AND a scaled config. The PKB-only gate on the OFFSET is our own
 * extension (the IME sets offset=0 for a VKB); the scale follows the blob unconditionally. */
/* Both default to ZERO. The Y default was 50 for a while — an experiment aimed at a different
 * problem, kept as a bring-up default from the old 324-tall root layout. It is wrong for the
 * layouts we actually ship: the athena PKB is authored for a 1:1 sensor mapping ("offset 0, no
 * scale", see assets/kdb/athena/qwerty_pkb.xml), and a 50px shift moves every point near a row
 * boundary a full row up — raw (453,312) is row 3 (V) but resolved as row 2 (G), which is where
 * the spurious keys in a decoded swipe path came from. Removed 2026-08-12.
 *
 * The mechanism stays: SetKeyboardOffset still sets these, and a device whose sensor genuinely has
 * a dead strip can use it. Nothing calls it at runtime today. */
static float g_calOffX = 0.0f, g_calOffY = 0.0f;   /* ctx+0xfc30 / ctx+0xfc32 */
static int   g_scaleW  = 0,    g_scaleH  = 0;       /* ctx+0xfc34 / ctx+0xfc36 (0 = no scale) */
/* The touch offset is a PHYSICAL-keyboard calibration. It is applied only when the active KDB is
 * a PKB; for a VKB (on-screen, coordinates already in the keyboard view's space) it is forced to
 * zero. Default 1 (this module's bring-up fixture is the pkb); SetKdbNum updates it per layout. */
static int g_active_is_pkb = 1;
void xt9kdb_set_active_pkb(int isPkb) { g_active_is_pkb = isPkb ? 1 : 0; }
int  xt9kdb_active_pkb(void) { return g_active_is_pkb; }
void xt9kdb_set_scale(int sw, int sh) { g_scaleW = sw; g_scaleH = sh; }   /* SetKeyboardSize */

/* L2: the CONTEXT holds the authoritative scale (ctx+0xfc34/36); this global is only the ctx-less
 * mirror xt9kdb_sensor_to_authored reads. Refresh it from the gesturing context at TouchStart —
 * same reason and same shape as the model re-publish next to it in kdb_trace.c. */
/* The scale a given context is configured with: ctx+0xfc34/36 when we own SetKeyboardSize and a
 * context was supplied, else the process-global mirror. Single reader for both the publish below
 * and the ctx-aware mapping — they must not drift apart. */
static void ctx_scale(ET9KDBInfoPtr ctx, int* sw, int* sh) {
#if defined(XT9KDB_CUTOVER)
    if (ctx) {
        const unsigned char* c = (const unsigned char*)ctx;
        *sw = (int)*(const ET9U16*)(c + ET9_OFF_KDB_SCALE_W);
        *sh = (int)*(const ET9U16*)(c + ET9_OFF_KDB_SCALE_H);
        return;
    }
#else
    (void)ctx;   /* DIFF: the blob owns SetKeyboardSize, so the global is the only scale we set */
#endif
    *sw = g_scaleW;
    *sh = g_scaleH;
}

void xt9kdb_publish_ctx_scale(ET9KDBInfoPtr ctx) {
#if defined(XT9KDB_CUTOVER)
    if (!ctx) return;
    ctx_scale(ctx, &g_scaleW, &g_scaleH);
#else
    (void)ctx;
#endif
}

/* The mapping proper, with the model and scale handed in. Everything about sensor->authored that
 * is not "which keyboard" lives here; the two public entry points differ only in where they get
 * that answer. */
static void sensor_to_authored_with(const ET9KdbLoaded* m, int scaleW, int scaleH,
                                    float sx, float sy, float* ax, float* ay) {
    float aw = m ? (float)m->authoredWidth  : (float)ET9_KDB_AUTHORED_W;
    float ah = m ? (float)m->authoredHeight : (float)ET9_KDB_AUTHORED_H;
    float ox = g_active_is_pkb ? g_calOffX : 0.0f;   /* offset applies to PKB only (owned extension) */
    float oy = g_active_is_pkb ? g_calOffY : 0.0f;
    float x = sx - ox;
    float y = sy - oy;
    if (scaleW) x = aw * x / (float)scaleW;           /* blob: authoredW*(view-off)/scaleW */
    if (scaleH) y = ah * y / (float)scaleH;
    if (x < 0.0f) x = 0.0f; else if (x > aw - 1.0f) x = aw - 1.0f;
    if (y < 0.0f) y = 0.0f; else if (y > ah - 1.0f) y = ah - 1.0f;
    if (ax) *ax = x;
    if (ay) *ay = y;
}

void xt9kdb_sensor_to_authored(float sx, float sy, float* ax, float* ay) {
    sensor_to_authored_with(g_kdb_model, g_scaleW, g_scaleH, sx, sy, ax, ay);
}

/* L4, second half (2026-09-20). The ctx-less form above is right for the GESTURE path, which
 * re-publishes the gesturing context's model and scale at TouchStart and then runs entirely
 * ctx-free. It is wrong for a one-shot QUERY on a handle: GetKeyPositionByTap and
 * GetKeyPositionByStoredTouch used to resolve against whichever model happened to be in the
 * global — so a query on the spellchecker's handle answered with the IME's keyboard. Give those
 * a context instead. Like GetKeyPositions (and unlike TouchStart) this does NOT re-publish: a
 * read-only query must not redirect the ctx-less consumers. */
void xt9kdb_sensor_to_authored_ctx(ET9KDBInfoPtr ctx, float sx, float sy, float* ax, float* ay) {
    int sw = 0, sh = 0;
    ctx_scale(ctx, &sw, &sh);
    sensor_to_authored_with(xt9kdb_model_for_ctx_or_global(ctx), sw, sh, sx, sy, ax, ay);
}

/* The model a context should be answered with: its own if it has loaded one through us, else the
 * legacy global (the bring-up fixture, and every offline driver that never loads per-ctx). */
const ET9KdbLoaded* xt9kdb_model_for_ctx_or_global(ET9KDBInfoPtr ctx) {
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx);
    return m ? m : g_kdb_model;
}

int xt9kdb_point_to_key(const ET9KdbLoaded* m, float x, float y) {
    if (!m || !m->keys || m->keyCount == 0) return -1;
    /* RE-confirmed (resolver 0x5cbe8): the tap is rounded to integer via floor(v+0.5) before testing.
     * Containment then nearest-center reproduces the blob's choice; the per-key protective core (bbox
     * x0.6 @ key+0x20) is subsumed by containment, and the NKL bias data is all-zero (see XT9NKL dump),
     * so the biased center (cx+biasX) equals the geometric center here. */
    x = floorf(x + 0.5f); y = floorf(y + 0.5f);
    /* 1) exact containment (INCLUSIVE rects, matching the blob: x in [left,right], y in [top,bottom]) */
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        if (x >= k->left && x <= k->right && y >= k->top && y <= k->bottom) return (int)i;
    }
    /* 2) nearest center, using the smart-touch BIASED center (cx+biasX, cy+biasY). With no bias
     *    data loaded (biasX/biasY = 0) this is plain nearest-center; once Load_AttachBias is owned
     *    the per-key bias pulls the effective center toward the engine's tuned hit point. */
    int best = -1; float bestd = 0;
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        float kcx = (float)k->cx + (float)k->biasX;
        float kcy = (float)k->cy + (float)k->biasY;
        float dx = x - kcx, dy = y - kcy, d = dx*dx + dy*dy;
        if (best < 0 || d < bestd) { best = (int)i; bestd = d; }
    }
    return best;
}

/* Smart-touch AMBIGUOUS candidate set for a point (AUTHORED coords): the nearest LETTER keys to the
 * biased centers, within ~1.5 key-widths of the closest, nearest-first, with descending byte freqs.
 * This is the owned-side approximation of the blob's per-tap ambiguous set (resolver @0x5cbe8); the
 * AW side ingests it via ET9AddCustomSymbolSet(symbs, freqs, count). Returns the count (<= max). */
int xt9kdb_point_to_candidates(const ET9KdbLoaded* m, float x, float y,
                               ET9SYMB* symbs, ET9U8* freqs, int max) {
    if (!m || !m->keys || max <= 0) return 0;
    int cap = max < 12 ? max : 12;
    float bestd[12]; int besti[12]; int n = 0;
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        if (!(k->keyCode >= 'a' && k->keyCode <= 'z')) continue;   /* ambiguity is over letters */
        float dx = x - (float)(k->cx + k->biasX), dy = y - (float)(k->cy + k->biasY);
        float d = dx * dx + dy * dy;
        if (n < cap || d < bestd[n - 1]) {
            int p = (n < cap) ? n++ : (cap - 1);
            while (p > 0 && bestd[p - 1] > d) { bestd[p] = bestd[p - 1]; besti[p] = besti[p - 1]; p--; }
            bestd[p] = d; besti[p] = (int)i;
        }
    }
    if (n == 0) return 0;
    /* Candidate-set radius. NOTE: the blob's TAP resolver (0x5cbe8) uses radius² = (Wp²+Hp²)/4 (half
     * the key diagonal, ~84px) — but that is its VKB tap protective test (PKB taps are hardware
     * keycodes, never coord-resolved). Our set feeds the GESTURE decoder -> AW, where we deliberately
     * use a WIDER radius (~1.5 key-widths) so horizontal neighbors are included and AW's lexicon can
     * recover the swiped word. This is an owned design choice, not a blob mismatch. */
    const ET9KdbKey* p0 = &m->keys[besti[0]];
    float kw = (float)(p0->right - p0->left); if (kw < 1.0f) kw = 108.0f;
    float rad2 = (1.5f * kw) * (1.5f * kw);
    int out = 0;
    for (int j = 0; j < n; j++) {
        if (bestd[j] > rad2) break;
        symbs[out] = m->keys[besti[j]].keyCode;
        int f = 255 - 45 * j; if (f < 40) f = 40;   /* rank-weighted: nearest highest */
        freqs[out] = (ET9U8)f;
        out++;
    }
    return out;
}

/* Attach a smart-touch bias to key[keyIdx], reversed from Load_AttachBias (@0xba0b0): the bias is
 * clamped to +/-((right-left+1)/2, (bottom-top+1)/2) — i.e. it can pull the effective center at most
 * to a key edge — then stored. The blob REJECTS an out-of-range bias (status 0x1a); we clamp, which
 * is safe for the owned model. The XML loader (Load_XmlKDB) calls this per key that carries bias
 * data; English qwerty_pkb ships none, so this stays inert there. point_to_key reads biasX/biasY. */
void xt9kdb_attach_bias(ET9KdbLoaded* m, ET9U16 keyIdx, ET9S16 biasX, ET9S16 biasY) {
    if (!m || !m->keys || keyIdx >= m->keyCount) return;
    ET9KdbKey* k = &m->keys[keyIdx];
    ET9S16 halfW = (ET9S16)(((k->right - k->left) + 1) / 2);
    ET9S16 halfH = (ET9S16)(((k->bottom - k->top) + 1) / 2);
    if (biasX >  halfW) biasX =  halfW; else if (biasX < -halfW) biasX = -halfW;
    if (biasY >  halfH) biasY =  halfH; else if (biasY < -halfH) biasY = -halfH;
    k->biasX = biasX;
    k->biasY = biasY;
}

/* --- exports --------------------------------------------------------------- */

/* Project one authored coordinate to VIEW space, matching the blob's per-key writer @0xb9200:
 *     view = offset + (scale ? round(scale*authored/dim) : authored)
 * where round adds 1 iff remainder > dim/2 (blob: `cmp rem, dim,lsr#1 ; cset hi`). */
static int xt9_proj(int v, int off, int scale, int dim) {
    if (scale == 0 || dim == 0) return off + v;
    int num = scale * v;
    int q = num / dim, r = num - q * dim;
    if (r > dim / 2) q += 1;
    return off + q;
}

/* @0xbb314 — fill the caller's key-position array. REAL ABI (reversed 2026-07-05):
 *     GetKeyPositions(ctx, out[stride 0x30], capacity, *count)
 * The JNI getKeys (@0x20fc0) calls this with capacity=0x50 and reads exactly 7 fields per 0x30 record:
 *   keyCode@+0x0a, centerX@+0x20, centerY@+0x24, left@+0x28, top@+0x2a, right@+0x2c, bottom@+0x2e
 * — authored coords projected to view via the active offset/scale. We also fill type@+0x00 and index@+0x08
 * (the blob does; the JNI ignores them) and zero the rest. offset/scale come from our owned setters
 * (SetKeyboardOffset/SetKeyboardSize), matching what the IME pushed for this layout. */
ET9STATUS XT9KDB(GetKeyPositions)(ET9KDBInfoPtr ctx, void* out, ET9U16 capacity, ET9U32* count) {
    /* L4 (2026-09-20): take the MODEL from the caller's context too, not just the projection.
     * Taking the model from the legacy global while projecting with the context's offset/scale
     * meant a getKeys on the spellchecker's handle could return the IME's geometry projected
     * through the spellchecker's scale. Unlike TouchStart and ProcessTap this does NOT re-publish
     * to the global: a read-only query must not redirect the ctx-less consumers. The global stays
     * the fallback for a context that has never loaded a KDB through us (the bring-up fixture,
     * and every offline test that drives the default model). */
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx_or_global(ctx);
    if (!m || !m->keys) return ET9STATUS_KDB_NOT_LOADED;
    ET9U16 n = m->keyCount;
    if (n == 0) return ET9STATUS_KDB_NOT_LOADED;
    /* The blob zeroes the count (a full 32-bit word, `str w0, [x23]` @0xbb3dc) as soon as the
     * context checks pass and before the capacity test; keep that order so a too-small buffer
     * never leaves a stale count behind either. The width matters: the JNI getKeys reads this slot
     * with a 32-bit `ldr` into its loop bound (see et9kdb.h). */
    if (count) *count = 0;
    if (out && capacity < n) return (ET9STATUS)0x1a;   /* buffer too small (blob returns 0x1a) */
    if (count) *count = n;
    if (!out) return ET9STATUS_NONE;

    /* Source the projection params from the CONTEXT, exactly as the blob's writer @0xb9200 does — NOT
     * from our sensor-calibration globals (g_calOffX/Y are the PKB touch->authored offset, a different
     * quantity). getKeys reads offset@ctx+0xfc30/32 and scale@ctx+0xfc34/36 (ldrh; 0 => no scale). For
     * the PKB all four are 0, so view == authored and the output is byte-identical to the blob. */
    int offX = 0, offY = 0, scaleW = 0, scaleH = 0;
    if (ctx) {
        const unsigned char* c = (const unsigned char*)ctx;
        offX   = *(const ET9U16*)(c + ET9_OFF_KDB_OFFSET_X);
        offY   = *(const ET9U16*)(c + ET9_OFF_KDB_OFFSET_Y);
        scaleW = *(const ET9U16*)(c + ET9_OFF_KDB_SCALE_W);
        scaleH = *(const ET9U16*)(c + ET9_OFF_KDB_SCALE_H);
    }
    const int aw = m->authoredWidth, ah = m->authoredHeight;
    unsigned char* base = (unsigned char*)out;
    for (ET9U16 i = 0; i < n; i++) {
        const ET9KdbKey* k = &m->keys[i];
        unsigned char* rec = base + (size_t)i * 0x30;
        memset(rec, 0, 0x30);
        *(ET9U32*)(rec + 0x00) = (ET9U32)k->type;                                   /* type    (ignored) */
        *(ET9U16*)(rec + 0x08) = i;                                                 /* index   (ignored) */
        *(ET9U16*)(rec + 0x0a) = (ET9U16)k->keyCode;                                /* keyCode <- read   */
        *(ET9U32*)(rec + 0x20) = (ET9U32)xt9_proj(k->cx,     offX, scaleW, aw);     /* centerX <- read   */
        *(ET9U32*)(rec + 0x24) = (ET9U32)xt9_proj(k->cy,     offY, scaleH, ah);     /* centerY <- read   */
        *(ET9U16*)(rec + 0x28) = (ET9U16)xt9_proj(k->left,   offX, scaleW, aw);     /* left    <- read   */
        *(ET9U16*)(rec + 0x2a) = (ET9U16)xt9_proj(k->top,    offY, scaleH, ah);     /* top     <- read   */
        *(ET9U16*)(rec + 0x2c) = (ET9U16)xt9_proj(k->right,  offX, scaleW, aw);     /* right   <- read   */
        *(ET9U16*)(rec + 0x2e) = (ET9U16)xt9_proj(k->bottom, offY, scaleH, ah);     /* bottom  <- read   */
    }
    return ET9STATUS_NONE;
}
ET9STATUS XT9KDB(GetKeyboardSize)(ET9KDBInfoPtr ctx, ET9U16* w, ET9U16* h) {
    (void)ctx;
    if (w) *w = g_kdb_model ? g_kdb_model->authoredWidth : 0;
    if (h) *h = g_kdb_model ? g_kdb_model->authoredHeight : 0;
    return ET9STATUS_NONE;
}
/* RE-CONFIRMED (ET9KDB_SetKeyboardSize @0xbb668): stores w->ctx+0xfc34, h->ctx+0xfc36 (the scale
 * numerators). 0 means "no scaling" (the config the IME uses for the PKB). The sensor->authored map
 * and getKeys both branch on these being non-zero. We store them so the owned transform matches the
 * blob in either config. (Blob also validates that w==0 iff h==0, else returns BAD_PARAM 1.) */
ET9STATUS XT9KDB(SetKeyboardSize)(ET9KDBInfoPtr ctx, ET9U16 w, ET9U16 h) {
    if ((w == 0) != (h == 0)) return ET9STATUS_ERROR;   /* mismatched: blob returns 1 */
    XT9KDB_LOGI("SetKeyboardSize ctx=%p w=%u h=%u", (void*)ctx, (unsigned)w, (unsigned)h);
    /* L2/L3 (2026-09-20): store it where the blob stores it — WITH THE CONTEXT. Two engine
     * contexts exist (primary IME + spellchecker, NuanceSDKManager) and each runs its own
     * syncKeyboardLayout -> setKeyboardSize, so a process-global made the last sync win
     * process-wide; and our own GetKeyPositions already read ctx+0xfc34/36, so on a stretched
     * VKB the recognition path scaled while getKeys handed back unprojected authored rects.
     * No-op under DIFF, where the blob's own SetKeyboardSize owns the field. */
    xt9kdb_ctx_set_scale(ctx, w, h);
    /* The ctx-less mirror, for xt9kdb_sensor_to_authored between gestures. TouchStart refreshes
     * it from the gesturing context (xt9kdb_publish_ctx_scale), so this is a starting value, not
     * the source of truth. */
    xt9kdb_set_scale((int)w, (int)h);
    return ET9STATUS_NONE;
}
ET9STATUS XT9KDB(GetKeyboardDefaultSize)(ET9KDBInfoPtr ctx, ET9U16* w, ET9U16* h) { (void)ctx; if(w)*w=ET9_KDB_AUTHORED_W; if(h)*h=ET9_KDB_AUTHORED_H; return ET9STATUS_NONE; }
/* Top/left dead-strip offset in SENSOR px. Zero for every layout we ship (athena included —
 * it is authored 1:1); kept for devices that need one. */
ET9STATUS XT9KDB(SetKeyboardOffset)(ET9KDBInfoPtr ctx, ET9S16 x, ET9S16 y) {
    (void)ctx; g_calOffX = (float)x; g_calOffY = (float)y; return ET9STATUS_NONE;
}
/* Resolve a tap (RAW sensor x,y) to the key under it. FUNCTIONAL: sensor->authored translation
 * then containment/nearest. NOTE: the blob's ByTap (@0xc02d8) additionally applies the per-key
 * smart-touch bias (floating-point proximity weighting vs the active keyboard half-dims) — that
 * refinement is tracked separately; nearest-center is the correct first cut. `out` receives the
 * owned ET9KdbKey; the blob's out is the 0x30 record (match only for DIFF/CUTOVER).
 *
 * L4 (2026-09-20): model AND projection now both come from the caller's context — see
 * xt9kdb_sensor_to_authored_ctx. Reachability note: this symbol IS in the cutover contract, but
 * the blob's only reference to it is a tail call from GetKeyPositionByStoredTouch, which has no
 * callers at all (elf_cutover.py INERT_NO_CALLERS), so nothing on device calls it today. */
ET9STATUS XT9KDB(GetKeyPositionByTap)(ET9KDBInfoPtr ctx, ET9U16 x, ET9U16 y, void* out) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx_or_global(ctx);
    if (!m || m->keyCount == 0) return ET9STATUS_KDB_NOT_LOADED;
    float ax, ay;
    xt9kdb_sensor_to_authored_ctx(ctx, (float)x, (float)y, &ax, &ay);
    int k = xt9kdb_point_to_key(m, ax, ay);
    if (k < 0) return ET9STATUS_NONE;
    if (out) *(ET9KdbKey*)out = m->keys[k];
    return ET9STATUS_NONE;
}

/* Resolve the idx-th stored gesture sample to its key (same resolution as ByTap). The PATH is
 * process-global (one gesture at a time, §3.1), so only the resolution is per-context. */
ET9STATUS XT9KDB(GetKeyPositionByStoredTouch)(ET9KDBInfoPtr ctx, ET9U16 i, void* out) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx_or_global(ctx);
    if (!m || m->keyCount == 0) return ET9STATUS_KDB_NOT_LOADED;
    float sx, sy;
    if (!xt9kdb_stored_sample((ET9U32)i, &sx, &sy)) return ET9STATUS_NONE; /* idx out of range */
    float ax, ay;
    xt9kdb_sensor_to_authored_ctx(ctx, sx, sy, &ax, &ay);
    int k = xt9kdb_point_to_key(m, ax, ay);
    if (k < 0) return ET9STATUS_NONE;
    if (out) *(ET9KdbKey*)out = m->keys[k];
    return ET9STATUS_NONE;
}
/* ---- Long-tail queries -----------------------------------------------------------------------
 * These are peripheral to the gesture/tap core and read context working regions that belong to
 * subsystems the owned module does not yet build (shift/page state, multitap tables). Each is
 * implemented with the same context validation the blob does and returns a well-defined EMPTY
 * result, which is safe for the CKB swipe/tap pipeline (it uses none of them). Full fidelity is a
 * CUTOVER item; the real multi-out ABIs are recorded below.
 */

/* Blob @0xba848 GetSwitchedKeys(ctx, out_active:u8*, out_count:?, out_sym:u16*, ...): reports keys
 * whose symbol changes under the current shift/page state, read from the ctx+0x18000 working area
 * (+1866 u8, +1868 u16). Owned: no shift-state subsystem yet -> report "no switched keys". */
ET9STATUS XT9KDB(GetSwitchedKeys)(ET9KDBInfoPtr ctx, void* out) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (out) *(ET9U8*)out = 0;   /* count of switched keys = 0 */
    return ET9STATUS_NONE;
}

/* Blob @0xbaff0 GetMultiTapSequence(ctx, symb, out): the multitap cycle for a symbol (phone-keypad
 * ABC input; symb gated < 0x1f). Owned: multitap tables not loaded (Load_AttachMultitapInfo stub)
 * and the CKB path never multitaps -> empty sequence. */
ET9STATUS XT9KDB(GetMultiTapSequence)(ET9KDBInfoPtr ctx, ET9SYMB s, void* out) {
    (void)s;
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (out) *(ET9U16*)out = 0;  /* sequence length = 0 */
    return ET9STATUS_NONE;
}

/* Blob @0xbde90 GetTouchInfo(ctx, out_flag:u8*, out_val:u32*, out_ptr:void**, ...): basic touch
 * state; the blob zero-inits its three out-params. Owned: return the same zeroed defaults. */
ET9STATUS XT9KDB(GetTouchInfo)(ET9KDBInfoPtr ctx, void* out) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (out) *(ET9U32*)out = 0;
    return ET9STATUS_NONE;
}
