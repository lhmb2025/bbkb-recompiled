/* touch_abi_selftest.c: pins the owned ET9KDB module's touch/gesture ABI contracts, observed
 * through its own read-back API (xt9kdb_stored_path, GetKeyPositions, the AW commit sink). Runs
 * without the blob; built by test/run_tests.sh. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

/* Built in both symbol modes (again under -DXT9KDB_CUTOVER for 3b). In cutover the entry points
 * export as ET9KDB_*, so bind the names used here; non-exported helpers keep their names. */
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

/* Big enough for GetKeyPositions' ctx+0xfc30..36 reads; zeroed = offset/scale 0 (PKB config). */
#define CTX_SZ 0x10000
static unsigned char* g_ctx;

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

/* With an explicit contact id, for concurrent pointers. */
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
/* x of the idx-th stored sample, or -1: shows whose points are in the buffer. */
static float path_x(ET9U32 idx) {
    ET9U32 n = 0; const ET9KdbSample* p = xt9kdb_stored_path(&n);
    return idx < n ? p[idx].x : -1.0f;
}

static void test_session_lifecycle(void) {
    printf("[1] touch session lifecycle\n");

    tstart(g_ctx, 53.0f, 63.0f, 100);
    CHECK(path_n() == 1, "TouchStart did not store the down point (n=%u)", path_n());

    tmove(g_ctx, 161.0f, 63.0f, 110);
    tmove(g_ctx, 269.0f, 63.0f, 120);
    CHECK(path_n() == 3, "TouchMove did not append (n=%u)", path_n());

    /* TouchCancel clears the session: the gesture arbiter cancels contacts it consumes. */
    ET9STATUS st = xt9kdb_TouchCancel(g_ctx, 1);
    CHECK(st == ET9STATUS_NONE, "TouchCancel status=%d", (int)st);
    CHECK(path_n() == 0, "TouchCancel did not clear the session (n=%u)", path_n());

    /* TouchCancel ignores its touchId: any id clears the single global buffer. */
    tstart(g_ctx, 53.0f, 63.0f, 200);
    tmove(g_ctx, 161.0f, 63.0f, 210);
    xt9kdb_TouchCancel(g_ctx, 999);              /* a different pointer id */
    CHECK(path_n() == 0, "TouchCancel(other id) left the session alive (n=%u)", path_n());

    /* TouchStart resets the accumulated path. */
    tstart(g_ctx, 53.0f, 63.0f, 300);
    tmove(g_ctx, 161.0f, 63.0f, 310);
    tmove(g_ctx, 269.0f, 63.0f, 320);
    tstart(g_ctx, 917.0f, 63.0f, 330);
    CHECK(path_n() == 1, "TouchStart did not reset the previous path (n=%u)", path_n());

    /* TouchEnd appends and keeps the path readable after recognition (ByStoredTouch, TRACEPT dump,
     * ranker). Only TouchStart / TouchCancel / Set|ClearTraceInput clear it. */
    tmove(g_ctx, 809.0f, 63.0f, 340);
    tend(g_ctx, 701.0f, 63.0f, 350);
    CHECK(path_n() == 3, "TouchEnd should append and keep the path (n=%u)", path_n());

    /* SetTraceInput and ClearTraceInput both clear, whatever the flag. */
    xt9kdb_SetTraceInput(g_ctx, 1);
    CHECK(path_n() == 0, "SetTraceInput(on) did not clear (n=%u)", path_n());
    tstart(g_ctx, 53.0f, 63.0f, 400);
    tmove(g_ctx, 161.0f, 63.0f, 410);
    xt9kdb_ClearTraceInput(g_ctx);
    CHECK(path_n() == 0, "ClearTraceInput did not clear (n=%u)", path_n());

    /* TouchEndAll and TouchTimeOut keep the path, like TouchEnd. */
    tstart(g_ctx, 53.0f, 63.0f, 500);
    tmove(g_ctx, 161.0f, 63.0f, 510);
    xt9kdb_TouchEndAll(g_ctx);
    xt9kdb_TouchTimeOut(g_ctx);
    CHECK(path_n() == 2, "TouchEndAll/TouchTimeOut unexpectedly touched the path (n=%u)", path_n());

    /* ...but they release the session owner, or the foreign-contact filter stays armed against
     * every other pointer: afterwards a different contact may write. */
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

/* The contact that most recently called TouchStart owns the path buffer; nobody else can write
 * or finish it. Owner points are x<=300, the intruder's x>=900, so the buffer shows who wrote. */
static void test_multi_contact(void) {
    printf("[1b] multi-contact isolation (touchId)\n");
    xt9kdb_TouchCancel(g_ctx, 7);                    /* quiesce: no live session */
    const ET9U32 drops0 = xt9kdb_foreign_contact_drops();

    tstart_id(g_ctx, 7, 100.0f, 63.0f, 100);
    tmove_id (g_ctx, 7, 200.0f, 63.0f, 110);
    tmove_id (g_ctx, 8, 900.0f, 63.0f, 115);         /* intruder, dropped */
    tmove_id (g_ctx, 7, 300.0f, 63.0f, 120);
    CHECK(path_n() == 3, "a foreign contact's move entered the path (n=%u)", path_n());
    CHECK(path_x(0) == 100.0f && path_x(1) == 200.0f && path_x(2) == 300.0f,
          "the path is not the owner's alone (%.0f,%.0f,%.0f)",
          (double)path_x(0), (double)path_x(1), (double)path_x(2));

    /* the intruder's lift neither appends nor runs the recognizer */
    CHECK(tend_id(g_ctx, 8, 900.0f, 63.0f, 125) == ET9STATUS_NONE,
          "a foreign TouchEnd must still report success");
    CHECK(path_n() == 3, "a foreign contact's END entered the path (n=%u)", path_n());

    CHECK(tend_id(g_ctx, 7, 400.0f, 63.0f, 130) == ET9STATUS_NONE, "owner TouchEnd failed");
    CHECK(path_n() == 4 && path_x(3) == 400.0f,
          "the owner's own end was rejected (n=%u last=%.0f)", path_n(), (double)path_x(3));

    /* every rejection is counted: one move + one end */
    CHECK(xt9kdb_foreign_contact_drops() == drops0 + 2,
          "foreign-contact drops counted %u, expected %u",
          xt9kdb_foreign_contact_drops() - drops0, 2u);

    /* Newest contact wins on TouchStart: the CKB arbiter abandons an in-flight trace when a second
     * finger lands (chained flicks). The abandonment is counted. */
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

    /* TouchCancel clears whatever is live, whatever id: the arbiter's escape hatch and the only
     * unstick path. */
    xt9kdb_TouchCancel(g_ctx, 999);
    CHECK(path_n() == 0, "TouchCancel(other id) left the session alive (n=%u)", path_n());
    /* with no session live, any contact may write */
    tmove_id(g_ctx, 42, 500.0f, 63.0f, 300);
    CHECK(path_n() == 1, "filtering outlived the session (n=%u)", path_n());

    /* Set/ClearTraceInput release the owner too. */
    tstart_id(g_ctx, 7, 100.0f, 63.0f, 400);
    xt9kdb_ClearTraceInput(g_ctx);
    tmove_id(g_ctx, 8, 900.0f, 63.0f, 410);
    CHECK(path_n() == 1, "ClearTraceInput did not release the session owner (n=%u)", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

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
    /* merge needs both axes under 1px */
    tmove(g_ctx, 101.6f, 140.0f, 30);            /* dy = 39.5 -> admitted */
    CHECK(path_n() == 3, "large-dy move was dropped (n=%u)", path_n());

    /* hard cap 2499 samples (ET9_TRACE_CAP, as in the blob) */
    tstart(g_ctx, 0.0f, 0.0f, 0);
    for (int i = 1; i < 4000; i++) tmove(g_ctx, (float)(i * 2), (float)(i % 300), (ET9U32)i);
    CHECK(path_n() == 2499, "trace cap is not 2499 (n=%u)", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

static void test_keyboard_size(void) {
    printf("[3] SetKeyboardSize scale contract\n");
    const ET9KdbLoaded* m = g_kdb_model;
    const float aw = (float)m->authoredWidth, ah = (float)m->authoredHeight;

    /* (0,0) = no scaling, the PKB configuration (NuanceSDK.setKeyboardSize isPkb branch). */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 0, 0) == ET9STATUS_NONE, "SetKeyboardSize(0,0) failed");
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(540.0f, 192.0f, &ax, &ay);
        CHECK(ax == 540.0f && ay == 192.0f, "no-scale map is not 1:1 (%.1f,%.1f)",
              (double)ax, (double)ay);
    }

    /* one zero dimension is BAD_PARAM */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 1080, 0) != ET9STATUS_NONE,
          "SetKeyboardSize(1080,0) should be rejected");
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 0, 600) != ET9STATUS_NONE,
          "SetKeyboardSize(0,600) should be rejected");

    /* VKB stretch: a view twice the authored size halves every coordinate. */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, (ET9U16)(aw * 2), (ET9U16)(ah * 2)) == ET9STATUS_NONE,
          "SetKeyboardSize(2x) failed");
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(aw, ah, &ax, &ay);
        CHECK(ax == aw / 2.0f && ay == ah / 2.0f, "2x stretch did not halve (%.1f,%.1f)",
              (double)ax, (double)ay);
    }
    /* out-of-range input is clamped, never wrapped */
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(-500.0f, 99999.0f, &ax, &ay);
        CHECK(ax == 0.0f && ay == ah - 1.0f, "clamp failed (%.1f,%.1f)", (double)ax, (double)ay);
    }

    /* a rejected call leaves the live scale alone */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 1234, 0) != ET9STATUS_NONE, "expected rejection");
    {
        float ax = -1, ay = -1;
        xt9kdb_sensor_to_authored(aw, ah, &ax, &ay);
        CHECK(ax == aw / 2.0f && ay == ah / 2.0f,
              "a rejected SetKeyboardSize clobbered the live scale (%.1f,%.1f)",
              (double)ax, (double)ay);
    }
    xt9kdb_SetKeyboardSize(g_ctx, 0, 0);          /* back to the PKB config */
}

