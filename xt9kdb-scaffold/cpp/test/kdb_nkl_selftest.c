/* kdb_nkl_selftest.c — validate the NKL emitter against the live XT9NKL dump (324 root KDB).
 * Loads the actual root qwerty_pkb.xml, emits the NKL, and checks the geometry fields of three
 * clean keys (a=key[10], s=key[11], d=key[12]) byte-for-byte against the captured dump values. */
#include "et9kdb.h"
#include "et9_context.h"
#include "et9_nkl.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

static int g_fail = 0;
/* header + key array + room for the char arena (multi-code layouts pack more symbols than the
 * 30-key English default; emit_nkl refuses a short buffer outright). */
static unsigned char g_nkl[0x68 + 64 * NKL_KEY_STRIDE + 8192];

static ET9U16 ku16(int idx, ET9U32 koff) {
    unsigned char* k = g_nkl + NKL_OFF_KEYS + (size_t)idx * NKL_KEY_STRIDE;
    ET9U16 v; memcpy(&v, k + koff, 2); return v;
}
static ET9U32 ku32(int idx, ET9U32 koff) {
    unsigned char* k = g_nkl + NKL_OFF_KEYS + (size_t)idx * NKL_KEY_STRIDE;
    ET9U32 v; memcpy(&v, k + koff, 4); return v;
}
static void chk(const char* what, int got, int want) {
    if (got != want) { g_fail++; printf("  FAIL %-22s got=%d want=%d\n", what, got, want); }
    else             { printf("  ok   %-22s = %d\n", what, got); }
}

/* one key's expected geometry (from the XT9NKL dump) */
static void check_key(const char* name, int idx, int cx, int cy, int l, int t, int r, int b,
                      int cl, int ct, int cr, int cb) {
    printf("[%s] key[%d]\n", name, idx);
    chk("cx",     (int)ku32(idx, KEY_OFF_CX),     cx);
    chk("cy",     (int)ku32(idx, KEY_OFF_CY),     cy);
    chk("bbox.l", (int)ku16(idx, KEY_OFF_BBOX_L), l);
    chk("bbox.t", (int)ku16(idx, KEY_OFF_BBOX_T), t);
    chk("bbox.r", (int)ku16(idx, KEY_OFF_BBOX_R), r);
    chk("bbox.b", (int)ku16(idx, KEY_OFF_BBOX_B), b);
    chk("core.l", (int)ku16(idx, KEY_OFF_CORE_L), cl);
    chk("core.t", (int)ku16(idx, KEY_OFF_CORE_T), ct);
    chk("core.r", (int)ku16(idx, KEY_OFF_CORE_R), cr);
    chk("core.b", (int)ku16(idx, KEY_OFF_CORE_B), cb);
    chk("ctrGeo.x",(int)ku16(idx, KEY_OFF_CTR_GEO+0), cx);
    chk("ctrGeo.y",(int)ku16(idx, KEY_OFF_CTR_GEO+2), cy);
}

/* Load a KDB XML and emit its NKL into g_nkl. Returns 0 on success. */
static int load_and_emit(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) { printf("cannot open %s\n", path); return 2; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* xml = malloc((size_t)n);
    if (!xml) { fclose(f); printf("oom\n"); return 2; }
    if (fread(xml, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(xml); printf("read fail\n"); return 2; }
    fclose(f);

    int dummy = 1;
    xt9kdb_Init((ET9KDBInfoPtr)&dummy);
    ET9STATUS st = xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)n);
    free(xml);
    if (st != ET9STATUS_NONE || !g_kdb_model) { printf("load failed st=%d\n", (int)st); return 2; }
    printf("loaded %ux%u keys=%u  pid=%u sid=%u\n", g_kdb_model->authoredWidth, g_kdb_model->authoredHeight,
           g_kdb_model->keyCount, g_kdb_model->primaryId, g_kdb_model->secondaryId);

    memset(g_nkl, 0, sizeof(g_nkl));
    if (!xt9kdb_emit_nkl(g_kdb_model, g_nkl, sizeof(g_nkl))) { printf("emit failed\n"); return 2; }
    return 0;
}

