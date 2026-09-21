/* touch_abi_selftest.c — pins the TOUCH / GESTURE ABI contracts of the owned ET9KDB module.
 *
 * Companion to docs/2026-09_kdb-touch-abi_audit.md. Every CHECK here corresponds to a numbered
 * claim in that document; the point is that the claims stop being prose the moment someone edits
 * the entry points. Nothing here asserts anything about the blob — these are the OWNED module's
 * own contracts, observed through its own read-back API (xt9kdb_stored_path / GetKeyPositions /
 * the AW commit sink).
 *
 * Runs WITHOUT the blob (DIFF symbol names, xt9kdb_*), like the rest of the offline suite.
 *
 * Build/run: via test/run_tests.sh (stage "touch/gesture ABI contracts").
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

/* run_tests.sh builds this file in BOTH symbol modes — the DIFF names below, and again under
 * -DXT9KDB_CUTOVER for section 3b, which is about a context field only the cutover build may
 * write. In cutover the entry points export as ET9KDB_* (et9kdb.h's XT9KDB macro), so bind the
 * thirteen names the checks use rather than spelling XT9KDB(...) at every call site. The
 * non-exported helpers (xt9kdb_stored_path, xt9kdb_sensor_to_authored, ...) keep their names in
 * both modes and are deliberately absent from this list. */
