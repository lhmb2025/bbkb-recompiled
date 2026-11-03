/* et9dlm.c — the engine's own learned-model export and import, reached from Java.
 *
 * WHAT THIS IS
 *   The blob (libnative-lib.so, XT9 V13.00.05.00) carries the full dynamic-language-model API,
 *   including ET9AWDLMExport / ET9AWDLMImport: a checksummed, pointer-free byte stream of the
 *   whole model — every learned word with its use count, language, category and recency, the
 *   categories, and the next-word pairs — that the engine itself reads back. The blob's JNI layer
 *   binds none of it; this file does, for `com.blackberry.nuanceshim.Et9Dlm`.
 *
 * HOW THE ENGINE IS REACHED
 *   By address, the way et9probe.c calls the engine's setters: the blob's load bias from
 *   dl_iterate_phdr plus the function's virtual address in the pristine binary (the cutover patch
 *   rewrites the dynamic section only, so text addresses are unchanged). Android keeps the blob's
 *   symbols out of RTLD_DEFAULT, so dlsym cannot be used. Two guards keep a wrong build from
 *   being called into: the first instruction words of each function are compared with the ones
 *   recorded here before any call, and the engine validates its 0x1428 ABI magic chain on entry
 *   and answers with a status code rather than touching memory when it does not match.
 *
 * SIGNATURES (recovered from the disassembly at the addresses below)
 *   ET9U32    ET9AWDLMGetExportMaxDataSize(ET9AWLingInfo *ling)           — 0 on a bad context
 *   ET9STATUS ET9AWDLMExport(ling, ET9U8 *buf, ET9U32 size, ET9U32 *written)
 *             status 26 when `size` is below GetExportMaxDataSize; `written` includes the
 *             trailing checksum; the rest of `buf` is zero-filled
 *   ET9STATUS ET9AWDLMImport(ling, const ET9U8 *buf, ET9U32 size)
 *             RESETS THE MODEL FIRST, then verifies the checksum (22 on a mismatch, 91 on a
 *             version above 5, 26 on a buffer of 8 bytes or less); zero padding after the
 *             checksum is accepted
 *   The caller (NuanceSDK, under sMutex) validates the stream and keeps an export of the current
 *   model before importing, so a refused import can be undone.
 *   ET9STATUS ET9AWDisableDBs(ling, ET9U32 mask) / ET9AWEnableDBs(ling, ET9U32 mask)
 *             switch whole word databases off and on for this engine instance only; bit 0x2 is
 *             the learned model. With it off, isSpellingCorrect answers from the language
 *             dictionaries alone (verified on the emulator: learned and added words fail, the
 *             other instance keeps accepting them). Status 0 = done, 62 = busy.
 *   ET9STATUS ET9AWSelLstGetWord(ling, ET9AWWordInfo **word, ET9U8 index)
 *             the built selection list's word at `index`; byte +0x11 of the word is its source:
 *             9 the typed word, 2 a language dictionary (contact names too), 1 the learned model
 *             (learned, typed-once and added words alike). The blob's JNI never passes it on.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "et9probe.h"

#define TAG "ET9DLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Virtual addresses in the pristine blob, and the first four instruction words at each. */
#define BLOB_DLM_GET_EXPORT_MAX_SIZE 0xe5c80u
#define BLOB_DLM_EXPORT              0xe5dd8u
#define BLOB_DLM_IMPORT              0xe6430u

static const uint32_t FP_GET_EXPORT_MAX_SIZE[4] = {0xaa0003e1u, 0xb4000a40u, 0x79407002u, 0x52828503u};
static const uint32_t FP_EXPORT[4]              = {0xd10103ffu, 0xa90053f3u, 0xa9015bf5u, 0xa90263f7u};
static const uint32_t FP_IMPORT[4]              = {0xd11843ffu, 0xd10203ffu, 0x6d05a7e8u, 0x6d06afeau};

/* The two database switches share their first instructions, so each is also told apart by the
 * word at +0x74. */
#define BLOB_DISABLE_DBS 0xd42b8u
#define BLOB_ENABLE_DBS  0xd3fd8u
#define DBS_TELL_OFFSET  0x74u
#define DB_MASK_LEARNED_MODEL 0x2u

static const uint32_t FP_DBS[4]       = {0xaa0003e3u, 0xb4000a80u, 0x79407000u, 0x52828504u};
static const uint32_t TELL_DISABLE_DBS = 0x12000022u;
static const uint32_t TELL_ENABLE_DBS  = 0x52800027u;

