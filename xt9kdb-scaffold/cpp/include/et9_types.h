/* et9_types.h — minimal ET9 scalar/typedef surface used by the KDB module.
 *
 * These mirror the XT9 SDK's ET9*.h primitive types. Values/sizes are chosen to
 * match the aarch64 ABI of the prebuilt blob (libxt9core.so). Anything marked
 * VERIFY must be confirmed against the differential harness or real SDK headers
 * before it is trusted in the cutover build.
 */
#ifndef XT9_ET9_TYPES_H
#define XT9_ET9_TYPES_H

#include <stdint.h>
#include <stddef.h>

typedef uint8_t   ET9U8;
typedef int8_t    ET9S8;
typedef uint16_t  ET9U16;
typedef int16_t   ET9S16;
typedef uint32_t  ET9U32;
typedef uint64_t  ET9U64;   /* Touch* touchId: Java widens PointerTracker.mPointerId to jlong */
typedef int32_t   ET9S32;
typedef ET9U16    ET9SYMB;      /* ET9 symbol (UCS-2)            */
typedef ET9U8     ET9BOOL;
typedef ET9U16    ET9STATUS;    /* 0 == ET9STATUS_NONE (success) */

/* Status codes observed/needed in the KDB path (VERIFY full set vs SDK). */
enum {
    ET9STATUS_NONE              = 0,
    ET9STATUS_ERROR            = 1,
    ET9STATUS_INVALID_MEMORY   = 2,
    ET9STATUS_KDB_NOT_LOADED   = 0x15, /* 21 — kdbLoad "no (pid,sid) match"      */
    ET9STATUS_NO_INIT          = 0x1a, /* 26 — kdb subsystem not initialised     */
};

#endif /* XT9_ET9_TYPES_H */
