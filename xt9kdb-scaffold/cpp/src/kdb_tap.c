/* kdb_tap.c — discrete tap / key path (non-gesture input) + the AW-commit hook.
 *
 * ProcessTap (@0xca358) maps a touch point to a key; ProcessKey/ProcessKeyBySymbol feed an explicit
 * symbol. All of these COMMIT a recognized symbol to ET9AW's ET9WordSymbInfo buffer (at ctx+0x52),
 * which ET9AWSelLstBuild reads to produce candidates. That buffer is owned by AW (the blob), and the
 * commit entry is ET9AddExplicitSymb(ctx+0x52, symb) — an AW/core export, NOT a KDB function.
 *
 * So the owned KDB does NOT reimplement the buffer: it decodes -> calls a commit HOOK. Integration
 * wires the hook to a thunk that calls the blob's ET9AddExplicitSymb; standalone leaves it NULL
 * (inert) or sets a mock (see owned_selftest). This keeps the AW boundary unwired by default.
 *
 * 2026-07-24: "unwired by default" is safe for the offline tests and the DIFF mirror, but it was
 * NOT safe in CUTOVER — these entry points were redirected there while the only sink wiring lived
 * in DIFF-mode-only JNI glue, so every typed character was dropped and the keyboard could not
 * autocomplete. ProcessKey/ProcessKeyBySymbol/ProcessStoredTouch/ModifyCurrentKey are therefore no
 * longer redirected (see elf_cutover.py NOT_REDIRECTED); the blob keeps the tap -> AW path and runs
 * it against our NKL. The functions below stay for the DIFF mirror and the offline tests, and now
 * announce themselves loudly if they are ever put back on a live path unwired.
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

/* XT9KDB_LOGE now lives in kdb_internal.h — shared with the selector invariant in kdb_state.c. */

/* The AW-commit hook (see kdb_internal.h). NULL = inert (no AW present). */
xt9kdb_commit_fn g_aw_commit = 0;
void xt9kdb_set_aw_commit(xt9kdb_commit_fn fn) { g_aw_commit = fn; }

/* Commit one recognized symbol to AW (if a sink is wired). Exposed for kdb_trace.c. */
ET9STATUS xt9kdb_commit_symbol(void* ctx, ET9SYMB symb) {
    if (!g_aw_commit) {
        /* An unwired sink used to be indistinguishable from "the engine had nothing to say":
         * silent ET9STATUS_NONE, no error, no log. That silence is why a CUTOVER build which
         * could not autocomplete at all passed validation and shipped (2026-07-24). If these
         * entry points are ever redirected again, this fires on the first keystroke. */
        static int warned = 0;
        if (!warned) { warned = 1; XT9KDB_LOGE("commit sink NOT WIRED — typed symbols are being dropped"); }
        return ET9STATUS_NONE;
    }
    return g_aw_commit(ctx, symb);
}

/* Ambiguous (smart-touch) commit sink + helper. */
xt9kdb_commit_ambig_fn g_aw_commit_ambig = 0;
void xt9kdb_set_aw_commit_ambig(xt9kdb_commit_ambig_fn fn) { g_aw_commit_ambig = fn; }

/* Compute the ambiguous candidate set at an AUTHORED point and commit it: as a set if a set-sink is
 * wired (-> ET9AddCustomSymbolSet), else the top candidate exact (-> ET9AddExplicitSymb). */
ET9STATUS xt9kdb_commit_candidates(void* ctx, float ax, float ay) {
    ET9SYMB symbs[8]; ET9U8 freqs[8];
    int n = xt9kdb_point_to_candidates(g_kdb_model, ax, ay, symbs, freqs, 8);
    if (n <= 0) return ET9STATUS_NONE;
    if (g_aw_commit_ambig) return g_aw_commit_ambig(ctx, symbs, freqs, n);
    return xt9kdb_commit_symbol(ctx, symbs[0]);
}