#define BLOB_SELLST_GET_WORD 0xef7e4u
static const uint32_t FP_SELLST_GET_WORD[4] = {0xaa0003e4u, 0x53001c42u, 0xb40005a0u, 0x79407000u};
#define WORD_SOURCE_OFFSET 0x11u
/* The engine's selection list holds at most this many words. */
#define MAX_SELECTION_LIST 255

/* A plausible upper bound for one model: the file itself is 2 MiB. */
#define MAX_EXPORT_BYTES (8u << 20)

typedef uint32_t (*fn_dlm_get_max)(void *ling);
typedef int (*fn_dlm_export)(void *ling, uint8_t *buf, uint32_t size, uint32_t *written);
typedef int (*fn_dlm_import)(void *ling, const uint8_t *buf, uint32_t size);
typedef int (*fn_dbs)(void *ling, uint32_t mask);
typedef int (*fn_sellst_get_word)(void *ling, const uint8_t **word, uint32_t index);

/* Negative results of the Java entry points: this side could not reach the engine at all.
 * Non-negative results are the engine's own status codes (0 = success). */
enum {
    ET9DLM_ERR_CONTEXT = -1,   /* the engine context could not be resolved from the handle */
    ET9DLM_ERR_BLOB = -2,      /* the blob is not loaded, or is not the build these addresses belong to */
    ET9DLM_ERR_EMPTY = -3,     /* the engine reports no exportable model */
    ET9DLM_ERR_ARGS = -4,      /* no data, or too little to be a model */
    ET9DLM_ERR_MEMORY = -5,
};

typedef struct {
    void *ling;
    fn_dlm_get_max get_max;
    fn_dlm_export export_;
    fn_dlm_import import_;
} dlm_fns;

static int fingerprint_ok(uintptr_t base, unsigned vaddr, const uint32_t expect[4], const char *name) {
    const uint32_t *code = (const uint32_t *)(base + vaddr);
    if (memcmp(code, expect, 16) != 0) {
        LOGW("%s: code at 0x%x does not match this build (%08x %08x %08x %08x)", name, vaddr,
             code[0], code[1], code[2], code[3]);
        return 0;
    }
    return 1;
}

/* Everything a call needs, or a negative ET9DLM_ERR_* code. */
static int resolve_fns(jlong handle, dlm_fns *out) {
    et9_ctx c = et9_resolve(handle);
    if (!c.ling) {
        LOGW("engine context: %s", c.error ? c.error : "ling not located");
        return ET9DLM_ERR_CONTEXT;
    }
    uintptr_t base = et9_blob_base();
    if (!base) {
        LOGW("libnative-lib.so is not loaded");
        return ET9DLM_ERR_BLOB;
    }
    if (!fingerprint_ok(base, BLOB_DLM_GET_EXPORT_MAX_SIZE, FP_GET_EXPORT_MAX_SIZE, "GetExportMaxDataSize") ||
        !fingerprint_ok(base, BLOB_DLM_EXPORT, FP_EXPORT, "Export") ||
        !fingerprint_ok(base, BLOB_DLM_IMPORT, FP_IMPORT, "Import")) {
        return ET9DLM_ERR_BLOB;
    }
    out->ling = c.ling;
    out->get_max = (fn_dlm_get_max)(base + BLOB_DLM_GET_EXPORT_MAX_SIZE);
    out->export_ = (fn_dlm_export)(base + BLOB_DLM_EXPORT);
    out->import_ = (fn_dlm_import)(base + BLOB_DLM_IMPORT);
    return 0;
}

/* The export buffer size the engine asks for, or a negative ET9DLM_ERR_* code. A cheap way for
 * Java to learn whether the shim can reach the engine on this phone. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Et9Dlm_nativeExportMaxSize(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    dlm_fns f;
    int err = resolve_fns(handle, &f);
    if (err) return err;
    uint32_t max = f.get_max(f.ling);
    if (max == 0) return ET9DLM_ERR_EMPTY;
    if (max > MAX_EXPORT_BYTES) {
        LOGW("export size %u is implausible", max);
        return ET9DLM_ERR_BLOB;
    }
    return (jint)max;
}

/* The model as the engine's export stream (checksum included, no padding), or null. */
JNIEXPORT jbyteArray JNICALL
Java_com_blackberry_nuanceshim_Et9Dlm_nativeExport(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    dlm_fns f;
    if (resolve_fns(handle, &f)) return NULL;
    uint32_t max = f.get_max(f.ling);
    if (max == 0 || max > MAX_EXPORT_BYTES) {
        LOGW("export: size %u", max);
        return NULL;
    }
    uint8_t *buf = calloc(max, 1);
    if (!buf) return NULL;
    uint32_t written = 0;
    int status = f.export_(f.ling, buf, max, &written);
    if (status != 0 || written < 9 || written > max) {
        LOGW("export: status %d, %u of %u bytes", status, written, max);
        free(buf);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)written);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)written, (const jbyte *)buf);
    free(buf);
    LOGI("export: %u bytes (buffer %u)", written, max);
    return out;
}

