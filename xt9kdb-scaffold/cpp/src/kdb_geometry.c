/* kdb_geometry.c: key rectangles, readback, point->key resolution.
 * The fixture below is the engine's English qwerty_pkb (30 keys), used as the bring-up default
 * model and the differential harness's golden data. point->key (containment, else nearest
 * center) is shared by ProcessTrace and ProcessTap. */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stddef.h>
#include <string.h>
#include <math.h>

/* Trailing fields: bias 0,0, then a one-entry symbol list holding the code itself. */
#define K(code,ty,l,t,r,b,cx,cy) { (ET9U16)(code), ty, l,t,r,b, cx,cy, 0,0, 1, { (ET9SYMB)(code) } }
/* Authored 1080x384, 108x128 keys, inclusive rects (right=left+107, cx=left+53, cy=top+63).
 * Engine key order: regional/nonRegional first, then function keys (backspace/alt/enter at
 * 27/28/29), so test/qwerty_pkb_even.xml round-trips index-for-index.
 * Types (NKL +0x04): 1=regional, 2=nonRegional ($), 5=function. */
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
    30, /*authoredRadiusY*/0, (ET9KdbKey*)kQwertyPkbEn, /*generation*/0
};

const ET9KdbLoaded* g_kdb_model = &XT9_QWERTY_PKB_EN;   /* bring-up default */
void xt9kdb_set_model(const ET9KdbLoaded* m) { if (m) g_kdb_model = m; }

/* Sensor/view -> authored mapping (engine ET9KDB_GetKeyPositionByTap @0xc02d8), per axis:
 *     authored = (view - offset)                         when scale == 0
 *     authored = (view - offset) * authoredDim / scale   when scale != 0
 * offset = ctx+0xfc30/0xfc32 (SetKeyboardOffset), scale = ctx+0xfc34/0xfc36 (SetKeyboardSize),
 * authoredDim = NKL+0x1a/0x1c. Inverse (getKeys @0xb9200): view = offset + round(scale*authored/dim).
 * Applying the offset only to a PKB is our own extension. Offsets default to zero: shipped
 * layouts are authored 1:1 with the sensor, and any shift moves points near a row boundary
 * into the wrong row. */

static float g_calOffX = 0.0f, g_calOffY = 0.0f;   /* ctx+0xfc30 / ctx+0xfc32 */
static int   g_scaleW  = 0,    g_scaleH  = 0;       /* ctx+0xfc34 / ctx+0xfc36 (0 = no scale) */
/* The touch offset is a physical-keyboard calibration, forced to zero for a VKB (coordinates
 * already in view space). SetKdbNum updates this per layout; default 1 matches the fixture. */
static int g_active_is_pkb = 1;
void xt9kdb_set_active_pkb(int isPkb) { g_active_is_pkb = isPkb ? 1 : 0; }
int  xt9kdb_active_pkb(void) { return g_active_is_pkb; }
void xt9kdb_set_scale(int sw, int sh) { g_scaleW = sw; g_scaleH = sh; }   /* SetKeyboardSize */

/* The context holds the authoritative scale (ctx+0xfc34/36); the global is the ctx-less mirror
 * that xt9kdb_sensor_to_authored reads, refreshed at TouchStart. This is the single reader for
 * both the publish and the ctx-aware mapping so they cannot drift apart. */