#if defined(XT9KDB_CUTOVER)
#define xt9kdb_TouchStart         ET9KDB_TouchStart
#define xt9kdb_TouchMove          ET9KDB_TouchMove
#define xt9kdb_TouchEnd           ET9KDB_TouchEnd
#define xt9kdb_TouchCancel        ET9KDB_TouchCancel
#define xt9kdb_TouchEndAll        ET9KDB_TouchEndAll
#define xt9kdb_TouchTimeOut       ET9KDB_TouchTimeOut
#define xt9kdb_SetTraceInput      ET9KDB_SetTraceInput
#define xt9kdb_ClearTraceInput    ET9KDB_ClearTraceInput
#define xt9kdb_SetKeyboardSize    ET9KDB_SetKeyboardSize
#define xt9kdb_GetKeyboardSize    ET9KDB_GetKeyboardSize
#define xt9kdb_GetKeyPositions    ET9KDB_GetKeyPositions
#define xt9kdb_GetKeyPositionByTap         ET9KDB_GetKeyPositionByTap
#define xt9kdb_GetKeyPositionByStoredTouch ET9KDB_GetKeyPositionByStoredTouch
#define xt9kdb_ProcessKeyBySymbol ET9KDB_ProcessKeyBySymbol
#define xt9kdb_ProcessTap         ET9KDB_ProcessTap
#define xt9kdb_Load_XmlKDB        ET9KDB_Load_XmlKDB
#endif

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, msg, ...) do { \
    if (cond) { g_pass++; } \
    else { g_fail++; printf("  FAIL: " msg "\n", ##__VA_ARGS__); } } while (0)

/* A context big enough that GetKeyPositions' ctx+0xfc30..36 reads land inside it. Zeroed, so
 * offset/scale read as 0 — the PKB configuration. */
#define CTX_SZ 0x10000
static unsigned char* g_ctx;

/* Touch* take arrays with a count; keep the scalar call shape readable. */
static ET9STATUS tstart(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    return xt9kdb_TouchStart(c, 1, xs, ys, ts, 1, 0);
}
static ET9STATUS tmove(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    return xt9kdb_TouchMove(c, 1, xs, ys, ts, 1, 0);
}
static ET9STATUS tend(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    return xt9kdb_TouchEnd(c, 1, xs, ys, ts, 1);
}
static ET9U32 path_n(void) { ET9U32 n = 0; (void)xt9kdb_stored_path(&n); return n; }

/* Same three, with an explicit contact id — section 1b needs two concurrent pointers. */
static ET9STATUS tstart_id(void* c, ET9U64 id, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    return xt9kdb_TouchStart(c, id, xs, ys, ts, 1, 0);
}
static ET9STATUS tmove_id(void* c, ET9U64 id, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    return xt9kdb_TouchMove(c, id, xs, ys, ts, 1, 0);
}
static ET9STATUS tend_id(void* c, ET9U64 id, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    return xt9kdb_TouchEnd(c, id, xs, ys, ts, 1);
}
/* The x of the idx-th stored sample, or -1 — proves WHOSE points are in the buffer. */
static float path_x(ET9U32 idx) {
    ET9U32 n = 0; const ET9KdbSample* p = xt9kdb_stored_path(&n);
    return idx < n ? p[idx].x : -1.0f;
}

/* ---- 1. session lifecycle: what starts, extends and clears the path buffer ------------------ */
static void test_session_lifecycle(void) {
    printf("[1] touch session lifecycle\n");

    /* TouchStart opens a session and stores the down point. */
    tstart(g_ctx, 53.0f, 63.0f, 100);
    CHECK(path_n() == 1, "TouchStart did not store the down point (n=%u)", path_n());

    tmove(g_ctx, 161.0f, 63.0f, 110);
    tmove(g_ctx, 269.0f, 63.0f, 120);
    CHECK(path_n() == 3, "TouchMove did not append (n=%u)", path_n());

    /* TouchCancel clears the session. This is the contract the gesture arbiter relies on: a
     * contact it consumes is cancelled so its points never reach the recognizer. */
    ET9STATUS st = xt9kdb_TouchCancel(g_ctx, 1);
    CHECK(st == ET9STATUS_NONE, "TouchCancel status=%d", (int)st);
    CHECK(path_n() == 0, "TouchCancel did not clear the session (n=%u)", path_n());

    /* TouchCancel ignores its touchId: any id clears the single global buffer. Pinning this
     * because it is the multi-contact hazard recorded in kdb_trace.c's TouchStart note. */
    tstart(g_ctx, 53.0f, 63.0f, 200);
    tmove(g_ctx, 161.0f, 63.0f, 210);
    xt9kdb_TouchCancel(g_ctx, 999);              /* a DIFFERENT pointer id */
    CHECK(path_n() == 0, "TouchCancel(other id) left the session alive (n=%u)", path_n());

    /* TouchStart resets whatever was accumulated — a second contact restarts the path. */
    tstart(g_ctx, 53.0f, 63.0f, 300);
    tmove(g_ctx, 161.0f, 63.0f, 310);
    tmove(g_ctx, 269.0f, 63.0f, 320);
    tstart(g_ctx, 917.0f, 63.0f, 330);
    CHECK(path_n() == 1, "TouchStart did not reset the previous path (n=%u)", path_n());

    /* TouchEnd appends its own point and does NOT clear the buffer: the deposited path has to
     * stay readable after recognition (GetKeyPositionByStoredTouch, the TRACEPT dump, the
     * owned ranker's snapshot). Only TouchStart / TouchCancel / Set|ClearTraceInput clear it. */
    tmove(g_ctx, 809.0f, 63.0f, 340);
    tend(g_ctx, 701.0f, 63.0f, 350);
    CHECK(path_n() == 3, "TouchEnd should append and keep the path (n=%u)", path_n());

    /* SetTraceInput and ClearTraceInput both clear, regardless of the flag's value. */
    xt9kdb_SetTraceInput(g_ctx, 1);
    CHECK(path_n() == 0, "SetTraceInput(on) did not clear (n=%u)", path_n());
    tstart(g_ctx, 53.0f, 63.0f, 400);
    tmove(g_ctx, 161.0f, 63.0f, 410);
    xt9kdb_ClearTraceInput(g_ctx);
    CHECK(path_n() == 0, "ClearTraceInput did not clear (n=%u)", path_n());

    /* TouchEndAll and TouchTimeOut do NOT clear the owned buffer — like TouchEnd, they leave the
     * deposited path readable. Only TouchStart / TouchCancel / Set|ClearTraceInput clear it. */
    tstart(g_ctx, 53.0f, 63.0f, 500);
    tmove(g_ctx, 161.0f, 63.0f, 510);
    xt9kdb_TouchEndAll(g_ctx);
    xt9kdb_TouchTimeOut(g_ctx);
    CHECK(path_n() == 2, "TouchEndAll/TouchTimeOut unexpectedly touched the path (n=%u)", path_n());

    /* ...but they DO release the session (audit L9). They are terminators, and once the buffer
     * has an owner (L1) a terminator that keeps it leaves the foreign-contact filter armed
     * against every other pointer. Witness: after TouchEndAll a DIFFERENT contact may write. */
    xt9kdb_TouchCancel(g_ctx, 1);
    tstart_id(g_ctx, 11, 53.0f, 63.0f, 600);
    xt9kdb_TouchEndAll(g_ctx);
    tmove_id(g_ctx, 12, 900.0f, 63.0f, 610);
    CHECK(path_n() == 2, "TouchEndAll did not release the session owner (n=%u)", path_n());

    xt9kdb_TouchCancel(g_ctx, 1);
    tstart_id(g_ctx, 13, 53.0f, 63.0f, 700);
    xt9kdb_TouchTimeOut(g_ctx);
    tmove_id(g_ctx, 14, 900.0f, 63.0f, 710);
    CHECK(path_n() == 2, "TouchTimeOut did not release the session owner (n=%u)", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

/* ---- 1b. multi-contact isolation (audit L1) ------------------------------------------------
 * One path buffer, several contacts. The contract after the L1 fix: the contact that most
 * recently called TouchStart OWNS the buffer, and nobody else can write to it or finish it.
 * Before the fix every one of these checks failed -- touchId was `(void)`-discarded, so a stray
 * finger's moves were appended to the real gesture and its lift ran the recognizer.
 *
 * Coordinates are chosen so the owner's path is all x<=300 and the intruder's all x>=900: the
 * buffer contents alone say which contact wrote them.
 */
static void test_multi_contact(void) {
    printf("[1b] multi-contact isolation (touchId)\n");
    xt9kdb_TouchCancel(g_ctx, 7);                    /* quiesce: no live session */
    const ET9U32 drops0 = xt9kdb_foreign_contact_drops();

    /* Contact 7 is swiping. Contact 8 brushes the sensor edge: its moves must NOT be appended. */
    tstart_id(g_ctx, 7, 100.0f, 63.0f, 100);
    tmove_id (g_ctx, 7, 200.0f, 63.0f, 110);
    tmove_id (g_ctx, 8, 900.0f, 63.0f, 115);         /* intruder — dropped */
    tmove_id (g_ctx, 7, 300.0f, 63.0f, 120);
    CHECK(path_n() == 3, "a foreign contact's move entered the path (n=%u)", path_n());
    CHECK(path_x(0) == 100.0f && path_x(1) == 200.0f && path_x(2) == 300.0f,
          "the path is not the owner's alone (%.0f,%.0f,%.0f)",
          (double)path_x(0), (double)path_x(1), (double)path_x(2));

    /* The intruder lifting must not append its point and must not run the recognizer. */
    CHECK(tend_id(g_ctx, 8, 900.0f, 63.0f, 125) == ET9STATUS_NONE,
          "a foreign TouchEnd must still report success");
    CHECK(path_n() == 3, "a foreign contact's END entered the path (n=%u)", path_n());

    /* ...and the owner can still finish normally afterwards. */
    CHECK(tend_id(g_ctx, 7, 400.0f, 63.0f, 130) == ET9STATUS_NONE, "owner TouchEnd failed");
    CHECK(path_n() == 4 && path_x(3) == 400.0f,
          "the owner's own end was rejected (n=%u last=%.0f)", path_n(), (double)path_x(3));

    /* Every rejection is RECORDED, not silent: two moves' worth here (one move + one end). */
    CHECK(xt9kdb_foreign_contact_drops() == drops0 + 2,
          "foreign-contact drops counted %u, expected %u",
          xt9kdb_foreign_contact_drops() - drops0, 2u);

    /* NEWEST CONTACT WINS on TouchStart — unchanged, and deliberately so: the CKB gesture
     * arbiter abandons an in-flight trace when a secondary finger lands (chained flicks). The
     * abandonment is counted; the buffer still restarts from the new contact. */
    {
        const ET9U32 d = xt9kdb_foreign_contact_drops();
        tstart_id(g_ctx, 7, 100.0f, 63.0f, 200);
        tmove_id (g_ctx, 7, 200.0f, 63.0f, 210);
        tstart_id(g_ctx, 8, 900.0f, 63.0f, 220);     /* second finger lands */
        CHECK(path_n() == 1 && path_x(0) == 900.0f,
              "a newer contact did not take the buffer (n=%u first=%.0f)",
              path_n(), (double)path_x(0));
        CHECK(xt9kdb_foreign_contact_drops() == d + 1,
              "abandoning an in-flight contact was not recorded");
        /* ownership transferred: 8 now writes, 7 does not */
        tmove_id(g_ctx, 7, 300.0f, 63.0f, 230);
        tmove_id(g_ctx, 8, 800.0f, 63.0f, 240);
        CHECK(path_n() == 2 && path_x(1) == 800.0f,
              "ownership did not transfer to the newer contact (n=%u [1]=%.0f)",
              path_n(), (double)path_x(1));
    }

    /* TouchCancel still clears whatever is live, whatever id it carries. That is the arbiter's
     * escape hatch AND the only unstick path, so an owner can never outlive its gesture. */
    xt9kdb_TouchCancel(g_ctx, 999);
    CHECK(path_n() == 0, "TouchCancel(other id) left the session alive (n=%u)", path_n());
    /* ...and with no session live, nothing is filtered: any contact may write again. */
    tmove_id(g_ctx, 42, 500.0f, 63.0f, 300);
    CHECK(path_n() == 1, "filtering outlived the session (n=%u)", path_n());

    /* Set/ClearTraceInput release the session too, so the engine core abandoning a trace does
     * not leave a stale owner behind. */
    tstart_id(g_ctx, 7, 100.0f, 63.0f, 400);
    xt9kdb_ClearTraceInput(g_ctx);
    tmove_id(g_ctx, 8, 900.0f, 63.0f, 410);
    CHECK(path_n() == 1, "ClearTraceInput did not release the session owner (n=%u)", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

/* ---- 2. point admission: the 1px near-duplicate merge and the 2499 cap --------------------- */
static void test_point_admission(void) {
    printf("[2] point admission (1px merge, 2499 cap)\n");

    tstart(g_ctx, 100.0f, 100.0f, 0);
    tmove(g_ctx, 100.5f, 100.5f, 10);            /* < 1.0 in BOTH axes -> merged */
    CHECK(path_n() == 1, "near-duplicate was stored (n=%u)", path_n());
    {
        ET9U32 n = 0; const ET9KdbSample* p = xt9kdb_stored_path(&n);
        CHECK(n == 1 && p[0].timestamp == 10, "merge must update the timestamp (t=%u)",
              n ? p[0].timestamp : 0u);
        CHECK(n == 1 && p[0].x == 100.0f && p[0].y == 100.0f,
              "merge must keep the ORIGINAL coordinates");
    }
    tmove(g_ctx, 101.5f, 100.5f, 20);            /* dx = 1.5 -> admitted */
    CHECK(path_n() == 2, "1.5px move was dropped (n=%u)", path_n());
    /* The merge test is per-axis-AND: a big dx with a tiny dy still admits. */
    tmove(g_ctx, 101.6f, 140.0f, 30);            /* dy = 39.5 -> admitted */
    CHECK(path_n() == 3, "large-dy move was dropped (n=%u)", path_n());

    /* Hard cap at 2499 stored samples (ET9_TRACE_CAP, RE-confirmed from the blob). */
    tstart(g_ctx, 0.0f, 0.0f, 0);
    for (int i = 1; i < 4000; i++) tmove(g_ctx, (float)(i * 2), (float)(i % 300), (ET9U32)i);
    CHECK(path_n() == 2499, "trace cap is not 2499 (n=%u)", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

/* ---- 3. SetKeyboardSize: the scale contract ------------------------------------------------ */
static void test_keyboard_size(void) {
    printf("[3] SetKeyboardSize scale contract\n");
    const ET9KdbLoaded* m = g_kdb_model;
    const float aw = (float)m->authoredWidth, ah = (float)m->authoredHeight;

    /* (0,0) = "no scaling": sensor space IS authored space. This is the PKB configuration the
     * IME pushes (NuanceSDK.setKeyboardSize's isPkb branch). */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 0, 0) == ET9STATUS_NONE, "SetKeyboardSize(0,0) failed");
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(540.0f, 192.0f, &ax, &ay);
        CHECK(ax == 540.0f && ay == 192.0f, "no-scale map is not 1:1 (%.1f,%.1f)",
              (double)ax, (double)ay);
    }

    /* Mismatched zero: the blob returns BAD_PARAM and so do we. */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 1080, 0) != ET9STATUS_NONE,
          "SetKeyboardSize(1080,0) should be rejected");
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 0, 600) != ET9STATUS_NONE,
          "SetKeyboardSize(0,600) should be rejected");

    /* A real size = the VKB stretch: authored = authoredDim * view / scale. A view twice the
     * authored width halves every incoming coordinate. */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, (ET9U16)(aw * 2), (ET9U16)(ah * 2)) == ET9STATUS_NONE,
          "SetKeyboardSize(2x) failed");
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(aw, ah, &ax, &ay);
        CHECK(ax == aw / 2.0f && ay == ah / 2.0f, "2x stretch did not halve (%.1f,%.1f)",
              (double)ax, (double)ay);
    }
    /* Out-of-range input is clamped to the authored grid, never wrapped. */
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(-500.0f, 99999.0f, &ax, &ay);
        CHECK(ax == 0.0f && ay == ah - 1.0f, "clamp failed (%.1f,%.1f)", (double)ax, (double)ay);
    }

    /* A rejected call must NOT have changed the live scale. Re-assert the 2x map after the two
     * rejections above by setting it again and then failing a call. */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 1234, 0) != ET9STATUS_NONE, "expected rejection");
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(aw, ah, &ax, &ay);
        CHECK(ax == aw / 2.0f && ay == ah / 2.0f,
              "a rejected SetKeyboardSize clobbered the live scale (%.1f,%.1f)",
              (double)ax, (double)ay);
    }
    xt9kdb_SetKeyboardSize(g_ctx, 0, 0);          /* back to the PKB config for later stages */
}

