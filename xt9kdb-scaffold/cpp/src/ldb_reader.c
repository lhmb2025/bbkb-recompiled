/* ldb_reader.c: owned LDB read callback and index teardown. elf_cutover.py hands over
 * ldbReadDataAW (0x1e6d0, via GLOB_DAT), ldbTerminate and ldbDeleteIndex (via PLT); the blob keeps
 * ldbInit, the ldbIndex* builders and ldbReadDataCP (which jumps into the blob's ldbReadDataAW+0x24).
 * The blob mallocs a 5 to 6 MB copy per LDB per engine. Uncompressed APK assets are instead mmapped
 * MAP_PRIVATE read/write (same copy-on-write semantics, clean shared pages). Everything else takes
 * malloc + memcpy / fread; downloaded packs are never mapped since an update can rewrite them in place.
 *
 * node->data: set only here; cached by the engine at ling+0x28 (size +0x30) by ET9AWLdbValidate
 * (0xec238) and 0x6f534, never freed by it; freed only by ldbTerminate (0x1f070), reached from
 * dispose and init failure paths (0x20164/0x201d4/0x20224/0x20378); ldbDeleteIndex (0x1efb8, from
 * deregisterLdb 0x2289c) frees the node only. Index table at block+0xae200.
 *
 * NODE (calloc(1, 0x30) in ldbIndexAssetManager 0x1eaf4 / ldbIndexFile 0x1ef08)
 *   +0x00 u8 primary language (bucket)   +0x01 u8 secondary language   +0x08 char* name
 *   +0x10 u8 format flag   +0x14 u32 size   +0x18 u8* data   +0x20 u8 asset (1) or file (0)
 *   +0x21 u8 mapped (ours: padding the blob never touches, zero from calloc)
 *   +0x28 next
 * Table: calloc(1, 0x7f8), 255 buckets keyed by the language id's low byte. */
#define _POSIX_C_SOURCE 200809L   /* mmap, sysconf, strdup under -std=c11 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <sys/mman.h>
#include "ldb_reader.h"

#define LDB_BUCKETS 255
#define LDB_STATUS_NO_DATA 10   /* the blob's "no LDB for this language" status */

typedef struct LdbNode {
    uint8_t  lang0, lang1;
    uint8_t  pad0[6];
    char*    name;
    uint8_t  format;
    uint8_t  pad1[3];
    uint32_t size;
    uint8_t* data;
    uint8_t  isAsset;
    uint8_t  mapped;
    uint8_t  pad2[6];
    struct LdbNode* next;
} LdbNode;
_Static_assert(sizeof(LdbNode) == 0x30, "LDB index node must match the blob's 0x30-byte calloc");
_Static_assert(offsetof(LdbNode, size) == 0x14 && offsetof(LdbNode, data) == 0x18 &&
               offsetof(LdbNode, isAsset) == 0x20 && offsetof(LdbNode, next) == 0x28,
               "LDB index node layout");

static size_t page_size(void) {
    long p = sysconf(_SC_PAGESIZE);
    return p > 0 ? (size_t)p : 4096;
}

/* Maps [start, start+len) of fd copy-on-write; NULL on failure. */
uint8_t* ldb_map_range(int fd, int64_t start, int64_t len) {
    if (fd < 0 || start < 0 || len <= 0 || len > 0x7fffffff) return NULL;
    size_t pg = page_size();
    int64_t base = start & ~(int64_t)(pg - 1);
    size_t delta = (size_t)(start - base);
    void* p = mmap(NULL, (size_t)len + delta, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, (off_t)base);
    return p == MAP_FAILED ? NULL : (uint8_t*)p + delta;
}

/* Releases a node's data the way it was obtained. */
static void release_data(LdbNode* n) {
    if (!n->data) return;
    if (n->mapped) {
        size_t delta = (uintptr_t)n->data & (page_size() - 1);
        munmap(n->data - delta, (size_t)n->size + delta);
    } else {
        free(n->data);
    }
    n->data = NULL;
    n->mapped = 0;
}