static void ctx_scale(ET9KDBInfoPtr ctx, int* sw, int* sh) {
#if defined(XT9KDB_CUTOVER)
    if (ctx) {
        const unsigned char* c = (const unsigned char*)ctx;
        *sw = (int)*(const ET9U16*)(c + ET9_OFF_KDB_SCALE_W);
        *sh = (int)*(const ET9U16*)(c + ET9_OFF_KDB_SCALE_H);
        return;
    }
#else
    (void)ctx;   /* DIFF: the engine owns SetKeyboardSize, so the global is our only scale */
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

/* The mapping proper; the two public entry points differ only in where model and scale come from. */
static void sensor_to_authored_with(const ET9KdbLoaded* m, int scaleW, int scaleH,
                                    float sx, float sy, float* ax, float* ay) {
    float aw = m ? (float)m->authoredWidth  : (float)ET9_KDB_AUTHORED_W;
    float ah = m ? (float)m->authoredHeight : (float)ET9_KDB_AUTHORED_H;
    float ox = g_active_is_pkb ? g_calOffX : 0.0f;   /* offset applies to PKB only (owned extension) */
    float oy = g_active_is_pkb ? g_calOffY : 0.0f;
    float x = sx - ox;
    float y = sy - oy;
    if (scaleW) x = aw * x / (float)scaleW;           /* engine: authoredW*(view-off)/scaleW */
    if (scaleH) y = ah * y / (float)scaleH;
    if (x < 0.0f) x = 0.0f; else if (x > aw - 1.0f) x = aw - 1.0f;
    if (y < 0.0f) y = 0.0f; else if (y > ah - 1.0f) y = ah - 1.0f;
    if (ax) *ax = x;
    if (ay) *ay = y;
}

void xt9kdb_sensor_to_authored(float sx, float sy, float* ax, float* ay) {
    sensor_to_authored_with(g_kdb_model, g_scaleW, g_scaleH, sx, sy, ax, ay);
}

/* For one-shot queries on a handle (the ctx-less form suits only the gesture path, which
 * re-publishes at TouchStart). Does not re-publish: a read-only query must not redirect the
 * ctx-less consumers. */
void xt9kdb_sensor_to_authored_ctx(ET9KDBInfoPtr ctx, float sx, float sy, float* ax, float* ay) {
    int sw = 0, sh = 0;
    ctx_scale(ctx, &sw, &sh);
    sensor_to_authored_with(xt9kdb_model_for_ctx_or_global(ctx), sw, sh, sx, sy, ax, ay);
}

/* The context's own model if it loaded one through us, else the legacy global (fixture/offline). */
const ET9KdbLoaded* xt9kdb_model_for_ctx_or_global(ET9KDBInfoPtr ctx) {
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx);
    return m ? m : g_kdb_model;
}

int xt9kdb_point_to_key(const ET9KdbLoaded* m, float x, float y) {
    if (!m || !m->keys || m->keyCount == 0) return -1;
    /* Engine resolver @0x5cbe8 rounds via floor(v+0.5), then containment, then nearest center.
     * Its per-key protective core (bbox x0.6 @ key+0x20) is subsumed by containment, and NKL
     * bias data is all-zero, so the biased center equals the geometric center. */
    x = floorf(x + 0.5f); y = floorf(y + 0.5f);
    /* 1) exact containment (inclusive rects) */
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        if (x >= k->left && x <= k->right && y >= k->top && y <= k->bottom) return (int)i;
    }
    /* 2) nearest biased center (cx+biasX, cy+biasY); plain nearest-center while bias is zero */
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

/* Ambiguous candidate set for an authored point: nearest letter keys within ~1.5 key-widths,
 * nearest-first with descending byte freqs, fed to AW via ET9AddCustomSymbolSet.
 * Returns the count (<= max). */
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
    /* Deliberately wider than the engine's VKB tap radius (half the key diagonal): the gesture
     * decoder needs horizontal neighbors so AW's lexicon can recover the swiped word. */
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

/* Attach a smart-touch bias (Load_AttachBias @0xba0b0), clamped to half the key size so it
 * moves the center at most to a key edge. The engine rejects out-of-range bias (0x1a); we clamp.
 * Currently has no caller that applies data. */
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

/* Authored -> view, as the engine's per-key writer @0xb9200:
 *     view = offset + (scale ? round(scale*authored/dim) : authored)
 * where round adds 1 iff remainder > dim/2 (`cmp rem, dim,lsr#1 ; cset hi`). */
static int xt9_proj(int v, int off, int scale, int dim) {
    if (scale == 0 || dim == 0) return off + v;
    int num = scale * v;
    int q = num / dim, r = num - q * dim;
    if (r > dim / 2) q += 1;
    return off + q;
}

/* @0xbb314 GetKeyPositions(ctx, out[stride 0x30], capacity, *count). JNI getKeys (@0x20fc0)
 * passes capacity=0x50 and reads keyCode@+0x0a, centerX@+0x20, centerY@+0x24, left@+0x28,
 * top@+0x2a, right@+0x2c, bottom@+0x2e (view coords). type@+0x00 and index@+0x08 are filled but
 * ignored; the rest is zeroed. */
