/* kdb_nkl.c: emit our parsed KDB geometry in the engine's in-memory NKL format (et9_nkl.h).
 * The engine's TouchMove/End store the path and ET9_CP_Trace reads geometry from here.
 * Opaque engine fields (label pointers, format housekeeping) are zeroed; the recognizer
 * reads geometry only. kdb_nkl_selftest pins the output. */
#include "et9kdb.h"
#include "et9_context.h"
#include "et9_nkl.h"
#include "et9_accents.h"
#include "kdb_internal.h"
#include <string.h>
#include <math.h>
#include <stdint.h>
#if defined(__ANDROID__)
#include <sys/system_properties.h>
/* debug.et9.rady overrides the emitted RADY for experiments; 0/unset = default. */
static int nkl_rady_override(int def) {
    char v[PROP_VALUE_MAX];
    if (__system_property_get("debug.et9.rady", v) > 0 && v[0]) { int n = atoi(v); if (n > 0) return n; }
    return def;
}
#include <stdlib.h>
#else
static int nkl_rady_override(int def) { return def; }
#endif

/* Build one key's char list into `buf` and return its length:
 *   1. the key's own symbols, primary first (codes[]; a codeCount-0 fixture key gives keyCode);
 *   2. the primary's accent variants from the engine's accent table (et9_accents.h, rodata
 *      @0x187d12), minus anything already listed. English: q -> 0071 (1); e -> 0065 00e8 00e9 … (12).
 * The order between the groups is inferred, not read from the engine; it only matters for keys
 * with both keyLabel and keyCodes (Nordic ä/å, Spanish ñ/ª/º, Catalan ·). */
static ET9U32 char_list_for(const ET9KdbKey* k, ET9SYMB* buf, ET9U32 cap) {
    ET9U32 n = 0;
    if (k->codeCount == 0) { if (cap) buf[n++] = k->keyCode; }
    for (ET9U32 i = 0; i < k->codeCount && n < cap; i++) buf[n++] = k->codes[i];
    if (!n) return 0;
    for (int i = 0; i < ET9_ACCENT_TABLE_N; i++) {
        if (kAccentTable[i].base != buf[0]) continue;
        for (ET9U32 j = 0; j < kAccentTable[i].count && n < cap; j++) {
            ET9SYMB s = kAccentTable[i].chars[j];
            ET9U32 seen = 0;
            for (ET9U32 q = 0; q < n; q++) if (buf[q] == s) { seen = 1; break; }
            if (!seen) buf[n++] = s;
        }
        break;
    }
    return n;
}
/* Worst case for one key: its own codes plus the longest accent group (o, 14 entries). */
#define NKL_CHARLIST_MAX (ET9_KDB_MAX_KEY_CODES + 14)

/* Active-KDB content stamp (NKL+0x0c), never validated by the engine. The default gives the offline
 * trace selftest a fixed tag; emit_nkl overwrites it per KDB. */
ET9U32 g_kdb_checksum = 0xBB786DC7u;

/* +0x4c smart-touch search-box bound (engine computer ~0x3d5e0): the minimum, over ordered pairs of
 * regional (type-1) keys, of the overlap area between one key's box (NKL +0x44 x +0x48, centered on
 * the key) and the other key's bbox, divided by 2. */
static ET9U32 agg4c(const ET9KdbLoaded* m, int boxW, int boxH) {
    long mn = -1;
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        if (m->keys[i].type != 1) continue;                      /* regional (letter) keys only */
        int cx = m->keys[i].cx, cy = m->keys[i].cy;
        int bl = cx - boxW / 2, br = (boxW - 1) + bl;             /* box centered on the key */
        int bt = cy - boxH / 2, bb = (boxH - 1) + bt;
        for (ET9U16 j = 0; j < m->keyCount; j++) {
            if (j == i || m->keys[j].type != 1) continue;
            int ox = (br < m->keys[j].right ? br : m->keys[j].right)
                   - (bl > m->keys[j].left  ? bl : m->keys[j].left) + 1;
            int oy = (bb < m->keys[j].bottom ? bb : m->keys[j].bottom)
                   - (bt > m->keys[j].top   ? bt : m->keys[j].top)  + 1;
            if (ox > 0 && oy > 0) { long a = (long)ox * oy; if (mn < 0 || a < mn) mn = a; }
        }
    }
    return mn < 0 ? 0u : (ET9U32)(mn / 2);
}