ET9STATUS XT9KDB(ProcessTap)(ET9KDBInfoPtr ctx, ET9U16 x, ET9U16 y) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    /* Re-publish this ctx's model (per-ctx slots — see TouchStart) before resolving keys. */
    { const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx); if (m) xt9kdb_set_model(m); }
    if (!g_kdb_model || g_kdb_model->keyCount == 0) return ET9STATUS_KDB_NOT_LOADED;
    /* Map raw sensor (x,y) -> authored, resolve the key, commit its symbol to AW.
     * NOTE: the blob commits an AMBIGUOUS smart-touch set per tap; this first cut commits the single
     * resolved key (exact), which is a correct-but-narrower feed. Ambiguous commit = later refinement. */
    float ax, ay;
    xt9kdb_sensor_to_authored((float)x, (float)y, &ax, &ay);
    int k = xt9kdb_point_to_key(g_kdb_model, ax, ay);
    if (k < 0) return ET9STATUS_NONE;
    ET9U16 code = g_kdb_model->keys[k].keyCode;
    if (code >= 'a' && code <= 'z')                 /* letters -> ambiguous smart-touch set */
        return xt9kdb_commit_candidates(ctx, ax, ay);
    return xt9kdb_commit_symbol(ctx, code);         /* function/non-letter -> exact */
}

/* ---- ET9KDB_ProcessKey: instrumented pass-through to the blob's own implementation ------------
 * RE (docs/libnative-documentation.md) shows ET9KDB_ProcessKeyBySymbol is only a symbol->key
 * RESOLVER: both its success and fallback paths converge on `bl 0x16430`, which is the PLT stub for
 * ET9KDB_ProcessKey. All the real work — and the commit into WordSymbInfo — happens inside
 * ProcessKey. Because that call goes through the PLT, redirecting the symbol puts us in the middle
 * of it, which is the only instrumentation seam we have on the deciding path.
 *
 * The blob's own ProcessKey is still present at .text 0xbf424 (orphaned by the UNDEF, not removed),
 * so we can call it directly at load_bias + 0xbf424 and report its status. That status is the datum
 * the investigation needs: 0 means the symbol was accepted and the fault is downstream; non-zero
 * names which internal gate rejected it.
 *
 * TRUE SIGNATURE — 5 args, from the call site at 0xc08dc-0xc08f0:
 *     x0=ctx  w1=keyIndex(u16)  w2=u32  w3=u8  x4=ptr(must be non-null; ProcessKeyBySymbol
 *     returns 9 early if it is null)
 * The old declaration here was (ctx, key, flags) — 3 args — so w3/x4 were never passed through. */
#if defined(XT9KDB_CUTOVER) && defined(__ANDROID__)
#include <link.h>
#include <stdint.h>
#include <string.h>

#define BLOB_PROCESSKEY_VADDR 0xbf424u

typedef ET9STATUS (*xt9_blob_processkey_fn)(void*, ET9U16, ET9U32, ET9U8, void*);
static xt9_blob_processkey_fn g_blob_processkey;
static int                    g_blob_lookup_done;

static int xt9_find_blob_cb(struct dl_phdr_info* info, size_t size, void* out) {
    (void)size;
    if (info->dlpi_name && strstr(info->dlpi_name, "libnative-lib.so")) {
        *(uintptr_t*)out = (uintptr_t)info->dlpi_addr;
        return 1;                       /* stop iterating */
    }
    return 0;
}

static xt9_blob_processkey_fn xt9_blob_processkey(void) {
    if (!g_blob_lookup_done) {
        uintptr_t bias = 0;
        g_blob_lookup_done = 1;
        dl_iterate_phdr(xt9_find_blob_cb, &bias);
        if (bias) g_blob_processkey = (xt9_blob_processkey_fn)(bias + BLOB_PROCESSKEY_VADDR);
        XT9KDB_LOGF("blob ProcessKey trampoline: bias=0x%lx -> %p",
                    (unsigned long)bias, (void*)g_blob_processkey);
    }
    return g_blob_processkey;
}

