/* kdb_nkl.c — emit our parsed KDB geometry into the blob's in-memory NKL format (et9_nkl.h).
 *
 * Option-1 handoff: the owned module OWNS the geometry by producing this structure; the blob's
 * TouchMove/End store the path and ET9_CP_Trace recognizes it, reading the geometry from here.
 * Validated offline against the live XT9NKL dump (kdb_nkl_selftest). Opaque blob fields (label
 * pointers, format housekeeping) are zeroed — the recognizer reads geometry, not those.
 */
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
/* debug.et9.rady : override the emitted RADY (home-row height) at NKL build time, to A/B whether
 * the swipe segmentation over-segments because of the uneven-band RADY. 0/unset = use row2H. */
static int nkl_rady_override(int def) {
    char v[PROP_VALUE_MAX];
    if (__system_property_get("debug.et9.rady", v) > 0 && v[0]) { int n = atoi(v); if (n > 0) return n; }
    return def;
}
#include <stdlib.h>
#else
static int nkl_rady_override(int def) { return def; }
#endif

/* Build one key's char list into `buf` and return its length. The list is, in order:
 *
 *   1. the key's own symbols, primary first — ET9KdbKey.codes[0..codeCount-1], which is exactly
 *      what the layout's keyLabel/keyCodes gave (L13). A key with codeCount 0 (the static
 *      bring-up fixtures) contributes its keyCode alone, as before.
 *   2. the long-press/accent variants of the PRIMARY, for base letters that have an entry in the
 *      blob's own accent table (et9_accents.h, extracted verbatim from rodata @0x187d12), minus
 *      anything the layout already listed.
 *
 * Step 2 alone is the pre-L13 behaviour and is what the live XT9NKL capture pins for English
 * (q -> 0071 count 1; e -> 0065 00e8 00e9 … count 12): no qwerty_* layout carries keyCodes at
 * all, so for those layouts step 1 contributes only the primary and the emitted bytes are
 * unchanged. Order between the two groups is the one place the blob's loader could not be read
 * off the binary — its XML attribute names are not in the .so at all, so the tokenizer's
 * attribute dispatch could not be followed — and "what the layout states explicitly outranks
 * what the engine adds generically" is the reading that matches the `addAltChars="true"` header
 * flag (the engine's variants are an ADDITION to the authored list). It only ever matters for the
 * dozen Latin PKB keys that carry both a keyLabel and a keyCodes list (Nordic ä/å, Spanish ñ/ª/º,
 * Catalan ·): the SET of symbols is the same either way, only the order differs. */
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

/* Active-KDB content stamp (NKL+0x0c). Default = uneven KDB's value so the offline trace selftest sees a
 * fixed tag; emit_nkl overwrites it per loaded KDB. See kdb_internal.h — the engine never validates it. */
ET9U32 g_kdb_checksum = 0xBB786DC7u;

/* +0x4c smart-touch search-box bound (the resolver reads it). CRACKED by RE of the computer (a double
 * loop over regional keys at file offset ~0x3d5e0, found via a desync-proof byte scan for sqrtf callers +
 * key-stride iteration): it is the MINIMUM, over ordered pairs of regional (type-1) keys, of the overlap
 * AREA between one key's smart-touch box (boxW×boxH = NKL +0x44/+0x48, centered on the key) and another
 * key's bbox — divided by 2 (the min is the diagonal-corner overlap). Reproduces all 5 captured layouts
 * exactly (A144 B195 U238 C161 D119), so it generalizes to any layout. See docs/2026-07_re-nkl-struct-findings_reference.md. */
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

/* Deterministic per-KDB stamp over the geometry (FNV-1a/32). NOT the blob's exact algorithm — RE showed
 * NKL+0x0c is an UNVALIDATED identity stamp (SetKdbNum only copies it to ctx+0x44; it flows to trace
 * +0x28), so a stable per-layout value is all that's needed. Deterministic ⇒ same KDB ⇒ same stamp. */
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

/* round-half-up of a non-negative float (matches the blob's +0.5 truncation for these derived insets) */
static int iround(float f) { return (int)(f + 0.5f); }

