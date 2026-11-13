/* freq_table_selftest.c: src/kdb_freq_en.h reads back exactly what kdb_freq_en_gen.py wrote. The
 * words are one NUL-separated blob plus u32 offsets, which can go wrong silently (an off-by-one
 * still binary-searches), so this checks the layout and recomputes the generator's FNV-1a hash.
 * Negative control: one word read a byte late must differ. Run by run_tests.sh from cpp/. */
#include <stdio.h>
#include <string.h>
#include "../src/kdb_freq_en.h"

static int g_fail = 0;
#define CHECK(cond, ...) do { if (!(cond)) { g_fail++; printf("  FAIL: " __VA_ARGS__); printf("\n"); } } while (0)

/* kdb_freq_en_gen.py table_hash: word bytes, NUL, zipf, rank low, rank high. `skew` reads word
 * `skew_at` one byte late (the negative control). */
static uint32_t table_hash(int skew_at) {
    uint32_t h = 0x811C9DC5u;
    for (int i = 0; i < kFreqCountEn; i++) {
        const char* w = kFreqWordEn(i) + (i == skew_at ? 1 : 0);
        unsigned char tail[4] = { 0, kFreqZipfEn[i], (unsigned char)(kFreqRankEn[i] & 0xff), (unsigned char)(kFreqRankEn[i] >> 8) };
        for (const unsigned char* p = (const unsigned char*)w; *p; p++) h = (h ^ *p) * 0x01000193u;
        for (int k = 0; k < 4; k++) h = (h ^ tail[k]) * 0x01000193u;
    }
    return h;
}

int main(void) {
    printf("== frequency table: blob + offsets read back what the generator wrote (C-6) ==\n");
    CHECK(sizeof kFreqWordOffEn / sizeof kFreqWordOffEn[0] == (size_t)kFreqCountEn, "offset count");
    CHECK(sizeof kFreqZipfEn == (size_t)kFreqCountEn, "zipf count");
    CHECK(sizeof kFreqRankEn / sizeof kFreqRankEn[0] == (size_t)kFreqCountEn, "rank count");
    CHECK(kFreqWordOffEn[0] == 0, "first word does not start the blob");
    int laid = 1, sorted = 1, alpha = 1;
    for (int i = 0; i < kFreqCountEn; i++) {
        const char* w = kFreqWordEn(i);
        size_t len = strlen(w);
        /* each word ends exactly where the next begins: no gaps, no overlaps */
        uint32_t end = kFreqWordOffEn[i] + (uint32_t)len + 1;
        if (i + 1 < kFreqCountEn ? kFreqWordOffEn[i + 1] != end : end + 1 != sizeof kFreqWordsBlobEn) laid = 0;
        if (len == 0 || strspn(w, "abcdefghijklmnopqrstuvwxyz'") != len) alpha = 0;
        if (i && strcmp(kFreqWordEn(i - 1), w) >= 0) sorted = 0;
    }
    CHECK(laid, "offsets do not tile the blob");
    CHECK(alpha, "a word outside [a-z']+");
    CHECK(sorted, "words not strictly ascending (the binary searches need strcmp order)");
    uint32_t h = table_hash(-1), neg = table_hash(kFreqCountEn / 2);
    CHECK(h == kFreqTableHashEn, "hash 0x%08x != generator's 0x%08x", h, kFreqTableHashEn);
    CHECK(neg != kFreqTableHashEn, "negative control: a skewed word did not change the hash");
    if (g_fail) { printf("  %d check(s) FAILED\n", g_fail); return 1; }
    printf("  OK: %d words, %zu-byte blob, hash 0x%08x matches; negative control differs\n",
           kFreqCountEn, sizeof kFreqWordsBlobEn, h);
    return 0;
}
