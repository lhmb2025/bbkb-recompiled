/* et9probe.h — what et9probe.c shares with the other files of libxt9trace: the engine context
 * resolved from the Java handle (every pointer validated against the engine's 0x1428 ABI magic
 * before it is dereferenced) and the blob's load address, for calling its entry points by
 * address. See et9probe.c for why the dynamic linker cannot be used instead. */
#ifndef ET9PROBE_H
#define ET9PROBE_H

#include <jni.h>
#include <stdint.h>
#include <android/log.h>

typedef struct {
    unsigned char *ling;   /* ET9AWLingInfo*: what every public ET9AW* entry takes first */
    unsigned char *info;
    unsigned char *core;
    const char *error;     /* set when something could not be resolved; the others may be NULL */
} et9_ctx;

/* Resolves the engine context behind NuanceSDK.mNativeHandle. `ling` is NULL on failure.
 * Cached per handle and revalidated against the magic chain on every call (see et9probe.c). */
et9_ctx et9_resolve(jlong handle);

/* Diagnostics switch for libxt9trace (P-5): always on in debug builds; in a release build
 * (NDEBUG) only while debug.et9.probe is set to something other than 0. Read once per process. */
int et9_diag_enabled(void);
#define ET9_DIAG(prio, tag, ...) \
    do { if (et9_diag_enabled()) __android_log_print((prio), (tag), __VA_ARGS__); } while (0)

/* The load address of libnative-lib.so in this process, or 0 if it is not loaded. */
uintptr_t et9_blob_base(void);

#endif
