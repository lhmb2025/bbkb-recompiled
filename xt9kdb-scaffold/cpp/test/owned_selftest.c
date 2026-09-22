/* owned_selftest.c — standalone self-tests for the owned ET9KDB module (DIFF mode, xt9kdb_*).
 *
 * Runs WITHOUT the blob (pure owned code), so it executes anywhere the owned sources compile —
 * including this CI/sandbox. It validates the confidently-RE'd, data-driven pieces end to end:
 *   1. XML round-trip      : Load_XmlKDB(athena qwerty_pkb) == the verified 30-key fixture
 *   2. Selection lifecycle : SetKdbNum variant override + root fallback, GetKdbNum, Invalidate
 *   3. Geometry resolution : every key center resolves to itself; ByTap (sensor+offset) -> code
 *
 * Build:  gcc -std=c11 -Iinclude src/[a-z]*.c test/owned_selftest.c -lm -o owned_selftest
 * Run:    ./owned_selftest path/to/kdb/athena/qwerty_pkb.xml
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include "et9_nkl.h"
#include <stdint.h>

/* Touch* take arrays (count-based) since the 2026-08-03 signature correction; these keep the
 * old scalar call shape readable in the tests. */
static void tt_start(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    xt9kdb_TouchStart(c, 0, xs, ys, ts, 1, 0);
}
static void tt_move(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    xt9kdb_TouchMove(c, 0, xs, ys, ts, 1, 0);
}
static void tt_end(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    xt9kdb_TouchEnd(c, 0, xs, ys, ts, 1);
}

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, msg, ...) do { \
    if (cond) { g_pass++; } \
    else { g_fail++; printf("  FAIL: " msg "\n", ##__VA_ARGS__); } } while (0)

static unsigned char* slurp(const char* p, long* n) {
    FILE* f = fopen(p, "rb"); if (!f) return 0;
    fseek(f, 0, SEEK_END); *n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* b = malloc((size_t)*n); if (b) { if (fread(b, 1, (size_t)*n, f) != (size_t)*n) { free(b); b = 0; } }
    fclose(f); return b;
}

/* 1. XML round-trip vs fixture */
static void test_roundtrip(const unsigned char* xml, long n) {
    printf("[1] XML round-trip\n");
    int dummy = 1;
    ET9STATUS st = xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)n);
    CHECK(st == ET9STATUS_NONE, "Load_XmlKDB status=%d", st);
    const ET9KdbLoaded* m = g_kdb_model;
    const ET9KdbLoaded* fx = &XT9_QWERTY_PKB_EN;
    CHECK(m->primaryId == 9 && m->secondaryId == 6, "pid/sid = %u/%u", m->primaryId, m->secondaryId);
    CHECK(m->authoredWidth == 1080 && m->authoredHeight == 384, "dims = %ux%u", m->authoredWidth, m->authoredHeight);
    CHECK(m->keyCount == fx->keyCount, "keyCount %u != fixture %u", m->keyCount, fx->keyCount);
    int diffs = 0;
    for (ET9U16 i = 0; i < m->keyCount && i < fx->keyCount; i++) {
        ET9KdbKey a = m->keys[i], b = fx->keys[i];
        if (a.keyCode != b.keyCode || a.left != b.left || a.top != b.top ||
            a.right != b.right || a.bottom != b.bottom || a.cx != b.cx || a.cy != b.cy) diffs++;
    }
    CHECK(diffs == 0, "%d key(s) differ from fixture", diffs);
}

/* 2. Selection lifecycle: root from a synthetic blob index, variant from the owned registry.
 * The variant layout has a distinct height (450) + keyCount (2) so a variant load is
 * distinguishable from the root fixture (384, 30 keys). */
static const char VARIANT_XML[] =
    "<keyboard primaryId=\"9\" secondaryId=\"6\""
    " defaultLayoutWidth=\"1080\" defaultLayoutHeight=\"450\" addAltChars=\"true\">"
    "<key keyType=\"regional\" keyLabel=\"Q\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"0dp\" />"
    "<key keyType=\"regional\" keyLabel=\"W\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"540dp\" />"
    "</keyboard>";

static void test_selection(unsigned char* xml, long n) {
    printf("[2] Selection lifecycle (variant registry + root fallback)\n");
    ET9KdbNode root = {0}; ET9KdbIndex idx = {0};
    root.primaryId = 9; root.secondaryId = 6; root.contents = xml; root.size = (ET9U32)n;
    idx.head = &root;
    unsigned char ctx[0x40] = {0};
    *(ET9KdbIndex**)(ctx + 0x18) = &idx;
    ET9KDBInfoPtr c = (ET9KDBInfoPtr)ctx;

    xt9kdb_Init(c);
    CHECK(xt9kdb_GetKdbNum(c) == 0, "GetKdbNum after Init = 0x%x", xt9kdb_GetKdbNum(c));

    /* Read the model through the per-ctx slot, not the legacy global: since the per-ctx
     * model slots landed, Load_XmlKDB only republishes into g_kdb_model for the ctx that
     * owns the current global, and this test runs on a different ctx than test_roundtrip. */
#define MODEL xt9kdb_model_for_ctx(c)

    /* default (no variant requested) -> root node from the blob index */
    xt9kdb_set_requested_variant(0);
    ET9STATUS st = xt9kdb_SetKdbNum(c, 0x609, 0);
    CHECK(st == ET9STATUS_NONE, "SetKdbNum status=%d", st);
    CHECK(xt9kdb_GetKdbNum(c) == 0x609, "GetKdbNum = 0x%x", xt9kdb_GetKdbNum(c));
    CHECK(MODEL->keyCount == 30 && MODEL->authoredHeight == 384,
          "root load keyCount=%u h=%u", MODEL->keyCount, MODEL->authoredHeight);
    CHECK(xt9kdb_Validate(c) == ET9STATUS_NONE, "Validate != OK");

    /* register an athena 9:6 variant; selecting it overrides the root copy */
    CHECK(xt9kdb_register_variant_kdb("athena", (const ET9U8*)VARIANT_XML,
                                      (ET9U32)strlen(VARIANT_XML)) == 0, "variant register failed");
    xt9kdb_set_requested_variant("athena");        /* change invalidates -> next SetKdbNum reloads */
    st = xt9kdb_SetKdbNum(c, 0x609, 0);
    CHECK(st == ET9STATUS_NONE && MODEL->keyCount == 2 && MODEL->authoredHeight == 450,
          "variant load keyCount=%u h=%u", MODEL->keyCount, MODEL->authoredHeight);
    CHECK(xt9kdb_GetKdbNum(c) == 0x609, "GetKdbNum = 0x%x", xt9kdb_GetKdbNum(c));

    xt9kdb_InvalidateLoadedKdbInfo(c);
    CHECK(xt9kdb_GetKdbNum(c) == 0, "GetKdbNum after Invalidate = 0x%x", xt9kdb_GetKdbNum(c));

    /* unregistered variant -> root fallback */
    xt9kdb_set_requested_variant("venice");
    st = xt9kdb_SetKdbNum(c, 0x609, 0);
    CHECK(st == ET9STATUS_NONE && MODEL->keyCount == 30 && MODEL->authoredHeight == 384,
          "venice->root fallback failed (keyCount=%u h=%u)", MODEL->keyCount, MODEL->authoredHeight);

    /* clearing the variant returns to root (and reloads: the change invalidates) */
    xt9kdb_set_requested_variant("athena");
    xt9kdb_SetKdbNum(c, 0x609, 0);
    xt9kdb_set_requested_variant(0);
    st = xt9kdb_SetKdbNum(c, 0x609, 0);
    CHECK(st == ET9STATUS_NONE && MODEL->authoredHeight == 384,
          "variant->default did not reload root (h=%u)", MODEL->authoredHeight);

    CHECK(xt9kdb_SetKdbNum(c, 0x60A, 0) == ET9STATUS_KDB_NOT_LOADED, "missing (pid,sid) not rejected");
#undef MODEL
}

/* 3. Geometry resolution: centers resolve to self; ByTap maps sensor (authored+offset) -> key */
static void test_resolution(unsigned char* xml, long n) {
    printf("[3] Geometry resolution\n");
    int dummy = 1;
    xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)n);
    xt9kdb_set_active_pkb(1);                                 /* PKB: offset applies */
    const ET9KdbLoaded* m = g_kdb_model;
    int self = 0, tap_ok = 0;
    for (ET9U16 i = 0; i < m->keyCount; i++) {
        ET9KdbKey k = m->keys[i];
        /* authored-space: a key's own center must land in that key */
        if (xt9kdb_point_to_key(m, (float)k.cx, (float)k.cy) == (int)i) self++;
        /* sensor-space: tap at (cx, cy+50) -> authored (cx, cy) -> same key code */
        ET9KdbKey out; memset(&out, 0, sizeof(out));
        xt9kdb_GetKeyPositionByTap((ET9KDBInfoPtr)&dummy, (ET9U16)k.cx, (ET9U16)(k.cy + 50), &out);
        if (out.keyCode == k.keyCode) tap_ok++;
    }
    CHECK(self == m->keyCount, "%d/%u centers resolved to self", self, m->keyCount);
    CHECK(tap_ok == m->keyCount, "%d/%u taps resolved to correct code", tap_ok, m->keyCount);

    /* spot checks: known letters by authored center */
    int qi = xt9kdb_point_to_key(m, 54, 64);    CHECK(qi >= 0 && m->keys[qi].keyCode == 'q', "(54,64) -> 'q'");
    int gi = xt9kdb_point_to_key(m, 486, 192);   CHECK(gi >= 0 && m->keys[gi].keyCode == 'g', "(486,192) -> 'g'");
    int mi = xt9kdb_point_to_key(m, 810, 320);   CHECK(mi >= 0 && m->keys[mi].keyCode == 'm', "(810,320) -> 'm'");
}

