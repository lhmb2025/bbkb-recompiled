/* et9probe.c — read/write the stock ET9 alphabetic engine's configuration from Java.
 *
 * WHY THIS EXISTS
 *   The blob exposes 49 engine knobs (see docs/et9-ranking-documentation.md) but its JNI
 *   layer binds only two of them. Everything else — auto-correct fences, DLM quarantine
 *   level, adaptation flags — is unreachable from Java. This file reaches them the only
 *   way that does not require patching the blob: the ET9AW* symbols are exported with
 *   default visibility, so a library in the same process can dlsym and call them.
 *
 * WHY dlsym RATHER THAN LINKING
 *   libkb.so is pulled in as a DT_NEEDED of libnative-lib.so; linking the other way would
 *   be circular. This lives in libxt9trace.so (loaded independently by Xt9Trace) and
 *   resolves everything lazily through RTLD_DEFAULT, so a missing symbol degrades to a
 *   diagnostic instead of a load failure.
 *
 * SAFETY
 *   Every pointer hop is validated against the engine's own ABI fingerprint (the struct
 *   size 0x1428 that every public ET9AW entry checks) BEFORE it is dereferenced. If any
 *   check fails the probe reports the failure and touches nothing further — this runs
 *   inside the user's live IME and a bad dereference would kill the keyboard.
 *
 * POINTER CHAIN (recovered from Java_..._buildSelectionList and ET9AWSetPrimaryFence)
 *   handle                                    Java NuanceSDK.mNativeHandle (engine block)
 *   P     = *(void**)(handle + 0xAE210)       block -> engine object
 *   ling  = P + 8                             ET9AWLingInfo*, magic u16 @ +0x38
 *   info  = *(void**)(ling + 0x20)            magic u16 @ +0xD8
 *   core  = *(void**)(info + 0x98)            magic u16 @ +0x00
 *   fences live at info + 0x249008 / +0x249009; STM gating at core + 0x15038 / +0x30408.
 */

#include <jni.h>
#include <dlfcn.h>
#include <link.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include <pthread.h>
#include <sys/system_properties.h>

#define TAG "ET9PROBE"
/* Every line here is a diagnostic; et9_resolve runs on each suggestion request (P-5). */
#define LOGI(...) ET9_DIAG(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

#define ET9_MAGIC 0x1428

#define OFF_BLOCK_TO_P   0xAE210
#define OFF_P_TO_LING    0x8
#define OFF_LING_MAGIC   0x38
#define OFF_LING_INFO    0x20
#define OFF_INFO_MAGIC   0xD8
#define OFF_INFO_CORE    0x98
#define OFF_FENCE_BLOCK  0x249000
#define OFF_PRIMARY_FENCE   (OFF_FENCE_BLOCK + 0x8)
#define OFF_SECONDARY_FENCE (OFF_FENCE_BLOCK + 0x9)
#define OFF_STM_MODE     0x15038
#define OFF_STM_MODEL    0x30408

#include "et9probe.h"

/* Resolve the engine context by SIGNATURE SCAN rather than a hardcoded pointer path.
 *
 * The JNI layer reaches the engine through C++ virtual dispatch, so there is no stable
 * offset chain to hardcode from Java's handle. Instead we scan the engine block for a
 * structure that satisfies all three of the ABI checks every public ET9AW entry performs:
 *
 *     *(u16*)(ling + 0x38) == 0x1428
 *     info = *(void**)(ling + 0x20);  *(u16*)(info + 0xD8) == 0x1428
 *     core = *(void**)(info + 0x98);  *(u16*)(core)        == 0x1428
 *
 * Three chained magics plus two pointer dereferences make a false positive implausible.
 * The scan is bounded to a window the blob's own JNI is known to dereference (it reads
 * handle+0xAE1D8 and handle+0xAE230), so every byte we touch is already proven mapped.
 */
#define SCAN_LIMIT 0xAE1D0   /* strictly inside the range the blob itself dereferences */