/* ---- 3b. the scale lives WITH THE CONTEXT (audit L2/L3) -------------------------------------
 * CUTOVER only. There we own SetKeyboardSize, so ctx+0xfc34/36 — where the blob keeps it, and
 * where our own GetKeyPositions has always read it — is ours to maintain. Under DIFF the blob's
 * SetKeyboardSize owns that field and ours must not write it, so this whole section is compiled
 * out (run_tests.sh builds this file twice, once per mode).
 *
 * Before the fix: SetKeyboardSize set a process-global ONLY, so (a) the context never learned the
 * VKB stretch and getKeys kept returning unprojected authored rects, and (b) a second engine
 * context's sync clobbered the first's scale process-wide.
 */
static void test_ctx_scale(void) {
#if defined(XT9KDB_CUTOVER)
    printf("[3b] SetKeyboardSize stores the scale in the CONTEXT\n");
    const ET9KdbLoaded* m = g_kdb_model;
    const ET9U16 aw = m->authoredWidth, ah = m->authoredHeight;

    /* A VKB stretch reaches the context... */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, (ET9U16)(aw * 2), (ET9U16)(ah * 2)) == ET9STATUS_NONE,
          "SetKeyboardSize(2x) failed");
    CHECK(*(ET9U16*)(g_ctx + 0xfc34) == (ET9U16)(aw * 2) &&
          *(ET9U16*)(g_ctx + 0xfc36) == (ET9U16)(ah * 2),
          "the scale did not reach ctx+0xfc34/36 (%u,%u)",
          *(ET9U16*)(g_ctx + 0xfc34), *(ET9U16*)(g_ctx + 0xfc36));

    /* ...so getKeys projects: 'q' spans authored [0,107] in a 1080 grid -> [0,214] at 2x. This is
     * the consumer that was wrong by exactly the stretch factor (CkbKeyGridCapture divides by the
     * VIEW size, so an unprojected rect made the cached grid wrong). */
    {
        unsigned char* out = calloc(0x30, (size_t)m->keyCount + 8);
        ET9U16 count = 0;
        CHECK(xt9kdb_GetKeyPositions(g_ctx, out, m->keyCount, &count) == ET9STATUS_NONE,
              "stretched getKeys failed");
        CHECK(*(const ET9U16*)(out + 0x2c) == 214,
              "getKeys did not project the stretch: right = %u, expected 214",
              *(const ET9U16*)(out + 0x2c));
        free(out);
    }

    /* A REJECTED call must leave the context's scale alone, exactly as it leaves the live
     * sensor mapping alone (section 3). */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 1234, 0) != ET9STATUS_NONE, "expected rejection");
    CHECK(*(ET9U16*)(g_ctx + 0xfc34) == (ET9U16)(aw * 2),
          "a rejected SetKeyboardSize clobbered the context scale (%u)",
          *(ET9U16*)(g_ctx + 0xfc34));

    /* A SECOND engine context (the spellchecker's) syncing its own keyboard must not disturb the
     * first's. This is L2: one process-global made the last sync win everywhere. */
    {
        unsigned char* ctx2 = calloc(1, CTX_SZ);
        CHECK(ctx2 != 0, "OOM allocating the second context");
        if (ctx2) {
            CHECK(xt9kdb_SetKeyboardSize(ctx2, 0, 0) == ET9STATUS_NONE, "ctx2 PKB sync failed");
            CHECK(*(ET9U16*)(g_ctx + 0xfc34) == (ET9U16)(aw * 2),
                  "a second context's sync clobbered the first's scale (%u)",
                  *(ET9U16*)(g_ctx + 0xfc34));

            /* ...and the gesture path follows the GESTURING context: TouchStart re-publishes its
             * scale to the ctx-less mapper, so ctx2's (0,0) does not survive into g_ctx's swipe. */
            tstart_id(g_ctx, 3, 0.0f, 0.0f, 0);
            {
                float ax = -1, ay = -1;
                xt9kdb_sensor_to_authored((float)aw, (float)ah, &ax, &ay);
                CHECK(ax == aw / 2.0f && ay == ah / 2.0f,
                      "TouchStart did not restore the gesturing context's stretch (%.1f,%.1f)",
                      (double)ax, (double)ay);
            }
            /* and starting a gesture on ctx2 maps 1:1, its own configuration */
            tstart_id(ctx2, 4, 0.0f, 0.0f, 0);
            {
                float ax = -1, ay = -1;
                xt9kdb_sensor_to_authored((float)(aw / 2), (float)(ah / 2), &ax, &ay);
                CHECK(ax == (float)(aw / 2) && ay == (float)(ah / 2),
                      "the second context's gesture inherited the first's stretch (%.1f,%.1f)",
                      (double)ax, (double)ay);
            }
            xt9kdb_TouchCancel(ctx2, 4);
            free(ctx2);
        }
    }
    xt9kdb_SetKeyboardSize(g_ctx, 0, 0);          /* back to the PKB config for later stages */
    xt9kdb_TouchCancel(g_ctx, 1);