/* 4. AW commit hook: tap/key/trace decode -> committed symbols (via a mock sink, no blob) */
static ET9SYMB g_cap[64]; static int g_capn;
static ET9STATUS mock_commit(void* ctx, ET9SYMB s) { (void)ctx; if (g_capn < 64) g_cap[g_capn++] = s; return ET9STATUS_NONE; }

static void test_aw_commit(unsigned char* xml, long n) {
    printf("[4] AW commit hook\n");
    int dummy = 1;
    xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)n);
    xt9kdb_set_active_pkb(1);
    xt9kdb_set_aw_commit(mock_commit);

    g_capn = 0; xt9kdb_ProcessKeyBySymbol((ET9KDBInfoPtr)&dummy, 'a', 0, 0, &dummy, 0);
    CHECK(g_capn == 1 && g_cap[0] == 'a', "ProcessKeyBySymbol committed n=%d", g_capn);

    g_capn = 0; xt9kdb_ProcessKey((ET9KDBInfoPtr)&dummy, 'b', 0, 0, &dummy);
    CHECK(g_capn == 1 && g_cap[0] == 'b', "ProcessKey committed n=%d", g_capn);

    /* tap at 'q' center in SENSOR space (authored + 50 offset) -> commits 'q' */
    g_capn = 0; xt9kdb_ProcessTap((ET9KDBInfoPtr)&dummy, 54, 64 + 50);
    CHECK(g_capn == 1 && g_cap[0] == 'q', "ProcessTap@q committed '%c'", g_capn ? g_cap[0] : '?');

    /* trace: straight swipe q(54)->e(270) on row 1 (sensor y = authored 64 + 50) */
    unsigned char* trace = calloc(1, 0x80000);
    unsigned char ctx[0x40] = {0}; *(void**)(ctx + 0x30) = trace;   /* traceObj = ctx+0x30 */
    ET9KDBInfoPtr c = (ET9KDBInfoPtr)ctx;
    g_capn = 0;
    tt_start(c, 54.0f, 114.0f, 0);
    for (int x = 54; x <= 270; x += 18) tt_move(c, (float)x, 114.0f, (ET9U32)x);
    tt_move(c, 270.0f, 114.0f, 300);
    xt9kdb_decode_scratch();                                        /* DIFF decode path (feeds sinks) */
    CHECK(g_capn >= 2 && g_cap[0] == 'q' && g_cap[g_capn - 1] == 'e',
          "trace q..e committed (n=%d first=%c last=%c)", g_capn, g_capn ? g_cap[0] : '?', g_capn ? g_cap[g_capn - 1] : '?');
    free(trace);

    /* hook cleared -> inert (the gameplan's default) */
    xt9kdb_set_aw_commit(0);
    g_capn = 0; xt9kdb_ProcessKeyBySymbol((ET9KDBInfoPtr)&dummy, 'z', 0, 0, &dummy, 0);
    CHECK(g_capn == 0, "hook cleared -> inert (n=%d)", g_capn);
}