/* CUTOVER only: SetKeyboardSize stores the scale at ctx+0xfc34/36, where GetKeyPositions reads it,
 * so each engine context keeps its own. Under DIFF the blob owns that field. */
static void test_ctx_scale(void) {
#if defined(XT9KDB_CUTOVER)
    printf("[3b] SetKeyboardSize stores the scale in the CONTEXT\n");
    const ET9KdbLoaded* m = g_kdb_model;
    const ET9U16 aw = m->authoredWidth, ah = m->authoredHeight;

    CHECK(xt9kdb_SetKeyboardSize(g_ctx, (ET9U16)(aw * 2), (ET9U16)(ah * 2)) == ET9STATUS_NONE,
          "SetKeyboardSize(2x) failed");
    CHECK(*(ET9U16*)(g_ctx + 0xfc34) == (ET9U16)(aw * 2) &&
          *(ET9U16*)(g_ctx + 0xfc36) == (ET9U16)(ah * 2),
          "the scale did not reach ctx+0xfc34/36 (%u,%u)",
          *(ET9U16*)(g_ctx + 0xfc34), *(ET9U16*)(g_ctx + 0xfc36));

    /* getKeys projects: 'q' [0,107] in a 1080 grid -> [0,214] at 2x (CkbKeyGridCapture divides by
     * the view size). */
    {
        unsigned char* out = calloc(0x30, (size_t)m->keyCount + 8);
        ET9U32 count = 0;
        CHECK(xt9kdb_GetKeyPositions(g_ctx, out, m->keyCount, &count) == ET9STATUS_NONE,
              "stretched getKeys failed");
        CHECK(*(const ET9U16*)(out + 0x2c) == 214,
              "getKeys did not project the stretch: right = %u, expected 214",
              *(const ET9U16*)(out + 0x2c));
        free(out);
    }

    /* a rejected call leaves the context's scale alone */
    CHECK(xt9kdb_SetKeyboardSize(g_ctx, 1234, 0) != ET9STATUS_NONE, "expected rejection");
    CHECK(*(ET9U16*)(g_ctx + 0xfc34) == (ET9U16)(aw * 2),
          "a rejected SetKeyboardSize clobbered the context scale (%u)",
          *(ET9U16*)(g_ctx + 0xfc34));

    /* a second context (the spell checker's) syncing must not disturb the first's */
    {
        unsigned char* ctx2 = calloc(1, CTX_SZ);
        CHECK(ctx2 != 0, "OOM allocating the second context");
        if (ctx2) {
            CHECK(xt9kdb_SetKeyboardSize(ctx2, 0, 0) == ET9STATUS_NONE, "ctx2 PKB sync failed");
            CHECK(*(ET9U16*)(g_ctx + 0xfc34) == (ET9U16)(aw * 2),
                  "a second context's sync clobbered the first's scale (%u)",
                  *(ET9U16*)(g_ctx + 0xfc34));

            /* TouchStart re-publishes the gesturing context's scale to the ctx-less mapper */
            tstart_id(g_ctx, 3, 0.0f, 0.0f, 0);
            {
                float ax = -1, ay = -1;
                xt9kdb_sensor_to_authored((float)aw, (float)ah, &ax, &ay);
                CHECK(ax == aw / 2.0f && ay == ah / 2.0f,
                      "TouchStart did not restore the gesturing context's stretch (%.1f,%.1f)",
                      (double)ax, (double)ay);
            }
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
    xt9kdb_SetKeyboardSize(g_ctx, 0, 0);
    xt9kdb_TouchCancel(g_ctx, 1);
#endif
}

static void test_get_key_positions(void) {
    printf("[4] GetKeyPositions contract\n");
    const ET9KdbLoaded* m = g_kdb_model;
    const ET9U16 n = m->keyCount;
    unsigned char* out = calloc(0x30, (size_t)n + 8);
    ET9U32 count = 0;

    /* out == NULL returns the key count only */
    CHECK(xt9kdb_GetKeyPositions(g_ctx, 0, 0, &count) == ET9STATUS_NONE, "count probe failed");
    CHECK(count == n, "count probe returned %u, expected %u", count, n);

    /* capacity < keyCount is rejected with 0x1a */
    count = 0;
    CHECK(xt9kdb_GetKeyPositions(g_ctx, out, (ET9U16)(n - 1), &count) == (ET9STATUS)0x1a,
          "short buffer was not rejected with 0x1a");

    /* stride 0x30; with offset/scale zero the records are the authored rects */
    count = 0;
    CHECK(xt9kdb_GetKeyPositions(g_ctx, out, n, &count) == ET9STATUS_NONE, "full read failed");
    CHECK(count == n, "full read wrote count=%u", count);

    /* count is a 32-bit out-param: JNI getKeys (0x2103c) reads it with a 32-bit ldr and never
     * zeroes it, so poison the slot like a dirty stack and expect the whole word back. */
    {
        ET9U32 slot = 0xDEAD0000u;
        CHECK(xt9kdb_GetKeyPositions(g_ctx, out, n, &slot) == ET9STATUS_NONE, "poisoned-slot read failed");
        CHECK(slot == n, "count must be written as a full 32-bit word: got 0x%08x, expected %u",
              (unsigned)slot, (unsigned)n);
        slot = 0xDEAD0000u;
        CHECK(xt9kdb_GetKeyPositions(g_ctx, out, (ET9U16)(n - 1), &slot) == (ET9STATUS)0x1a && slot == 0,
              "a too-small buffer must leave count=0 like the blob, got 0x%08x", (unsigned)slot);
    }
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

    /* GetKeyPositions projects with the context's offset/scale (ctx+0xfc30..36), not the sensor
     * globals: a 2x context scale doubles the records while the sensor map stays 1:1. */
    *(ET9U16*)(g_ctx + 0xfc34) = (ET9U16)(m->authoredWidth * 2);
    *(ET9U16*)(g_ctx + 0xfc36) = (ET9U16)(m->authoredHeight * 2);
    count = 0;
    CHECK(xt9kdb_GetKeyPositions(g_ctx, out, n, &count) == ET9STATUS_NONE, "scaled read failed");
    {
        /* 'q' right 107 in a 1080 grid -> round(2160*107/1080) = 214 */
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

    /* GetKeyboardSize reports the authored dimensions, not the SetKeyboardSize input */
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

/* GetKeyPositions reads the caller's model, not the legacy global (each engine context loads its
 * own KDB). The contexts here hold different layouts. */
static const char TWO_KEY_XML[] =
    "<keyboard primaryId=\"9\" secondaryId=\"6\""
    " defaultLayoutWidth=\"1080\" defaultLayoutHeight=\"450\">"
    "<key keyType=\"regional\" keyLabel=\"Q\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"0dp\" />"
    "<key keyType=\"regional\" keyLabel=\"W\" keyWidth=\"540dp\" keyHeight=\"450dp\" keyTop=\"0dp\" keyLeft=\"540dp\" />"
    "</keyboard>";

static void test_get_key_positions_per_ctx(void) {
    printf("[4b] GetKeyPositions resolves the model per context\n");
    /* room for the field map and an arena for the NKL (cutover) */
    unsigned char* ctx2 = calloc(1, CTX_SZ + 0x4000);
    if (!ctx2) { CHECK(0, "OOM allocating the second context"); return; }
    *(void**)(ctx2 + ET9_OFF_ARENA) = ctx2 + CTX_SZ;
    *(ET9U16*)(ctx2 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;

    CHECK(xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)TWO_KEY_XML, (ET9U32)(sizeof TWO_KEY_XML - 1))
              == ET9STATUS_NONE, "loading the second context's KDB failed");
    /* the loader publishes to the global; point it back at the 30-key fixture so they disagree */
    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);
    const ET9U16 globalKeys = g_kdb_model->keyCount;          /* 30, the bring-up fixture */
    CHECK(globalKeys != 2, "test setup: the two layouts must differ (%u keys)", globalKeys);

    {
        ET9U32 count = 0;
        CHECK(xt9kdb_GetKeyPositions(ctx2, 0, 0, &count) == ET9STATUS_NONE, "ctx2 count probe failed");
        CHECK(count == 2, "getKeys on ctx2 returned %u keys — the other context's model", count);
    }
    /* ctx2's geometry: 'q' spans the left half */
    {
        unsigned char* out = calloc(0x30, 8);
        ET9U32 count = 0;
        CHECK(xt9kdb_GetKeyPositions(ctx2, out, 8, &count) == ET9STATUS_NONE, "ctx2 read failed");
        CHECK(count == 2 && *(const ET9U16*)(out + 0x0a) == (ET9U16)'q' &&
              *(const ET9U16*)(out + 0x2c) == 539,
              "ctx2 record 0 is code=%u right=%u, expected 'q'/539",
              count ? *(const ET9U16*)(out + 0x0a) : 0u,
              count ? *(const ET9U16*)(out + 0x2c) : 0u);
        free(out);
    }
    /* a read-only query must not re-publish to the global like TouchStart does */
    CHECK(g_kdb_model->keyCount == globalKeys,
          "getKeys redirected the legacy global model (%u keys, was %u)",
          g_kdb_model->keyCount, globalKeys);
    /* a model-less context falls back to the global (offline drivers depend on it) */
    {
        ET9U32 count = 0;
        CHECK(xt9kdb_GetKeyPositions(g_ctx, 0, 0, &count) == ET9STATUS_NONE, "fallback probe failed");
        CHECK(count == globalKeys, "a model-less context did not fall back (%u)", count);
    }
    free(ctx2);
}

/* GetKeyPositionByTap / ByStoredTouch read the caller's model (and, in cutover, scale), not the
 * globals the gesture path maintains. ctx2 = two-key layout ('q' left, 'w' right); the global
 * fixture puts the same point on 'i'. */
static void test_key_position_by_ctx(void) {
    printf("[4c] GetKeyPositionByTap / ByStoredTouch resolve per context\n");
    unsigned char* ctx2 = calloc(1, CTX_SZ + 0x4000);
    if (!ctx2) { CHECK(0, "OOM allocating the second context"); return; }
    *(void**)(ctx2 + ET9_OFF_ARENA) = ctx2 + CTX_SZ;
    *(ET9U16*)(ctx2 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;
    CHECK(xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)TWO_KEY_XML, (ET9U32)(sizeof TWO_KEY_XML - 1))
              == ET9STATUS_NONE, "loading the second context's KDB failed");
    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);     /* the global disagrees with ctx2 */

    ET9KdbKey k;
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByTap(ctx2, 800, 60, &k) == ET9STATUS_NONE, "ByTap(ctx2) failed");
    CHECK(k.keyCode == (ET9U16)'w', "ByTap on ctx2 resolved '%c' — the other context's model",
          (char)k.keyCode);
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByTap(g_ctx, 800, 60, &k) == ET9STATUS_NONE, "ByTap(g_ctx) failed");
    CHECK(k.keyCode == (ET9U16)'i', "a model-less context did not fall back to the global ('%c')",
          (char)k.keyCode);

    /* The stored path is process-global; only resolution is per context. g_ctx has no model, so
     * depositing on it publishes none. */
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
    /* out-of-range index = no key */
    memset(&k, 0, sizeof k);
    CHECK(xt9kdb_GetKeyPositionByStoredTouch(ctx2, 9999, &k) == ET9STATUS_NONE,
          "ByStoredTouch(out of range) should be a benign no-op");
    CHECK(k.keyCode == 0, "ByStoredTouch(out of range) wrote a key");
    xt9kdb_TouchCancel(g_ctx, 1);