/* Fills node->data/size from the asset or file (blob 0x1e7d4-0x1e938); data stays NULL on failure. */
static void load_node(LdbNode* n) {
    if (n->isAsset) {
        LdbAsset* a = ldb_asset_open(n->name);
        if (!a) { ldb_log("Failed to open file %s", n->name); return; }
        int64_t start = 0, len = 0;
        int fd = ldb_asset_fd(a, &start, &len);
        int64_t want = ldb_asset_length(a);
        if (fd >= 0) {
            uint8_t* p = (len == want) ? ldb_map_range(fd, start, len) : NULL;
            close(fd);
            if (p) { n->data = p; n->size = (uint32_t)len; n->mapped = 1; }
        }
        if (!n->data) {
            const void* buf = ldb_asset_buffer(a);
            if (want < 1 || !buf) {
                ldb_log("Empty file at %s", n->name);
            } else {
                uint8_t* copy = (uint8_t*)malloc((size_t)want);
                if (copy) { memcpy(copy, buf, (size_t)want); n->data = copy; n->size = (uint32_t)want; }
            }
        }
        ldb_asset_close(a);
        return;
    }
    FILE* f = fopen(n->name, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len > 0) {
        uint8_t* buf = (uint8_t*)malloc((size_t)len);
        if (buf) {
            /* a short read is logged and the buffer still handed out */
            if (fread(buf, (size_t)len, 1, f) == 0)
                ldb_log("Failed to read entire LDB.  errno: %s", strerror(errno));
            n->data = buf; n->size = (uint32_t)len;
        }
    }
    fclose(f);
}

int ldb_read_data(void** table, uint32_t langid, uint8_t** ppData, uint32_t* pSize) {
    unsigned bucket = langid & 0xff, second = (langid >> 8) & 0xff;
    if (!table || bucket >= LDB_BUCKETS) return LDB_STATUS_NO_DATA;
    LdbNode* n = (LdbNode*)table[bucket];
    while (n && n->lang1 != second) n = n->next;
    if (!n) {
        if (table[bucket]) ldb_log("No LDB found for language %d:%d", (int)bucket, (int)second);
        return LDB_STATUS_NO_DATA;
    }
    if (!n->data) load_node(n);
    if (!n->data) return LDB_STATUS_NO_DATA;
    *pSize = n->size;
    *ppData = n->data;
    return 0;
}

/* ET9 read callback. Index table = first word of the LingInfo; language id at *(ling+0x20) + 0xa0. */
int ldbReadDataAW(void* ling, uint8_t** ppData, uint32_t* pSize) {
    if (!ling) return LDB_STATUS_NO_DATA;
    void** table = *(void***)ling;
    const uint8_t* info = *(const uint8_t* const*)((const uint8_t*)ling + 0x20);
    if (!info) return LDB_STATUS_NO_DATA;
    return ldb_read_data(table, *(const uint32_t*)(info + 0xa0), ppData, pSize);
}

/* Frees every node, name, data and the table; *holder = NULL. Always returns 1. */
int ldbTerminate(void*** holder) {
    if (!holder) return 1;
    void** table = *holder;
    if (table) {
        for (unsigned b = 0; b < LDB_BUCKETS; b++) {
            LdbNode* n = (LdbNode*)table[b];
            while (n) {
                LdbNode* next = n->next;
                release_data(n);
                free(n->name);
                free(n);
                n = next;
            }
        }
        free(table);
    }
    *holder = NULL;
    return 1;
}

/* Unlinks and frees the node for langid. The data is not released: deregisterLdb does not
 * deactivate the language, so the engine still reads it through ling+0x28 until setLanguage. */
void ldbDeleteIndex(void** table, uint32_t langid) {
    unsigned bucket = langid & 0xff, second = (langid >> 8) & 0xff;
    if (!table || bucket >= LDB_BUCKETS) return;
    LdbNode** link = (LdbNode**)&table[bucket];
    while (*link && (*link)->lang1 != second) link = &(*link)->next;
    LdbNode* n = *link;
    if (!n) return;
    *link = n->next;
    free(n->name);
    free(n);
}