/* Deterministic per-KDB stamp (FNV-1a/32), not the engine's hash: NKL+0x0c is only copied to
 * ctx+0x44 by SetKdbNum and flows to trace +0x28, so any stable per-layout value works. */
static ET9U32 kdb_stamp(const ET9KdbLoaded* m) {
    ET9U32 hsh = 0x811c9dc5u;
    #define FNV_B(v) do { hsh = (hsh ^ (ET9U32)(ET9U8)(v)) * 0x01000193u; } while (0)
    #define FNV_U16(v) do { FNV_B((v)); FNV_B((v) >> 8); } while (0)
    FNV_U16(m->authoredWidth); FNV_U16(m->authoredHeight); FNV_U16(m->keyCount);
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        FNV_U16(k->keyCode); FNV_B(k->type);
        FNV_U16(k->left); FNV_U16(k->top); FNV_U16(k->right); FNV_U16(k->bottom);
    }
    #undef FNV_B
    #undef FNV_U16
    return hsh;
}

static void wr_u16(void* p, ET9U32 off, ET9U16 v) { ET9U16 t = v; memcpy((char*)p + off, &t, 2); }
static void wr_u32(void* p, ET9U32 off, ET9U32 v) { ET9U32 t = v; memcpy((char*)p + off, &t, 4); }
static void wr_f32(void* p, ET9U32 off, float v)  { float  t = v; memcpy((char*)p + off, &t, 4); }

/* round-half-up of a non-negative float (the engine's +0.5 truncation for derived insets) */
static int iround(float f) { return (int)(f + 0.5f); }

/* Only regional (1) and nonRegional (2) keys grow or are grown toward; function keys (5) are inert
 * ('A' does not grow toward ALT, 'L' does grow toward '$'). */
static int grows(const ET9KdbKey* k) { return k->type == 1 || k->type == 2; }

/* Find the key adjacent to key i on a side (dx,dy in {-1,0,1}); returns index or -1. Grid adjacency:
 * the neighbor's touching edge meets key i's opposite edge and the perpendicular span covers i's center. */
static int neighbor(const ET9KdbLoaded* m, ET9U16 i, int dx, int dy) {
    const ET9KdbKey* a = &m->keys[i];
    for (ET9U16 j = 0; j < m->keyCount; j++) {
        if (j == i) continue;
        const ET9KdbKey* b = &m->keys[j];
        if (dx ==  1 && b->left  == a->right + 1 && b->top <= a->cy && a->cy <= b->bottom) return j;
        if (dx == -1 && b->right == a->left  - 1 && b->top <= a->cy && a->cy <= b->bottom) return j;
        if (dy ==  1 && b->top   == a->bottom+ 1 && b->left<= a->cx && a->cx <= b->right)  return j;
        if (dy == -1 && b->bottom== a->top   - 1 && b->left<= a->cx && a->cx <= b->right)  return j;
    }
    return -1;
}