int et9_diag_enabled(void) {
#ifdef NDEBUG
    static int cached = -1;
    if (cached < 0) {
        char v[PROP_VALUE_MAX] = {0};
        cached = __system_property_get("debug.et9.probe", v) > 0 && v[0] && v[0] != '0';
    }
    return cached;
#else
    return 1;
#endif
}

/* Unused since et9_resolve walks the chain itself; kept as the statement of what a valid chain is. */
__attribute__((unused)) static int chain_ok(unsigned char *ling) {
    if (*(uint16_t *)(ling + OFF_LING_MAGIC) != ET9_MAGIC) return 0;
    unsigned char *info = *(unsigned char **)(ling + OFF_LING_INFO);
    if (!info) return 0;
    /* Reject obviously bogus pointers before dereferencing them. */
    if ((uintptr_t)info < 0x1000 || ((uintptr_t)info & 0x7)) return 0;
    if (*(uint16_t *)(info + OFF_INFO_MAGIC) != ET9_MAGIC) return 0;
    unsigned char *core = *(unsigned char **)(info + OFF_INFO_CORE);
    if (!core) return 0;
    if ((uintptr_t)core < 0x1000 || ((uintptr_t)core & 0x7)) return 0;
    if (*(uint16_t *)core != ET9_MAGIC) return 0;
    return 1;
}

/* The C++ objects the JNI itself loads as candidate ctx values: ling = *(*(block + off)) + 8. */
static const size_t CAND_OFFSETS[] = {0xAE210, 0xAE228, 0xAE230};

/* CONFIRMED ON-DEVICE: handle + 0x18750 is the engine "core" struct — the blob's own JNI
 * passes it to ET9ClearAllSymbs, and its magic sits at offset 0, matching the third level of
 * the chain every ET9AW entry validates. From there we walk BACKWARDS to the structs that
 * point at it, because those (info, ling) are separate allocations, not inline in the block.
 *
 * This is the full signature scan. Also reports how it got there (the recipe et9_resolve caches):
 * info's offset in the block or the candidate index that led to it, and ling's offset in the
 * block (*has_ling_off) when the second scan found it there. */
static et9_ctx et9_resolve_scan(jlong handle, size_t *info_off, size_t *ling_off,
                                int *has_ling_off, int *cand_idx) {
    et9_ctx c = {0, 0, 0, 0};
    *info_off = 0; *ling_off = 0; *has_ling_off = 0; *cand_idx = -1;
    if (!handle) { c.error = "handle is 0"; return c; }
    unsigned char *block = (unsigned char *)(intptr_t)handle;

    unsigned char *core = block + 0x18750;
    if (*(uint16_t *)core != ET9_MAGIC) { c.error = "core magic mismatch at handle+0x18750"; return c; }
    c.core = core;   /* enough on its own for the STM questions */

    /* info: the struct holding core at +0x98, with its own magic at +0xD8. */
    for (size_t off = 0; off + 8 < SCAN_LIMIT; off += 8) {
        if (*(unsigned char **)(block + off) != core) continue;
        if (off < OFF_INFO_CORE) continue;
        unsigned char *info = block + off - OFF_INFO_CORE;
        if (*(uint16_t *)(info + OFF_INFO_MAGIC) != ET9_MAGIC) continue;
        c.info = info;
        *info_off = off - OFF_INFO_CORE;
        LOGI("layout: info at handle+0x%zx", off - OFF_INFO_CORE);
        break;
    }
    /* alphaBuildSelectionList computes pLingInfo = *(void**)ctx + 8. Try the C++ objects the
     * JNI itself loads as candidate ctx values before falling back to scanning. */
    if (!c.info) {
        const size_t *cand = CAND_OFFSETS;
        for (unsigned i = 0; i < 3 && !c.ling; i++) {
            unsigned char *pobj = *(unsigned char **)(block + cand[i]);
            if (!pobj || ((uintptr_t)pobj & 7)) continue;
            unsigned char *d = *(unsigned char **)pobj;
            if (!d || ((uintptr_t)d & 7)) continue;
            unsigned char *ling = d + 8;
            uint16_t m = *(uint16_t *)(ling + OFF_LING_MAGIC);
            LOGI("layout: candidate ctx handle+0x%zx -> ling %p magic 0x%04x", cand[i], (void *)ling, m);
            if (m == ET9_MAGIC) {
                unsigned char *info = *(unsigned char **)(ling + OFF_LING_INFO);
                if (info && !((uintptr_t)info & 7) && *(uint16_t *)(info + OFF_INFO_MAGIC) == ET9_MAGIC) {
                    c.ling = ling; c.info = info;
                    *cand_idx = (int)i;
                    LOGI("resolved via alpha ctx at handle+0x%zx", cand[i]);
                }
            }
        }
    }
    if (!c.info) { c.error = "info not located (STM data still valid)"; return c; }

    /* ling: the struct holding info at +0x20, with its own magic at +0x38. Searched even when the
     * candidate path already found one: an in-block ling, when there is one, wins as it always has. */
    for (size_t off = 0; off + 8 < SCAN_LIMIT; off += 8) {
        if (*(unsigned char **)(block + off) != c.info) continue;
        if (off < OFF_LING_INFO) continue;
        unsigned char *ling = block + off - OFF_LING_INFO;
        if (*(uint16_t *)(ling + OFF_LING_MAGIC) != ET9_MAGIC) continue;
        c.ling = ling;
        *ling_off = off - OFF_LING_INFO;
        *has_ling_off = 1;
        LOGI("layout: ling at handle+0x%zx", off - OFF_LING_INFO);
        break;
    }
    if (!c.ling) c.error = "ling not located (STM + fence data still valid)";
    return c;
}