/* Replaces the model with `data`. Returns the engine's status (0 = success, 22 = checksum or
 * structure, 91 = written by a newer engine) or a negative ET9DLM_ERR_* code. The engine resets
 * the model before it checks the data, so a refusal leaves it EMPTY — the caller restores. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Et9Dlm_nativeImport(JNIEnv *env, jclass cls, jlong handle, jbyteArray data) {
    (void)cls;
    if (!data) return ET9DLM_ERR_ARGS;
    jsize n = (*env)->GetArrayLength(env, data);
    if (n < 9 || (uint32_t)n > MAX_EXPORT_BYTES) return ET9DLM_ERR_ARGS;
    dlm_fns f;
    int err = resolve_fns(handle, &f);
    if (err) return err;
    uint8_t *buf = malloc((size_t)n);
    if (!buf) return ET9DLM_ERR_MEMORY;
    (*env)->GetByteArrayRegion(env, data, 0, n, (jbyte *)buf);
    int status = f.import_(f.ling, buf, (uint32_t)n);
    free(buf);
    LOGI("import: %d bytes -> status %d", (int)n, status);
    return status;
}

/* Switches the learned model off (enabled = false) or back on for this engine instance, so a
 * spelling check can answer from the dictionaries alone. Returns the engine's status (0 =
 * success) or a negative ET9DLM_ERR_* code. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Et9Dlm_nativeSetLearnedModelEnabled(JNIEnv *env, jclass cls, jlong handle,
                                                                   jboolean enabled) {
    (void)env; (void)cls;
    et9_ctx c = et9_resolve(handle);
    if (!c.ling) {
        LOGW("engine context: %s", c.error ? c.error : "ling not located");
        return ET9DLM_ERR_CONTEXT;
    }
    uintptr_t base = et9_blob_base();
    if (!base) return ET9DLM_ERR_BLOB;
    unsigned vaddr = enabled ? BLOB_ENABLE_DBS : BLOB_DISABLE_DBS;
    uint32_t tell = enabled ? TELL_ENABLE_DBS : TELL_DISABLE_DBS;
    if (!fingerprint_ok(base, vaddr, FP_DBS, enabled ? "EnableDBs" : "DisableDBs")) return ET9DLM_ERR_BLOB;
    if (*(const uint32_t *)(base + vaddr + DBS_TELL_OFFSET) != tell) {
        LOGW("%s: word at +0x%x does not match this build", enabled ? "EnableDBs" : "DisableDBs", DBS_TELL_OFFSET);
        return ET9DLM_ERR_BLOB;
    }
    return ((fn_dbs)(base + vaddr))(c.ling, DB_MASK_LEARNED_MODEL);
}

/* The source byte of each of the first `count` words of the built selection list (0 where a word
 * cannot be read), or null when the engine cannot be reached. */
JNIEXPORT jbyteArray JNICALL
Java_com_blackberry_nuanceshim_Et9Dlm_nativeSelectionListSources(JNIEnv *env, jclass cls, jlong handle, jint count) {
    (void)cls;
    if (count <= 0 || count > MAX_SELECTION_LIST) return NULL;
    et9_ctx c = et9_resolve(handle);
    if (!c.ling) return NULL;
    uintptr_t base = et9_blob_base();
    if (!base || !fingerprint_ok(base, BLOB_SELLST_GET_WORD, FP_SELLST_GET_WORD, "SelLstGetWord")) return NULL;
    fn_sellst_get_word get_word = (fn_sellst_get_word)(base + BLOB_SELLST_GET_WORD);
    jbyte sources[MAX_SELECTION_LIST];
    for (int i = 0; i < count; i++) {
        const uint8_t *word = NULL;
        sources[i] = get_word(c.ling, &word, (uint32_t)i) == 0 && word ? (jbyte)word[WORD_SOURCE_OFFSET] : 0;
    }
    jbyteArray out = (*env)->NewByteArray(env, count);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, count, sources);
    return out;
}