#endif
}

/* ---- 4. GetKeyPositions: the geometry read-back contract ----------------------------------- */
static void test_get_key_positions(void) {
    printf("[4] GetKeyPositions contract\n");
    const ET9KdbLoaded* m = g_kdb_model;
    const ET9U16 n = m->keyCount;
    unsigned char* out = calloc(0x30, (size_t)n + 8);
    ET9U16 count = 0;

    /* count-only probe: out == NULL returns the key count without touching a buffer. */
    CHECK(xt9kdb_GetKeyPositions(g_ctx, 0, 0, &count) == ET9STATUS_NONE, "count probe failed");
    CHECK(count == n, "count probe returned %u, expected %u", count, n);

    /* capacity < keyCount is rejected with the blob's 0x1a. */
    count = 0;
    CHECK(xt9kdb_GetKeyPositions(g_ctx, out, (ET9U16)(n - 1), &count) == (ET9STATUS)0x1a,
          "short buffer was not rejected with 0x1a");

    /* Full read: stride 0x30, and with offset/scale zero the records are the authored rects. */
    count = 0;
    CHECK(xt9kdb_GetKeyPositions(g_ctx, out, n, &count) == ET9STATUS_NONE, "full read failed");
    CHECK(count == n, "full read wrote count=%u", count);
    {
        int diffs = 0;
        for (ET9U16 i = 0; i < n; i++) {
            const unsigned char* r = out + (size_t)i * 0x30;
            if (*(const ET9U16*)(r + 0x0a) != m->keys[i].keyCode) diffs++;
            else if (*(const ET9U16*)(r + 0x28) != m->keys[i].left)   diffs++;
            else if (*(const ET9U16*)(r + 0x2a) != m->keys[i].top)    diffs++;
            else if (*(const ET9U16*)(r + 0x2c) != m->keys[i].right)  diffs++;
            else if (*(const ET9U16*)(r + 0x2e) != m->keys[i].bottom) diffs++;
            else if (*(const ET9U32*)(r + 0x20) != (ET9U32)m->keys[i].cx) diffs++;
            else if (*(const ET9U32*)(r + 0x24) != (ET9U32)m->keys[i].cy) diffs++;
        }
        CHECK(diffs == 0, "%d record(s) differ from the authored model", diffs);
    }

    /* GetKeyPositions projects with the offset/scale in the CONTEXT (ctx+0xfc30..36), NOT with
     * the sensor-calibration globals SetKeyboardSize/SetKeyboardOffset maintain. Proving it:
     * set a context scale of 2x and the records double, while a live SetKeyboardSize(0,0)
     * leaves the sensor mapping 1:1. */
    *(ET9U16*)(g_ctx + 0xfc34) = (ET9U16)(m->authoredWidth * 2);
    *(ET9U16*)(g_ctx + 0xfc36) = (ET9U16)(m->authoredHeight * 2);
    count = 0;
    CHECK(xt9kdb_GetKeyPositions(g_ctx, out, n, &count) == ET9STATUS_NONE, "scaled read failed");
    {
        /* key 0 is 'q' at left 0, right 107 in a 1080-wide authored grid; at 2x the right edge
         * projects to round(2160*107/1080) = 214. */
        const unsigned char* r = out;
        CHECK(*(const ET9U16*)(r + 0x28) == 0, "scaled left != 0");
        CHECK(*(const ET9U16*)(r + 0x2c) == 214, "scaled right = %u, expected 214",
              *(const ET9U16*)(r + 0x2c));
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(540.0f, 192.0f, &ax, &ay);
        CHECK(ax == 540.0f && ay == 192.0f,
              "the ctx scale leaked into the sensor mapping (%.1f,%.1f)", (double)ax, (double)ay);
    }
    *(ET9U16*)(g_ctx + 0xfc34) = 0;
    *(ET9U16*)(g_ctx + 0xfc36) = 0;

    /* GetKeyboardSize reports the AUTHORED dimensions, not whatever SetKeyboardSize was given. */
    {
        ET9U16 w = 0, h = 0;
        xt9kdb_SetKeyboardSize(g_ctx, 2000, 1000);
        CHECK(xt9kdb_GetKeyboardSize(g_ctx, &w, &h) == ET9STATUS_NONE, "GetKeyboardSize failed");
        CHECK(w == m->authoredWidth && h == m->authoredHeight,
              "GetKeyboardSize returned %ux%u, expected the authored %ux%u",
              w, h, m->authoredWidth, m->authoredHeight);
        xt9kdb_SetKeyboardSize(g_ctx, 0, 0);
    }
    free(out);
}