/* ---- the per-handle cache (P-5) -------------------------------------------------------------
 * et9_resolve runs on every suggestion request (Et9Dlm.nativeSelectionListSources), and the scan
 * reads up to 2 x 0.7 MB of the engine block each time. The layout only changes when the engine is
 * re-created, so the RECIPE is cached — where in the block info and ling were found, as offsets
 * from the handle, or which candidate object led to them — never the resolved pointers: after a
 * dispose those can point into freed (even unmapped) memory. Each call re-derives the pointers
 * from the live block and re-checks the same magic chain the scan accepted (core magic, info magic
 * + info->core == core, ling magic + ling->info == info): a few loads. A new handle, or the same
 * address reused by an engine with a different layout, fails the check and rescans. Only a full
 * resolution (ling found) is cached; a partial one rescans next time, as before. */
typedef struct {
    jlong  handle;          /* 0 = empty */
    size_t info_off;        /* info = block + info_off, when cand_idx < 0 */
    size_t ling_off;        /* ling = block + ling_off, when has_ling_off */
    int    has_ling_off;
    int    cand_idx;        /* >= 0: info (and ling, unless has_ling_off) came via CAND_OFFSETS[i] */
} et9_recipe;
static et9_recipe g_recipe;
static pthread_mutex_t g_recipe_mu = PTHREAD_MUTEX_INITIALIZER;

static int aligned_ptr(const void *p) { return p && (uintptr_t)p >= 0x1000 && !((uintptr_t)p & 7); }

/* Re-derive the context from the block by `r`; 1 and *out filled when every check passes. */
static int recipe_apply(jlong handle, const et9_recipe *r, et9_ctx *out) {
    unsigned char *block = (unsigned char *)(intptr_t)handle;
    unsigned char *core = block + 0x18750;
    if (*(uint16_t *)core != ET9_MAGIC) return 0;
    unsigned char *info = NULL, *ling = NULL;
    if (r->cand_idx >= 0) {
        unsigned char *pobj = *(unsigned char **)(block + CAND_OFFSETS[r->cand_idx]);
        if (!aligned_ptr(pobj)) return 0;
        unsigned char *d = *(unsigned char **)pobj;
        if (!aligned_ptr(d)) return 0;
        unsigned char *cl = d + 8;
        if (*(uint16_t *)(cl + OFF_LING_MAGIC) != ET9_MAGIC) return 0;
        info = *(unsigned char **)(cl + OFF_LING_INFO);
        if (!aligned_ptr(info) || *(uint16_t *)(info + OFF_INFO_MAGIC) != ET9_MAGIC) return 0;
        ling = cl;
    } else {
        info = block + r->info_off;
        if (*(uint16_t *)(info + OFF_INFO_MAGIC) != ET9_MAGIC) return 0;
        if (*(unsigned char **)(info + OFF_INFO_CORE) != core) return 0;
    }
    if (r->has_ling_off) {
        unsigned char *bl = block + r->ling_off;
        if (*(uint16_t *)(bl + OFF_LING_MAGIC) != ET9_MAGIC) return 0;
        if (*(unsigned char **)(bl + OFF_LING_INFO) != info) return 0;
        ling = bl;
    }
    if (!ling) return 0;
    out->core = core; out->info = info; out->ling = ling; out->error = NULL;
    return 1;
}

