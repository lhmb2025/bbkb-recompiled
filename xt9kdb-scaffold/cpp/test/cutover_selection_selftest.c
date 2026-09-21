/* cutover_selection_selftest.c — the ctx+0x04/+0x08 selection contract (CUTOVER builds only).
 *
 * This is the regression net for the 2026-07 "suggestions frozen while typing" bug. That bug was
 * invisible to every existing test because the other selftests build in DIFF mode, where the
 * context accessors are no-ops, and because the blob's failure mode is silent.
 *
 * The contract, from disassembling the blob (docs/libnative-documentation.md §5-6):
 *   - ET9KDB_SetKdbNum (0xba654) stores its ARGUMENTS, not decomposed ids:
 *       ctx+0x04 = arg1 verbatim, the full u32 selector (secondaryId<<8)|primaryId
 *       ctx+0x08 = uxth(arg2),    the flags word — NOT the secondaryId
 *   - it is transactional: both fields roll back if the load fails (0xba77c)
 *   - the internal loader (0xba264) keeps our NKL only while
 *       *(u32*)(NKL+0x04) == *(u32*)(ctx+0x04)
 *     and otherwise reloads from the blob's own flat index, which cannot see variant KDBs.
 *
 * Build:  cc -std=c11 -DXT9KDB_CUTOVER -Iinclude "src/*.c" test/cutover_selection_selftest.c -lm
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, msg, ...) do { \
    if (cond) { g_pass++; } \
    else { g_fail++; printf("  FAIL: " msg "\n", ##__VA_ARGS__); } } while (0)

/* athena PKB: primaryId 9, secondaryId 6 -> selector 0x0609. The historical bug wrote 0x0009. */
#define SEL_ATHENA 0x0609u
#define PID_ONLY   0x0009u

static const char VARIANT_XML[] =
    "<keyboard primaryId=\"9\" secondaryId=\"6\""
    " defaultLayoutWidth=\"1080\" defaultLayoutHeight=\"450\" addAltChars=\"true\">"
    "<key keyType=\"regional\" keyLabel=\"Q\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"0dp\" />"
    "<key keyType=\"regional\" keyLabel=\"W\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"540dp\" />"
    "</keyboard>";

/* A context big enough for the real field map, with an arena the NKL can be emitted into. */
typedef struct {
    unsigned char ctx[0x100];
    unsigned char arena[0x8000];       /* >= 0x3ef0, the Load_Reset span */
} FakeCtx;

static ET9KDBInfoPtr make_ctx(FakeCtx* f, ET9KdbIndex* idx) {
    memset(f, 0, sizeof(*f));
    *(ET9KdbIndex**)(f->ctx + ET9_OFF_KDB_INDEX) = idx;
    *(void**)(f->ctx + ET9_OFF_ARENA)            = f->arena;
    *(ET9U16*)(f->ctx + ET9_OFF_MAGIC)           = ET9KDB_MAGIC;
    return (ET9KDBInfoPtr)f->ctx;
}

static ET9U32 ctx_selector(ET9KDBInfoPtr c) { return *(ET9U32*)((char*)c + ET9_OFF_KDB_SELECTOR); }
static ET9U16 ctx_flags(ET9KDBInfoPtr c)    { return *(ET9U16*)((char*)c + ET9_OFF_KDB_FLAGS); }
static const unsigned char* ctx_nkl(ET9KDBInfoPtr c) {
    return *(const unsigned char**)((char*)c + ET9_OFF_NKL);
}

