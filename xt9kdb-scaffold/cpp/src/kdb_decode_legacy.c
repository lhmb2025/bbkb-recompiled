/* kdb_decode_legacy.c: superseded corner+dwell gesture decoder emitting one exact key per position.
 * Not in the shipped library: built only in the DIFF branch (-Pxt9Diff) and the host suite, as an
 * independent check of the geometry and commit-sink plumbing (test/decode_drv.c, owned_selftest.c,
 * DIFF-only xt9kdb_jni.c). Frozen reference; do not add a device lever. The shipped decoder is
 * kdb_decode.c. Path buffer and key resolver: kdb_trace.c (xt9kdb_stored_path, xt9kdb_key_code_at). */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <string.h>
#include <math.h>

/* Last gesture's key sequence with each key's authored position (for per-position ambiguous sets). */
#define MAX_KEYSEQ 64
static ET9SYMB      g_keyseq[MAX_KEYSEQ];
static float        g_keyseq_x[MAX_KEYSEQ];
static float        g_keyseq_y[MAX_KEYSEQ];
static ET9U32       g_keyseq_n;

/* Appends a key and its position, collapsing consecutive duplicates. */
static void emit_key(ET9SYMB code, float ax, float ay) {
    if (g_keyseq_n && g_keyseq[g_keyseq_n - 1] == code) return;
    if (g_keyseq_n < MAX_KEYSEQ) {
        g_keyseq_x[g_keyseq_n] = ax; g_keyseq_y[g_keyseq_n] = ay;
        g_keyseq[g_keyseq_n++] = code;
    }
}

/* Tuning in sensor px (authored == sensor apart from the 50px Y offset) */
#define CORNER_WIN      90.0f    /* arc-length window each side of a sample (~one key width)        */
#define CORNER_COS      0.55f    /* corner if cos(angle between in/out legs) < this (~57 deg turn)  */
#define MIN_EMIT_SEP    66.0f    /* min authored-space gap between successive emits (NMS; < 1 key)  */
#define START_ARC       45.0f    /* average the touchdown over the first ~45px of travel (denoise)  */
#define DWELL_MS        40u      /* held-point time gap that signals an intended/repeated key       */
#define DWELL_MOVE2     100.0f   /* ...with squared local motion under this                          */

/* Index ~D arc-length before i (walking back, summing step lengths); -1 if the path is too short. */
static int walk_back(const ET9KdbSample* g_buf, ET9U32 i, float D) {
    float acc = 0.0f;
    for (ET9U32 j = i; j > 0; j--) {
        float dx = g_buf[j].x - g_buf[j-1].x, dy = g_buf[j].y - g_buf[j-1].y;
        acc += sqrtf(dx*dx + dy*dy);
        if (acc >= D) return (int)(j - 1);
    }
    return -1;
}
/* Index ~D arc-length after i (walking forward); -1 if the path is too short. */
static int walk_fwd(const ET9KdbSample* g_buf, ET9U32 g_n, ET9U32 i, float D) {
    float acc = 0.0f;
    for (ET9U32 j = i; j + 1 < g_n; j++) {
        float dx = g_buf[j+1].x - g_buf[j].x, dy = g_buf[j+1].y - g_buf[j].y;
        acc += sqrtf(dx*dx + dy*dy);
        if (acc >= D) return (int)(j + 1);
    }
    return -1;
}

/* Path -> start + corner/dwell keys + end. Corners use a distance-windowed curvature test (legs ~one
 * key each way) so gradual corners over dense samples are caught; NMS keeps one emit per corner. */
static void decode_keyseq(void) {
    ET9U32 g_n = 0;
    const ET9KdbSample* g_buf = xt9kdb_stored_path(&g_n);
    g_keyseq_n = 0;
    if (g_n == 0) return;
    float ax, ay;

    /* start: average the first START_ARC of travel to denoise the touchdown */
    {
        float sx = g_buf[0].x, sy = g_buf[0].y, acc = 0.0f; ET9U32 cnt = 1;
        for (ET9U32 i = 1; i < g_n; i++) {
            float dx = g_buf[i].x - g_buf[i-1].x, dy = g_buf[i].y - g_buf[i-1].y;
            acc += sqrtf(dx*dx + dy*dy);
            if (acc > START_ARC) break;
            sx += g_buf[i].x; sy += g_buf[i].y; cnt++;
        }
        int c = xt9kdb_key_code_at(sx / (float)cnt, sy / (float)cnt, &ax, &ay);
        if (c >= 0) emit_key((ET9SYMB)c, ax, ay);
    }
    float lastEX = ax, lastEY = ay;   /* authored pos of the last emitted key (for NMS) */

    for (ET9U32 i = 1; i + 1 < g_n; i++) {
        int corner = 0;
        int jb = walk_back(g_buf, i, CORNER_WIN), jf = walk_fwd(g_buf, g_n, i, CORNER_WIN);
        if (jb >= 0 && jf >= 0) {
            float ix = g_buf[i].x - g_buf[jb].x, iy = g_buf[i].y - g_buf[jb].y;
            float ox = g_buf[jf].x - g_buf[i].x, oy = g_buf[jf].y - g_buf[i].y;
            float li = sqrtf(ix*ix + iy*iy), lo = sqrtf(ox*ox + oy*oy);
            if (li > 1.0f && lo > 1.0f && (ix*ox + iy*oy) / (li*lo) < CORNER_COS) corner = 1;
        }
        float mx = g_buf[i+1].x - g_buf[i].x, my = g_buf[i+1].y - g_buf[i].y;
        int dwell = (g_buf[i+1].timestamp - g_buf[i].timestamp) > DWELL_MS && (mx*mx + my*my) < DWELL_MOVE2;

        if (corner || dwell) {
            float cx, cy;
            int ci = xt9kdb_key_code_at(g_buf[i].x, g_buf[i].y, &cx, &cy);
            if (ci >= 0) {
                float dx = cx - lastEX, dy = cy - lastEY;       /* NMS: ignore corners too close to last emit */
                if (dx*dx + dy*dy >= MIN_EMIT_SEP * MIN_EMIT_SEP) {
                    emit_key((ET9SYMB)ci, cx, cy);
                    lastEX = cx; lastEY = cy;
                }
            }
        }
    }

    int ce = xt9kdb_key_code_at(g_buf[g_n-1].x, g_buf[g_n-1].y, &ax, &ay); /* END (always) */
    if (ce >= 0) emit_key((ET9SYMB)ce, ax, ay);
}

