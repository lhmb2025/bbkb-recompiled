/* et9kdb.h: the ET9KDB ABI that libxt9kdb.so provides to libxt9core.so (all 50 ET9KDB_* exports).
 * Signatures are inferred from disassembly; @0x... is the blob address.
 * Build modes (CMakeLists.txt):
 *   -DXT9KDB_DIFF   : exported as xt9kdb_<Name>, side by side with the blob's ET9KDB_<Name> for A/B.
 *   -DXT9KDB_CUTOVER: exported as ET9KDB_<Name>, replacing the blob's (blob must be de-linked).
 */
#ifndef XT9_ET9KDB_H
#define XT9_ET9KDB_H

#include "et9_types.h"
#include "et9_context.h"

#ifdef __cplusplus
extern "C" {
#endif

#if defined(XT9KDB_CUTOVER)
#  define XT9KDB(name) ET9KDB_##name
#else
#  define XT9KDB(name) xt9kdb_##name
#endif
#define XT9KDB_API __attribute__((visibility("default")))

/* The engine passes the full ET9 context (== JNI handle) by pointer. */
typedef void* ET9KDBInfoPtr;

XT9KDB_API ET9STATUS XT9KDB(Init)(ET9KDBInfoPtr);                       /* @0xbe848 */
XT9KDB_API ET9STATUS XT9KDB(SetKdbNum)(ET9KDBInfoPtr, ET9U32 selector, ET9U32 flags); /* @0xba654 sel=(sid<<8)|pid */
XT9KDB_API ET9U32    XT9KDB(GetKdbNum)(ET9KDBInfoPtr);                  /* @0xbaac4 */
XT9KDB_API ET9STATUS XT9KDB(InvalidateLoadedKdbInfo)(ET9KDBInfoPtr);   /* @0xbe6a4 forces re-select */
XT9KDB_API ET9STATUS XT9KDB(SetPageNum)(ET9KDBInfoPtr, ET9U16);        /* @0xbe9e8 */
XT9KDB_API ET9U16    XT9KDB(GetPageNum)(ET9KDBInfoPtr);                /* @0xbab80 */
XT9KDB_API ET9STATUS XT9KDB(SetRegionalMode)(ET9KDBInfoPtr, ET9BOOL);  /* @0xbad14 */
XT9KDB_API ET9STATUS XT9KDB(SetDiscreteMode)(ET9KDBInfoPtr, ET9BOOL);  /* @0xbadac */
XT9KDB_API ET9STATUS XT9KDB(SetAmbigMode)(ET9KDBInfoPtr, ET9BOOL);     /* @0xbeaec */
XT9KDB_API ET9STATUS XT9KDB(SetMultiTapMode)(ET9KDBInfoPtr, ET9BOOL);  /* @0xbebcc */
XT9KDB_API ET9U32    XT9KDB(GetRegionality)(ET9KDBInfoPtr);            /* @0xbae44 */
XT9KDB_API ET9STATUS XT9KDB(SetRegionality)(ET9KDBInfoPtr, ET9U32);    /* @0xbe73c */
/* Four args: writes *outLen = 0 first (before validating ctx), then up to maxLen symbols of the
 * version string. The blob's ProcessKey calls it via the PLT (0xbf680) when
 * WordSymbInfo.bNumSymbs == 32 (word full), so it runs on the typing path. */
XT9KDB_API ET9STATUS XT9KDB(GetKdbVersion)(ET9KDBInfoPtr, ET9SYMB* out, ET9U16 maxLen,
                                           ET9U16* outLen);           /* @0xbecac */
XT9KDB_API ET9STATUS XT9KDB(Validate)(ET9KDBInfoPtr);                  /* @0xc0ec8 */
XT9KDB_API void      XT9KDB(TimeOut)(ET9KDBInfoPtr);                   /* @0xbac60 */

/* @0xbb314: (ctx, out[stride 0x30], capacity, *count). capacity < keyCount -> status 0x1a.
 * `count` must be written as a full 32 bits: JNI getKeys (@0x2103c) reads the never-zeroed slot as a
 * u32 loop bound, so a 16-bit store leaves stack residue in the upper half and overruns the stack. */
XT9KDB_API ET9STATUS XT9KDB(GetKeyPositions)(ET9KDBInfoPtr, void* out, ET9U16 capacity, ET9U32* count);
XT9KDB_API ET9STATUS XT9KDB(GetKeyboardSize)(ET9KDBInfoPtr, ET9U16* w, ET9U16* h);     /* @0xbb434 */
XT9KDB_API ET9STATUS XT9KDB(SetKeyboardSize)(ET9KDBInfoPtr, ET9U16 w, ET9U16 h);       /* @0xbb668 */
XT9KDB_API ET9STATUS XT9KDB(GetKeyboardDefaultSize)(ET9KDBInfoPtr, ET9U16* w, ET9U16* h); /* @0xbb78c */
XT9KDB_API ET9STATUS XT9KDB(SetKeyboardOffset)(ET9KDBInfoPtr, ET9S16 x, ET9S16 y);     /* @0xbb85c */
/* @0xc65b0, the trace decoder. Eight integer params on purpose: ProcessTrace's call sites set up
 * four, but a pass-through must forward x4-x7 in case the real arity is higher. No FP args. */
XT9KDB_API ET9STATUS XT9KDB(ProcessStoredTouch)(void* a0, ET9U64 a1, void* a2, ET9U64 a3,
                                                ET9U64 a4, ET9U64 a5, ET9U64 a6, ET9U64 a7);
XT9KDB_API ET9STATUS XT9KDB(GetKeyPositionByTap)(ET9KDBInfoPtr, ET9U16 x, ET9U16 y, void* out);        /* @0xc02d8 */
XT9KDB_API ET9STATUS XT9KDB(GetKeyPositionByStoredTouch)(ET9KDBInfoPtr, ET9U16 idx, void* out);        /* @0xc0510 */
XT9KDB_API ET9STATUS XT9KDB(GetSwitchedKeys)(ET9KDBInfoPtr, void* out);                /* @0xba848 */
XT9KDB_API ET9STATUS XT9KDB(GetMultiTapSequence)(ET9KDBInfoPtr, ET9SYMB, void* out);   /* @0xbaff0 */
XT9KDB_API ET9STATUS XT9KDB(GetTouchInfo)(ET9KDBInfoPtr, void* out);                   /* @0xbde90 */

/* @0xbf424, per the call site in ProcessKeyBySymbol (0xc08dc-0xc08f0):
 *   x0=ctx  w1=keyIndex(u16)  w2=u32  w3=u8  x4=ptr (non-null; the caller bails with 9 otherwise). */
XT9KDB_API ET9STATUS XT9KDB(ProcessKey)(ET9KDBInfoPtr, ET9U16 key, ET9U32 a2, ET9U8 a3, void* a4);
/* @0xc0798, 6 args (see kdb_tap.c). */
XT9KDB_API ET9STATUS XT9KDB(ProcessKeyBySymbol)(ET9KDBInfoPtr, ET9U16 s, ET9U32 a2, ET9U8 a3, void* a4, ET9U8 a5);
XT9KDB_API ET9STATUS XT9KDB(ProcessTap)(ET9KDBInfoPtr, ET9U16 x, ET9U16 y);            /* @0xca358 */
XT9KDB_API ET9STATUS XT9KDB(ModifyCurrentKey)(ET9KDBInfoPtr, ET9SYMB);                 /* @0xbfdc8 */
XT9KDB_API ET9STATUS XT9KDB(NextDiacritic)(ET9KDBInfoPtr);                             /* @0xc0224 */

XT9KDB_API ET9STATUS XT9KDB(SetTraceInput)(ET9KDBInfoPtr, ET9BOOL on);                 /* @0xbaeec */
XT9KDB_API ET9STATUS XT9KDB(ClearTraceInput)(ET9KDBInfoPtr);                           /* @0xbaf70 */
/* Touch* take arrays: the blob's JNI shims (touchStart @0x22918, touchEnd @0x22a08) do
 *     stp s1, s0, [sp,#0x10] ; add x2,sp,#0x14 ; add x3,sp,#0x10 ; add x4,sp,#0xc
 *     mov w5,#1              ; mov w6,wzr  (touchStart only)
 * so x2/x3/x4 point to one-element x/y/time arrays (float keyboard coords), w5 is the count and
 * w6 the start index. touchId is 64-bit (a widened Java pointer id); never dereference it. */
XT9KDB_API ET9STATUS XT9KDB(TouchStart)(ET9KDBInfoPtr, ET9U64 touchId, const float* xs, const float* ys,
                                        const ET9U32* ts, ET9U32 count, ET9U32 nStart);  /* @0xbb95c */
XT9KDB_API ET9STATUS XT9KDB(TouchMove)(ET9KDBInfoPtr, ET9U64 touchId, const float* xs, const float* ys,
                                       const ET9U32* ts, ET9U32 count, ET9U32 nStart);   /* @0xbc278 */
XT9KDB_API ET9STATUS XT9KDB(TouchEnd)(ET9KDBInfoPtr, ET9U64 touchId, const float* xs, const float* ys,
                                      const ET9U32* ts, ET9U32 count);                   /* @0xbc814 */
XT9KDB_API ET9STATUS XT9KDB(TouchEndAll)(ET9KDBInfoPtr);                               /* @0xbd148 */
XT9KDB_API ET9STATUS XT9KDB(TouchCancel)(ET9KDBInfoPtr, ET9U64 touchId);               /* @0xbd418;
                                        * 2 args, matching Java NuanceSDK.touchCancel(long,long) */
XT9KDB_API ET9STATUS XT9KDB(TouchTimeOut)(ET9KDBInfoPtr);                              /* @0xbd994 */
/* ProcessTrace is a complete standalone recognizer: it builds record[0], arms the dispatch lock and
 * drives ProcessStoredTouch. 6 args (0xca4e8 `uxtb w26, w4` reads the 5th). No callers inside the
 * blob, so calling it has no reentrancy risk. Returns 0x66 unless result+0x374 is zero. */
typedef struct { ET9U32 x, y; } ET9KdbTracePt;   /* 8 bytes; 0xca608/0xca610 use ucvtf => UNSIGNED */
XT9KDB_API ET9STATUS XT9KDB(ProcessTrace)(ET9KDBInfoPtr, const ET9KdbTracePt* pts, ET9U32 count,
                                          const ET9U32* times, ET9U8 mode, ET9U16* out); /* @0xca4cc */

XT9KDB_API ET9STATUS XT9KDB(Load_Reset)(ET9KDBInfoPtr);                                /* @0xba17c */
XT9KDB_API ET9STATUS XT9KDB(Load_SetProperties)(ET9KDBInfoPtr, void* props);           /* @0xb9410 */
XT9KDB_API ET9STATUS XT9KDB(Load_AddKey)(ET9KDBInfoPtr, void* key);                    /* @0xb95d4 */
XT9KDB_API ET9STATUS XT9KDB(Load_AttachShiftedChars)(ET9KDBInfoPtr, void*);            /* @0xb9c7c */
XT9KDB_API ET9STATUS XT9KDB(Load_AttachMultitapInfo)(ET9KDBInfoPtr, void*);            /* @0xb9e24 */
XT9KDB_API ET9STATUS XT9KDB(Load_AttachBias)(ET9KDBInfoPtr, void*);                    /* @0xba0b0 */
XT9KDB_API ET9STATUS XT9KDB(Load_SetSmartTouch)(ET9KDBInfoPtr, void*);                 /* @0xb94c4 */
XT9KDB_API ET9STATUS XT9KDB(Load_SetSmartTouchProtectiveArea)(ET9KDBInfoPtr, void*);   /* @0xb953c */
XT9KDB_API ET9STATUS XT9KDB(Load_TextKDB)(ET9KDBInfoPtr, const ET9U8* text, ET9U32 len); /* @0xc1210 */
XT9KDB_API ET9STATUS XT9KDB(Load_XmlKDB)(ET9KDBInfoPtr, const ET9U8* xml, ET9U32 len);   /* @0xc39d0 */

/* Underscore-prefixed in the blob. */
XT9KDB_API const void* XT9KDB(_GetLastTrace)(ET9KDBInfoPtr);                            /* @0xcaf3c -> _ET9KDB_GetLastTrace */
XT9KDB_API const void* XT9KDB(_GetLastTraceDbg)(ET9KDBInfoPtr);                         /* @0xcb1b8 -> _ET9KDB_GetLastTraceDbg */

#ifdef __cplusplus
}
#endif
#endif /* XT9_ET9KDB_H */