et9_ctx et9_resolve(jlong handle) {
    et9_ctx c = {0, 0, 0, 0};
    if (!handle) { c.error = "handle is 0"; return c; }
    pthread_mutex_lock(&g_recipe_mu);
    if (g_recipe.handle == handle && recipe_apply(handle, &g_recipe, &c)) {
        pthread_mutex_unlock(&g_recipe_mu);
        return c;
    }
    et9_recipe r = {0, 0, 0, 0, -1};
    c = et9_resolve_scan(handle, &r.info_off, &r.ling_off, &r.has_ling_off, &r.cand_idx);
    if (c.ling) {
        r.handle = handle;
        g_recipe = r;
    } else {
        g_recipe.handle = 0;   /* nothing valid to keep */
    }
    pthread_mutex_unlock(&g_recipe_mu);
    return c;
}

/* Replicate the cache-invalidation sweep the engine's own setters perform after storing a
 * value. Recovered verbatim from ET9AWSetPrimaryFence @ 0xd44b4-0xd452c:
 *
 *     for off in {0xca8,0xcb0,0xcb8,0xcc0,0xcc8,0xcd0}:
 *         p = *(void**)(core + 0x2a000 + off);  if (p) *(u8*)(p + 2) = 1;   // mark dirty
 *     *(u8*)(core + 0x150cb) = 0;                                            // list no longer valid
 *     *(u8*)(core + 0x150cd) = 0;
 *
 * Without this a raw field store is invisible to the engine for the rest of the session — it
 * keeps scoring against cached state. All plain field writes; no engine calls involved.
 */
/* Unused since the setters are called directly (they run this sweep themselves). */
__attribute__((unused)) static void invalidate_caches(unsigned char *core) {
    static const unsigned int slots[] = {0xca8, 0xcb0, 0xcb8, 0xcc0, 0xcc8, 0xcd0};
    unsigned char *tbl = core + 0x2a000;
    for (unsigned i = 0; i < sizeof(slots) / sizeof(slots[0]); i++) {
        unsigned char *obj = *(unsigned char **)(tbl + slots[i]);
        if (obj && !((uintptr_t)obj & 0x7)) *(unsigned char *)(obj + 2) = 1;
    }
    *(unsigned char *)(core + 0x150cb) = 0;
    *(unsigned char *)(core + 0x150cd) = 0;
    LOGI("caches invalidated");
}

/* ---- calling the engine's own setters -----------------------------------------------------------
 * Writing the fields directly was never equivalent to calling the engine: the real setters also run
 * a cache-invalidation sweep, which is why the earlier fence sweep produced no behavioural change.
 * Calling them was blocked because dlsym cannot reach libnative-lib.so — Android keeps it out of
 * the global namespace, and dlopen(RTLD_NOLOAD) returns a namespace-tagged handle dlsym faults on.
 *
 * Resolved by not using the dynamic linker: find the blob's load bias with dl_iterate_phdr and call
 * BY ADDRESS — the same trampoline the owned KDB already uses for ProcessKey/ProcessStoredTouch/
 * ProcessTrace, and indifferent to symbol visibility. The addresses are build-specific, but each
 * entry validates the 0x1428 magic at pLingInfo+0x38 before doing anything, so a wrong one reports
 * a status code rather than corrupting state.
 *
 * Signature confirmed at 0xd4438: ET9STATUS f(void* pLingInfo, ET9U8 value). NOT every setter
 * has that shape (2026-11-03): ET9AWSetDLMQuarantineLevel takes three ET9U8 levels, and the
 * Set/Clear pairs (FastAdaptation and the like) take the handle alone. Only the fences go through
 * call_ling_setter. */