/* 5. Ambiguous smart-touch commit: tap/trace -> candidate SETS via the set-sink */
static ET9SYMB g_aset[16]; static int g_asetn; static int g_asetcalls;
static ET9STATUS mock_ambig(void* ctx, const ET9SYMB* s, const ET9U8* f, int c) {
    (void)ctx; (void)f; g_asetcalls++; g_asetn = c < 16 ? c : 16;
    for (int i = 0; i < g_asetn; i++) g_aset[i] = s[i];
    return ET9STATUS_NONE;
}
static int set_has(ET9SYMB c) { for (int i = 0; i < g_asetn; i++) if (g_aset[i] == c) return 1; return 0; }

static void test_ambiguous(unsigned char* xml, long n) {
    printf("[5] Ambiguous smart-touch commit\n");
    int dummy = 1;
    xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)n);
    xt9kdb_set_active_pkb(1);
    xt9kdb_set_aw_commit(0);
    xt9kdb_set_aw_commit_ambig(mock_ambig);

    /* tap at 'g' center (sensor = authored 486,192 + 50): top candidate g, neighbors f & h present */
    g_asetn = 0; g_asetcalls = 0;
    xt9kdb_ProcessTap((ET9KDBInfoPtr)&dummy, 486, 192 + 50);
    CHECK(g_asetcalls == 1 && g_asetn >= 1 && g_aset[0] == 'g', "ProcessTap@g top='%c' setsize=%d", g_asetn ? g_aset[0] : '?', g_asetn);
    CHECK(set_has('f') && set_has('h'), "g's ambiguous set includes f,h (size %d)", g_asetn);
    CHECK(g_asetn > 1, "ambiguous set has >1 candidate (size %d)", g_asetn);

    /* trace a->...->d on row 2 commits multiple ambiguous sets */
    unsigned char* trace = calloc(1, 0x80000);
    unsigned char ctx[0x40] = {0}; *(void**)(ctx + 0x30) = trace; ET9KDBInfoPtr c = (ET9KDBInfoPtr)ctx;
    g_asetcalls = 0;
    tt_start(c, 54.0f, 242.0f, 0);
    for (int x = 54; x <= 270; x += 18) tt_move(c, (float)x, 242.0f, (ET9U32)x);
    tt_move(c, 270.0f, 242.0f, 300);
    xt9kdb_decode_scratch();
    CHECK(g_asetcalls >= 2, "trace committed >=2 ambiguous sets (calls=%d)", g_asetcalls);
    free(trace);
    xt9kdb_set_aw_commit_ambig(0);
}

