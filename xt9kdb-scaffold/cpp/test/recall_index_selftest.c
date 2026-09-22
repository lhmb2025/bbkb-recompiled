/* recall_index_selftest.c — the owned-recall (first,last)-letter index: the live O(N) counting-sort
 * builder must reproduce the original O(26*26*N) reference builder byte for byte, and the
 * comparison must be able to fail (negative control flips one reference entry).
 *
 * Why this exists: the reference build ran on the main thread inside the FIRST gesture of every
 * IME process (touchEnd -> ProcessTrace(OWNED) -> rc_recall), ~160 ms on the emulator and 1-2 s
 * on the KEY2 — the "first swipe takes seconds to commit" bug. The recall loop depends on the
 * per-bucket order (survivor cap + equal-score tie-break), so equality here is the whole safety
 * argument for the rewrite.
 *
 * Build:  cc -std=c11 -Iinclude src/kdb_*.c test/recall_index_selftest.c -lm -o recall_index_selftest
 * Run:    ./recall_index_selftest          (no KDB needed; pure table work)
 */
#include <stdio.h>
#include <time.h>
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"

static int g_fail = 0, g_pass = 0;
#define CHECK(cond, msg, ...) do { \
    if (cond) { g_pass++; } \
    else { g_fail++; printf("  FAIL: " msg "\n", ##__VA_ARGS__); } } while (0)

static double now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

int main(void) {
    printf("[1] live counting-sort index == reference triple-loop index\n");
    unsigned words = 0;
    double t0 = now_ms();
    int rc = xt9kdb_recall_index_selfcheck(0, &words);
    double t1 = now_ms();
    CHECK(rc == 0, "selfcheck rc=%d (1=count 2=offsets 3=list order/content, -1=oom)", rc);
    CHECK(words > 50000 && words <= 65536, "bucketed words=%u (expected ~59.8k)", words);
    printf("  bucketed words=%u, both builders ran in %.1f ms total (the reference dominates)\n",
           words, t1 - t0);

    printf("[2] negative control: a corrupted reference must be detected\n");
    rc = xt9kdb_recall_index_selfcheck(1, 0);
    CHECK(rc == 3, "corrupted reference not detected (rc=%d, expected 3)", rc);

    printf("%s: %d passed, %d failed\n", g_fail ? "FAILED" : "OK", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