ET9U32 xt9kdb_emit_nkl(const ET9KdbLoaded* m, void* nkl, ET9U32 buf_size) {
    if (!m || !m->keys || !nkl) return 0;
    /* Layout: header | key array (keyCount*136) | char arena (u16 lists packed in key order). */
    ET9U32 arena_off = NKL_OFF_KEYS + (ET9U32)m->keyCount * NKL_KEY_STRIDE;
    ET9U32 arena_bytes = 0;
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        ET9SYMB cl[NKL_CHARLIST_MAX];
        arena_bytes += char_list_for(&m->keys[i], cl, NKL_CHARLIST_MAX) * 2u;
    }
    ET9U32 need = arena_off + arena_bytes;
    if (buf_size < need) return 0;
    memset(nkl, 0, need);
    ET9U32 arena_cursor = arena_off;   /* running write offset into the char arena */

    wr_u32(nkl, NKL_OFF_FORMAT,   0x00000101u);
    /* pid/sid are bytes (Load_SetProperties @0xb9410: NKL+0x12/+0x13, mirrored at +0x04/+0x05).
     * Finalizer constants: +0x14=15, +0x18=1 (SetProperties w7, 1 for the PKB), +0x1e=2. */
    { unsigned char* h = (unsigned char*)nkl;
      h[0x04] = (unsigned char)m->primaryId;   h[0x05] = (unsigned char)m->secondaryId;
      h[0x12] = (unsigned char)m->primaryId;   h[0x13] = (unsigned char)m->secondaryId; }
    wr_u32(nkl, 0x14, 15u);
    wr_u16(nkl, 0x18, 1u);
    wr_u16(nkl, 0x1e, 2u);
    /* +0x0c content stamp, also cached in g_kdb_checksum for the trace-record writer. Engine
     * values for reference: uniform-108=0xAF5B3D8D, uneven=0xBB786DC7. */
    { ET9U32 stamp = kdb_stamp(m); wr_u32(nkl, NKL_OFF_FMTMAGIC, stamp); g_kdb_checksum = stamp; }
    wr_u16(nkl, NKL_OFF_WIDTH,    (ET9U16)m->authoredWidth);
    wr_u16(nkl, NKL_OFF_HEIGHT,   (ET9U16)m->authoredHeight);
    wr_f32(nkl, NKL_OFF_CORE_FRAC,  0.6f);
    wr_f32(nkl, NKL_OFF_CORE_FRAC2, 0.6f);
    wr_f32(nkl, NKL_OFF_UNITF,    1.0f);
    wr_u32(nkl, NKL_OFF_KEYCOUNT, (ET9U32)m->keyCount);

    /* Header geometry aggregates, fitted to captured layouts. Heights reference the home row (row 2),
     * not the tallest row.
     *   RADX/+0x30 = max letter width       RADY/+0x3c = ROW2 height  (radius² = (RADX²+RADY²)/4)
     *   +0x2c = round(row2H/3)              +0x34 = row1 height
     *   +0x50 = sqrt(maxW² + ((H-row1H)/2)²)   +0x58 = 3*row2H - 2
     *   +0x60 = sqrt(maxW² + (2*row2H + row3H/2 - 1.5*row1H)²)   +0x44 = round(4*maxW/3)   +0x54 = 3*maxW - 2
     *   +0x48 = (13*row2H+36)/10           +0x4c = see agg4c */
    int aw = (int)m->authoredWidth, ah = (int)m->authoredHeight;
    {
        int maxW = 0, maxW_reg = 0;
        /* row heights by distinct top coordinate (row1=topmost, row2=home row, row3=next) */
        int tops[8], th[8], nrows = 0;
        for (ET9U16 i = 0; i < m->keyCount; i++) {
            int kw = (m->keys[i].right - m->keys[i].left) + 1;
            if (kw > maxW) maxW = kw;
            if (grows(&m->keys[i]) && kw > maxW_reg) maxW_reg = kw;
            int t = m->keys[i].top, hh = (m->keys[i].bottom - m->keys[i].top) + 1, seen = 0;
            for (int r = 0; r < nrows; r++) if (tops[r] == t) { seen = 1; break; }
            if (!seen && nrows < 8) { tops[nrows] = t; th[nrows] = hh; nrows++; }
        }
        for (int a = 0; a < nrows; a++) for (int b = a + 1; b < nrows; b++)   /* sort rows top-ascending */
            if (tops[b] < tops[a]) { int tt = tops[a]; tops[a] = tops[b]; tops[b] = tt;
                                     int hh = th[a]; th[a] = th[b]; th[b] = hh; }
        if (maxW <= 0) maxW = 108;
        /* maxW is the regional (letter-key) max: these aggregates size the nearest-key resolver,
         * and a VKB space bar (a wide function key) would otherwise make every letter a row away a
         * regional alternative. kdb_nkl_selftest pins a PKB (RADX 108, +0x44 144) and
         * qwerty_vkb.xml (RADX 120, +0x44 160). */
        if (maxW_reg > 0) maxW = maxW_reg;
        int row1H = nrows > 0 ? th[0] : 108;
        int row2H = nrows > 1 ? th[1] : row1H;
        int row3H = nrows > 2 ? th[2] : row2H;
        float d50 = sqrtf((float)(maxW * maxW) + ((float)(ah - row1H) / 2.0f) * ((float)(ah - row1H) / 2.0f));
        float z60 = 2.0f * row2H + 0.5f * row3H - 1.5f * row1H;
        float d60 = sqrtf((float)(maxW * maxW) + z60 * z60);
        wr_u32(nkl, NKL_OFF_AGG2C,  (ET9U32)iround((float)row2H / 3.0f));
        wr_u32(nkl, NKL_OFF_MAXW2,  (ET9U32)maxW);
        wr_u32(nkl, NKL_OFF_ROW1H,  (ET9U32)row1H);
        wr_u32(nkl, NKL_OFF_RADX,   (ET9U32)maxW);
        /* RADY precedence: debug.et9.rady prop > layout radiusY= attribute > home-row height.
         * The engine's swipe segmenter derives its thresholds from RADY (cluster cut RADY/0.9 x
         * RADY/2, inflection area pi*(RADY/6)^2, angle arc (RADY/3)*pi), tuned for ~108. No single
         * RADY fits uneven row bands, so the attribute is for experiments only; keep the default. */
        int rady_default = (m->authoredRadiusY > 0) ? (int)m->authoredRadiusY : row2H;
        int rady_emit = nkl_rady_override(rady_default);
        wr_u32(nkl, NKL_OFF_RADY,   (ET9U32)rady_emit);
        /* +0x44..+0x60 are two per-page descriptors {box_w, box_h, overlap, diag} from the engine's
         * computer @0x3d35c(NKL, class, pageIdx). Caller @0x6e984 uses class 0 (regional) for
         * page 0 and ctx+0x48's class for page 1, which is 2 (discrete) in every captured layout:
         * box = trunc(1.5*(2*R-1)) = 3R-2 per axis, overlap forced 0. */
        int box_w = iround((float)maxW * 4.0f / 3.0f);   /* +0x44 search-box width  */
        int box_h = (13 * row2H + 36) / 10;               /* +0x48 search-box height */
        wr_u32(nkl, NKL_OFF_AGG44,  (ET9U32)box_w);
        wr_f32(nkl, NKL_OFF_DIAG50, d50);
        wr_u32(nkl, NKL_OFF_AGG54,  (ET9U32)(3 * maxW - 2));  /* page-1 discrete: trunc(1.5*(2*RADX-1)) */
        wr_u32(nkl, NKL_OFF_AGG58,  (ET9U32)(3 * row2H - 2)); /* page-1 discrete: trunc(1.5*(2*RADY-1)) */
        wr_f32(nkl, NKL_OFF_DIAG60, d60);
        wr_u32(nkl, NKL_OFF_AGG48,  (ET9U32)box_h);
        wr_u32(nkl, NKL_OFF_AGG4C,  agg4c(m, box_w, box_h));  /* page-0 regional: min box-overlap>>1 (see agg4c) */
        /* +0x5c (page-1 overlap) stays 0, as the engine's discrete path does. */
    }

    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        char* key = (char*)nkl + NKL_OFF_KEYS + (size_t)i * NKL_KEY_STRIDE;
        int l = k->left, t = k->top, r = k->right, b = k->bottom;
        int w = (r - l) + 1, h = (b - t) + 1;
        wr_u32(key, KEY_OFF_INDEX, i);
        wr_u32(key, KEY_OFF_TYPE,  (ET9U32)k->type);      /* 1 regional / 2 nonRegional / 5 function */
        /* +0x08 flag8: 0 for nonRegional (type 2), 1 for regional and function. */
        wr_u32(key, KEY_OFF_FLAG8, (k->type == 2) ? 0u : 1u);
        wr_u32(key, KEY_OFF_CX,    (ET9U32)k->cx);
        wr_u32(key, KEY_OFF_CY,    (ET9U32)k->cy);

        wr_u16(key, KEY_OFF_BBOX_L, (ET9U16)l);
        wr_u16(key, KEY_OFF_BBOX_T, (ET9U16)t);
        wr_u16(key, KEY_OFF_BBOX_R, (ET9U16)r);
        wr_u16(key, KEY_OFF_BBOX_B, (ET9U16)b);

        /* Protective core: grows(k) keys inset the bbox by 0.2*own dim ('a'=22, '$'=18/22);
         * function keys use the bbox itself. */
        int ix = grows(k) ? iround(0.2f * (float)w) : 0;
        int iy = grows(k) ? iround(0.2f * (float)h) : 0;
        wr_u16(key, KEY_OFF_CORE_L, (ET9U16)(l + ix));
        wr_u16(key, KEY_OFF_CORE_T, (ET9U16)(t + iy));
        wr_u16(key, KEY_OFF_CORE_R, (ET9U16)(r - ix));
        wr_u16(key, KEY_OFF_CORE_B, (ET9U16)(b - iy));

        /* Expanded reach, per side:
         *   board left/top edge      -> no grow (exp = bbox edge)
         *   board right/bottom edge  -> 0xFFFF sentinel
         *   regional neighbor        -> grow by round(0.2 * NEIGHBOR's dim on that axis)
         *   function/absent neighbor -> no grow
         * (Core above uses the key's OWN dim; expanded uses the NEIGHBOR's dim.) */
        int el, et, er, eb;
        int src = grows(k);   /* function-key source is inert: only edge sentinels apply */
        int nL = neighbor(m, i, -1, 0), nR = neighbor(m, i, 1, 0);
        int nA = neighbor(m, i, 0, -1), nB = neighbor(m, i, 0, 1);
        /* left */
        if (l == 0) el = l;
        else if (src && nL >= 0 && grows(&m->keys[nL]))
            el = l - iround(0.2f * (float)((m->keys[nL].right - m->keys[nL].left) + 1));
        else el = l;
        /* top */
        if (t == 0) et = t;
        else if (src && nA >= 0 && grows(&m->keys[nA]))
            et = t - iround(0.2f * (float)((m->keys[nA].bottom - m->keys[nA].top) + 1));
        else et = t;
        /* right */
        if (r == aw - 1) er = 0xFFFF;
        else if (src && nR >= 0 && grows(&m->keys[nR]))
            er = r + iround(0.2f * (float)((m->keys[nR].right - m->keys[nR].left) + 1));
        else er = r;
        /* bottom */
        if (b == ah - 1) eb = 0xFFFF;
        else if (src && nB >= 0 && grows(&m->keys[nB]))
            eb = b + iround(0.2f * (float)((m->keys[nB].bottom - m->keys[nB].top) + 1));
        else eb = b;
        wr_u16(key, KEY_OFF_EXP_L, (ET9U16)el);
        wr_u16(key, KEY_OFF_EXP_T, (ET9U16)et);
        wr_u16(key, KEY_OFF_EXP_R, (ET9U16)er);
        wr_u16(key, KEY_OFF_EXP_B, (ET9U16)eb);

        /* Four resolver center slots, all read by the resolver: +0x30/+0x34 geometric (cx,cy),
         * +0x38/+0x3c biased (cx+bias, cy+bias). */
        wr_u16(key, 0x30, (ET9U16)k->cx);               wr_u16(key, 0x32, (ET9U16)k->cy);
        wr_u16(key, 0x34, (ET9U16)k->cx);               wr_u16(key, 0x36, (ET9U16)k->cy);
        wr_u16(key, 0x38, (ET9U16)(k->cx + k->biasX));  wr_u16(key, 0x3a, (ET9U16)(k->cy + k->biasY));
        wr_u16(key, 0x3c, (ET9U16)(k->cx + k->biasX));  wr_u16(key, 0x3e, (ET9U16)(k->cy + k->biasY));

        /* Char list: +0x40 = count, +0x48 = absolute pointer into the arena in this same buffer. */
        ET9SYMB cl[NKL_CHARLIST_MAX];
        ET9U32 cnt = char_list_for(k, cl, NKL_CHARLIST_MAX);
        char* arena = (char*)nkl + arena_cursor;
        for (ET9U32 j = 0; j < cnt; j++)
            wr_u16(arena, j * 2, cl[j]);
        wr_u32(key, KEY_OFF_WEIGHT, cnt);                 /* +0x40 = alt-char count */
        { uintptr_t p = (uintptr_t)((char*)nkl + arena_cursor);
          memcpy(key + KEY_OFF_LABEL_PTR, &p, sizeof p); } /* +0x48 = char-list pointer */
        arena_cursor += cnt * 2u;
    }
    return need;
}