/* A key participates in the expanded-region grow (as source and as a grow-toward neighbor) iff it is
 * regional (type 1) or nonRegional (type 2). Function keys (type 5: BACK, ALT, RETURN) are inert — they
 * neither grow nor are grown toward. Device-confirmed across the probe captures (e.g. 'A' doesn't grow
 * down toward ALT/function, but 'L' DOES grow down toward '$'/nonRegional). */
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
    /* Layout: header | key array (keyCount*136) | char arena (all keys' u16 lists, packed back-to-back
     * in key order — device-confirmed contiguous). Compute the full size (incl. arena) before memset. */
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

    /* ---- header ---- */
    wr_u32(nkl, NKL_OFF_FORMAT,   0x00000101u);
    /* pid/sid are stored as BYTES (RE ET9KDB_Load_SetProperties @0xb9410: strb pid->NKL+0x12,
     * strb sid->NKL+0x13; the finalizer mirrors the pair at +0x04/+0x05). Our old u16 writes put sid at
     * +0x06 — wrong. Plus three finalizer constants: +0x14=15, +0x18=1 (SetProperties w7, =1 for the PKB),
     * +0x1e=2. (+0x06/07, +0x10/11, +0x15/16/17 stay 0 from the memset.) */
    { unsigned char* h = (unsigned char*)nkl;
      h[0x04] = (unsigned char)m->primaryId;   h[0x05] = (unsigned char)m->secondaryId;
      h[0x12] = (unsigned char)m->primaryId;   h[0x13] = (unsigned char)m->secondaryId; }
    wr_u32(nkl, 0x14, 15u);
    wr_u16(nkl, 0x18, 1u);
    wr_u16(nkl, 0x1e, 2u);
    /* +0x0c = per-KDB content stamp. RE-confirmed UNVALIDATED (SetKdbNum only copies it to ctx+0x44; it
     * flows into trace record +0x28), so we emit our own deterministic per-layout value rather than the
     * blob's exact (irrelevant) hash. Also cached in g_kdb_checksum for the trace-record writer. The
     * blob's own values (uniform-108=0xAF5B3D8D, uneven=0xBB786DC7) are documented but not reproduced. */
    { ET9U32 stamp = kdb_stamp(m); wr_u32(nkl, NKL_OFF_FMTMAGIC, stamp); g_kdb_checksum = stamp; }
    wr_u16(nkl, NKL_OFF_WIDTH,    (ET9U16)m->authoredWidth);
    wr_u16(nkl, NKL_OFF_HEIGHT,   (ET9U16)m->authoredHeight);
    wr_f32(nkl, NKL_OFF_CORE_FRAC,  0.6f);
    wr_f32(nkl, NKL_OFF_CORE_FRAC2, 0.6f);
    wr_f32(nkl, NKL_OFF_UNITF,    1.0f);
    wr_u32(nkl, NKL_OFF_KEYCOUNT, (ET9U32)m->keyCount);

    /* Header geometry aggregates — fit + validated across 4 captured layouts (uniform-108/324,
     * uniform-150/450, uneven-90|180|180/450, distinct-60|120|240/420). The KEY reference is the
     * HOME ROW (row 2) height, not the max — probe C (row2 != maxH) proved RADY/+0x2c/+0x50/+0x58 are
     * all row2-based (they only *looked* like maxH when row2==maxH). See docs/2026-07_re-nkl-struct-findings_reference.md.
     *   RADX/+0x30 = max key width          RADY/+0x3c = ROW2 height  (radius² = (RADX²+RADY²)/4)
     *   +0x2c = round(row2H/3)              +0x34 = row1 height
     *   +0x50 = sqrt(maxW² + ((H-row1H)/2)²)   +0x58 = 3*row2H - 2
     *   +0x60 = sqrt(maxW² + (2*row2H + row3H/2 - 1.5*row1H)²)   +0x44 = 144   +0x54 = 322
     *   +0x48/+0x4c: cosmetic, no clean closed form from 4 points; left 0 (not on recognition path). */
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
        /* maxW is the REGIONAL (letter-key) max, not the max over every key. The aggregates below
         * (RADX, the +0x44 search box, the +0x4c overlap threshold, +0x54 and the two diagonals)
         * size the blob's nearest-key resolver, so a wide FUNCTION key must not inflate them.
         *
         * Every PKB probe the formulas were fitted against has uniform key widths (108/108/108/90
         * across all three key types), so "max over all keys" and "max over regional keys" were
         * indistinguishable there and the wrong one was picked. It only shows on the on-screen
         * layouts, whose SPACE bar is a function key 4.5x the widest letter: qwerty_vkb.xml gave
         * RADX 540 and a 720px search box spanning +-3.3 keys at a 108px pitch, so every letter a
         * row above or below became a regional alternative and LDB frequency ranked the list
         * ("hello" -> "Group"). 37 of the 75 shipped layouts are affected, all of them VKB; no PKB
         * layout changes, which is why the KEY2 was always immune.
         *
         * Emulator A/B against the original APK (docs/2026-09_spacebar-ab/, beta-feedback triage
         * #6): agreement 32% -> 70%, typo correction 2/18 -> 12/18, valid words kept 9/14 -> 13/14.
         * Confirmed on a KEY2 in forced-VKB mode 2026-09-16: RADX 540 -> 120, +0x44 720 -> 160,
         * while the PKB layout in the same log stayed at 108/144.
         *
         * Parity: all four ground-truth probes in nkl_probes/ have identical maxW under both rules,
         * so no capture the formulas were validated against can change. kdb_nkl_selftest pins both
         * a PKB layout (RADX 108, +0x44 144) and qwerty_vkb.xml (RADX 120, +0x44 160). */
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
         *
         * WHY the attribute exists (2026-08-14, remediation log §8.6): RADY is not just tap
         * geometry — the blob's swipe segmenter derives EVERY clustering/inflection threshold
         * from it (cluster cut RADY/0.9 x RADY/2, inflection area pi*(RADY/6)^2, angle-arc
         * (RADY/3)*pi — TRACE_RESULT_REGION.md passes 4-5), tuned around the even-grid
         * invariant home-row height == row pitch ~= 108. The athena variant's true sensor
         * bands (90/180/180) put 180 here and inflated every threshold 1.67x: short terminal
         * transitions (hello's l->o hook) stopped segmenting, hello -> help/he'll 10/10.
         * radiusY="108" on the athena layouts restored the tuned scale for the hello class
         * (0/10 -> 10/10, single-knob, virgin DLM) — but corpus-A then showed the flip side:
         * the tightened cluster cuts over-segment transits through the 180px rows
         * (done -> "songs", WSI count 9 for a 4-key word; exactly the doc pass-5 class), so
         * NO single RADY fits uneven bands and the athena layouts carry no radiusY today.
         * The attribute stays for experiments; the production fix is the owned per-row-
         * threshold gesture decoder (remediation log §8.7). The home-row DEFAULT here is
         * blob-parity and must stay for un-annotated layouts.
         *
         * PREVIOUS FIXES, superseded: debug.et9.rady sysprop (session-only, dies on reboot);
         * forcing the original flat 324 KDB + Y*(324/450) input scaling (worked only because
         * the flat KDB's home row is 108 — same value by accident of geometry); the
         * athena-wide radiusY="108" stamps (reverted, see above). */
        int rady_default = (m->authoredRadiusY > 0) ? (int)m->authoredRadiusY : row2H;
        int rady_emit = nkl_rady_override(rady_default);
        wr_u32(nkl, NKL_OFF_RADY,   (ET9U32)rady_emit);
        /* +0x44..+0x60 are TWO per-page descriptors {box_w, box_h, overlap, diag}, filled by the
         * blob's descriptor computer @0x3d35c(NKL, class, pageIdx).  Its caller @0x6e984 hardcodes
         * page 0 = class 0 (REGIONAL: min-overlap math below) and passes ctx+0x48's class for
         * page 1, which is 2 (DISCRETE) in every captured layout: box = trunc(1.5*(2*R-1)) = 3R-2
         * per axis, overlap forced 0 (str wzr).  Page 0 never takes the discrete path. */
        int box_w = iround((float)maxW * 4.0f / 3.0f);   /* +0x44 search-box width  (device-confirmed) */
        int box_h = (13 * row2H + 36) / 10;               /* +0x48 search-box height (device-confirmed) */
        wr_u32(nkl, NKL_OFF_AGG44,  (ET9U32)box_w);
        wr_f32(nkl, NKL_OFF_DIAG50, d50);
        wr_u32(nkl, NKL_OFF_AGG54,  (ET9U32)(3 * maxW - 2));  /* page-1 discrete: trunc(1.5*(2*RADX-1)) */
        wr_u32(nkl, NKL_OFF_AGG58,  (ET9U32)(3 * row2H - 2)); /* page-1 discrete: trunc(1.5*(2*RADY-1)) */
        wr_f32(nkl, NKL_OFF_DIAG60, d60);
        wr_u32(nkl, NKL_OFF_AGG48,  (ET9U32)box_h);
        wr_u32(nkl, NKL_OFF_AGG4C,  agg4c(m, box_w, box_h));  /* page-0 regional: min box-overlap>>1 (see agg4c) */
        /* +0x5c (page-1 overlap) deliberately left 0 — matches the blob's discrete path. */
    }

    /* ---- per-key ---- */
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const ET9KdbKey* k = &m->keys[i];
        char* key = (char*)nkl + NKL_OFF_KEYS + (size_t)i * NKL_KEY_STRIDE;
        int l = k->left, t = k->top, r = k->right, b = k->bottom;
        int w = (r - l) + 1, h = (b - t) + 1;
        wr_u32(key, KEY_OFF_INDEX, i);
        wr_u32(key, KEY_OFF_TYPE,  (ET9U32)k->type);      /* 1 regional / 2 nonRegional / 5 function */
        /* +0x08 flag8: device-verified 0 for nonRegional (type 2), 1 for regional & function. */
        wr_u32(key, KEY_OFF_FLAG8, (k->type == 2) ? 0u : 1u);
        wr_u32(key, KEY_OFF_CX,    (ET9U32)k->cx);
        wr_u32(key, KEY_OFF_CY,    (ET9U32)k->cy);

        wr_u16(key, KEY_OFF_BBOX_L, (ET9U16)l);
        wr_u16(key, KEY_OFF_BBOX_T, (ET9U16)t);
        wr_u16(key, KEY_OFF_BBOX_R, (ET9U16)r);
        wr_u16(key, KEY_OFF_BBOX_B, (ET9U16)b);

        /* protective core: regional/nonRegional keys inset the bbox by 0.2*dim (verified 'a'=22, '$'=18/22);
         * function keys (type 5) take the bbox itself as the core — no inset (device-verified backspace/
         * alt/enter). Core-inset eligibility == grow eligibility == grows(k). */
        int ix = grows(k) ? iround(0.2f * (float)w) : 0;
        int iy = grows(k) ? iround(0.2f * (float)h) : 0;
        wr_u16(key, KEY_OFF_CORE_L, (ET9U16)(l + ix));
        wr_u16(key, KEY_OFF_CORE_T, (ET9U16)(t + iy));
        wr_u16(key, KEY_OFF_CORE_R, (ET9U16)(r - ix));
        wr_u16(key, KEY_OFF_CORE_B, (ET9U16)(b - iy));

        /* Expanded reach (device-confirmed general rule, per side):
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

        /* FOUR resolver center slots at +0x30/+0x34/+0x38/+0x3c (the blob writes the center to all four;
         * device-verified). +0x30/+0x34 = geometric (cx,cy); +0x38/+0x3c = smart-touch-biased
         * (cx+bias, cy+bias). With no per-key bias loaded (our current layouts) all four equal (cx,cy),
         * matching the blob byte-for-byte. Previously only +0x30/+0x38 were written, leaving +0x34/+0x3c
         * zero — the resolver reads those, so a tap/gesture near such a key mis-scored. */
        wr_u16(key, 0x30, (ET9U16)k->cx);               wr_u16(key, 0x32, (ET9U16)k->cy);
        wr_u16(key, 0x34, (ET9U16)k->cx);               wr_u16(key, 0x36, (ET9U16)k->cy);
        wr_u16(key, 0x38, (ET9U16)(k->cx + k->biasX));  wr_u16(key, 0x3a, (ET9U16)(k->cy + k->biasY));
        wr_u16(key, 0x3c, (ET9U16)(k->cx + k->biasX));  wr_u16(key, 0x3e, (ET9U16)(k->cy + k->biasY));

        /* char list: +0x40 = count, +0x48 = pointer into the char arena (absolute; the arena lives in
         * this same buffer, so the pointer is (nkl base + cursor) — valid at whatever address the NKL
         * is placed, on device or in the offline harness). */
        ET9SYMB cl[NKL_CHARLIST_MAX];
        ET9U32 cnt = char_list_for(k, cl, NKL_CHARLIST_MAX);
        char* arena = (char*)nkl + arena_cursor;
        for (ET9U32 j = 0; j < cnt; j++)
            wr_u16(arena, j * 2, cl[j]);
        wr_u32(key, KEY_OFF_WEIGHT, cnt);                 /* +0x40 = alt-char count (was wrongly 1) */
        { uintptr_t p = (uintptr_t)((char*)nkl + arena_cursor);
          memcpy(key + KEY_OFF_LABEL_PTR, &p, sizeof p); } /* +0x48 = char-list pointer */
        arena_cursor += cnt * 2u;
    }
    return need;
}