#if defined(XT9KDB_CUTOVER)
    /* scale from the context too: ctx2 at 2x maps 800 -> 400 ('q'); g_ctx still answers 'i' */
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

    /* ProcessKeyBySymbol commits verbatim (offline/DIFF; in cutover it stays with the blob) */
    g_sink_n = 0;
    CHECK(xt9kdb_ProcessKeyBySymbol(g_ctx, (ET9U16)'k', 0, 0, g_ctx, 0) == ET9STATUS_NONE,
          "ProcessKeyBySymbol failed");
    CHECK(g_sink_n == 1 && g_sink[0] == (ET9SYMB)'k', "PKBS committed %d symbol(s)", g_sink_n);

    g_sink_n = 0;
    CHECK(xt9kdb_ProcessKeyBySymbol(0, (ET9U16)'k', 0, 0, 0, 0) == ET9STATUS_INVALID_MEMORY,
          "PKBS(NULL ctx) not rejected");
    CHECK(g_sink_n == 0, "PKBS(NULL ctx) still committed");

    /* ProcessTap: a letter commits the ambiguous set, a function key its exact code */
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

static void test_ordering(void) {
    printf("[6] out-of-order / defensive ordering\n");

    /* TouchMove with no TouchStart still accumulates (no session-open flag); Java filters strays. */
    xt9kdb_TouchCancel(g_ctx, 1);
    tmove(g_ctx, 300.0f, 100.0f, 10);
    CHECK(path_n() == 1, "TouchMove without TouchStart was dropped (n=%u)", path_n());

    xt9kdb_TouchCancel(g_ctx, 1);
    CHECK(tend(g_ctx, 0.0f, 0.0f, 0) == ET9STATUS_NONE, "TouchEnd on empty session failed");

    /* TouchEnd always reports NONE (the JNI shim maps non-zero to false; the blob's dispatcher
     * hardcodes 0), so a failed recognizer (NULL ctx here) must bump a counter Java can read. */
    {
        const ET9U32 u0 = xt9kdb_recognizer_unavailable();
        tstart(g_ctx, 53.0f, 63.0f, 0);
        tmove(g_ctx, 161.0f, 63.0f, 10);
        CHECK(xt9kdb_TouchEnd(0, 1, 0, 0, 0, 0) == ET9STATUS_NONE,
              "TouchEnd must report success even when the recognizer fails");
        CHECK(xt9kdb_recognizer_unavailable() == u0 + 1,
              "an unrunnable recognizer was not counted (%u -> %u)",
              u0, xt9kdb_recognizer_unavailable());

        /* an ordinary end does not bump it: non-zero means swipe is dead */
        const ET9U32 u1 = xt9kdb_recognizer_unavailable();
        xt9kdb_TouchCancel(g_ctx, 1);
        tstart(g_ctx, 53.0f, 63.0f, 0);
        tmove(g_ctx, 161.0f, 63.0f, 10);
        tend(g_ctx, 269.0f, 63.0f, 20);
        CHECK(xt9kdb_recognizer_unavailable() == u1,
              "a normal TouchEnd bumped the unavailable counter (%u -> %u)",
              u1, xt9kdb_recognizer_unavailable());
        /* nothing offline runs a recognizer, so failures stay zero */
        CHECK(xt9kdb_recognizer_failures() == 0,
              "offline builds must never report a recognizer failure (%u)",
              xt9kdb_recognizer_failures());
    }

    /* NULL arrays with a count deposit (0,0) samples, no crash */
    xt9kdb_TouchCancel(g_ctx, 1);
    CHECK(xt9kdb_TouchStart(g_ctx, 1, 0, 0, 0, 1, 0) == ET9STATUS_NONE,
          "TouchStart(NULL arrays) failed");
    CHECK(path_n() == 1, "TouchStart(NULL arrays) stored %u samples", path_n());
    xt9kdb_TouchCancel(g_ctx, 1);
}

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