/* CUTOVER-only: build the owned NKL into the context's KDB buffer and activate it.
 * ctx+0x68 is not a bump allocator: ET9KDB_Init (0xbe848) points both +0x60 (NKL) and +0x68
 * (arena) at the fixed buffer ctx+0x70, and each load overwrites it (Load_Reset span 0x3ef0).
 * Never advance +0x68: SetKdbNum runs nearly every keystroke and a moving arena overwrites
 * live state such as ctx+0xfc30..0xfc38 (view offset/scale). */
#if defined(XT9KDB_CUTOVER)
ET9STATUS xt9kdb_install_nkl(void* ctx) {
    /* Each engine context installs its own model; never the IME's into the spellchecker or back. */
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx);
    if (!ctx || !m) return ET9STATUS_KDB_NOT_LOADED;
    char* buf = *(char**)((char*)ctx + ET9_OFF_ARENA);        /* fixed KDB buffer, NOT a bump head */
    if (!buf) return ET9STATUS_ERROR;
    ET9U32 sz = xt9kdb_emit_nkl(m, buf, 0x3ef0u);             /* 0x3ef0 = the Load_Reset span      */
    if (!sz) return ET9STATUS_ERROR;
    *(void**)((char*)ctx + ET9_OFF_NKL) = buf;                /* activate; +0x68 stays put         */

    /* Stamp the engine loader's "KDB loaded" marker at ctx+0x5a (writers in 0xba264: 0xba2a8
     * clears it when a reload begins, 0xba528 sets 0x1428 on success). ProcessKeyBySymbol rejects
     * every key (status 0x27, 0xc0814) unless ctx+0x5a == ctx+0x58. Because the engine early-outs
     * at 0xba2a0 when the selector matches, it never sets this itself. */
    *(ET9U16*)((char*)ctx + 0x5au) = *(ET9U16*)((char*)ctx + ET9_OFF_MAGIC);
    /* The selection state at ctx+0x04/+0x08 belongs to SetKdbNum, which has the real arguments
     * (+0x04 holds the full selector, not the pid). */
    return ET9STATUS_NONE;
}
#endif