#define BLOB_SET_PRIMARY_FENCE    0xd4438u
#define BLOB_SET_SECONDARY_FENCE  0xd4548u
#define BLOB_SET_DLM_QUARANTINE   0xd1e20u
#define BLOB_SET_FAST_ADAPTATION  0xd3314u
#define BLOB_SET_INDICT_AUTOCORR  0xd8558u
#define BLOB_SET_SPELLCORR_MODE   0xd22b0u
#define BLOB_SET_WORDCOMP_POINT   0xd1694u

typedef int (*fn_set_ling_u8)(void *ling, unsigned char v);
typedef int (*fn_set_ling_u8x3)(void *ling, unsigned char a, unsigned char b, unsigned char c);

static int find_blob_cb(struct dl_phdr_info *info, size_t size, void *out) {
    (void)size;
    if (info->dlpi_name && strstr(info->dlpi_name, "libnative-lib.so")) {
        *(uintptr_t *)out = (uintptr_t)info->dlpi_addr;
        return 1;
    }
    return 0;
}

uintptr_t et9_blob_base(void) {
    static uintptr_t base = 0;
    static int done = 0;
    if (!done) { done = 1; dl_iterate_phdr(find_blob_cb, &base);
                 LOGI("blob base = 0x%lx", (unsigned long)base); }
    return base;
}

/* Call one (pLingInfo, u8) setter by address. Returns the engine status, or -1 if unresolved.
 * Not for the multi-byte setters: the extra arguments would be whatever w2/w3 hold. */
static int call_ling_setter(unsigned vaddr, void *ling, int value, const char *what) {
    uintptr_t b = et9_blob_base();
    if (!b || !ling) { LOGI("%s: unresolved (base=%p ling=%p)", what, (void *)b, ling); return -1; }
    fn_set_ling_u8 f = (fn_set_ling_u8)(b + vaddr);
    int st = f(ling, (unsigned char)value);
    LOGI("%s(%d) -> status=%d", what, value, st);
    return st;
}

/* Field access from here down.
 *
 * An earlier revision resolved the engine's exported getters with dlsym and called them.
 * That crashed: Android does not expose libnative-lib.so's symbols to RTLD_DEFAULT, and
 * dlopen(RTLD_NOLOAD) hands back a namespace-tagged handle that dlsym cannot use. Since
 * every knob's storage offset is already known from static analysis (see §4 of
 * docs/et9-ranking-documentation.md), the probe just reads the fields. No calls into the
 * engine, nothing to get a signature wrong, and nothing that can corrupt engine state.
 *
 * Bases (recovered by disassembly, confirmed on-device by the ABI magic):
 *   core + 0x246cXX  the main settings block
 *   core + 0x1c45XX  selection-list / spell-correction modes
 *   info + 0x249008  primary fence, +0x249009 secondary fence
 */

#define K_QUARANTINE   0x246c1d
#define K_DBSTEMS      0x246c3f
#define K_DBCOMPLETION 0x246c40
#define K_NEXTWORDPRED 0x246c41
#define K_SPACESEG     0x246c50
#define K_CTXPRED      0x246c59
#define K_AUTOSPACE    0x246c5c
#define K_INCRBUILDS   0x246c5d
#define K_EXPLICITLRN  0x246c5e
#define K_FASTADAPT    0x246c61
#define K_NEWWORDCLEAN 0x246c62
#define K_SELLISTMODE  0x1c453c
#define K_WORDSTEMPT   0x1c4540
#define K_WORDCOMPPT   0x1c4542
#define K_SPELLCORR    0x1c454c