ET9STATUS XT9KDB(GetKeyPositions)(ET9KDBInfoPtr ctx, void* out, ET9U16 capacity, ET9U32* count) {
    /* Model and projection both come from the caller's context. A read-only query does not
     * re-publish to the global. */
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx_or_global(ctx);
    if (!m || !m->keys) return ET9STATUS_KDB_NOT_LOADED;
    ET9U16 n = m->keyCount;
    if (n == 0) return ET9STATUS_KDB_NOT_LOADED;
    /* Zero the full 32-bit count before the capacity test (engine `str w0, [x23]` @0xbb3dc), so a
     * too-small buffer leaves no stale count; JNI getKeys reads it with a 32-bit `ldr`. */
    if (count) *count = 0;
    if (out && capacity < n) return (ET9STATUS)0x1a;   /* buffer too small */
    if (count) *count = n;
    if (!out) return ET9STATUS_NONE;

    /* Projection params come from the context (offset@ctx+0xfc30/32, scale@ctx+0xfc34/36, ldrh),
     * not from g_calOffX/Y, which is the PKB touch->authored offset, a different quantity. */
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
/* Engine @0xbb668: w->ctx+0xfc34, h->ctx+0xfc36; 0 means no scaling (the PKB config).
 * w==0 must match h==0, else status 1. */
ET9STATUS XT9KDB(SetKeyboardSize)(ET9KDBInfoPtr ctx, ET9U16 w, ET9U16 h) {
    if ((w == 0) != (h == 0)) return ET9STATUS_ERROR;
    XT9KDB_LOGD("SetKeyboardSize ctx=%p w=%u h=%u", (void*)ctx, (unsigned)w, (unsigned)h);
    /* Stored per context: the IME and spellchecker contexts each sync their own size.
     * No-op under DIFF, where the engine's SetKeyboardSize owns the field. */
    xt9kdb_ctx_set_scale(ctx, w, h);
    /* Ctx-less mirror; only a starting value, TouchStart refreshes it from the gesturing ctx. */
    xt9kdb_set_scale((int)w, (int)h);
    return ET9STATUS_NONE;
}
ET9STATUS XT9KDB(GetKeyboardDefaultSize)(ET9KDBInfoPtr ctx, ET9U16* w, ET9U16* h) { (void)ctx; if(w)*w=ET9_KDB_AUTHORED_W; if(h)*h=ET9_KDB_AUTHORED_H; return ET9STATUS_NONE; }
/* Top/left dead-strip offset in sensor px; zero for every shipped layout. */
ET9STATUS XT9KDB(SetKeyboardOffset)(ET9KDBInfoPtr ctx, ET9S16 x, ET9S16 y) {
    (void)ctx; g_calOffX = (float)x; g_calOffY = (float)y; return ET9STATUS_NONE;
}
/* Resolve a raw sensor tap to the key under it, using the caller's context. The engine's ByTap
 * (@0xc02d8) also applies smart-touch proximity weighting, not done here. `out` receives an
 * ET9KdbKey, not the engine's 0x30 record. Its only engine caller has no callers itself. */
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

/* Resolve the idx-th stored gesture sample like ByTap. The path is process-global (one gesture
 * at a time); only the resolution is per-context. */
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
/* Long-tail queries for subsystems not built yet (shift/page state, multitap). Each validates
 * the context and returns an empty result; the swipe/tap pipeline uses none of them. */

/* Engine @0xba848 GetSwitchedKeys(ctx, out_active:u8*, out_count:?, out_sym:u16*, ...): keys whose
 * symbol changes under shift/page state, from ctx+0x18000 (+1866 u8, +1868 u16). Reports none. */
ET9STATUS XT9KDB(GetSwitchedKeys)(ET9KDBInfoPtr ctx, void* out) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (out) *(ET9U8*)out = 0;   /* count of switched keys = 0 */
    return ET9STATUS_NONE;
}

/* Engine @0xbaff0 GetMultiTapSequence(ctx, symb, out): multitap cycle for a symbol (symb < 0x1f).
 * No multitap tables are loaded, so the sequence is empty. */
ET9STATUS XT9KDB(GetMultiTapSequence)(ET9KDBInfoPtr ctx, ET9SYMB s, void* out) {
    (void)s;
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (out) *(ET9U16*)out = 0;  /* sequence length = 0 */
    return ET9STATUS_NONE;
}

/* Engine @0xbde90 GetTouchInfo(ctx, out_flag:u8*, out_val:u32*, out_ptr:void**, ...): zeroed defaults. */
ET9STATUS XT9KDB(GetTouchInfo)(ET9KDBInfoPtr ctx, void* out) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (out) *(ET9U32*)out = 0;
    return ET9STATUS_NONE;
}
