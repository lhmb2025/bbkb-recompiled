/* ldb_reader.c — the blob's LDB read callback and index teardown, owned (P-9, 2026-11 audit).
 *
 * WHAT IT REPLACES
 *   libnative-lib.so keeps every dictionary (LDB) it has opened in a private heap copy: its read
 *   callback ldbReadDataAW (0x1e6d0) opens the APK asset in BUFFER mode, mallocs the whole file
 *   (5 to 6 MB), memcpys it and closes the asset (0x1e7e8-0x1e838). That copy lives until the
 *   engine is disposed, one per language ever activated, per engine (the spell checker runs a
 *   second engine with its own copies). elf_cutover.py hands three of the blob's exports to this
 *   file, exactly as it hands over the ET9KDB_* contract: ldbReadDataAW (reached only through its
 *   GLOB_DAT, the callback alphaInit passes to ET9AWLdbInit), ldbTerminate and ldbDeleteIndex
 *   (reached only through their PLT slots). Everything else about the index stays with the blob:
 *   ldbInit, ldbIndexAssetManager / ldbIndexDirectory / ldbIndexFile build the nodes, and
 *   ldbReadDataCP (Chinese) keeps the blob's own body, which branches into the ORIGINAL
 *   ldbReadDataAW+0x24 and so still mallocs.
 *
 * WHAT CHANGES
 *   An APK asset that is stored uncompressed (app/build.gradle noCompress 'ldb', Wave 1) is
 *   mmapped straight from the APK, MAP_PRIVATE with PROT_READ|PROT_WRITE: clean, file-backed,
 *   reclaimable pages shared by both engines instead of a dirty heap copy each. A private
 *   writable mapping has exactly the semantics of the private copy it replaces — if the engine
 *   ever wrote into its LDB the written page would be copied on write and nothing else would
 *   see it — so the change does not depend on the engine treating the LDB as read-only (Swype's
 *   iOS build of the same engine reads it from a PROT_READ mmap, MemoryMappedStartOfFile in
 *   SKI8, which suggests it does). Anything else (a compressed asset, a downloaded pack on disk,
 *   a failed mapping) takes the blob's own path byte for byte: malloc + memcpy / fread. Downloaded
 *   packs are deliberately NOT mapped: a pack can be rewritten in place by an update while an
 *   engine still holds its pointer, which a heap copy survives and a mapping does not.
 *
 * WHO OWNS node->data (the proof the change rests on; addresses in the pristine blob)
 *   - set and returned only here (the blob's 0x1e744 / 0x1e834 / 0x1e89c);
 *   - cached by the engine at ling+0x28 (size at +0x30) by the two callers of the callback,
 *     ET9AWLdbValidate (0xec238) and 0x6f534; the engine never frees it;
 *   - freed only by ldbTerminate (0x1f070: the only free() of a node's +0x18 in the blob), which
 *     dispose and the init failure paths reach through the PLT (0x20164/0x201d4/0x20224/0x20378);
 *   - ldbDeleteIndex (0x1efb8, from deregisterLdb at 0x2289c) unlinks and frees the node only.
 *   The nodes are reachable only through the index table at block+0xae200 (users: init,
 *   setLanguage, registerLdb, deregisterLdb, and the read callbacks via *ling).
 *
 * NODE (calloc(1, 0x30) in ldbIndexAssetManager 0x1eaf4 / ldbIndexFile 0x1ef08)
 *   +0x00 u8 primary language (bucket)   +0x01 u8 secondary language   +0x08 char* name
 *   +0x10 u8 format flag   +0x14 u32 size   +0x18 u8* data   +0x20 u8 asset (1) or file (0)
 *   +0x21 u8 MAPPED — ours: padding the blob never reads or writes, zero from calloc
 *   +0x28 next
 *   The table is calloc(1, 0x7f8): 255 buckets, keyed by the language id's low byte.
 */
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

/* Fills node->data/size from the APK asset or the file named by node->name (the blob's
 * 0x1e7d4-0x1e938, plus the mapping). Leaves data NULL when nothing could be read. */
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
            /* As the blob does: a short read is logged and the buffer is still handed out. */
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

/* ---- the three exports elf_cutover.py hands over ------------------------------------------- */

/* ET9 read callback: ET9STATUS (ET9AWLingInfo*, ET9U8** ppData, ET9U32* pSize). The index table
 * is the first word of the LingInfo, the language id is at info (ling+0x20) + 0xa0: the blob's
 * 0x1e6d0-0x1e6e0. */
int ldbReadDataAW(void* ling, uint8_t** ppData, uint32_t* pSize) {
    if (!ling) return LDB_STATUS_NO_DATA;
    void** table = *(void***)ling;
    const uint8_t* info = *(const uint8_t* const*)((const uint8_t*)ling + 0x20);
    if (!info) return LDB_STATUS_NO_DATA;
    return ldb_read_data(table, *(const uint32_t*)(info + 0xa0), ppData, pSize);
}

/* Frees every node, its name and its data, then the table; *holder = NULL. Returns 1 (the blob
 * returns 1 whatever happens, including for a NULL holder). */
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

/* Unlinks the node for `langid` (the deregisterLdb path) and frees it and its name. The DATA is
 * deliberately not released, as in the blob: deregisterLdb does not deactivate the language,
 * so an engine whose active LDB this is still reads it through ling+0x28 until its next
 * setLanguage. A mapped buffer left behind is clean, reclaimable file memory; a heap one stays
 * allocated, as before. (The blob also leaked the name; nothing reads it once unlinked.) */
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
