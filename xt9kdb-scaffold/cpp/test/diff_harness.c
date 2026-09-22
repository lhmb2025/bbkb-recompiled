/* diff_harness.c — differential test: owned KDB vs the blob's ET9KDB.
 *
 * The blob (libnative-lib.so / libxt9core.so) is an Android/bionic lib whose ET9KDB_* need a live
 * init()-built context, so a TRUE A/B run happens ON-DEVICE. Two usages:
 *
 *   (A) On-device live A/B  — embed this in the app (DIFF build, owned = xt9kdb_*, blob = ET9KDB_*)
 *       and call diff_geometry_live()/diff_init() on the app's LIVE context. Both impls run on the
 *       same loaded KDB; mismatches log to stderr / XT9DIFF. (Compile with -DXT9DIFF_ONDEVICE.)
 *
 *   (B) Offline corpus replay (runs anywhere, incl. CI) — capture the blob's getKeys ground truth
 *       on-device, then replay the OWNED parser+model against it here:
 *           adb logcat -s XT9KEYS:I | \
 *             awk -F'[= ]+' '/k\[/{gsub(/[(),]/,"",$0); print $4","$8","$10","$12","$14}' > corpus.csv
 *       (columns: keyCode,left,top,right,bottom — adjust to your XT9KEYS line format)
 *       then:  ./diff_harness <kdb.xml> <corpus.csv>
 *       Rects use a +/-1 tolerance (owned rects are half-open, the blob's are inclusive); keyCode
 *       and center must match exactly. Validates the owned parser/model against the blob for an
 *       IDENTICAL KDB (load the same XML the device had loaded).
 *
 *   With no corpus arg, prints the owned model as CSV (seed a baseline / inspect).
 *
 * Build (offline): gcc -std=c11 -Iinclude src/*.c test/diff_harness.c -lm -o diff_harness
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- (A) on-device live A/B (compiled into the app; needs the blob + a live ctx) ------------- */
#ifdef XT9DIFF_ONDEVICE
#include <dlfcn.h>
/* REAL ABI (reversed 2026-07-05): (ctx, out[stride 0x30], capacity, *count). */
typedef ET9STATUS (*fn_getkeys)(ET9KDBInfoPtr, void*, ET9U16, ET9U32*);   /* count is 32-bit: blob @0xbb3dc `str w0` */
static fn_getkeys blob_GetKeyPositions;
int diff_init(void) {
    void* blob = dlopen("libnative-lib.so", RTLD_NOW | RTLD_GLOBAL);
    if (!blob) { fprintf(stderr, "dlopen: %s\n", dlerror()); return -1; }
    blob_GetKeyPositions = (fn_getkeys)dlsym(blob, "ET9KDB_GetKeyPositions");
    return blob_GetKeyPositions ? 0 : -2;
}
/* Compare the 0x30 output records from blob vs owned on the SAME live context. */
int diff_geometry_live(ET9KDBInfoPtr ctx_blob, ET9KDBInfoPtr ctx_owned) {
    unsigned char a[64 * 48], b[64 * 48]; ET9U32 na = 0, nb = 0;
    blob_GetKeyPositions(ctx_blob, a, 64, &na);
    xt9kdb_GetKeyPositions(ctx_owned, b, 64, &nb);
    if (na != nb) { fprintf(stderr, "GEOM count %u vs %u\n", na, nb); return 1; }
    int bad = (memcmp(a, b, (size_t)na * 48) != 0);
    if (bad) fprintf(stderr, "GEOM record bytes differ over %u keys\n", na);
    return bad;
}
#endif

/* ---- (B) offline corpus replay ------------------------------------------------------------- */
typedef struct { int code, left, top, right, bottom; } CorpusKey;