/* ---- 4b. GetKeyPositions reads the CALLER'S model, not the legacy global (audit L4) ---------
 * It used to `(void)ctx;` the model lookup and then project with ctx+0xfc30..36 — model from the
 * global, projection from the context. Two engine contexts exist and each loads its own KDB into
 * its own slot, so a getKeys on the spellchecker's handle returned the IME's geometry through the
 * spellchecker's scale. The contexts here hold deliberately different layouts.
 */
static const char TWO_KEY_XML[] =
    "<keyboard primaryId=\"9\" secondaryId=\"6\""
    " defaultLayoutWidth=\"1080\" defaultLayoutHeight=\"450\">"
    "<key keyType=\"regional\" keyLabel=\"Q\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"0dp\" />"
    "<key keyType=\"regional\" keyLabel=\"W\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"540dp\" />"
    "</keyboard>";

static void test_get_key_positions_per_ctx(void) {
    printf("[4b] GetKeyPositions resolves the model per context\n");
    /* Room for the field map AND an arena Load_XmlKDB can emit the NKL into (cutover mode). */
    unsigned char* ctx2 = calloc(1, CTX_SZ + 0x4000);
    if (!ctx2) { CHECK(0, "OOM allocating the second context"); return; }
    *(void**)(ctx2 + ET9_OFF_ARENA) = ctx2 + CTX_SZ;
    *(ET9U16*)(ctx2 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;

    CHECK(xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)TWO_KEY_XML, (ET9U32)(sizeof TWO_KEY_XML - 1))
              == ET9STATUS_NONE, "loading the second context's KDB failed");
    /* The loader publishes what it just loaded to the legacy global (that is its job). Point the
     * global back at the 30-key fixture so the two disagree — which is the whole scenario: the
     * global holds the IME's model while ctx2's slot holds the spellchecker's. */
    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);
    const ET9U16 globalKeys = g_kdb_model->keyCount;          /* 30, the bring-up fixture */
    CHECK(globalKeys != 2, "test setup: the two layouts must differ (%u keys)", globalKeys);

    /* The count-only probe is the cheapest witness: ctx2 has 2 keys, the global has 30. */
    {
        ET9U16 count = 0;
        CHECK(xt9kdb_GetKeyPositions(ctx2, 0, 0, &count) == ET9STATUS_NONE, "ctx2 count probe failed");
        CHECK(count == 2, "getKeys on ctx2 returned %u keys — the other context's model", count);
    }
    /* ...and the records are ctx2's geometry: 'q' spans the left half of a 1080 grid. */
    {
        unsigned char* out = calloc(0x30, 8);
        ET9U16 count = 0;
        CHECK(xt9kdb_GetKeyPositions(ctx2, out, 8, &count) == ET9STATUS_NONE, "ctx2 read failed");
        CHECK(count == 2 && *(const ET9U16*)(out + 0x0a) == (ET9U16)'q' &&
              *(const ET9U16*)(out + 0x2c) == 539,
              "ctx2 record 0 is code=%u right=%u, expected 'q'/539",
              count ? *(const ET9U16*)(out + 0x0a) : 0u,
              count ? *(const ET9U16*)(out + 0x2c) : 0u);
        free(out);
    }
    /* A read-only query must NOT re-publish to the ctx-less global the way TouchStart does —
     * that would let a spellchecker getKeys redirect the IME's own resolution. */
    CHECK(g_kdb_model->keyCount == globalKeys,
          "getKeys redirected the legacy global model (%u keys, was %u)",
          g_kdb_model->keyCount, globalKeys);
    /* A context with no model of its own still falls back to the global (the bring-up fixture
     * and every offline driver depend on it). */
    {
        ET9U16 count = 0;
        CHECK(xt9kdb_GetKeyPositions(g_ctx, 0, 0, &count) == ET9STATUS_NONE, "fallback probe failed");
        CHECK(count == globalKeys, "a model-less context did not fall back (%u)", count);
    }
    free(ctx2);
}

