/* xt9kdb_variant_jni.c — device-variant KDB registration, built in BOTH modes (unlike
 * xt9kdb_jni.c, which is DIFF-only debug glue).
 *
 * The blob's kdbIndexAssetManager scans assets/kdb/ flat, so per-device variant folders
 * (assets/kdb/<variant>/) are invisible to it. Java (Xt9KdbVariant.apply, driven by the matched
 * device config's <kdb-variant>) reads those files and registers the bytes here; the owned
 * SetKdbNum then prefers a registered (variant,pid,sid) layout over the blob-indexed root copy.
 *
 * In CUTOVER (libkb.so) this is live: the engine's ET9KDB_SetKdbNum is ours. In DIFF
 * (libxt9kdb.so) the registry is populated but the blob still owns the live SetKdbNum, so the
 * variant is inert — same graceful no-op as a fully stock build (where neither lib loads).
 */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/system_properties.h>
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

/* ---- owned gesture ranker JNI (§8.7.6) --------------------------------------------------------
 * Ranks candidate words against the CURRENT stored gesture path (kdb_trace.c's accumulated
 * samples) using xt9kdb_rank_score over the loaded model with per-row normalization. Returns the
 * winning index into `words`, or -1 if unrankable (no path / no model / empty list). Called from
 * the bridge's gesture branch under the gesture lock, immediately after buildSelectionList, so
 * the path is still the gesture the candidates came from. */
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
    char dbg[256]; int dn = 0;
    for (jsize i = 0; i < n && i < 32; i++) {
        jstring jw = (jstring)(*env)->GetObjectArrayElement(env, words, i);
        if (!jw) continue;
        const char* w = (*env)->GetStringUTFChars(env, jw, 0);
        if (w) {
            float s = xt9kdb_rank_score(g_kdb_model, &p, path, pn, w, (int)i);
            if (dn < 200) dn += snprintf(dbg + dn, sizeof(dbg) - dn, "%s=%.2f ", w, (double)s);
            if (s < bestScore) { bestScore = s; best = (int)i; }
            (*env)->ReleaseStringUTFChars(env, jw, w);
        }
        (*env)->DeleteLocalRef(env, jw);
    }
    /* Contamination guard (§8.7.13): when the request-queue race feeds a next-word PREDICTION
     * list instead of the swipe's candidates (proven in gate-7: inline correct, pool = kitty/
     * everyone/beautiful), NONE of the candidates fits the swipe geometry, so even the best score
     * is high (~2.4+ vs ~0.5-1.5 for a real match). Decline (return -1) above a ceiling; the
     * bridge then keeps the inline word — which for the contaminated case is the engine's actual
     * gesture inline (often correct, e.g. "the"/"world"), never a random prediction. This is a
     * robustness net UNDER the full rank-at-deposit fix (W1), not a replacement for it.
     * debug.et9.rankmax = ceiling in centi-units (default 210 = 2.10; real hard-word matches ~1.86, contamination ~2.36); 0 disables the guard. */
    int ceil_centi = 210;
    {
        char v[PROP_VALUE_MAX];
        if (__system_property_get("debug.et9.rankmax", v) > 0 && v[0] >= '0' && v[0] <= '9')
            ceil_centi = atoi(v);
    }
    int declined = (ceil_centi > 0 && best >= 0 && bestScore * 100.0f > (float)ceil_centi);
    LOGI("OWNRANK n=%d path=%u best=%d score=%.2f ceil=%d%s | %s",
         (int)n, pn, best, (double)bestScore, ceil_centi, declined ? " DECLINED" : "", dbg);
    return declined ? -1 : best;
}

/* Deposit sequence of the current rank snapshot (§8.7.11): the bridge consumes each sequence
 * exactly once, so stale worker requests (prediction requests that still see the pending flag,
 * which is only consumed by the main-thread commit handler) can never rank dead engine state. */
JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeRankSeq(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    return (jint)xt9kdb_rank_seq();
}

/* AUDIT L10 (docs/2026-09_kdb-touch-abi_audit.md): the in-band channel for a recognizer that
 * could not run. ET9KDB_TouchEnd has to report success whatever happened — the blob's JNI shim
 * collapses any non-zero ET9STATUS to `false` in Java, and the blob's own dispatcher hardcodes 0
 * — so without these a dead recognizer is a logcat line and nothing else. It is not hypothetical:
 * on the KEY2 in September 2026 libkb.so loaded before the blob, the weak ET9KDB_ProcessTrace
 * reference resolved to NULL, and every swipe for the life of the process silently did nothing.
 * Both counters are monotonic for the process and cheap enough to read per gesture. */
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

/* W1 (§8.7.14): take the deposit-time-ranked gesture word for the given seq (consume-once).
 * Returns the word, or null if none was produced for this seq (ownrankc off, declined, or
 * already taken). The bridge uses it verbatim as the gesture result — no Java list build, so the
 * §8.7.13 request-queue contamination cannot occur. */
JNIEXPORT jstring JNICALL
Java_com_blackberry_nuanceshim_Xt9KdbVariant_nativeTakeGestureWord(JNIEnv* env, jclass cls, jint seq) {
    (void)cls;
    char buf[80];
    if (seq <= 0) return NULL;
    if (xt9kdb_take_gesture_word((ET9U32)seq, buf, (int)sizeof(buf)) <= 0) return NULL;
    return (*env)->NewStringUTF(env, buf);
}
