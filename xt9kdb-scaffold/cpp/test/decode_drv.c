/* decode_drv.c — offline driver: feed synthetic SENSOR swipe paths through the owned gesture
 * decoder and print the resulting "owned spell", to validate the windowed-curvature corner fix. */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <math.h>
#include <string.h>

/* Touch* take arrays (count-based) since the 2026-08-03 signature correction; these keep the
 * old scalar call shape readable in the tests. */
static void tt_start(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    xt9kdb_TouchStart(c, 0, xs, ys, ts, 1, 0);
}
static void tt_move(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    xt9kdb_TouchMove(c, 0, xs, ys, ts, 1, 0);
}
static void tt_end(void* c, float x, float y, ET9U32 t) {
    float xs[1] = {x}, ys[1] = {y}; ET9U32 ts[1] = {t};
    xt9kdb_TouchEnd(c, 0, xs, ys, ts, 1);
}

static char g_spell[64]; static int g_sp; static int g_fail;
static ET9STATUS cap_exact(void* c, ET9SYMB s){ (void)c; if(g_sp<63) g_spell[g_sp++]=(char)s; return 0; }
static ET9STATUS cap_ambig(void* c, const ET9SYMB* s, const ET9U8* f, int n){ (void)c;(void)f;(void)n; if(g_sp<63) g_spell[g_sp++]=(char)s[0]; return 0; }

/* feed a polyline of SENSOR waypoints, sampled every ~step px, then decode (scratch path) */
static void swipe(const char* name, const char* expect, const float* wp, int nwp, float step){
    int dummy=1; ET9KDBInfoPtr ctx=(ET9KDBInfoPtr)&dummy;
    ET9U32 t=0; int started=0; float px=wp[0], py=wp[1];
    for(int s=0;s+1<nwp;s++){
        float x0=wp[2*s],y0=wp[2*s+1],x1=wp[2*s+2],y1=wp[2*s+3];
        float dx=x1-x0,dy=y1-y0,len=sqrtf(dx*dx+dy*dy); int steps=(int)(len/step); if(steps<1)steps=1;
        for(int k=0;k<=steps;k++){
            float f=(float)k/steps, x=x0+dx*f, y=y0+dy*f; t+=16;
            if(!started){ tt_start(ctx, x,y,t); started=1; }
            else tt_move(ctx, x,y,t);
            px=x; py=y;
        }
    }
    tt_move(ctx, px,py,t+16);  /* push final point (no real traceObj -> avoid ProcessTrace) */
    g_sp=0; memset(g_spell,0,sizeof(g_spell));
    xt9kdb_decode_scratch();             /* W3a scratch path: decode -> commit via sinks */
    int ok = (strcmp(g_spell, expect)==0);
    if(!ok) g_fail++;
    printf("  %-7s -> owned spell='%s' (%d pos)  expect='%s'  %s\n",
           name, g_spell, g_sp, expect, ok?"OK":"FAIL");
}

int main(void){
    int dummy=1; xt9kdb_Init((ET9KDBInfoPtr)&dummy);
    xt9kdb_set_model(&XT9_QWERTY_PKB_EN);
    xt9kdb_set_active_pkb(1); xt9kdb_SetKeyboardOffset((ET9KDBInfoPtr)&dummy,0,50);
    xt9kdb_set_aw_commit(cap_exact); xt9kdb_set_aw_commit_ambig(cap_ambig);
    printf("model: %ux%u keys=%u\n", g_kdb_model->authoredWidth, g_kdb_model->authoredHeight, g_kdb_model->keyCount);

    /* sensor = authored + (0,50). centers: o(917,63) n(701,319) e(269,63) w(161,63)
       h(593,191) r(377,63) i(809,63) m(809,319) t(485,63) */
    float we[]    ={161,113, 269,113};
    float one[]   ={917,113, 701,369, 269,113};                     /* o -> n -> e (full dip)        */
    float one_sh[]={917,113, 760,300, 700,330, 640,300, 269,113};   /* shallow rounded n corner      */
    float where[] ={161,113, 593,241, 269,113, 377,113, 269,113};   /* w h e r e                     */
    float time_[] ={485,113, 809,113, 809,369, 269,113};            /* t i m e                       */
    swipe("we",    "we",    we,    2, 14);
    swipe("one",   "one",   one,   3, 14);
    swipe("one~",  "one",   one_sh,5, 14);   /* shallow corner: old 3-sample test missed the 'n' */
    swipe("where", "where", where, 5, 14);
    swipe("time",  "time",  time_, 4, 14);
    printf(g_fail ? "==== decode driver: %d FAILED ====\n" : "==== decode driver: all passed ====\n", g_fail);
    return g_fail ? 1 : 0;
}