JNIEXPORT jstring JNICALL
Java_com_blackberry_nuanceshim_Et9Probe_nativeProbe(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    char buf[1400];
    et9_ctx c = et9_resolve(handle);
    if (!c.core) {
        snprintf(buf, sizeof(buf), "{\"ok\":false,\"error\":\"%s\"}", c.error ? c.error : "?");
        LOGI("probe failed: %s", c.error ? c.error : "?");
        return (*env)->NewStringUTF(env, buf);
    }
    /* Which base holds the 0x246cXX settings block? The fences proved `info` is right for
     * the 0x249xxx region; report both candidates for the settings region and let the known
     * SysInit defaults (NextWordPrediction=1, SelectionListMode=1, WordStemsPoint=2) decide. */
    unsigned char *k = c.core;
    if (c.info) {
        unsigned char *i = c.info;
        LOGI("basecmp: core-rel nwp=%u sel=%u stem=%u | info-rel nwp=%u sel=%u stem=%u",
             *(unsigned char *)(k + K_NEXTWORDPRED), *(uint32_t *)(k + K_SELLISTMODE),
             *(uint16_t *)(k + K_WORDSTEMPT),
             *(unsigned char *)(i + K_NEXTWORDPRED), *(uint32_t *)(i + K_SELLISTMODE),
             *(uint16_t *)(i + K_WORDSTEMPT));
        /* Adopt whichever base shows the engine's documented defaults. */
        if (*(unsigned char *)(i + K_NEXTWORDPRED) == 1 && *(uint16_t *)(i + K_WORDSTEMPT) == 2) {
            k = i;
            LOGI("basecmp: settings block is INFO-relative");
        }
    }

    int stm_mode    = *(unsigned char *)(c.core + OFF_STM_MODE);
    void *stm_model = *(void **)(c.core + OFF_STM_MODEL);
    int fenceP = -1, fenceS = -1;
    if (c.info) {
        fenceP = *(unsigned char *)(c.info + OFF_PRIMARY_FENCE);
        fenceS = *(unsigned char *)(c.info + OFF_SECONDARY_FENCE);
    }

    snprintf(buf, sizeof(buf),
        "{\"ok\":true,\"resolved\":\"core=%d info=%d ling=%d\","
        "\"stmModeByte\":%d,\"stmModelAttached\":%d,"
        "\"primaryFence\":%d,\"secondaryFence\":%d,"
        "\"dlmQuarantine\":%u,\"dlmQuarantine2\":%u,\"dlmQuarantine3\":%u,"
        "\"explicitLearning\":%u,\"fastAdaptation\":%u,"
        "\"newWordCleaning\":%u,\"contextBasedPrediction\":%u,\"nextWordPrediction\":%u,"
        "\"incrementalBuilds\":%u,\"dbStems\":%u,\"dbCompletion\":%u,\"spaceSegmentation\":%u,"
        "\"autoSpace\":%u,\"selectionListMode\":%u,\"spellCorrectionMode\":%u,"
        "\"wordStemsPoint\":%u,\"wordCompletionPoint\":%u}",
        c.core?1:0, c.info?1:0, c.ling?1:0,
        stm_mode, stm_model ? 1 : 0,
        fenceP, fenceS,
        *(unsigned char *)(k + K_QUARANTINE),
        *(unsigned char *)(k + K_QUARANTINE + 1),
        *(unsigned char *)(k + K_QUARANTINE + 2),
        *(unsigned char *)(k + K_EXPLICITLRN),
        *(unsigned char *)(k + K_FASTADAPT),
        *(unsigned char *)(k + K_NEWWORDCLEAN),
        *(unsigned char *)(k + K_CTXPRED),
        *(unsigned char *)(k + K_NEXTWORDPRED),
        *(unsigned char *)(k + K_INCRBUILDS),
        *(unsigned char *)(k + K_DBSTEMS),
        *(unsigned char *)(k + K_DBCOMPLETION),
        *(unsigned char *)(k + K_SPACESEG),
        *(unsigned char *)(k + K_AUTOSPACE),
        *(uint32_t *)(k + K_SELLISTMODE),
        *(uint32_t *)(k + K_SPELLCORR),
        *(uint16_t *)(k + K_WORDSTEMPT),
        *(uint16_t *)(k + K_WORDCOMPPT));
    /* Experiment hook: `adb shell setprop debug.et9.fence N` applies N to both fences on the
     * next input session, so the fence semantics can be swept without a UI. Unset/empty =
     * leave the engine's own value alone. */
    char prop[PROP_VALUE_MAX] = {0};
    if (c.ling && __system_property_get("debug.et9.fence", prop) > 0 && prop[0]) {
        int v = atoi(prop);
        if (v >= 0 && v <= 255) {
            /* Through the engine's own setters now, so the cache invalidation actually happens. */
            call_ling_setter(BLOB_SET_PRIMARY_FENCE,   c.ling, v, "SetPrimaryFence");
            call_ling_setter(BLOB_SET_SECONDARY_FENCE, c.ling, v, "SetSecondaryFence");
            if (c.info)
                LOGI("fence override: requested=%d readback=%u/%u", v,
                     *(unsigned char *)(c.info + OFF_PRIMARY_FENCE),
                     *(unsigned char *)(c.info + OFF_SECONDARY_FENCE));
        }
    }

    LOGI("%s", buf);
    return (*env)->NewStringUTF(env, buf);
}

