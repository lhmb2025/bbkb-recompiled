/* xt9kdb_jni.c — W1/W3a glue: load + self-check the owned ET9KDB module from the app, and (W3a)
 * mirror real swipe points into the owned trace on a SCRATCH context to log the owned candidate sets.
 *
 * Built into libxt9kdb.so (DIFF mode -> the owned symbols are xt9kdb_*). This is debug-only and does
 * NOT touch the live keyboard: it parses a KDB asset through the owned Load_XmlKDB, and the W3a path
 * runs the owned gesture decoder on a NULL context (never the blob's), capturing the committed
 * candidate sets via local sinks. No blob calls; the blob's state is untouched.
 */
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <string.h>
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "XT9KDB",  __VA_ARGS__)
#define LOGO(...) __android_log_print(ANDROID_LOG_INFO, "XT9OWNED", __VA_ARGS__)
#define LOGT(...) __android_log_print(ANDROID_LOG_INFO, "XT9TAP",  __VA_ARGS__)

/* ---- W3a capture sinks: record the owned candidate sets instead of calling AW ------------------ */
#define CAP_MAX 64
static struct { ET9SYMB symbs[8]; ET9U8 freqs[8]; int n; } g_cap[CAP_MAX];
static int  g_cap_n;
static int  g_sinks_installed;

static ET9STATUS cap_exact(void* ctx, ET9SYMB symb) {
    (void)ctx;
    if (g_cap_n < CAP_MAX) { g_cap[g_cap_n].symbs[0] = symb; g_cap[g_cap_n].freqs[0] = 255; g_cap[g_cap_n].n = 1; g_cap_n++; }
    return ET9STATUS_NONE;
}
static ET9STATUS cap_ambig(void* ctx, const ET9SYMB* symbs, const ET9U8* freqs, int count) {
    (void)ctx;
    if (g_cap_n < CAP_MAX) {
        int n = count > 8 ? 8 : count;
        for (int i = 0; i < n; i++) { g_cap[g_cap_n].symbs[i] = symbs[i]; g_cap[g_cap_n].freqs[i] = freqs[i]; }
        g_cap[g_cap_n].n = n; g_cap_n++;
    }
    return ET9STATUS_NONE;
}
static void install_sinks(void) {
    if (g_sinks_installed) return;
    xt9kdb_set_aw_commit(cap_exact);
    xt9kdb_set_aw_commit_ambig(cap_ambig);
    g_sinks_installed = 1;
}

JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9Kdb_nativeInit(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    int dummy = 1;
    ET9STATUS st = xt9kdb_Init((ET9KDBInfoPtr)&dummy);
    LOGI("owned Init status=%d", (int)st);
    return (jint)st;
}

JNIEXPORT jint JNICALL
Java_com_blackberry_nuanceshim_Xt9Kdb_nativeLoadKdb(JNIEnv* env, jclass cls, jbyteArray xml) {
    (void)cls;
    if (!xml) return -1;
    jsize n = (*env)->GetArrayLength(env, xml);
    jbyte* buf = (*env)->GetByteArrayElements(env, xml, 0);
    if (!buf) return -1;
    int dummy = 1;
    ET9STATUS st = xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, (const ET9U8*)buf, (ET9U32)n);
    (*env)->ReleaseByteArrayElements(env, xml, buf, JNI_ABORT);   /* read-only */
    int kc = (st == ET9STATUS_NONE && g_kdb_model) ? (int)g_kdb_model->keyCount : -1;
    LOGI("owned Load_XmlKDB status=%d keyCount=%d pid=%u sid=%u %ux%u",
         (int)st, kc,
         g_kdb_model ? g_kdb_model->primaryId   : 0u,
         g_kdb_model ? g_kdb_model->secondaryId : 0u,
         g_kdb_model ? g_kdb_model->authoredWidth  : 0u,
         g_kdb_model ? g_kdb_model->authoredHeight : 0u);
    /* The athena KDB now uses the firmware's UNEVEN sensor bands (rows 90/180/180), authored 1:1 with
     * the sensor — so authored == sensor, offset (0,0), no scale, no dead strip (row1 starts at y=0). */
    xt9kdb_set_active_pkb(1);
    xt9kdb_SetKeyboardOffset((ET9KDBInfoPtr)&dummy, 0, 0);
    install_sinks();
    return (jint)kc;
}

/* ---- Tap calibration: resolve a single tap through the OWNED model and log it ------------------
 * Logs raw SENSOR coords, the owned AUTHORED coords (after the 50px offset), the resolved letter, and
 * the offset from that key's (biased) center. Tap each physical key N times, then average the raw
 * coords per key to build a correction map. View: adb logcat -s XT9TAP:I */
JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Kdb_nativeLogTap(JNIEnv* env, jclass cls, jfloat x, jfloat y) {
    (void)env; (void)cls;
    if (!g_kdb_model || g_kdb_model->keyCount == 0) { LOGT("(no model)"); return; }
    float ax, ay;
    xt9kdb_sensor_to_authored((float)x, (float)y, &ax, &ay);
    int k = xt9kdb_point_to_key(g_kdb_model, ax, ay);
    if (k < 0) { LOGT("raw=(%.0f,%.0f) authored=(%.0f,%.0f) -> (none)", (double)x,(double)y,(double)ax,(double)ay); return; }
    const ET9KdbKey* key = &g_kdb_model->keys[k];
    int code = key->keyCode;
    char c = (code >= 32 && code < 127) ? (char)code : '?';
    float bcx = (float)(key->cx + key->biasX), bcy = (float)(key->cy + key->biasY);
    int inside = (ax >= key->left && ax <= key->right && ay >= key->top && ay <= key->bottom);
    LOGT("raw=(%.0f,%.0f) auth=(%.0f,%.0f) -> '%c' code=%d center=(%.0f,%.0f) off=(%+.0f,%+.0f)%s",
         (double)x,(double)y,(double)ax,(double)ay, c, code, (double)bcx,(double)bcy,
         (double)(ax-bcx),(double)(ay-bcy), inside ? "" : " [nearest]");
}

/* ---- W3a: scratch-context gesture mirror -------------------------------------------------------
 * NuanceSDK.touchStart/Move/End mirror raw SENSOR points here. The owned trace accumulates on its own
 * static buffer (never the blob's context). traceEnd decodes + commits to the capture sinks and logs
 * the owned candidate sets (XT9OWNED), to be diffed against the blob's recognized list (XT9WORDS). */

JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Kdb_nativeTraceStart(JNIEnv* env, jclass cls, jfloat x, jfloat y, jlong t) {
    (void)env; (void)cls;
    install_sinks();
    g_cap_n = 0;
    { float xs[1] = {(float)x}, ys[1] = {(float)y}; ET9U32 ts[1] = {(ET9U32)t};
      xt9kdb_TouchStart((ET9KDBInfoPtr)0, 0u, xs, ys, ts, 1u, 0u); }
}

JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Kdb_nativeTraceMove(JNIEnv* env, jclass cls, jfloat x, jfloat y, jlong t) {
    (void)env; (void)cls;
    { float xs[1] = {(float)x}, ys[1] = {(float)y}; ET9U32 ts[1] = {(ET9U32)t};
      xt9kdb_TouchMove((ET9KDBInfoPtr)0, 0u, xs, ys, ts, 1u, 0u); }
}

JNIEXPORT void JNICALL
Java_com_blackberry_nuanceshim_Xt9Kdb_nativeTraceEnd(JNIEnv* env, jclass cls, jfloat x, jfloat y, jlong t) {
    (void)env; (void)cls;
    if (!g_kdb_model || g_kdb_model->keyCount == 0) { LOGO("(no model loaded)"); return; }
    { float xs[1] = {(float)x}, ys[1] = {(float)y}; ET9U32 ts[1] = {(ET9U32)t};   /* push final point */
      xt9kdb_TouchMove((ET9KDBInfoPtr)0, 0u, xs, ys, ts, 1u, 0u); }
    g_cap_n = 0;
    xt9kdb_decode_scratch();                                                /* decode + capture */

    /* Concatenated top-candidate-per-position decode, for a direct eyeball vs XT9WORDS 'spell'. */
    char spell[80]; int sp = 0;
    for (int s = 0; s < g_cap_n && sp < (int)sizeof(spell) - 1; s++) {
        ET9SYMB sy = g_cap[s].n ? g_cap[s].symbs[0] : '?';
        spell[sp++] = (sy >= 32 && sy < 127) ? (char)sy : '?';
    }
    spell[sp] = 0;
    LOGO("owned spell='%s' (%d pos)  <- compare to XT9WORDS spell/word", spell, g_cap_n);

    /* Then one line per position with the full ambiguous set: "pos0: g[255] h[210] f[170]". */
    char line[512];
    for (int s = 0; s < g_cap_n; s++) {
        int off = 0;
        off += snprintf(line + off, sizeof(line) - off, "pos%d:", s);
        for (int i = 0; i < g_cap[s].n && off < (int)sizeof(line) - 12; i++) {
            ET9SYMB sy = g_cap[s].symbs[i];
            char c = (sy >= 32 && sy < 127) ? (char)sy : '?';
            off += snprintf(line + off, sizeof(line) - off, " %c[%u]", c, (unsigned)g_cap[s].freqs[i]);
        }
        LOGO("%s", line);
    }
}
