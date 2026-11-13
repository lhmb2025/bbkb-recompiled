/* ldb_reader.h: the blob's LDB read callback and index teardown, owned (src/ldb_reader.c). */
#ifndef LDB_READER_H
#define LDB_READER_H
#include <stdint.h>
#include <stddef.h>

/* The exports elf_cutover.py hands over from libnative-lib.so (same names, same ABI). */
int  ldbReadDataAW(void* ling, uint8_t** ppData, uint32_t* pSize);
int  ldbTerminate(void*** holder);
void ldbDeleteIndex(void** table, uint32_t langid);

/* The read on its own, for a table and a language id (low byte = bucket, next byte = secondary). */
int  ldb_read_data(void** table, uint32_t langid, uint8_t** ppData, uint32_t* pSize);
/* Copy-on-write mapping of [start, start+len) of fd; NULL on failure. */
uint8_t* ldb_map_range(int fd, int64_t start, int64_t len);

/* Platform layer: the APK asset API on Android (ldb_reader_android.c); the host suite supplies
 * its own (test/ldb_reader_selftest.c). */
typedef struct LdbAsset LdbAsset;
LdbAsset*   ldb_asset_open(const char* name);
/* A descriptor for the asset's bytes inside the APK (stored assets only), or -1. */
int         ldb_asset_fd(LdbAsset* a, int64_t* start, int64_t* len);
int64_t     ldb_asset_length(LdbAsset* a);
const void* ldb_asset_buffer(LdbAsset* a);
void        ldb_asset_close(LdbAsset* a);
void        ldb_log(const char* fmt, ...) __attribute__((format(printf, 1, 2)));

#endif
