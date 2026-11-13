/* kdb_tap.c: discrete tap/key path and the AW-commit hook. Recognized symbols go to AW's
 * ET9WordSymbInfo (ctx+0x52) via ET9AddExplicitSymb, an AW export, through a hook that is NULL
 * (inert) unless wired. ProcessTap is at 0xca358. In CUTOVER, ProcessKey/ProcessKeyBySymbol/
 * ProcessStoredTouch/ModifyCurrentKey are not redirected (elf_cutover.py NOT_REDIRECTED): the blob
 * keeps the tap -> AW path against our NKL; the owned versions serve DIFF and the offline tests. */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

/* AW-commit hook; NULL = inert. */
xt9kdb_commit_fn g_aw_commit = 0;
void xt9kdb_set_aw_commit(xt9kdb_commit_fn fn) { g_aw_commit = fn; }

/* Commits one symbol to AW if a sink is wired. Used by kdb_trace.c. */
ET9STATUS xt9kdb_commit_symbol(void* ctx, ET9SYMB symb) {
    if (!g_aw_commit) {
        /* log loudly: an unwired sink on a live path silently drops every typed symbol */
        static int warned = 0;
        if (!warned) { warned = 1; XT9KDB_LOGE("commit sink NOT WIRED — typed symbols are being dropped"); }
        return ET9STATUS_NONE;
    }
    return g_aw_commit(ctx, symb);
}

/* Ambiguous (smart-touch) commit sink. */
xt9kdb_commit_ambig_fn g_aw_commit_ambig = 0;
void xt9kdb_set_aw_commit_ambig(xt9kdb_commit_ambig_fn fn) { g_aw_commit_ambig = fn; }

/* Commits the candidate set at an authored point: as a set if the set sink is wired
 * (ET9AddCustomSymbolSet), else the top candidate (ET9AddExplicitSymb). */
ET9STATUS xt9kdb_commit_candidates(void* ctx, float ax, float ay) {
    ET9SYMB symbs[8]; ET9U8 freqs[8];
    int n = xt9kdb_point_to_candidates(g_kdb_model, ax, ay, symbs, freqs, 8);
    if (n <= 0) return ET9STATUS_NONE;
    if (g_aw_commit_ambig) return g_aw_commit_ambig(ctx, symbs, freqs, n);
    return xt9kdb_commit_symbol(ctx, symbs[0]);
}

ET9STATUS XT9KDB(ProcessTap)(ET9KDBInfoPtr ctx, ET9U16 x, ET9U16 y) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    /* re-publish this ctx's model (per-ctx slots) before resolving keys */
    { const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx); if (m) xt9kdb_set_model(m); }
    if (!g_kdb_model || g_kdb_model->keyCount == 0) return ET9STATUS_KDB_NOT_LOADED;
    float ax, ay;
    xt9kdb_sensor_to_authored((float)x, (float)y, &ax, &ay);
    int k = xt9kdb_point_to_key(g_kdb_model, ax, ay);
    if (k < 0) return ET9STATUS_NONE;
    ET9U16 code = g_kdb_model->keys[k].keyCode;
    if (code >= 'a' && code <= 'z')                 /* letters -> ambiguous smart-touch set */
        return xt9kdb_commit_candidates(ctx, ax, ay);
    return xt9kdb_commit_symbol(ctx, code);         /* function/non-letter -> exact */
}

/* ET9KDB_ProcessKey: logging pass-through to the blob's body, still at .text 0xbf424 (orphaned by
 * the UNDEF). ProcessKeyBySymbol only resolves symbol -> key and calls this via PLT `bl 0x16430`;
 * the commit into WordSymbInfo happens here. Status 0 = accepted; non-zero names the rejecting gate.
 * Signature (call site 0xc08dc): x0=ctx w1=keyIndex(u16) w2=a2 u32 w3=a3 u8 x4=a4 non-null ptr. */
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
    /* key is an index into the loaded KDB; resolve it for the log. Gated on log.tag.XT9Trace
     * (fires on every keystroke). */
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
/* DIFF and host builds: commit directly so the offline suite exercises the commit sink. */
ET9STATUS XT9KDB(ProcessKey)(ET9KDBInfoPtr ctx, ET9U16 key, ET9U32 a2, ET9U8 a3, void* a4) {
    (void)a2; (void)a3; (void)a4;
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    return xt9kdb_commit_symbol(ctx, (ET9SYMB)key);
}
#endif

/* ET9KDB_ProcessKeyBySymbol @0xc0798, same pass-through. Signature from the prologue:
 *   x0=ctx  w1=symb(uxth)  w2=a2 u32(->s10)  w3=a3 u8  x4=a4 ptr (non-null or it returns 9)  w5=a5 u8 */
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

/* Editing the in-progress symbol set is an AW-buffer operation, left to the blob. */
ET9STATUS XT9KDB(ModifyCurrentKey)(ET9KDBInfoPtr ctx, ET9SYMB s) { (void)ctx;(void)s; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(NextDiacritic)(ET9KDBInfoPtr ctx) { (void)ctx; return ET9STATUS_NONE; }
