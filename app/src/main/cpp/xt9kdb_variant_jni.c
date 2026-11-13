/* xt9kdb_variant_jni.c: Xt9KdbVariant JNI, built in both modes. The blob indexes assets/kdb/ flat,
 * so Java registers assets/kdb/<variant>/ layouts here and the owned SetKdbNum prefers a registered
 * (variant,pid,sid) layout. Live only in CUTOVER (libkb.so); in DIFF the blob's SetKdbNum ignores it.
 * Also hosts the owned gesture ranker and a few small helpers. */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include "et9kdb.h"
#include "kdb_internal.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "XT9KDB", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "XT9KDB", __VA_ARGS__)

JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeRegister(JNIEnv* env, jclass cls,
                                                            jstring variant, jbyteArray xml) {
    (void)cls;
    if (!variant || !xml) return -1;
    const char* v = (*env)->GetStringUTFChars(env, variant, 0);
    jsize n = (*env)->GetArrayLength(env, xml);
    jbyte* buf = (*env)->GetByteArrayElements(env, xml, 0);
    int rc = -1;
    if (v && buf && n > 0)
        rc = xt9kdb_register_variant_kdb(v, (const ET9U8*)buf, (ET9U32)n);
    if (buf) (*env)->ReleaseByteArrayElements(env, xml, buf, JNI_ABORT);  /* read-only */
    if (rc != 0) LOGW("variant register failed (variant=%s, %d bytes)", v ? v : "?", (int)n);
    if (v) (*env)->ReleaseStringUTFChars(env, variant, v);
    return (jint)rc;
}

JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeSetVariant(JNIEnv* env, jclass cls,
                                                              jstring variant) {
    (void)cls;
    const char* v = variant ? (*env)->GetStringUTFChars(env, variant, 0) : 0;
    xt9kdb_set_requested_variant(v);   /* copies; empty/NULL = default (root layouts) */
    LOGI("requested KDB variant = '%s'", (v && v[0]) ? v : "(default)");
    if (v) (*env)->ReleaseStringUTFChars(env, variant, v);
}

/* Ranks `words` against the current stored gesture path; returns the winning index or -1.
 * Must be called under the gesture lock right after buildSelectionList, so the path is still
 * the gesture the candidates came from. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeRankGesture(JNIEnv* env, jclass cls,
                                                               jobjectArray words) {
    (void)cls;
    if (!words || !g_kdb_model) return -1;
    jsize n = (*env)->GetArrayLength(env, words);
    if (n <= 0) return -1;

    static Xt9DecPt path[2503];
    ET9U32 pn = 0;
    float x, y;
    while (pn < 2503 && xt9kdb_rank_sample(pn, &x, &y)) {
        path[pn].x = x; path[pn].y = y; path[pn].t = 0;
        pn++;
    }
    if (pn < 2) return -1;

    Xt9DecParams p;
    xt9kdb_decode_params_from_model(g_kdb_model, &p, 1);

    int best = -1;
    float bestScore = 1e9f;
    char dbg[256] = ""; int dn = 0;
    const int diag = xt9kdb_diag_enabled();   /* the score list is built only to be logged */
    Xt9RankPath rp;                            /* the path half of every score, once */
    xt9kdb_rank_prepare(&rp, g_kdb_model, &p, path, pn);
    for (jsize i = 0; i < n && i < 32; i++) {
        jstring jw = (jstring)(*env)->GetObjectArrayElement(env, words, i);
        if (!jw) continue;
        const char* w = (*env)->GetStringUTFChars(env, jw, 0);
        if (w) {
            float s = xt9kdb_rank_score_prepared(&rp, w, (int)i);
            if (diag && dn < 200) dn += snprintf(dbg + dn, sizeof(dbg) - dn, "%s=%.2f ", w, (double)s);
            if (s < bestScore) { bestScore = s; best = (int)i; }
            (*env)->ReleaseStringUTFChars(env, jw, w);
        }
        (*env)->DeleteLocalRef(env, jw);
    }
    /* Contamination guard: if a prediction list arrives instead of the swipe's candidates, even
     * the best score is high (~2.36 vs ~1.86 for a hard real match). Above the ceiling return -1 so
     * the bridge keeps the engine's inline word. debug.et9.rankmax = ceiling x100 (0 disables). */
    int ceil_centi = 210;
    {
        char v[PROP_VALUE_MAX];
        if (__system_property_get("debug.et9.rankmax", v) > 0 && v[0] >= '0' && v[0] <= '9')
            ceil_centi = atoi(v);
    }
    int declined = (ceil_centi > 0 && best >= 0 && bestScore * 100.0f > (float)ceil_centi);
    if (diag) LOGI("OWNRANK n=%d path=%u best=%d score=%.2f ceil=%d%s | %s",
         (int)n, pn, best, (double)bestScore, ceil_centi, declined ? " DECLINED" : "", dbg);
    return declined ? -1 : best;
}

/* Deposit sequence of the current rank snapshot. The bridge consumes each sequence once, so stale
 * worker requests that still see the pending flag never rank dead engine state. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeRankSeq(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    return (jint)xt9kdb_rank_seq();
}

/* Reports a recognizer that could not run: ET9KDB_TouchEnd must return success regardless (the
 * blob's JNI and dispatcher drop its status), e.g. when the weak ET9KDB_ProcessTrace resolved to
 * NULL. Both counters are process-monotonic and cheap to read per gesture. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeRecognizerUnavailable(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    return (jint)xt9kdb_recognizer_unavailable();
}

JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeRecognizerFailures(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    return (jint)xt9kdb_recognizer_failures();
}

/* Takes (once) the deposit-time-ranked gesture word for `seq`, or null (ownrankc off, declined,
 * already taken). Used verbatim, so no list build can be contaminated by a prediction request. */
JNIEXPORT jstring JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeTakeGestureWord(JNIEnv* env, jclass cls, jint seq) {
    (void)cls;
    char buf[80];
    if (seq <= 0) return NULL;
    if (xt9kdb_take_gesture_word((ET9U32)seq, buf, (int)sizeof(buf)) <= 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}

/* Typed-word guard: zipf*10 of a lowercase word from the English LDB frequency table, 0 when absent. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeWordZipf10(JNIEnv* env, jclass cls, jstring word) {
    (void)cls;
    if (!word) return 0;
    const char* w = (*env)->GetStringUTFChars(env, word, NULL);
    if (!w) return 0;
    int z = xt9kdb_word_zipf10(w);
    (*env)->ReleaseStringUTFChars(env, word, w);
    return (jint)z;
}

/* mallopt(M_PURGE = -101, 0) returns cached free pages to the kernel. Resolved at run time since
 * minSdk predates it (API 28); without it this returns false. */
#define BBKB_M_PURGE (-101)

JNIEXPORT jboolean JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativePurgeHeap(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    typedef int (*mallopt_fn)(int, int);
    static mallopt_fn fn;
    static int resolved;
    if (!resolved) {
        fn = (mallopt_fn)dlsym(RTLD_DEFAULT, "mallopt");
        resolved = 1;
    }
    return (fn && fn(BBKB_M_PURGE, 0) != 0) ? JNI_TRUE : JNI_FALSE;
}