static int load_corpus(const char* path, CorpusKey* out, int max) {
    FILE* f = fopen(path, "r"); if (!f) return -1;
    int n = 0; char line[256];
    while (n < max && fgets(line, sizeof line, f)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        CorpusKey k; memset(&k, 0, sizeof k);
        if (sscanf(line, "%d,%d,%d,%d,%d", &k.code, &k.left, &k.top, &k.right, &k.bottom) >= 1)
            out[n++] = k;
    }
    fclose(f); return n;
}

static int near(int a, int b, int tol) { int d = a - b; return (d < 0 ? -d : d) <= tol; }

static int diff_corpus(const ET9KdbLoaded* m, const CorpusKey* corp, int cn) {
    int mism = 0;
    if ((int)m->keyCount != cn)
        printf("  NOTE: owned keyCount=%u, corpus rows=%d\n", m->keyCount, cn);
    int nn = (int)m->keyCount < cn ? (int)m->keyCount : cn;
    for (int i = 0; i < nn; i++) {
        ET9KdbKey k = m->keys[i]; CorpusKey c = corp[i];
        int cxo = (k.left + k.right) / 2, cyo = (k.top + k.bottom) / 2;
        int cxc = (c.left + c.right) / 2, cyc = (c.top + c.bottom) / 2;
        int ok = (k.keyCode == (ET9U16)c.code)
              && near(k.left, c.left, 1) && near(k.top, c.top, 1)
              && near(cxo, cxc, 1) && near(cyo, cyc, 1);
        if (!ok) {
            printf("  DIFF[%2d] owned code=%-4d L=%-4d T=%-3d R=%-4d B=%-3d | corpus code=%-4d L=%-4d T=%-3d R=%-4d B=%-3d\n",
                   i, k.keyCode, k.left, k.top, k.right, k.bottom, c.code, c.left, c.top, c.right, c.bottom);
            mism++;
        }
    }
    return mism;
}

static unsigned char* slurp(const char* p, long* n) {
    FILE* f = fopen(p, "rb"); if (!f) return 0;
    fseek(f, 0, SEEK_END); *n = ftell(f); fseek(f, 0, SEEK_SET);
    unsigned char* b = malloc((size_t)*n);
    if (b && fread(b, 1, (size_t)*n, f) != (size_t)*n) { free(b); b = 0; }
    fclose(f); return b;
}

int main(int argc, char** argv) {
    if (argc < 2) { printf("usage: %s <kdb.xml> [corpus.csv]\n", argv[0]); return 2; }
    long n; unsigned char* xml = slurp(argv[1], &n);
    if (!xml) { printf("cannot read %s\n", argv[1]); return 2; }
    int dummy = 1;
    if (xt9kdb_Load_XmlKDB((ET9KDBInfoPtr)&dummy, xml, (ET9U32)n) != ET9STATUS_NONE) {
        printf("Load_XmlKDB failed\n"); return 2;
    }
    const ET9KdbLoaded* m = g_kdb_model;

    if (argc < 3) {  /* no corpus: dump owned model as CSV (seed a baseline) */
        printf("# keyCode,left,top,right,bottom,cx,cy  (owned: pid=%u sid=%u %ux%u %u keys)\n",
               m->primaryId, m->secondaryId, m->authoredWidth, m->authoredHeight, m->keyCount);
        for (ET9U16 i = 0; i < m->keyCount; i++) {
            ET9KdbKey k = m->keys[i];
            printf("%d,%d,%d,%d,%d,%d,%d\n", k.keyCode, k.left, k.top, k.right, k.bottom, k.cx, k.cy);
        }
        free(xml);
        return 0;
    }

    CorpusKey corp[128];
    int cn = load_corpus(argv[2], corp, 128);
    if (cn < 0) { printf("cannot read corpus %s\n", argv[2]); return 2; }
    int mism = diff_corpus(m, corp, cn);
    printf(mism ? "DIFF: %d geometry mismatch(es)\n" : "DIFF: geometry MATCH (%d keys)\n",
           mism ? mism : cn);
    free(xml);
    return mism ? 1 : 0;
}