/* --vkb mode. The on-screen layouts are the ONLY ones where "max over all keys" and "max over
 * regional keys" differ, and no probe capture distinguishes the two rules — which is exactly how
 * the wrong one shipped and went unnoticed for months (beta-feedback triage #6). qwerty_vkb.xml's
 * SPACE is a 540px function key against a 120px widest letter, so this case fails loudly on a
 * regression to the all-keys max: RADX would read 540, the search box 720 (spanning +-3.3 keys at
 * a 108px pitch) and +0x54 1618. Device-confirmed on a KEY2 in forced-VKB mode, 2026-09-16:
 * radius(0x38)=120,140 and +0x44=0xa0.
 *
 * Runs as its own process (its own stage in run_tests.sh) rather than as a second load inside the
 * pkb run: re-Init-ing and re-loading in one process leaves g_kdb_model on the first layout, and
 * the live code only ever has one KDB active per context anyway. */
static int check_vkb_header(const char* vkbPath) {
    printf("[vkb regional-maxW] %s\n", vkbPath);
    if (load_and_emit(vkbPath) != 0) { printf("  FAIL could not load the VKB layout\n"); return 2; }
    ET9U16 hw, hh; memcpy(&hw, g_nkl + NKL_OFF_WIDTH, 2); memcpy(&hh, g_nkl + NKL_OFF_HEIGHT, 2);
    chk("width",  hw, 1080);
    chk("height", hh, 600);
    ET9U32 h;
    memcpy(&h, g_nkl + NKL_OFF_RADX,  4); chk("RADX +0x38 (letters)", (int)h, 120);
    memcpy(&h, g_nkl + NKL_OFF_MAXW2, 4); chk("maxW +0x30",           (int)h, 120);
    memcpy(&h, g_nkl + NKL_OFF_AGG44, 4); chk("searchbox +0x44",      (int)h, 160);  /* round(120*4/3) */
    memcpy(&h, g_nkl + NKL_OFF_AGG54, 4); chk("agg +0x54",            (int)h, 358);  /* 3*120-2 */
    printf(g_fail ? "\n==== NKL vkb: %d FAILED ====\n" : "\n==== NKL vkb: regional maxW in force (RADX 120, not 540) ====\n", g_fail);
    return g_fail ? 1 : 0;
}

/* --dump <kdb.xml>: print the emitted NKL as deterministic text, for checking a loader change
 * layout-by-layout (the committed before/after pairs under docs/2026-09_kdb-touch-abi_audit/).
 *
 * Deterministic on purpose: the per-key char-list pointer at +0x48 is an ABSOLUTE address into
 * whatever buffer the NKL was emitted into, so it is printed as the OFFSET from the NKL base.
 * Everything else is emitted bytes, read back through the same accessors the checks above use. */
