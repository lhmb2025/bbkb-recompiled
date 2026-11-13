/* et9probe.c: read/write ET9 alphabetic engine knobs the blob's JNI does not bind.
 * Lives in libxt9trace.so; calls blob entry points by address (dlsym cannot reach libnative-lib.so).
 * Every pointer hop is checked against the 0x1428 ABI magic before it is dereferenced: this runs in
 * the live IME and a bad dereference kills the keyboard.
 *   P = *(void**)(handle + 0xAE210); ling = P + 8 (magic u16 @ +0x38)
 *   info = *(void**)(ling + 0x20) (magic @ +0xD8); core = *(void**)(info + 0x98) (magic @ +0)
 *   fences at info + 0x249008 / +0x249009; STM gating at core + 0x15038 / +0x30408. */

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
/* Every line here is a diagnostic; et9_resolve runs on each suggestion request. */
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

/* The context is found by signature scan (the JNI reaches it through C++ virtual dispatch, so no
 * stable offset chain exists): three chained 0x1428 magics make a false positive implausible.
 * The scan stays below handle+0xAE1D8, which the blob's JNI itself reads, so every byte is mapped. */
#define SCAN_LIMIT 0xAE1D0

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

/* Unused: states what a valid chain is (et9_resolve walks it itself). */
__attribute__((unused)) static int chain_ok(unsigned char *ling) {
    if (*(uint16_t *)(ling + OFF_LING_MAGIC) != ET9_MAGIC) return 0;
    unsigned char *info = *(unsigned char **)(ling + OFF_LING_INFO);
    if (!info) return 0;
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

/* handle + 0x18750 is the core struct (the blob's JNI passes it to ET9ClearAllSymbs). info and ling
 * are found by walking back to the structs that point at it. Also reports the recipe et9_resolve
 * caches: info's block offset or candidate index, and ling's block offset when found in the block. */
static et9_ctx et9_resolve_scan(jlong handle, size_t *info_off, size_t *ling_off,
                                int *has_ling_off, int *cand_idx) {
    et9_ctx c = {0, 0, 0, 0};
    *info_off = 0; *ling_off = 0; *has_ling_off = 0; *cand_idx = -1;
    if (!handle) { c.error = "handle is 0"; return c; }
    unsigned char *block = (unsigned char *)(intptr_t)handle;

    unsigned char *core = block + 0x18750;
    if (*(uint16_t *)core != ET9_MAGIC) { c.error = "core magic mismatch at handle+0x18750"; return c; }
    c.core = core;   /* enough on its own for the STM fields */

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
    /* alphaBuildSelectionList computes pLingInfo = *(void**)ctx + 8: try the JNI's candidate ctx objects. */
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

    /* Searched even when the candidate path found a ling: an in-block ling wins. */
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

/* Per-handle cache: the scan reads up to 2 x 0.7 MB per suggestion request. Only the recipe (offsets
 * or candidate index) is cached, never pointers, which can dangle after a dispose. Each call
 * re-derives and re-checks the magic chain; a mismatch rescans. Only full resolutions are cached. */
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

/* The cache-invalidation sweep the engine's setters run after a store (ET9AWSetPrimaryFence
 * @ 0xd44b4-0xd452c); without it a raw field store is ignored for the session. Unused: the setters
 * are called directly and run it themselves. */
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

/* Engine setters, called by address from the dl_iterate_phdr load bias: dlsym cannot reach
 * libnative-lib.so (Android keeps it out of the global namespace; RTLD_NOLOAD handles fault).
 * Addresses are build-specific; each entry checks the magic at pLingInfo+0x38 first.
 * Fences: ET9STATUS f(void *pLingInfo, ET9U8). SetDLMQuarantineLevel takes three ET9U8 levels;
 * Set/Clear pairs (FastAdaptation etc.) take the handle alone. */
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

/* Knob storage offsets, read directly (no engine calls):
 *   +0x246cXX  main settings block (base decided in nativeProbe)
 *   +0x1c45XX  selection-list / spell-correction modes */

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
    /* The settings block may be core- or info-relative: pick the base showing the SysInit
     * defaults (NextWordPrediction=1, WordStemsPoint=2). */
    unsigned char *k = c.core;
    if (c.info) {
        unsigned char *i = c.info;
        LOGI("basecmp: core-rel nwp=%u sel=%u stem=%u | info-rel nwp=%u sel=%u stem=%u",
             *(unsigned char *)(k + K_NEXTWORDPRED), *(uint32_t *)(k + K_SELLISTMODE),
             *(uint16_t *)(k + K_WORDSTEMPT),
             *(unsigned char *)(i + K_NEXTWORDPRED), *(uint32_t *)(i + K_SELLISTMODE),
             *(uint16_t *)(i + K_WORDSTEMPT));
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
        "\"wordStemsPoint\":%u,\"wordCompletionPoint\":%u,\"multiThreading\":%u}",
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
        *(uint16_t *)(k + K_WORDCOMPPT),
        *(unsigned char *)(c.core + 0x492d8));   /* OFF_CORE_MULTITHREADING */
    /* Experiment hook: `setprop debug.et9.fence N` applies N to both fences on the next
     * input session; unset leaves the engine's value alone. */
    char prop[PROP_VALUE_MAX] = {0};
    if (c.ling && __system_property_get("debug.et9.fence", prop) > 0 && prop[0]) {
        int v = atoi(prop);
        if (v >= 0 && v <= 255) {
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

/* Sets either fence through the engine's setter; a negative value leaves that fence alone. */
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

/* ET9AWSetDLMQuarantineLevel (0xd1e20) takes three levels, stored at info+0x246c1d/1e/1f, each
 * 0..5 (status 26 otherwise). A negative level keeps that field's current value. */
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

/* Multithreading switch. MT search paths spawn up to five 64 KB-stack pthreads per search (spawner
 * 0x54014), 6 to 10 per keystroke. All nine spawn sites read the byte core + 0x492d8 and run the
 * worker inline when it is 0. ET9WordSymbInit sets it to 1, and NuanceSDK.setLanguage re-runs that,
 * so the switch must be reapplied after every native setLanguage.
 * Set/Clear at 0xb8160 / 0xb81bc: ET9STATUS f(ET9WordSymbInfo *core); 9 on NULL, 2 on bad magic,
 * 62 while a list is built over non-empty input (core+0x150cb and core+2 non-zero), else 0. */
#define BLOB_SET_MULTITHREADING   0xb8160u
#define BLOB_CLEAR_MULTITHREADING 0xb81bcu
#define OFF_CORE_MULTITHREADING   0x492d8u
/* The pair differs only in its first word (the cbz offset); the store at +0x48 tells them apart. */
static const uint32_t FP_SET_MT[4]   = {0xb4000280u, 0x79400003u, 0x52828502u, 0x52800041u};
static const uint32_t FP_CLEAR_MT[4] = {0xb4000260u, 0x79400003u, 0x52828502u, 0x52800041u};

typedef int (*fn_core_switch)(void *core);

/* 0 = done; otherwise the engine's status (62 = busy, retry with an empty input), or -1 when the
 * engine context is not reachable, -2 when the blob is not the build these addresses belong to. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Et9EngineOptions_nativeSetMultiThreading(JNIEnv *env, jclass cls,
                                                                       jlong handle, jboolean enabled) {
    (void)env; (void)cls;
    et9_ctx c = et9_resolve(handle);
    if (!c.core) { LOGI("setMultiThreading: %s", c.error ? c.error : "core unavailable"); return -1; }
    uintptr_t b = et9_blob_base();
    if (!b) return -2;
    unsigned vaddr = enabled ? BLOB_SET_MULTITHREADING : BLOB_CLEAR_MULTITHREADING;
    if (memcmp((const void *)(b + vaddr), enabled ? FP_SET_MT : FP_CLEAR_MT, 16) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "setMultiThreading: code at 0x%x does not match this build", vaddr);
        return -2;
    }
    int st = ((fn_core_switch)(b + vaddr))(c.core);
    LOGI("%sMultiThreading -> status=%d readback=%u", enabled ? "Set" : "Clear", st,
         *(unsigned char *)(c.core + OFF_CORE_MULTITHREADING));
    return st;
}