/* ---- 4c. GetKeyPositionByTap / ByStoredTouch resolve per context (audit L4, second half) ----
 * The same defect 4b fixed for GetKeyPositions, on the two point->key QUERIES: they took the
 * model (and, in cutover, the scale) from the process globals, which the GESTURE path maintains
 * by re-publishing the gesturing context at TouchStart. A one-shot query on another handle
 * therefore answered with whichever keyboard gestured last. Both now read the caller's context.
 *
 * ctx2 holds the two-key layout from 4b: 'q' covers the LEFT half of a 1080-wide grid, 'w' the
 * right. The global holds the 30-key fixture, where the same point is 'i'. One sensor point,
 * two answers — that is the whole test.
 */
static void test_key_position_by_ctx(void) {
    printf("[4c] GetKeyPositionByTap / ByStoredTouch resolve per context\n");
    unsigned char* ctx2 = calloc(1, CTX_SZ + 0x4000);
    if (!ctx2) { CHECK(0, "OOM allocating the second context"); return; }
    *(void**)(ctx2 + ET9_OFF_ARENA) = ctx2 + CTX_SZ;
    *(ET9U16*)(ctx2 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;
    CHECK(xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)TWO_KEY_XML, (ET9U32)(sizeof TWO_KEY_XML - 1))
              == ET9STATUS_NONE, "loading the second context's KDB failed");
    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);     /* the global disagrees with ctx2, as in 4b */

    ET9KdbKey k;
    /* (800,60): ctx2's grid puts it in 'w'; the 30-key fixture puts it in 'i'. */
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByTap(ctx2, 800, 60, &k) == ET9STATUS_NONE, "ByTap(ctx2) failed");
    CHECK(k.keyCode == (ET9U16)'w', "ByTap on ctx2 resolved '%c' — the other context's model",
          (char)k.keyCode);
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByTap(g_ctx, 800, 60, &k) == ET9STATUS_NONE, "ByTap(g_ctx) failed");
    CHECK(k.keyCode == (ET9U16)'i', "a model-less context did not fall back to the global ('%c')",
          (char)k.keyCode);

    /* The stored PATH is process-global (one gesture at a time); only the RESOLUTION is
     * per-context. Depositing on g_ctx must not publish a model (it has none), so the global
     * stays the fixture and the two handles still disagree about the same sample. */
    xt9kdb_TouchCancel(g_ctx, 1);
    tstart(g_ctx, 800.0f, 60.0f, 0);
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByStoredTouch(ctx2, 0, &k) == ET9STATUS_NONE,
          "ByStoredTouch(ctx2) failed");
    CHECK(k.keyCode == (ET9U16)'w', "ByStoredTouch on ctx2 resolved '%c'", (char)k.keyCode);
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByStoredTouch(g_ctx, 0, &k) == ET9STATUS_NONE,
          "ByStoredTouch(g_ctx) failed");
    CHECK(k.keyCode == (ET9U16)'i', "ByStoredTouch fallback resolved '%c'", (char)k.keyCode);
    /* An out-of-range index is still "no key", not the caller's buffer filled with rubbish. */
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByStoredTouch(ctx2, 9999, &k) == ET9STATUS_NONE,
          "ByStoredTouch(out of range) should be a benign no-op");
    CHECK(k.keyCode == 0, "ByStoredTouch(out of range) wrote a key");
    xt9kdb_TouchCancel(g_ctx, 1);