int main(int argc, char** argv) {
    if (argc < 2) { printf("usage: %s qwerty_pkb.xml\n", argv[0]); return 2; }
    long n; FILE* fp = fopen(argv[1], "rb");
    if (!fp) { printf("cannot read %s\n", argv[1]); return 2; }
    fseek(fp, 0, SEEK_END); n = ftell(fp); fseek(fp, 0, SEEK_SET);
    unsigned char* xml = malloc((size_t)n);
    if (!xml || fread(xml, 1, (size_t)n, fp) != (size_t)n) { printf("read failed\n"); return 2; }
    fclose(fp);

    ET9KdbNode root = {0}; ET9KdbIndex idx = {0};
    root.primaryId = 9; root.secondaryId = 6; root.contents = xml; root.size = (ET9U32)n;
    idx.head = &root;

    static FakeCtx f;
    ET9KDBInfoPtr c = make_ctx(&f, &idx);
    XT9KDB(Init)(c);
    xt9kdb_set_requested_variant(0);

    printf("[1] ctx+0x04 holds the SELECTOR, not the primaryId\n");
    ET9STATUS st = XT9KDB(SetKdbNum)(c, SEL_ATHENA, 0);
    CHECK(st == ET9STATUS_NONE, "SetKdbNum status=%d", st);
    CHECK(ctx_selector(c) == SEL_ATHENA,
          "ctx+0x04 = 0x%04x, want 0x%04x", ctx_selector(c), SEL_ATHENA);
    /* The exact historical defect. Pinned so it cannot come back by another route. */
    CHECK(ctx_selector(c) != PID_ONLY,
          "ctx+0x04 = 0x%04x — the pid-only regression is back", ctx_selector(c));

    printf("[2] ctx+0x08 holds the FLAGS argument, not the secondaryId\n");
    st = XT9KDB(SetKdbNum)(c, SEL_ATHENA, 0x21u);
    CHECK(st == ET9STATUS_NONE, "SetKdbNum status=%d", st);
    CHECK(ctx_flags(c) == 0x21u, "ctx+0x08 = 0x%04x, want 0x0021", ctx_flags(c));
    CHECK(ctx_flags(c) != 6u, "ctx+0x08 = 6 — secondaryId is being written where flags belong");

    printf("[3] THE INVARIANT: *(u32*)(NKL+0x04) == *(u32*)(ctx+0x04)\n");
    /* This is the check the blob performs on every single keystroke at 0xba2a0. If it fails, the
     * blob discards our NKL, reloads from an index that cannot see variants, and silently stops
     * committing typed symbols to the word engine. */
    const unsigned char* nkl = ctx_nkl(c);
    CHECK(nkl != 0, "no NKL installed");
    if (nkl) {
        ET9U32 in_nkl = *(const ET9U32*)(nkl + 0x04);
        CHECK(in_nkl == ctx_selector(c),
              "NKL+0x04 = 0x%04x but ctx+0x04 = 0x%04x — the blob would reload and drop keystrokes",
              in_nkl, ctx_selector(c));
        /* Both must equal the selector, and the NKL's byte layout must be pid,sid,0,0. */
        CHECK(in_nkl == SEL_ATHENA, "NKL+0x04 = 0x%04x, want 0x%04x", in_nkl, SEL_ATHENA);
        CHECK(nkl[0x04] == 9 && nkl[0x05] == 6 && nkl[0x06] == 0 && nkl[0x07] == 0,
              "NKL+0x04..07 = %02x %02x %02x %02x, want 09 06 00 00",
              nkl[0x04], nkl[0x05], nkl[0x06], nkl[0x07]);
        /* The blob also requires the first two bytes to be non-zero to take the early-out. */
        CHECK(nkl[0x00] != 0 && nkl[0x01] != 0,
              "NKL[0]=%02x NKL[1]=%02x — a zero here forces a full reload", nkl[0x00], nkl[0x01]);
    }

    printf("[4] the invariant survives a variant override\n");
    CHECK(xt9kdb_register_variant_kdb("athena", (const ET9U8*)VARIANT_XML,
                                      (ET9U32)strlen(VARIANT_XML)) == 0, "variant register failed");
    xt9kdb_set_requested_variant("athena");
    st = XT9KDB(SetKdbNum)(c, SEL_ATHENA, 0);
    CHECK(st == ET9STATUS_NONE && g_kdb_model->authoredHeight == 450, "variant did not load");
    nkl = ctx_nkl(c);
    CHECK(nkl && *(const ET9U32*)(nkl + 0x04) == ctx_selector(c),
          "invariant broken after variant load: NKL+0x04=0x%04x ctx+0x04=0x%04x",
          nkl ? *(const ET9U32*)(nkl + 0x04) : 0u, ctx_selector(c));

    printf("[5] the KDB buffer pointer (ctx+0x68) NEVER moves across loads\n");
    /* Blob Init sets ctx+0x60 == ctx+0x68 == ctx+0x70 and nothing ever advances +0x68 — each load
     * overwrites the one buffer. We used to bump it per load; since SetKdbNum runs on nearly every
     * keystroke, the NKL marched into ctx+0xfc30, the engine's live view offset/scale fields. */
    void* buf_before = *(void**)((char*)c + ET9_OFF_ARENA);
    const unsigned char* nkl_before = ctx_nkl(c);
    for (int i = 0; i < 8; i++) {
        xt9kdb_set_requested_variant(i & 1 ? "athena" : 0);   /* force a real reload each time */
        XT9KDB(SetKdbNum)(c, SEL_ATHENA, 0);
    }
    CHECK(*(void**)((char*)c + ET9_OFF_ARENA) == buf_before,
          "ctx+0x68 moved %ld bytes over 8 loads — the NKL is marching through the context",
          (long)((char*)*(void**)((char*)c + ET9_OFF_ARENA) - (char*)buf_before));
    CHECK(ctx_nkl(c) == nkl_before, "NKL pointer moved across loads");
    CHECK(ctx_nkl(c) == buf_before, "NKL is not at the KDB buffer");

    printf("[6] ctx+0x5a carries the loader's \"KDB loaded\" stamp\n");
    /* ProcessKeyBySymbol rejects every keystroke with 0x27 unless ctx+0x5a == ctx+0x58
     * (0xc0814-0xc0820). The blob sets it at 0xba528 when ITS loader completes; since we replaced
     * the loader, install_nkl must stamp it. Missing this rejected 100% of typed characters. */
    CHECK(*(ET9U16*)((char*)c + 0x5a) == *(ET9U16*)((char*)c + ET9_OFF_MAGIC),
          "ctx+0x5a = 0x%04x, want 0x%04x — the engine would reject every keystroke with 0x27",
          (unsigned)*(ET9U16*)((char*)c + 0x5a), (unsigned)*(ET9U16*)((char*)c + ET9_OFF_MAGIC));

    printf("[7] a failed selection rolls the context back (blob does this at 0xba77c)\n");
    ET9U32 keep_sel = ctx_selector(c); ET9U16 keep_flags = ctx_flags(c);
    st = XT9KDB(SetKdbNum)(c, 0x0102u, 0x77u);          /* no such (pid,sid) anywhere */
    CHECK(st != ET9STATUS_NONE, "unknown selector should fail, got %d", st);
    CHECK(ctx_selector(c) == keep_sel,
          "ctx+0x04 = 0x%04x after a failed select, want 0x%04x preserved", ctx_selector(c), keep_sel);
    CHECK(ctx_flags(c) == keep_flags,
          "ctx+0x08 = 0x%04x after a failed select, want 0x%04x preserved", ctx_flags(c), keep_flags);

    printf("\n==== cutover selection self-test: %d passed, %d failed ====\n", g_pass, g_fail);
    free(xml);
    return g_fail ? 1 : 0;
}