/* Builds a layout with n regional keys in a 10-column grid. */
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
    ET9U32 count = 0;

    int len = build_grid_xml(xml, 32768, 81);
    ET9STATUS st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)xml, (ET9U32)len);
    CHECK(st == (ET9STATUS)0x38, "81 keys must fail the load with the blob's status 0x38 (got 0x%x)", (unsigned)st);
    CHECK(st != ET9STATUS_NONE, "81 keys must not load as a silently truncated layout");

    len = build_grid_xml(xml, 32768, 80);
    st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)xml, (ET9U32)len);
    CHECK(st == ET9STATUS_NONE, "80 keys must load (got 0x%x)", (unsigned)st);
    CHECK(xt9kdb_GetKeyPositions(ctx2, 0, 0, &count) == ET9STATUS_NONE && count == 80,
          "80-key layout must report 80 keys (got %u)", (unsigned)count);

    /* A failed load leaves the previous model whole: SetKdbNum's selector rollback assumes the
     * previous layout is still loaded. */
    len = build_grid_xml(xml, 32768, 81);
    (void)xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)xml, (ET9U32)len);
    count = 0;
    st = xt9kdb_GetKeyPositions(ctx2, 0, 0, &count);
    CHECK(st == ET9STATUS_NONE && count == 80,
          "a failed load must leave the previous 80-key model in place (st=0x%x count=%u)",
          (unsigned)st, (unsigned)count);

    /* and on a context that never loaded, a failed load leaves no model at all */
    unsigned char* ctx3 = calloc(1, CTX_SZ + 0x8000);
    if (ctx3) {
        *(void**)(ctx3 + ET9_OFF_ARENA) = ctx3 + CTX_SZ;
        *(ET9U16*)(ctx3 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;
        len = build_grid_xml(xml, 32768, 81);
        (void)xt9kdb_Load_XmlKDB(ctx3, (const ET9U8*)xml, (ET9U32)len);
        CHECK(xt9kdb_model_for_ctx(ctx3) == 0, "a first load that fails must publish no model");
        free(ctx3);
    }

    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);
    free(ctx2); free(xml);
}

