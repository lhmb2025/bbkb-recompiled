/* recall_dump_drv.c: prints the owned recall's top words per trace, so a frequency table or index
 * change can be diffed against the previous build. Scores print as hex floats (bit-exact diff).
 *
 * Usage (from cpp/): recall_dump <kdb.xml> <corpus.txt> [<kdb.xml> <corpus.txt> ...]
 * run_tests.sh's recall_top8 stage runs it over the corpora and compares the whole output with
 * test/data/recall_top8_golden.txt. */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned char* read_file(const char* path, long* len) {
    FILE* fp = fopen(path, "rb");
    if (!fp) return 0;
    fseek(fp, 0, SEEK_END); *len = ftell(fp); fseek(fp, 0, SEEK_SET);
    unsigned char* b = (unsigned char*)malloc((size_t)*len + 1);
    if (b && fread(b, 1, (size_t)*len, fp) != (size_t)*len) { free(b); b = 0; }
    if (b) b[*len] = 0;
    fclose(fp);
    return b;
}

static Xt9DecPt g_pts[4096];

static int dump(const char* kdb, const char* corpus) {
    long len; unsigned char* xml = read_file(kdb, &len);
    static unsigned char ctx[0x10000];
    if (!xml) { fprintf(stderr, "cannot read %s\n", kdb); return -1; }
    ET9STATUS st = XT9KDB(Load_XmlKDB)((ET9KDBInfoPtr)ctx, xml, (ET9U32)len);
    free(xml);
    const ET9KdbLoaded* m = xt9kdb_model_for_ctx((ET9KDBInfoPtr)ctx);
    if (st != ET9STATUS_NONE || !m) { fprintf(stderr, "%s did not load\n", kdb); return -1; }
    Xt9DecParams p;
    xt9kdb_decode_params_from_model(m, &p, 1);
    FILE* fp = fopen(corpus, "r");
    if (!fp) { fprintf(stderr, "cannot open %s\n", corpus); return -1; }
    const char* kn = strrchr(kdb, '/'), *cn = strrchr(corpus, '/');
    char line[512];
    int traces = 0;
    while (fgets(line, sizeof line, fp)) {
        int n; const char* q = strstr(line, " n=");
        if (strncmp(line, "TRACE", 5) || !q || sscanf(q, " n=%d", &n) != 1 || n <= 0 || n > 4096) continue;
        for (int i = 0; i < n; i++) {
            float x, y; unsigned t;
            if (!fgets(line, sizeof line, fp) || sscanf(line, "%f %f %u", &x, &y, &t) != 3) {
                fprintf(stderr, "%s truncated\n", corpus); fclose(fp); return -1;
            }
            g_pts[i].x = x; g_pts[i].y = y; g_pts[i].t = t;
        }
        int idx[8]; float geo[8];
        int k = xt9kdb_recall_top(m, &p, g_pts, (ET9U32)n, idx, geo);
        printf("%s %s #%d:", kn ? kn + 1 : kdb, cn ? cn + 1 : corpus, traces);
        for (int i = 0; i < k; i++) printf(" %s=%a", xt9kdb_freq_word(idx[i]), (double)geo[i]);
        printf("\n");
        traces++;
    }
    fclose(fp);
    return traces;
}

int main(int argc, char** argv) {
    if (argc < 3 || (argc - 1) % 2) { fprintf(stderr, "usage: %s <kdb.xml> <corpus.txt> ...\n", argv[0]); return 2; }
    for (int a = 1; a + 1 < argc; a += 2)
        if (dump(argv[a], argv[a + 1]) < 0) return 1;
    return 0;
}
