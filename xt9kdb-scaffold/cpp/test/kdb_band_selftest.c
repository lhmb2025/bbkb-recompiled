/* kdb_band_selftest.c — verify the live athena KDB emits the firmware's UNEVEN row bands.
 * Measured (visualizer + pen-on-dividers): row1=90, row2=180, row3=180 px; authored == sensor (1:1). */
#include "et9kdb.h"
#include "et9_context.h"
#include "et9_nkl.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char g_nkl[0x68 + 64 * NKL_KEY_STRIDE];
static int g_fail = 0;

/* Model/NKL index of a key by its keyCode (emit_nkl preserves model order). Robust against the
 * loader's stable partition (regional/nonRegional first, function keys appended). */
static int key_index(ET9U16 code) {
    for (ET9U16 i = 0; i < g_kdb_model->keyCount; i++)
        if (g_kdb_model->keys[i].keyCode == code) return (int)i;
    return -1;
}

static void chk_bbox(const char* name, ET9U16 code, int l, int t, int r, int b) {
    int idx = key_index(code);
    if (idx < 0) { g_fail++; printf("  FAIL %-8s key code %u not in model\n", name, code); return; }
    unsigned char* k = g_nkl + NKL_OFF_KEYS + (size_t)idx * NKL_KEY_STRIDE;
    unsigned short gl, gt, gr, gb;
    memcpy(&gl, k + KEY_OFF_BBOX_L, 2); memcpy(&gt, k + KEY_OFF_BBOX_T, 2);
    memcpy(&gr, k + KEY_OFF_BBOX_R, 2); memcpy(&gb, k + KEY_OFF_BBOX_B, 2);
    if (gl != l || gt != t || gr != r || gb != b) {
        g_fail++; printf("  FAIL %-8s got(%d,%d,%d,%d) want(%d,%d,%d,%d)\n", name, gl,gt,gr,gb, l,t,r,b);
    } else printf("  ok   %-8s idx=%d bbox=(%d,%d,%d,%d)\n", name, idx, gl,gt,gr,gb);
}

int main(int argc, char** argv) {
    const char* path = (argc > 1) ? argv[1] : "../../app/src/main/assets/kdb/athena/qwerty_pkb.xml";
    FILE* f = fopen(path, "rb"); if (!f) { printf("open fail %s\n", path); return 2; }
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* x = malloc((size_t)n);
    if (fread(x, 1, (size_t)n, f) != (size_t)n) return 2; fclose(f);
    int d = 1; xt9kdb_Init((ET9KDBInfoPtr)&d);
    if (xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&d, x, (ET9U32)n) != ET9STATUS_NONE) { printf("load fail\n"); return 2; }
    if (g_kdb_model->authoredHeight != 450) { g_fail++; printf("  FAIL height=%u want 450\n", g_kdb_model->authoredHeight); }
    else printf("  ok   authoredHeight=450\n");
    xt9kdb_emit_nkl(g_kdb_model, g_nkl, sizeof(g_nkl));
    chk_bbox("q row1", 'q', 0,   0, 107,  89);   /* band 0..90   (90px) */
    chk_bbox("a row2", 'a', 0,  90, 107, 269);   /* band 90..270 (180px) */
    chk_bbox("z row3", 'z', 108,270, 215, 449);  /* band 270..450 (180px) */
    printf(g_fail ? "==== athena bands: %d FAILED ====\n" : "==== athena bands: uneven 90/180/180 OK ====\n", g_fail);
    return g_fail ? 1 : 0;
}