ET9STATUS XT9KDB(ProcessKey)(ET9KDBInfoPtr ctx, ET9U16 key, ET9U32 a2, ET9U8 a3, void* a4) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    xt9_blob_processkey_fn fn = xt9_blob_processkey();
    if (!fn) {
        XT9KDB_LOGE("ProcessKey: blob original not found — cannot forward, dropping key");
        return ET9STATUS_ERROR;
    }
    ET9STATUS st = fn(ctx, key, a2, a3, a4);
    /* `key` is an INDEX into the loaded KDB, not a symbol — resolve it, otherwise the log says
     * nothing about which letters the decoder chose. Gated on log.tag.XT9Trace because this fires
     * on every keystroke as well as every decoded gesture key. */
    if (xt9kdb_trace_dump_enabled()) {
        int code = (g_kdb_model && key < g_kdb_model->keyCount)
                 ? (int)g_kdb_model->keys[key].keyCode : -1;
        XT9KDB_LOGF("KEYSEQ ProcessKey idx=%u '%c' (code=%d) a2=0x%08x a3=0x%02x -> st=%d",
                    (unsigned)key, (code >= 32 && code < 127) ? (char)code : '?', code,
                    (unsigned)a2, (unsigned)a3, (int)st);
    }
    return st;
}
#else
/* DIFF builds, and host builds of the CUTOVER selftest (no dl_iterate_phdr off-device): keep the
 * original owned behaviour so the offline suite still exercises the commit sink. */
ET9STATUS XT9KDB(ProcessKey)(ET9KDBInfoPtr ctx, ET9U16 key, ET9U32 a2, ET9U8 a3, void* a4) {
    (void)a2; (void)a3; (void)a4;
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    return xt9kdb_commit_symbol(ctx, (ET9SYMB)key);
}
#endif

/* ET9KDB_ProcessKeyBySymbol @0xc0798 — TRUE SIGNATURE is 6 args, from the prologue:
 *   x0=ctx  w1=symb(uxth)  w2=u32(->s10)  w3=u8(uxtb)  x4=ptr(non-null or it returns 9)  w5=u8(uxtb)
 * Declared here as (ctx, ET9SYMB) for a long time. Same instrumented pass-through as ProcessKey. */
#if defined(XT9KDB_CUTOVER) && defined(__ANDROID__)
#define BLOB_PKBS_VADDR 0xc0798u
typedef ET9STATUS (*xt9_blob_pkbs_fn)(void*, ET9U16, ET9U32, ET9U8, void*, ET9U8);
static xt9_blob_pkbs_fn g_blob_pkbs;
static int              g_blob_pkbs_done;

ET9STATUS XT9KDB(ProcessKeyBySymbol)(ET9KDBInfoPtr ctx, ET9U16 s, ET9U32 a2, ET9U8 a3,
                                     void* a4, ET9U8 a5) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    if (!g_blob_pkbs_done) {
        uintptr_t bias = 0;
        g_blob_pkbs_done = 1;
        dl_iterate_phdr(xt9_find_blob_cb, &bias);
        if (bias) g_blob_pkbs = (xt9_blob_pkbs_fn)(bias + BLOB_PKBS_VADDR);
        XT9KDB_LOGF("blob ProcessKeyBySymbol trampoline -> %p", (void*)g_blob_pkbs);
    }
    if (!g_blob_pkbs) { XT9KDB_LOGE("PKBS: blob original not found"); return ET9STATUS_ERROR; }
    ET9STATUS st = g_blob_pkbs(ctx, s, a2, a3, a4, a5);
    if (xt9kdb_trace_dump_enabled())
        XT9KDB_LOGF("KEYSEQ PKBS symb=0x%04x('%c') -> st=%d",
                    (unsigned)s, (s >= 32 && s < 127) ? (char)s : '?', (int)st);
    return st;
}
#else
ET9STATUS XT9KDB(ProcessKeyBySymbol)(ET9KDBInfoPtr ctx, ET9U16 s, ET9U32 a2, ET9U8 a3,
                                     void* a4, ET9U8 a5) {
    (void)a2; (void)a3; (void)a4; (void)a5;
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    return xt9kdb_commit_symbol(ctx, (ET9SYMB)s);
}
#endif

/* Editing the in-progress symbol set is an AW-buffer operation; left to the AW owner. */
ET9STATUS XT9KDB(ModifyCurrentKey)(ET9KDBInfoPtr ctx, ET9SYMB s) { (void)ctx;(void)s; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(NextDiacritic)(ET9KDBInfoPtr ctx) { (void)ctx; return ET9STATUS_NONE; }