/* Decodes the stored path and commits it through the hooks without touching any result block:
 * letters as ambiguous sets at their authored position, other keys exact. ctx may be NULL (scratch). */
ET9STATUS xt9kdb_decode_and_commit(ET9KDBInfoPtr ctx) {
    decode_keyseq();
    for (ET9U32 i = 0; i < g_keyseq_n; i++) {
        ET9SYMB code = g_keyseq[i];
        if (code >= 'a' && code <= 'z')
            xt9kdb_commit_candidates(ctx, g_keyseq_x[i], g_keyseq_y[i]);
        else
            xt9kdb_commit_symbol(ctx, code);
    }
    return ET9STATUS_NONE;
}

/* Scratch path: decode + commit the accumulated buffer to the capture sinks (NULL ctx). */
ET9STATUS xt9kdb_decode_scratch(void) { return xt9kdb_decode_and_commit((ET9KDBInfoPtr)0); }

/* Reference for the explicit-symbol feed ABI. Never compiled: XT9KDB_CUTOVER builds exclude this
 * file. Self-contained so it stays a buildable contract. The live feed (kdb_trace.c) uses
 * ET9AddCustomSymbolSet. */
#if defined(XT9KDB_CUTOVER) && defined(__ANDROID__)
#include <stdint.h>
#define BLOB_CLEAR_ALL_SYMBS   0xb5984u
#define BLOB_ADD_EXPLICIT_SYMB 0xb6e8cu
#define WSI_OFFSET             0x18750u
typedef ET9STATUS (*fn_clear_all)(void* wsi);
typedef ET9STATUS (*fn_add_explicit)(void* wsi, ET9U16 symb, ET9U32 a2, ET9U32 a3, ET9U8 flag);

/* Decode + feed in place of ET9KDB_ProcessStoredTouch; ctx = engine block, WordSymbInfo magic 0x1428. */
static ET9STATUS owned_process_stored_touch(void* ctx, uintptr_t base) {
    if (!base || !ctx) { XT9KDB_LOGE("ownPSt: no base/ctx"); return ET9STATUS_ERROR; }

    void* wsi = (char*)ctx + WSI_OFFSET;
    if (*(ET9U16*)wsi != 0x1428u) {
        XT9KDB_LOGF("ownPSt: WordSymbInfo magic mismatch (0x%04x) — refusing", *(ET9U16*)wsi);
        return ET9STATUS_ERROR;
    }

    fn_clear_all    clear_all    = (fn_clear_all)(base + BLOB_CLEAR_ALL_SYMBS);
    fn_add_explicit add_explicit = (fn_add_explicit)(base + BLOB_ADD_EXPLICIT_SYMB);

    ET9STATUS st = clear_all(wsi);
    XT9KDB_LOGI("ownPSt: ClearAllSymbs -> %d", (int)st);

    decode_keyseq();

    char spell[MAX_KEYSEQ + 1]; ET9U32 sp = 0;
    for (ET9U32 i = 0; i < g_keyseq_n; i++) {
        ET9SYMB code = g_keyseq[i];
        st = add_explicit(wsi, (ET9U16)code, 0, 0, 0);
        if (st != ET9STATUS_NONE) { XT9KDB_LOGF("ownPSt: AddExplicitSymb('%c') -> %d", (code>=32&&code<127)?(char)code:'?', (int)st); }
        if (sp < MAX_KEYSEQ) spell[sp++] = (code >= 32 && code < 127) ? (char)code : '?';
    }
    spell[sp] = 0;
    XT9KDB_LOGI("ownPSt: fed %u explicit symbols, spell='%s'", g_keyseq_n, spell);
    return ET9STATUS_NONE;
}
#endif