/* CUTOVER-only: build the owned NKL into the context's KDB buffer and make it active, replacing what
 * the blob's Load_XmlKDB would do.
 *
 * ⚠️ ctx+0x68 IS NOT A BUMP ALLOCATOR. Reversing blob ET9KDB_Init (0xbe848) settles it:
 *      add x1, x19, #0x70
 *      str x1, [x19, #0x60]      ; NKL   = ctx+0x70
 *      str x1, [x19, #0x68]      ; arena = ctx+0x70
 * Both point at the SAME fixed buffer inside the context, and no blob code ever advances +0x68 —
 * each KDB load simply overwrites the one buffer (which is why Load_Reset memsets 0x3ef0 bytes
 * there rather than allocating).
 *
 * We used to advance it on every load. Because SetKdbNum is called on essentially every keystroke,
 * the "arena" marched up through the context: measured live at ctx+0xf388 after one typing session.
 * A 0x3ef0-byte NKL emitted there spans ctx+0xf388..0x13278, straight over ctx+0xfc30..0xfc38 —
 * which ProcessKeyBySymbol reads as the view offset/scale fields (`add x2, x19, #0xf, lsl #12` then
 * `ldrh [x2, #0xc30]`), and which 0xba7e0 keeps in sync with the trace object. So we were steadily
 * overwriting live engine state, and the damage grew with every keystroke.
 *
 * Fix: emit at the buffer and leave +0x68 alone, exactly as the blob does. */
