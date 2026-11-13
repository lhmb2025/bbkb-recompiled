/* ldb_reader_selftest.c: src/ldb_reader.c with a host stand-in for the asset API. The "APK" is a
 * temp file with the asset at an unaligned offset; a compressed asset has only a buffer. Pins:
 * stored assets mapped copy-on-write, compressed assets and file packs heap-copied, exact bytes and
 * a stable pointer, status 10 on misses, delete/terminate. Run (also under ASan/LSan) by run_tests.sh. */
#define _POSIX_C_SOURCE 200809L   /* mmap, sysconf, strdup under -std=c11 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include "ldb_reader.h"

static int g_fail = 0, g_logs = 0;
#define CHECK(cond, ...) do { if (!(cond)) { g_fail++; printf("  FAIL: " __VA_ARGS__); printf("\n"); } } while (0)

#define ASSET_OFF 4099                  /* deliberately not page aligned */
static char g_apk[256], g_pack[256];
static uint8_t g_bytes[3][20000];       /* [0] stored asset, [1] compressed asset, [2] file pack */
static int g_len_mismatch;              /* make the descriptor disagree with getLength */

struct LdbAsset { int which; };
LdbAsset* ldb_asset_open(const char* name) {
    LdbAsset* a = (LdbAsset*)malloc(sizeof *a);
    if (!a) return NULL;
    if (!strcmp(name, "ldb/stored.ldb")) a->which = 0;
    else if (!strcmp(name, "ldb/deflated.ldb")) a->which = 1;
    else { free(a); return NULL; }
    return a;
}
int ldb_asset_fd(LdbAsset* a, int64_t* start, int64_t* len) {
    if (a->which != 0) return -1;
    *start = ASSET_OFF; *len = (int64_t)sizeof g_bytes[0] + (g_len_mismatch ? 1 : 0);
    return open(g_apk, O_RDONLY);
}
int64_t ldb_asset_length(LdbAsset* a) { (void)a; return (int64_t)sizeof g_bytes[0]; }
const void* ldb_asset_buffer(LdbAsset* a) { return g_bytes[a->which]; }
void ldb_asset_close(LdbAsset* a) { free(a); }
void ldb_log(const char* fmt, ...) { (void)fmt; g_logs++; }

/* An index built as the blob builds it (255-bucket table, calloc(1, 0x30) nodes, strdup'd names). */
typedef struct Node {
    uint8_t lang0, lang1, pad0[6]; char* name; uint8_t format, pad1[3]; uint32_t size;
    uint8_t* data; uint8_t isAsset, mapped, pad2[6]; struct Node* next;
} Node;

static void add(void** table, unsigned l0, unsigned l1, const char* name, int asset) {
    Node* n = (Node*)calloc(1, 0x30);
    n->lang0 = (uint8_t)l0; n->lang1 = (uint8_t)l1; n->name = strdup(name); n->isAsset = (uint8_t)asset;
    Node** link = (Node**)&table[l0];
    while (*link) link = &(*link)->next;
    *link = n;
}
static Node* find(void** table, unsigned l0, unsigned l1) {
    Node* n = (Node*)table[l0];
    while (n && n->lang1 != l1) n = n->next;
    return n;
}