static int dump_nkl(const char* path) {
    if (load_and_emit(path) != 0) return 2;
    const ET9KdbLoaded* m = g_kdb_model;
    ET9U32 u; float f; ET9U16 w;
    #define HU32(off) (memcpy(&u, g_nkl + (off), 4), u)
    #define HU16(off) (memcpy(&w, g_nkl + (off), 2), w)
    #define HF32(off) (memcpy(&f, g_nkl + (off), 4), f)
    printf("NKL dump: %s\n", path);
    printf("header\n");
    printf("  +0x00 format      = 0x%08x\n", HU32(NKL_OFF_FORMAT));
    printf("  +0x04 pid/sid     = %u/%u\n", g_nkl[0x04], g_nkl[0x05]);
    printf("  +0x0c stamp       = (content hash, geometry only — omitted)\n");
    printf("  +0x12 pid/sid     = %u/%u\n", g_nkl[0x12], g_nkl[0x13]);
    printf("  +0x14             = %u\n", HU32(0x14));
    printf("  +0x18             = %u\n", HU16(0x18));
    printf("  +0x1a width       = %u\n", HU16(NKL_OFF_WIDTH));
    printf("  +0x1c height      = %u\n", HU16(NKL_OFF_HEIGHT));
    printf("  +0x1e             = %u\n", HU16(0x1e));
    printf("  +0x20 coreFracX   = %.3f\n", (double)HF32(NKL_OFF_CORE_FRAC));
    printf("  +0x24 coreFracY   = %.3f\n", (double)HF32(NKL_OFF_CORE_FRAC2));
    printf("  +0x2c agg         = %u\n", HU32(NKL_OFF_AGG2C));
    printf("  +0x30 maxW        = %u\n", HU32(NKL_OFF_MAXW2));
    printf("  +0x34 row1H       = %u\n", HU32(NKL_OFF_ROW1H));
    printf("  +0x38 RADX        = %u\n", HU32(NKL_OFF_RADX));
    printf("  +0x3c RADY        = %u\n", HU32(NKL_OFF_RADY));
    printf("  +0x40 unit        = %.3f\n", (double)HF32(NKL_OFF_UNITF));
    printf("  +0x44 boxW        = %u\n", HU32(NKL_OFF_AGG44));
    printf("  +0x48 boxH        = %u\n", HU32(NKL_OFF_AGG48));
    printf("  +0x4c overlap     = %u\n", HU32(NKL_OFF_AGG4C));
    printf("  +0x50 diag        = %.3f\n", (double)HF32(NKL_OFF_DIAG50));
    printf("  +0x54 agg         = %u\n", HU32(NKL_OFF_AGG54));
    printf("  +0x58 agg         = %u\n", HU32(NKL_OFF_AGG58));
    printf("  +0x60 diag        = %.3f\n", (double)HF32(NKL_OFF_DIAG60));
    printf("  +0x64 keyCount    = %u\n", HU32(NKL_OFF_KEYCOUNT));
    printf("keys\n");
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        const unsigned char* k = g_nkl + NKL_OFF_KEYS + (size_t)i * NKL_KEY_STRIDE;
        unsigned long long ptr; memcpy(&ptr, k + KEY_OFF_LABEL_PTR, 8);
        size_t aoff = (size_t)(ptr - (unsigned long long)(uintptr_t)g_nkl);
        ET9U32 cnt = ku32(i, KEY_OFF_WEIGHT);
        printf("  key[%2u] idx=%u type=%u flag8=%u c=(%u,%u) bbox=(%u,%u,%u,%u) "
               "core=(%u,%u,%u,%u) exp=(%u,%u,%u,%u) geo=(%u,%u) bias=(%u,%u)\n",
               i, ku32(i, KEY_OFF_INDEX), ku32(i, KEY_OFF_TYPE), ku32(i, KEY_OFF_FLAG8),
               ku32(i, KEY_OFF_CX), ku32(i, KEY_OFF_CY),
               ku16(i, KEY_OFF_BBOX_L), ku16(i, KEY_OFF_BBOX_T), ku16(i, KEY_OFF_BBOX_R), ku16(i, KEY_OFF_BBOX_B),
               ku16(i, KEY_OFF_CORE_L), ku16(i, KEY_OFF_CORE_T), ku16(i, KEY_OFF_CORE_R), ku16(i, KEY_OFF_CORE_B),
               ku16(i, KEY_OFF_EXP_L), ku16(i, KEY_OFF_EXP_T), ku16(i, KEY_OFF_EXP_R), ku16(i, KEY_OFF_EXP_B),
               ku16(i, KEY_OFF_CTR_GEO), ku16(i, KEY_OFF_CTR_GEO + 2),
               ku16(i, KEY_OFF_CTR_BIAS), ku16(i, KEY_OFF_CTR_BIAS + 2));
        printf("          +0x40 chars=%u  +0x48 arena=+0x%04zx  [", cnt, aoff);
        for (ET9U32 j = 0; j < cnt; j++) {
            ET9U16 s; memcpy(&s, (const unsigned char*)(uintptr_t)ptr + j * 2, 2);
            printf("%s%04x", j ? " " : "", s);
        }
        printf("]\n");
    }
    printf("truncated keys = %u\n", xt9kdb_key_code_truncations());
    #undef HU32
    #undef HU16
    #undef HF32
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 1 && strcmp(argv[1], "--dump") == 0)
        return argc > 2 ? dump_nkl(argv[2]) : (printf("usage: nkl --dump <kdb.xml>\n"), 2);
    if (argc > 1 && strcmp(argv[1], "--vkb") == 0)
        return check_vkb_header((argc > 2) ? argv[2] : "../../app/src/main/assets/kdb/qwerty_vkb.xml");

    const char* path = (argc > 1) ? argv[1] : "../../app/src/main/assets/kdb/qwerty_pkb.xml";
    if (load_and_emit(path) != 0) return 2;

    /* header checks */
    ET9U16 hw, hh; memcpy(&hw, g_nkl + NKL_OFF_WIDTH, 2); memcpy(&hh, g_nkl + NKL_OFF_HEIGHT, 2);
    ET9U32 kc; memcpy(&kc, g_nkl + NKL_OFF_KEYCOUNT, 4);
    printf("[header]\n"); chk("width", hw, 1080); chk("height", hh, 324); chk("keyCount", (int)kc, 30);

    /* header geometry aggregates — device-confirmed (uniform-108/324 == probe A capture) */
    ET9U32 h; float hf;
    memcpy(&h,  g_nkl + NKL_OFF_AGG2C, 4); chk("agg+0x2c (maxH/3)", (int)h, 36);
    memcpy(&h,  g_nkl + NKL_OFF_ROW1H, 4); chk("row1H +0x34", (int)h, 108);
    memcpy(&h,  g_nkl + NKL_OFF_RADX,  4); chk("RADX +0x38", (int)h, 108);
    memcpy(&h,  g_nkl + NKL_OFF_RADY,  4); chk("RADY +0x3c", (int)h, 108);
    memcpy(&h,  g_nkl + NKL_OFF_AGG44, 4); chk("const +0x44", (int)h, 144);
    memcpy(&hf, g_nkl + NKL_OFF_DIAG50,4); chk("diag +0x50 (x10)", (int)(hf*10+0.5f), 1527); /* sqrt(108^2+108^2)=152.7 */
    memcpy(&h,  g_nkl + NKL_OFF_AGG54, 4); chk("const +0x54", (int)h, 322);
    memcpy(&h,  g_nkl + NKL_OFF_AGG58, 4); chk("agg +0x58", (int)h, 322);  /* H-row1H+maxH-2 = 324-108+108-2 */

    /* expanded-region rule (device-confirmed 30/30 vs probe B, 29/30 vs probe A): grow by
     * round(0.2*neighbor dim) toward regional/nonRegional neighbors; right/bottom board edge -> 0xFFFF */
    printf("[expanded regions]\n");
    #define EXP(idx,el,et,er,eb) do{ \
        chk("exp.l", (int)ku16(idx,KEY_OFF_EXP_L), el); chk("exp.t", (int)ku16(idx,KEY_OFF_EXP_T), et); \
        chk("exp.r", (int)ku16(idx,KEY_OFF_EXP_R), er); chk("exp.b", (int)ku16(idx,KEY_OFF_EXP_B), eb);}while(0)
    EXP(0,  0,   0, 129, 129);   /* q: top-left, grows right+down 22 */
    EXP(10, 0,  86, 129, 215);   /* a: left edge; up-grow 22 into row1; ALT (function) below -> no down-grow */
    EXP(11, 86, 86, 237, 237);   /* s: interior, grows all four sides 22 */

    /* dump-derived expectations (324 root KDB, 108px keys, inclusive rects) */
    check_key("a", 10,  53, 161,   0, 108, 107, 215,   22, 130,  85, 193);
    check_key("s", 11, 161, 161, 108, 108, 215, 215,  130, 130, 193, 193);
    check_key("d", 12, 269, 161, 216, 108, 323, 215,  238, 130, 301, 193);

    printf(g_fail ? "\n==== NKL emitter: %d FAILED ====\n" : "\n==== NKL emitter: all geometry matches dump ====\n", g_fail);
    return g_fail ? 1 : 0;
}
