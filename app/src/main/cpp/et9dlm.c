/* et9dlm.c: JNI for Et9Dlm, the blob's (XT9 V13.00.05.00) learned-model API, called by address
 * (load bias + pristine vaddr; the cutover patch leaves text addresses unchanged). Each function's
 * first instruction words are checked before any call; the engine also checks its 0x1428 magic.
 *   ET9U32    ET9AWDLMGetExportMaxDataSize(ling)                       0 on a bad context
 *   ET9STATUS ET9AWDLMExport(ling, ET9U8 *buf, ET9U32 size, ET9U32 *written)
 *             26 if size < max; written includes the checksum; rest of buf zero-filled
 *   ET9STATUS ET9AWDLMImport(ling, const ET9U8 *buf, ET9U32 size)
 *             resets the model first, then checks: 22 bad checksum, 91 version > 5, 26 size <= 8;
 *             zero padding after the checksum is accepted. The caller keeps an export to restore.
 *   ET9STATUS ET9AWDisableDBs / ET9AWEnableDBs(ling, ET9U32 mask)
 *             per engine instance; bit 0x2 = learned model; 0 done, 62 busy
 *   ET9STATUS ET9AWSelLstGetWord(ling, ET9AWWordInfo **word, ET9U8 index)
 *             word byte +0x11 = source: 9 typed word, 2 language dictionary (and contacts),
 *             1 learned model (learned, typed-once and added words) */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "et9probe.h"

#define TAG "ET9DLM"
/* LOGI is silent in release unless debug.et9.probe is set; LOGW is for real failures. */
#define LOGI(...) ET9_DIAG(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* Virtual addresses in the pristine blob, and the first four instruction words at each. */
#define BLOB_DLM_GET_EXPORT_MAX_SIZE 0xe5c80u
#define BLOB_DLM_EXPORT              0xe5dd8u
#define BLOB_DLM_IMPORT              0xe6430u

static const uint32_t FP_GET_EXPORT_MAX_SIZE[4] = {0xaa0003e1u, 0xb4000a40u, 0x79407002u, 0x52828503u};
static const uint32_t FP_EXPORT[4]              = {0xd10103ffu, 0xa90053f3u, 0xa9015bf5u, 0xa90263f7u};
static const uint32_t FP_IMPORT[4]              = {0xd11843ffu, 0xd10203ffu, 0x6d05a7e8u, 0x6d06afeau};

/* The two DB switches share their first instructions; the word at +0x74 tells them apart. */
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

/* Negative results: the engine could not be reached. Non-negative ones are engine statuses. */
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

/* The export buffer size the engine asks for, or a negative ET9DLM_ERR_* code (a cheap reachability check). */
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

/* Replaces the model with `data`; engine status or a negative ET9DLM_ERR_* code. A refusal leaves
 * the model empty (reset happens before the check), so the caller restores. */
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

/* Switches the learned model off or on for this engine instance (spell checks from the dictionaries
 * alone). Engine status or a negative ET9DLM_ERR_* code. */
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