int main(void) {
    printf("== owned LDB reader: mapped stored assets, blob-identical everything else (P-9) ==\n");
    for (int k = 0; k < 3; k++) for (size_t i = 0; i < sizeof g_bytes[k]; i++) g_bytes[k][i] = (uint8_t)(i * 7 + k * 13 + (i >> 8));
    snprintf(g_apk, sizeof g_apk, "/tmp/ldbsel_apk_%d", (int)getpid());
    snprintf(g_pack, sizeof g_pack, "/tmp/ldbsel_pack_%d.ldb", (int)getpid());
    FILE* f = fopen(g_apk, "wb");
    for (int i = 0; i < ASSET_OFF; i++) fputc(0xee, f);
    fwrite(g_bytes[0], 1, sizeof g_bytes[0], f);
    for (int i = 0; i < 100; i++) fputc(0xdd, f);
    fclose(f);
    f = fopen(g_pack, "wb"); fwrite(g_bytes[2], 1, sizeof g_bytes[2], f); fclose(f);

    void** table = (void**)calloc(1, 0x7f8);
    void*** holder = &table;
    add(table, 9, 1, "ldb/stored.ldb", 1);      /* EN-ish: bucket 9, secondary 1 */
    add(table, 9, 2, "ldb/deflated.ldb", 1);    /* same bucket, second node */
    add(table, 9, 3, g_pack, 0);                /* third node: a downloaded pack on disk */
    add(table, 25, 0, "ldb/missing.ldb", 1);    /* the asset does not exist */

    uint8_t* p = NULL; uint32_t n = 0;
    /* stored asset: mapped at the unaligned offset */
    CHECK(ldb_read_data(table, 9 | (1 << 8), &p, &n) == 0, "stored asset not read");
    CHECK(n == sizeof g_bytes[0] && p && !memcmp(p, g_bytes[0], n), "stored asset bytes differ");
    CHECK(find(table, 9, 1)->mapped == 1, "stored asset was not mapped");
    uint8_t* again = NULL; uint32_t n2 = 0;
    CHECK(ldb_read_data(table, 9 | (1 << 8), &again, &n2) == 0 && again == p && n2 == n, "second read not served from the node");
    /* copy-on-write: the engine may write its copy; nobody else may see it */
    p[0] ^= 0xff; p[n - 1] ^= 0xff;
    int fd = open(g_apk, O_RDONLY);
    uint8_t* other = ldb_map_range(fd, ASSET_OFF, sizeof g_bytes[0]);
    close(fd);
    CHECK(other && other[0] == g_bytes[0][0] && other[sizeof g_bytes[0] - 1] == g_bytes[0][sizeof g_bytes[0] - 1],
          "a write through one mapping reached another (not MAP_PRIVATE)");
    if (other) munmap(other - (ASSET_OFF & 4095), sizeof g_bytes[0] + (ASSET_OFF & 4095));
    FILE* chk = fopen(g_apk, "rb"); fseek(chk, ASSET_OFF, SEEK_SET);
    CHECK(fgetc(chk) == g_bytes[0][0], "a write through the mapping reached the file");
    fclose(chk);

    /* compressed asset: the blob's malloc + memcpy */
    CHECK(ldb_read_data(table, 9 | (2 << 8), &p, &n) == 0 && n == sizeof g_bytes[1] && !memcmp(p, g_bytes[1], n), "compressed asset");
    CHECK(find(table, 9, 2)->mapped == 0, "a compressed asset must be a heap copy");
    /* file pack: fopen/fread, never mapped */
    CHECK(ldb_read_data(table, 9 | (3 << 8), &p, &n) == 0 && n == sizeof g_bytes[2] && !memcmp(p, g_bytes[2], n), "file pack");
    CHECK(find(table, 9, 3)->mapped == 0, "a downloaded pack must be a heap copy");

    /* misses: the blob's status 10, with its log line where it logs */
    int logs = g_logs;
    CHECK(ldb_read_data(table, 9 | (7 << 8), &p, &n) == 10, "unknown secondary language");
    CHECK(g_logs == logs + 1, "an unknown secondary in a known bucket is logged, as by the blob");
    CHECK(ldb_read_data(table, 40, &p, &n) == 10, "empty bucket");
    CHECK(ldb_read_data(table, 25, &p, &n) == 10 && !find(table, 25, 0)->data, "asset that cannot be opened");
    CHECK(ldb_read_data(table, 255, &p, &n) == 10, "bucket 255 is outside the blob's 255-entry table");
    CHECK(ldb_read_data(NULL, 9, &p, &n) == 10, "no table");

    /* the callback entry: table = first word of the LingInfo, language at info+0xa0 */
    uint8_t info[0x100] = {0}; *(uint32_t*)(info + 0xa0) = 9 | (2 << 8);
    void* ling[8] = {0}; ling[0] = table; ling[4] = info;   /* ling+0x20 */
    CHECK(ldbReadDataAW(ling, &p, &n) == 0 && n == sizeof g_bytes[1] && !memcmp(p, g_bytes[1], n), "ldbReadDataAW");

    /* a length that disagrees with getLength falls back to the heap copy */
    g_len_mismatch = 1;
    add(table, 30, 0, "ldb/stored.ldb", 1);
    CHECK(ldb_read_data(table, 30, &p, &n) == 0 && find(table, 30, 0)->mapped == 0 && n == sizeof g_bytes[0], "length mismatch fallback");
    g_len_mismatch = 0;

    /* delete: the middle node, then the head; the data stays with the caller (see ldbDeleteIndex) */
    Node* mid = find(table, 9, 2);
    uint8_t* midData = mid->data;
    ldbDeleteIndex(table, 9 | (2 << 8));
    CHECK(!find(table, 9, 2) && find(table, 9, 1) && find(table, 9, 3), "delete middle");
    free(midData);
    Node* head = find(table, 9, 1);
    uint8_t* headData = head->data; uint32_t headSize = head->size;
    ldbDeleteIndex(table, 9 | (1 << 8));
    CHECK(!find(table, 9, 1) && find(table, 9, 3) && table[9] == (void*)find(table, 9, 3), "delete head");
    munmap(headData - ((uintptr_t)headData & 4095), headSize + ((uintptr_t)headData & 4095));
    ldbDeleteIndex(table, 9 | (9 << 8));    /* absent: no-op */
    ldbDeleteIndex(table, 77);              /* empty bucket: no-op */

    /* terminate: everything left (heap and mapped) is released; ASan/LSan check the rest */
    add(table, 50, 0, "ldb/stored.ldb", 1);
    CHECK(ldb_read_data(table, 50, &p, &n) == 0 && find(table, 50, 0)->mapped == 1, "second mapped node");
    CHECK(ldbTerminate(holder) == 1 && table == NULL, "terminate");
    CHECK(ldbTerminate(holder) == 1, "terminate of an empty holder");
    CHECK(ldbTerminate(NULL) == 1, "terminate(NULL)");

    unlink(g_apk); unlink(g_pack);
    if (g_fail) { printf("  %d check(s) FAILED\n", g_fail); return 1; }
    printf("  OK: stored asset mapped copy-on-write, compressed asset and file pack copied, misses = 10, delete + terminate release everything\n");
    return 0;
}
