/* ldb_reader_android.c — ldb_reader.c's platform layer on the device: the blob's AAssetManager
 * and the NDK asset API. CUTOVER builds only (CMakeLists.txt). */
#include <android/asset_manager.h>
#include <android/log.h>
#include <link.h>
#include <stdarg.h>
#include <string.h>
#include "ldb_reader.h"

/* ldbIndexAssetManager stores AAssetManager_fromJava's result in this blob global (0x1e9cc) and
 * ldbReadDataAW reads it back with `adrp x8, 0x21a000; ldr x0, [x8, #0x8]` (0x1e7dc). Those two
 * words are checked before the global is trusted, so a different blob build reads nothing. */
#define BLOB_ASSET_MANAGER_VADDR 0x21a008u
#define BLOB_READ_MGR_VADDR      0x1e7dcu
static const uint32_t FP_READ_MGR[2] = {0x90000fe8u, 0xf9400500u};

static int find_blob_cb(struct dl_phdr_info* info, size_t size, void* out) {
    (void)size;
    if (info->dlpi_name && strstr(info->dlpi_name, "libnative-lib.so")) {
        *(uintptr_t*)out = (uintptr_t)info->dlpi_addr;
        return 1;
    }
    return 0;
}

static AAssetManager* asset_manager(void) {
    static uintptr_t base;
    static int checked, ok;
    if (!checked) {
        checked = 1;
        dl_iterate_phdr(find_blob_cb, &base);
        ok = base && memcmp((const void*)(base + BLOB_READ_MGR_VADDR), FP_READ_MGR, sizeof FP_READ_MGR) == 0;
        if (!ok) ldb_log("LDB reader: blob build not recognised (base=%p)", (void*)base);
    }
    return ok ? *(AAssetManager**)(base + BLOB_ASSET_MANAGER_VADDR) : NULL;
}

LdbAsset* ldb_asset_open(const char* name) {
    AAssetManager* m = asset_manager();
    return m ? (LdbAsset*)AAssetManager_open(m, name, AASSET_MODE_BUFFER) : NULL;
}

int ldb_asset_fd(LdbAsset* a, int64_t* start, int64_t* len) {
    off64_t s = 0, l = 0;
    int fd = AAsset_openFileDescriptor64((AAsset*)a, &s, &l);   /* -1 for a compressed asset */
    *start = (int64_t)s; *len = (int64_t)l;
    return fd;
}

int64_t ldb_asset_length(LdbAsset* a) { return (int64_t)AAsset_getLength64((AAsset*)a); }
const void* ldb_asset_buffer(LdbAsset* a) { return AAsset_getBuffer((AAsset*)a); }
void ldb_asset_close(LdbAsset* a) { AAsset_close((AAsset*)a); }

void ldb_log(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, "NativeShim", fmt, ap);   /* the blob's tag and level */
    va_end(ap);
}