#if defined(XT9KDB_CUTOVER)
ET9STATUS xt9kdb_install_nkl(void* ctx) {
    /* Per-ctx model: each engine context installs ITS OWN geometry into ITS arena — the
     * spellchecker's NKL must never be built from the IME's model or vice versa. */
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx);
    if (!ctx || !m) return ET9STATUS_KDB_NOT_LOADED;
    char* buf = *(char**)((char*)ctx + ET9_OFF_ARENA);        /* fixed KDB buffer, NOT a bump head */
    if (!buf) return ET9STATUS_ERROR;
    ET9U32 sz = xt9kdb_emit_nkl(m, buf, 0x3ef0u);             /* 0x3ef0 = the Load_Reset span      */
    if (!sz) return ET9STATUS_ERROR;
    *(void**)((char*)ctx + ET9_OFF_NKL) = buf;                /* activate; +0x68 stays put         */

    /* Stamp "a KDB is loaded" at ctx+0x5a. This is the blob loader's completion marker, and owning
     * the loader means owning the marker. Both writers live inside 0xba264:
     *     0xba2a8  strh wzr, [x19, #0x5a]   ; cleared when a full reload BEGINS
     *     0xba528  strh w1,  [x19, #0x5a]   ; set to 0x1428 when that reload SUCCEEDS
     * ET9KDB_ProcessKeyBySymbol refuses to touch a keystroke unless ctx+0x5a == ctx+0x58
     * (0xc0814-0xc0820, status 0x27), so without this every typed character is rejected outright
     * and the engine never sees a word — the 2026-07/08 "frozen suggestions" bug.
     *
     * Note the trap: making the selector match (so the blob early-outs at 0xba2a0 and preserves our
     * NKL) also means the blob never reaches 0xba528. Doing the KDB load ourselves and letting the
     * blob skip its own is exactly what leaves this marker unset. */
    *(ET9U16*)((char*)ctx + 0x5au) = *(ET9U16*)((char*)ctx + ET9_OFF_MAGIC);
    /* (5) The selection state at ctx+0x04/+0x08 is NOT written here. It is SetKdbNum's to own,
     * because only SetKdbNum has the real arguments — this function would have to reconstruct
     * them from the model and, before 2026-08-03, reconstructed them wrongly (pid alone at +0x04,
     * where the blob keeps the full selector). See kdb_state.c and docs/libnative-documentation.md. */
    return ET9STATUS_NONE;
}
#endif