/* 6. L13 — every code of a multi-code `keyCodes` survives the load.
 *
 * The blob's Load_AddKey (@0xb95d4) takes `(ctx, keyId, keyType, l, t, r, b, u32 count,
 * const ET9SYMB* symbols)` and memcpy's the whole array into the per-key char arena, writing the
 * count at key+0x40 and the pointer at key+0x48. `key_code_of` used to stop at the first comma,
 * so a Greek `keyCodes="0x037E, 0x003A, 0x003B"` reached the NKL as a ONE-symbol key.
 *
 * The NKL assertions below are the ones that fail against the parent commit (they read chars=1
 * where the layout states 3); the model assertions exercise ET9KdbKey.codes[], which is new. */
static const char MULTICODE_XML[] =
    "<keyboard primaryId=\"8\" secondaryId=\"6\""
    " defaultLayoutWidth=\"216\" defaultLayoutHeight=\"108\" addAltChars=\"true\"><area>"
    "<key keyType=\"nonRegional\" keyCodes=\"0x037E, 0x003A, 0x003B\""
    " keyWidth=\"108dp\" keyHeight=\"108dp\" keyTop=\"0dp\" keyLeft=\"0dp\" />"
    "<key keyType=\"regional\" keyLabel=\"A\" keyCodes=\"0x00E4, 0x00E5\""
    " keyWidth=\"108dp\" keyHeight=\"108dp\" keyTop=\"0dp\" keyLeft=\"108dp\" />"
    "</area></keyboard>";