#if defined(XT9KDB_CUTOVER)
    /* ...and the SCALE comes from the context too. ctx2 stretched 2x halves the incoming point:
     * 800 -> authored 400, which is 'q', not 'w'. The live sensor globals are untouched (the
     * gesture path owns those), so g_ctx keeps answering 'i' for the same coordinates. */
    *(ET9U16*)(ctx2 + 0xfc34) = 2160;
    *(ET9U16*)(ctx2 + 0xfc36) = 900;
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByTap(ctx2, 800, 60, &k) == ET9STATUS_NONE, "scaled ByTap failed");
    CHECK(k.keyCode == (ET9U16)'q', "ByTap ignored the context's stretch (resolved '%c')",
          (char)k.keyCode);
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByTap(g_ctx, 800, 60, &k) == ET9STATUS_NONE, "ByTap(g_ctx) failed");
    CHECK(k.keyCode == (ET9U16)'i', "a second context's stretch leaked into another handle ('%c')",
          (char)k.keyCode);
    *(ET9U16*)(ctx2 + 0xfc34) = 0;
    *(ET9U16*)(ctx2 + 0xfc36) = 0;
#endif
    free(ctx2);
}

/* ---- 5. the tap path: a symbol key reaches the AW commit sink ------------------------------- */
static ET9SYMB g_sink[16];
static int     g_sink_n;
static ET9STATUS mock_commit(void* ctx, ET9SYMB s) {
    (void)ctx;
    if (g_sink_n < (int)(sizeof g_sink / sizeof g_sink[0])) g_sink[g_sink_n++] = s;
    return ET9STATUS_NONE;
}
static ET9SYMB g_set[8]; static int g_set_n;
static ET9STATUS mock_commit_ambig(void* ctx, const ET9SYMB* s, const ET9U8* f, int n) {
    (void)ctx; (void)f;
    g_set_n = n > 8 ? 8 : n;
    for (int i = 0; i < g_set_n; i++) g_set[i] = s[i];
    return ET9STATUS_NONE;
}

static void test_tap_path(void) {
    printf("[5] tap path -> AW commit sink\n");
    xt9kdb_set_aw_commit(mock_commit);

    /* ProcessKeyBySymbol commits the symbol verbatim (the offline/DIFF definition; in CUTOVER
     * this entry point is deliberately NOT redirected and stays with the blob). */
    g_sink_n = 0;
    CHECK(xt9kdb_ProcessKeyBySymbol(g_ctx, (ET9U16)'k', 0, 0, g_ctx, 0) == ET9STATUS_NONE,
          "ProcessKeyBySymbol failed");
    CHECK(g_sink_n == 1 && g_sink[0] == (ET9SYMB)'k', "PKBS committed %d symbol(s)", g_sink_n);

    /* A NULL context is rejected before anything is committed. */
    g_sink_n = 0;
    CHECK(xt9kdb_ProcessKeyBySymbol(0, (ET9U16)'k', 0, 0, 0, 0) == ET9STATUS_INVALID_MEMORY,
          "PKBS(NULL ctx) not rejected");
    CHECK(g_sink_n == 0, "PKBS(NULL ctx) still committed");

    /* ProcessTap resolves a RAW SENSOR point to a key. A LETTER commits the ambiguous
     * smart-touch set; a FUNCTION key commits its code exactly. */
    xt9kdb_set_aw_commit_ambig(mock_commit_ambig);
    g_set_n = 0; g_sink_n = 0;
    CHECK(xt9kdb_ProcessTap(g_ctx, 809, 191) == ET9STATUS_NONE, "ProcessTap(letter) failed");
    CHECK(g_set_n > 0 && g_set[0] == (ET9SYMB)'k',
          "tap on 'k' resolved to '%c' (set of %d)", g_set_n ? (char)g_set[0] : '?', g_set_n);
    CHECK(g_sink_n == 0, "a letter tap must not use the EXACT sink");

    g_set_n = 0; g_sink_n = 0;
    CHECK(xt9kdb_ProcessTap(g_ctx, 1025, 191) == ET9STATUS_NONE, "ProcessTap(function) failed");
    CHECK(g_sink_n == 1 && g_sink[0] == 8, "tap on backspace committed %d symbol(s) [0]=%u",
          g_sink_n, g_sink_n ? (unsigned)g_sink[0] : 0u);
    CHECK(g_set_n == 0, "a function tap must not use the AMBIGUOUS sink");

    xt9kdb_set_aw_commit(0);
    xt9kdb_set_aw_commit_ambig(0);
}