/* Write a knob field directly. Same store the engine's own setter performs, minus the
 * cache-invalidation sweep — adequate for an A/B sweep, NOT a substitute for a real
 * binding if any of these graduate to a product setting. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Et9Probe_nativeSetFences(JNIEnv *env, jclass cls,
                                                       jlong handle, jint primary, jint secondary) {
    (void)env; (void)cls;
    et9_ctx c = et9_resolve(handle);
    if (!c.ling) { LOGI("setFences: ling unavailable"); return -1; }
    int st = 0;
    if (primary   >= 0) st |= call_ling_setter(BLOB_SET_PRIMARY_FENCE,   c.ling, primary,   "SetPrimaryFence");
    if (secondary >= 0) st |= call_ling_setter(BLOB_SET_SECONDARY_FENCE, c.ling, secondary, "SetSecondaryFence");
    return st;
}

/* ET9AWSetDLMQuarantineLevel (0xd1e20) takes THREE levels, stored at info+0x246c1d/1e/1f, each
 * 0..5 (status 26 otherwise). Until 2026-11-03 this went through call_ling_setter and so passed
 * whatever w2/w3 happened to hold into the other two fields. A negative level keeps the engine's
 * current value of that field. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Et9Probe_nativeSetQuarantine(JNIEnv *env, jclass cls,
                                                           jlong handle, jint l1, jint l2, jint l3) {
    (void)env; (void)cls;
    et9_ctx c = et9_resolve(handle);
    if (!c.ling) { LOGI("setQuarantine: ling unavailable"); return -1; }
    uintptr_t b = et9_blob_base();
    if (!b) { LOGI("setQuarantine: blob base unresolved"); return -1; }
    unsigned char *q = c.info ? c.info + K_QUARANTINE : NULL;
    if (!q && (l1 < 0 || l2 < 0 || l3 < 0)) {
        LOGI("setQuarantine: info unavailable, cannot keep the current levels");
        return -1;
    }
    unsigned char v1 = l1 < 0 ? q[0] : (unsigned char)l1;
    unsigned char v2 = l2 < 0 ? q[1] : (unsigned char)l2;
    unsigned char v3 = l3 < 0 ? q[2] : (unsigned char)l3;
    fn_set_ling_u8x3 f = (fn_set_ling_u8x3)(b + BLOB_SET_DLM_QUARANTINE);
    int st = f(c.ling, v1, v2, v3);
    if (q)
        LOGI("SetDLMQuarantineLevel(%u,%u,%u) -> status=%d readback=%u/%u/%u", v1, v2, v3, st, q[0], q[1], q[2]);
    else
        LOGI("SetDLMQuarantineLevel(%u,%u,%u) -> status=%d", v1, v2, v3, st);
    return st;
}