/* 20 distinct codes on one key, against ET9_KDB_MAX_KEY_CODES = 16. */
static const char OVERFLOW_XML[] =
    "<keyboard primaryId=\"8\" secondaryId=\"6\""
    " defaultLayoutWidth=\"108\" defaultLayoutHeight=\"108\"><area>"
    "<key keyType=\"regional\" keyCodes=\"0x0401,0x0402,0x0403,0x0404,0x0405,0x0406,0x0407,"
    "0x0408,0x0409,0x040A,0x040B,0x040C,0x040D,0x040E,0x040F,0x0410,0x0411,0x0412,0x0413,0x0414\""
    " keyWidth=\"108dp\" keyHeight=\"108dp\" keyTop=\"0dp\" keyLeft=\"0dp\" />"
    "</area></keyboard>";

static ET9U32 nkl_chars(const unsigned char* nkl, int idx, ET9SYMB* out, ET9U32 cap) {
    const unsigned char* k = nkl + NKL_OFF_KEYS + (size_t)idx * NKL_KEY_STRIDE;
    ET9U32 cnt; memcpy(&cnt, k + KEY_OFF_WEIGHT, 4);
    unsigned long long p; memcpy(&p, k + KEY_OFF_LABEL_PTR, 8);
    for (ET9U32 j = 0; j < cnt && j < cap; j++)
        memcpy(&out[j], (const unsigned char*)(uintptr_t)p + j * 2, 2);
    return cnt;
}