/* ---- 6. ordering: entry points must tolerate being called out of order --------------------- */
static void test_ordering(void) {
    printf("[6] out-of-order / defensive ordering\n");

    /* TouchMove with no TouchStart still accumulates (there is no "session open" flag). This is
     * the reason the Java side filters stray moves itself — pinned so the asymmetry is visible. */
    xt9kdb_TouchCancel(g_ctx, 1);
    tmove(g_ctx, 300.0f, 100.0f, 10);
    CHECK(path_n() == 1, "TouchMove without TouchStart was dropped (n=%u)", path_n());

    /* TouchEnd on an empty session is a no-op that still reports success. */
    xt9kdb_TouchCancel(g_ctx, 1);
    CHECK(tend(g_ctx, 0.0f, 0.0f, 0) == ET9STATUS_NONE, "TouchEnd on empty session failed");

    /* TouchEnd ALWAYS reports ET9STATUS_NONE: the JNI shim turns a non-zero status into a false
     * return in Java, and the blob's dispatcher hardcodes 0, so ours must never propagate the
     * recognizer's status. Drive it with a NULL context, which makes the recognizer fail.
     *
     * L10: and because the status is swallowed, the failure must land in a counter Java can read
     * — otherwise "the recognizer could not run" is a logcat line and nothing else, which is how
     * a KEY2 build shipped with swipe dead for the life of every process. */
    {
        const ET9U32 u0 = xt9kdb_recognizer_unavailable();
        tstart(g_ctx, 53.0f, 63.0f, 0);
        tmove(g_ctx, 161.0f, 63.0f, 10);
        CHECK(xt9kdb_TouchEnd(0, 1, 0, 0, 0, 0) == ET9STATUS_NONE,
              "TouchEnd must report success even when the recognizer fails");
        CHECK(xt9kdb_recognizer_unavailable() == u0 + 1,
              "an unrunnable recognizer was not counted (%u -> %u)",
              u0, xt9kdb_recognizer_unavailable());

        /* ...and an ordinary end does NOT bump it. The counter has to stay a signal: a build
         * where it is non-zero is a build where swipe is dead. */
        const ET9U32 u1 = xt9kdb_recognizer_unavailable();
        xt9kdb_TouchCancel(g_ctx, 1);
        tstart(g_ctx, 53.0f, 63.0f, 0);
        tmove(g_ctx, 161.0f, 63.0f, 10);
        tend(g_ctx, 269.0f, 63.0f, 20);
        CHECK(xt9kdb_recognizer_unavailable() == u1,
              "a normal TouchEnd bumped the unavailable counter (%u -> %u)",
              u1, xt9kdb_recognizer_unavailable());
        /* Nothing offline ever runs a recognizer, so the failure counter must stay at zero —
         * a non-zero here would mean the offline build had acquired one by accident. */
        CHECK(xt9kdb_recognizer_failures() == 0,
              "offline builds must never report a recognizer failure (%u)",
              xt9kdb_recognizer_failures());
    }

    /* NULL arrays with a non-zero count must not crash; they deposit (0,0) samples. */
    xt9kdb_TouchCancel(g_ctx, 1);
    CHECK(xt9kdb_TouchStart(g_ctx, 1, 0, 0, 0, 1, 0) == ET9STATUS_NONE,
          "TouchStart(NULL arrays) failed");
    CHECK(path_n() == 1, "TouchStart(NULL arrays) stored %u samples", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

/* ---- 7. debug-property sense (L11) ---------------------------------------------------------- */
static void test_prop_flag_sense(void) {
    printf("[7] debug-property sense: setprop 0 is OFF, anything else is ON\n");
    CHECK(XT9_PROP_OFF_CHAR == '0', "OFF marker must be '0' (was '\\0' for log.tag.XT9Trace)");
    CHECK(xt9kdb_prop_flag_parse("0", XT9_PROP_OFF_CHAR) == 0, "\"0\" must read OFF");
    CHECK(xt9kdb_prop_flag_parse("1", XT9_PROP_OFF_CHAR) == 1, "\"1\" must read ON");
    CHECK(xt9kdb_prop_flag_parse("true", XT9_PROP_OFF_CHAR) == 1, "\"true\" must read ON");
    CHECK(xt9kdb_prop_flag_parse("", XT9_PROP_OFF_CHAR) == 1, "set-but-empty must read ON");
    CHECK(xt9kdb_prop_flag_parse("0", '\0') == 1,
          "with the old '\\0' marker \"0\" read ON -- the L11 inversion this section pins against");
}

/* ---- 8. key-table ceiling (L12) ------------------------------------------------------------ */
/* Build a layout with `n` regional keys in a 10-column grid. */
static int build_grid_xml(char* out, size_t cap, int n) {
    int len = snprintf(out, cap,
        "<keyboard primaryId=\"9\" secondaryId=\"6\" defaultLayoutWidth=\"1080\" defaultLayoutHeight=\"900\">");
    for (int i = 0; i < n && (size_t)len < cap; i++) {
        len += snprintf(out + len, cap - (size_t)len,
            "<key keyType=\"regional\" keyCodes=\"0x%04x\" keyWidth=\"108dp\" keyHeight=\"100dp\" keyTop=\"%ddp\" keyLeft=\"%ddp\" />",
            0x0100 + i, (i / 10) * 100, (i % 10) * 108);
    }
    len += snprintf(out + len, cap - (size_t)len, "</keyboard>");
    return len;
}

static void test_key_table_ceiling(void) {
    printf("[8] key-table ceiling: 80 keys load (the blob's limit), 81 fail with 0x38 (L12)\n");
    unsigned char* ctx2 = calloc(1, CTX_SZ + 0x8000);
    char* xml = malloc(32768);
    if (!ctx2 || !xml) { CHECK(0, "OOM"); free(ctx2); free(xml); return; }
    *(void**)(ctx2 + ET9_OFF_ARENA) = ctx2 + CTX_SZ;
    *(ET9U16*)(ctx2 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;
    ET9U16 count = 0;

    int len = build_grid_xml(xml, 32768, 81);
    ET9STATUS st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)xml, (ET9U32)len);
    CHECK(st == (ET9STATUS)0x38, "81 keys must fail the load with the blob's status 0x38 (got 0x%x)", (unsigned)st);
    CHECK(st != ET9STATUS_NONE, "81 keys must not load as a silently truncated layout");

    len = build_grid_xml(xml, 32768, 80);
    st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)xml, (ET9U32)len);
    CHECK(st == ET9STATUS_NONE, "80 keys must load (got 0x%x)", (unsigned)st);
    CHECK(xt9kdb_GetKeyPositions(ctx2, 0, 0, &count) == ET9STATUS_NONE && count == 80,
          "80-key layout must report 80 keys (got %u)", (unsigned)count);

    /* a failed load leaves the slot empty, not half-filled */
    len = build_grid_xml(xml, 32768, 81);
    (void)xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)xml, (ET9U32)len);
    st = xt9kdb_GetKeyPositions(ctx2, 0, 0, &count);
    CHECK(st != ET9STATUS_NONE || count == 0, "a failed load must not leave keys behind (got %u)", (unsigned)count);

    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);
    free(ctx2); free(xml);
}

int main(void) {
    g_ctx = calloc(1, CTX_SZ);
    if (!g_ctx) { printf("OOM\n"); return 2; }
    printf("== owned touch/gesture ABI contracts ==\n");
    test_session_lifecycle();
    test_multi_contact();
    test_point_admission();
    test_keyboard_size();
    test_ctx_scale();
    test_get_key_positions();
    test_get_key_positions_per_ctx();
    test_key_position_by_ctx();
    test_tap_path();
    test_ordering();
    test_prop_flag_sense();
    test_key_table_ceiling();
    printf("---- %d passed, %d failed ----\n", g_pass, g_fail);
    free(g_ctx);
    return g_fail ? 1 : 0;
}
