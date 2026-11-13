/* et9probe.h: the magic-validated engine context and the blob's load address, shared within
 * libxt9trace for calling blob entry points by address. */
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

/* Resolves the context behind NuanceSDK.mNativeHandle; `ling` is NULL on failure. Cached per handle,
 * revalidated on every call. */
et9_ctx et9_resolve(jlong handle);

/* Diagnostics switch: always on in debug builds; in release only while debug.et9.probe is non-zero
 * (read once per process). */
int et9_diag_enabled(void);
#define ET9_DIAG(prio, tag, ...) \
    do { if (et9_diag_enabled()) __android_log_print((prio), (tag), __VA_ARGS__); } while (0)

/* The load address of libnative-lib.so in this process, or 0 if it is not loaded. */
uintptr_t et9_blob_base(void);

#endif