static void test_multicode(void) {
    printf("[6] multi-code keyCodes (L13)\n");
    static unsigned char nkl[0x68 + 64 * NKL_KEY_STRIDE + 8192];
    int dummy = 1;
    xt9kdb_Init((ET9KDBInfoPtr)&dummy);
    CHECK(xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, (const ET9U8*)MULTICODE_XML,
                             (ET9U32)(sizeof MULTICODE_XML - 1)) == ET9STATUS_NONE, "multicode load");
    /* read through the per-ctx slot: Load_XmlKDB only republishes into the legacy global for the
     * ctx that owns it, and the earlier sections ran on their own contexts. */
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx((ET9KDBInfoPtr)&dummy);
    CHECK(m && m->keyCount == 2, "keyCount = %u", m ? m->keyCount : 0);
    if (!m || m->keyCount != 2) return;

    /* model: all three Greek codes, primary first and still mirrored in keyCode */
    CHECK(m->keys[0].codeCount == 3, "key0 codeCount = %u (want 3)", m->keys[0].codeCount);
    CHECK(m->keys[0].codes[0] == 0x037E && m->keys[0].codes[1] == 0x003A &&
          m->keys[0].codes[2] == 0x003B, "key0 codes = %04x %04x %04x",
          m->keys[0].codes[0], m->keys[0].codes[1], m->keys[0].codes[2]);
    CHECK(m->keys[0].keyCode == 0x037E, "key0 keyCode = %04x (primary)", m->keys[0].keyCode);
    /* keyLabel wins the primary; keyCodes then supplies its alternates */
    CHECK(m->keys[1].keyCode == 'a' && m->keys[1].codeCount == 3 &&
          m->keys[1].codes[1] == 0x00E4 && m->keys[1].codes[2] == 0x00E5,
          "key1 = %04x + %u alts", m->keys[1].keyCode, m->keys[1].codeCount);

    /* NKL: +0x40 count and the arena bytes the engine reads */
    CHECK(xt9kdb_emit_nkl(m, nkl, sizeof nkl) > 0, "emit_nkl");
    ET9SYMB cl[40];
    ET9U32 n0 = nkl_chars(nkl, 0, cl, 40);
    CHECK(n0 == 3, "NKL key0 +0x40 = %u (want 3; parent commit emits 1)", n0);
    CHECK(n0 == 3 && cl[0] == 0x037E && cl[1] == 0x003A && cl[2] == 0x003B,
          "NKL key0 arena = %04x %04x %04x", cl[0], n0 > 1 ? cl[1] : 0, n0 > 2 ? cl[2] : 0);
    ET9U32 n1 = nkl_chars(nkl, 1, cl, 40);
    /* a's accent group is 11 long and already contains ä and å, so the layout's two explicit
     * alternates are promoted to the front and the total stays 11 — no duplicate, which the
     * blob's Load_AddKey would have rejected outright (status 0x3c). */
    CHECK(n1 == 11, "NKL key1 +0x40 = %u (want 11)", n1);
    CHECK(n1 >= 3 && cl[0] == 'a' && cl[1] == 0x00E4 && cl[2] == 0x00E5,
          "NKL key1 arena head = %04x %04x %04x", cl[0], n1 > 1 ? cl[1] : 0, n1 > 2 ? cl[2] : 0);

    /* overflow is truncated and COUNTED, never silently dropped */
    ET9U32 before = xt9kdb_key_code_truncations();
    xt9kdb_Init((ET9KDBInfoPtr)&dummy);
    CHECK(xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, (const ET9U8*)OVERFLOW_XML,
                             (ET9U32)(sizeof OVERFLOW_XML - 1)) == ET9STATUS_NONE, "overflow load");
    const ET9KdbLoaded* mo = xt9kdb_model_for_ctx((ET9KDBInfoPtr)&dummy);
    CHECK(mo && mo->keys[0].codeCount == ET9_KDB_MAX_KEY_CODES,
          "overflow key kept %u codes (cap %d)", mo ? mo->keys[0].codeCount : 0, ET9_KDB_MAX_KEY_CODES);
    CHECK(xt9kdb_key_code_truncations() == before + 1,
          "truncation counter %u -> %u", before, xt9kdb_key_code_truncations());
}

int main(int argc, char** argv) {
    if (argc < 2) { printf("usage: %s qwerty_pkb.xml\n", argv[0]); return 2; }
    long n; unsigned char* xml = slurp(argv[1], &n);
    if (!xml) { printf("cannot read %s\n", argv[1]); return 2; }

    test_roundtrip(xml, n);
    test_selection(xml, n);
    test_resolution(xml, n);
    test_aw_commit(xml, n);
    test_ambiguous(xml, n);
    test_multicode();

    printf("\n==== owned self-test: %d passed, %d failed ====\n", g_pass, g_fail);
    free(xml);
    return g_fail ? 1 : 0;
}