/* A KDB cut before its closing </keyboard> fails and keeps the previous model; every successful
 * (re)load changes the generation, though a context's model keeps one address. */
static void test_truncation_and_generation(void) {
    printf("[9] truncated KDB fails and keeps the previous model; reloads bump the generation\n");
    FILE* fp = fopen("test/qwerty_pkb_even.xml", "rb");
    if (!fp) { CHECK(0, "cannot open test/qwerty_pkb_even.xml (run from cpp/)"); return; }
    static char doc[65536];
    size_t n = fread(doc, 1, sizeof doc, fp);
    fclose(fp);
    unsigned char* ctx2 = calloc(1, CTX_SZ + 0x8000);
    if (!ctx2 || n == 0) { CHECK(0, "OOM/empty fixture"); free(ctx2); return; }
    *(void**)(ctx2 + ET9_OFF_ARENA) = ctx2 + CTX_SZ;
    *(ET9U16*)(ctx2 + ET9_OFF_MAGIC) = ET9KDB_MAGIC;

    ET9STATUS st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)doc, (ET9U32)n);
    CHECK(st == ET9STATUS_NONE, "the full fixture must load (got 0x%x)", (unsigned)st);
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx(ctx2);
    CHECK(m != 0, "the full fixture must publish a model");
    if (!m) { free(ctx2); return; }
    const ET9U16 keys = m->keyCount;
    const ET9U32 gen0 = m->generation;
    ET9KdbKey first = m->keys[0], last = m->keys[keys - 1];

    /* every cut strictly before the end of </keyboard> */
    const char* close = strstr(doc, "</keyboard");
    const char* gt = close ? strchr(close, '>') : 0;
    CHECK(gt != 0, "fixture must close its root element");
    size_t cutmax = gt ? (size_t)(gt - doc) : 0;   /* cutting here drops the final '>' */
    int accepted = 0, survived = 1;
    for (size_t cut = 1; cut <= cutmax; cut++) {
        if (xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)doc, (ET9U32)cut) == ET9STATUS_NONE) accepted++;
        const ET9KdbLoaded* mm = xt9kdb_model_for_ctx(ctx2);
        if (!mm || mm != m || mm->keyCount != keys || mm->generation != gen0
            || memcmp(&mm->keys[0], &first, sizeof first) || memcmp(&mm->keys[keys - 1], &last, sizeof last))
            survived = 0;
    }
    CHECK(accepted == 0, "%d of %zu truncated documents loaded as success", accepted, cutmax);
    CHECK(survived, "a failed (truncated) load disturbed the previous model");

    /* trailing bytes after the root closes are fine */
    st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)doc, (ET9U32)(cutmax + 1));
    CHECK(st == ET9STATUS_NONE, "a document cut right after </keyboard> must load (got 0x%x)", (unsigned)st);

    /* same address, new contents: the generation moves each time */
    m = xt9kdb_model_for_ctx(ctx2);
    const ET9U32 gen1 = m ? m->generation : 0;
    CHECK(m && gen1 != gen0, "a reload into the same slot must bump the generation (%u -> %u)", gen0, gen1);
    st = xt9kdb_Load_XmlKDB(ctx2, (const ET9U8*)doc, (ET9U32)n);
    const ET9KdbLoaded* m2 = xt9kdb_model_for_ctx(ctx2);
    CHECK(st == ET9STATUS_NONE && m2 == m && m2->generation != gen1,
          "reloading the same KDB in place still bumps the generation (same pointer, %u -> %u)",
          gen1, m2 ? m2->generation : 0);

    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);
    free(ctx2);
}

static void test_lang_gate(void) {
    printf("[10] locale gate: English variants, other languages, engine not ready, gate forced off\n");
    CHECK(xt9kdb_lang_is_english(1, 0x109) == 1, "English (US 0x109) must enable the English refinements");
    CHECK(xt9kdb_lang_is_english(1, 0x809) == 1, "English (UK 0x809) must enable them");
    CHECK(xt9kdb_lang_is_english(1, 0x10c) == 0, "French (0x10c) must disable them");
    CHECK(xt9kdb_lang_is_english(1, 0x107) == 0, "German (0x107) must disable them");
    CHECK(xt9kdb_lang_is_english(1, -1) == 1, "engine not ready (-1): English for that gesture only");
    CHECK(xt9kdb_lang_is_english(0, 0x10c) == 1, "debug.et9.langgate=0 forces English treatment");
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
    test_truncation_and_generation();
    test_lang_gate();
    printf("---- %d passed, %d failed ----\n", g_pass, g_fail);
    free(g_ctx);
    return g_fail ? 1 : 0;
}
