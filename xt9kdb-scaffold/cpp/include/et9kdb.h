/* et9kdb.h — the ET9KDB ABI that libxt9kdb.so provides to libxt9core.so.
 *
 * All 50 ET9KDB_* exports of the original engine are declared here (grouped by
 * role). Signatures are INFERRED from disassembly + XT9 conventions and are
 * marked VERIFY until confirmed by the differential harness or SDK headers.
 *
 * NAMING / BUILD MODES (see CMakeLists.txt):
 *   -DXT9KDB_DIFF   : symbols are exported as  xt9kdb_<Name>  so the owned module
 *                     can run side-by-side with the blob's own ET9KDB_<Name> for
 *                     A/B comparison. (Default during reimplementation.)
 *   -DXT9KDB_CUTOVER: symbols are exported as  ET9KDB_<Name>  to REPLACE the blob's
 *                     (requires the blob to be de-linked first; see PLAN.md §6).
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

/* Convenience: the engine passes the full ET9 context (== JNI handle) by ptr. */
typedef void* ET9KDBInfoPtr;   /* VERIFY: real type is &ctx or &ctx->kdb region */

/* ---- lifecycle / state --------------------------------------------------- */
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
/* @0xbecac — NOT `u32 f(ctx)`. RE of the blob shows four args: it writes *outLen = 0 FIRST (before
 * even validating ctx), then fills up to maxLen symbols of version string. Blob ET9KDB_ProcessKey
 * calls this through the PLT at 0xbf680, so our version runs on the typing path (only when
 * WordSymbInfo.bNumSymbs == 32, the word-full case). The old 1-arg stub left both out-params
 * untouched. See docs/libnative-documentation.md. */
XT9KDB_API ET9STATUS XT9KDB(GetKdbVersion)(ET9KDBInfoPtr, ET9SYMB* out, ET9U16 maxLen,
                                           ET9U16* outLen);           /* @0xbecac */
XT9KDB_API ET9STATUS XT9KDB(Validate)(ET9KDBInfoPtr);                  /* @0xc0ec8 */
XT9KDB_API void      XT9KDB(TimeOut)(ET9KDBInfoPtr);                   /* @0xbac60 */

/* ---- geometry ------------------------------------------------------------ */
/* @0xbb314 — REAL ABI (reversed 2026-07-05): (ctx, outArray[stride 0x30], capacity, *outCount).
 * capacity < keyCount -> status 0x1a; else fills `cap>=keyCount` records and writes *outCount=keyCount. */
/* @0xbb314 — `count` is a 32-BIT out-parameter. The blob stores it with `str w0, [x23]` (@0xbb3dc)
 * and its JNI getKeys reads the slot back with `ldr w8, [sp, #0x14]` (@0x2103c) into a loop bound,
 * without ever zeroing the slot. A 16-bit store leaves the upper half as stack residue: the debug
 * build happened to leave 0 there, the release build did not, and getKeys walked ~460 records off
 * the top of the main-thread stack (SIGSEGV on every keyboard show, 5.0.0-beta.18). */
XT9KDB_API ET9STATUS XT9KDB(GetKeyPositions)(ET9KDBInfoPtr, void* out, ET9U16 capacity, ET9U32* count);
XT9KDB_API ET9STATUS XT9KDB(GetKeyboardSize)(ET9KDBInfoPtr, ET9U16* w, ET9U16* h);     /* @0xbb434 */
XT9KDB_API ET9STATUS XT9KDB(SetKeyboardSize)(ET9KDBInfoPtr, ET9U16 w, ET9U16 h);       /* @0xbb668 */
XT9KDB_API ET9STATUS XT9KDB(GetKeyboardDefaultSize)(ET9KDBInfoPtr, ET9U16* w, ET9U16* h); /* @0xbb78c */
XT9KDB_API ET9STATUS XT9KDB(SetKeyboardOffset)(ET9KDBInfoPtr, ET9S16 x, ET9S16 y);     /* @0xbb85c */
/* @0xc65b0 — the trace decoder proper. Declared with EIGHT integer parameters on purpose: the call
 * sites in ProcessTrace set up four (x0,w1,x2,w3), but a pass-through that declared only those
 * would clobber x4-x7 if the real arity is higher. Forwarding all eight is ABI-safe either way;
 * the call sites set up no floating-point arguments. */
XT9KDB_API ET9STATUS XT9KDB(ProcessStoredTouch)(void* a0, ET9U64 a1, void* a2, ET9U64 a3,
                                                ET9U64 a4, ET9U64 a5, ET9U64 a6, ET9U64 a7);
XT9KDB_API ET9STATUS XT9KDB(GetKeyPositionByTap)(ET9KDBInfoPtr, ET9U16 x, ET9U16 y, void* out);        /* @0xc02d8 */
XT9KDB_API ET9STATUS XT9KDB(GetKeyPositionByStoredTouch)(ET9KDBInfoPtr, ET9U16 idx, void* out);        /* @0xc0510 */
XT9KDB_API ET9STATUS XT9KDB(GetSwitchedKeys)(ET9KDBInfoPtr, void* out);                /* @0xba848 */
XT9KDB_API ET9STATUS XT9KDB(GetMultiTapSequence)(ET9KDBInfoPtr, ET9SYMB, void* out);   /* @0xbaff0 */
XT9KDB_API ET9STATUS XT9KDB(GetTouchInfo)(ET9KDBInfoPtr, void* out);                   /* @0xbde90 */

/* ---- tap / key path ------------------------------------------------------ */
/* @0xbf424 — 5 args, per the call site in ProcessKeyBySymbol at 0xc08dc-0xc08f0:
 *   x0=ctx  w1=keyIndex(u16)  w2=u32  w3=u8  x4=ptr (non-null; the caller bails with 9 otherwise).
 * Was declared (ctx, ET9SYMB, ET9U16) — 3 args — so the last two were silently dropped. */
XT9KDB_API ET9STATUS XT9KDB(ProcessKey)(ET9KDBInfoPtr, ET9U16 key, ET9U32 a2, ET9U8 a3, void* a4);
/* @0xc0798 — 6 args (see kdb_tap.c); was declared (ctx, ET9SYMB). */
XT9KDB_API ET9STATUS XT9KDB(ProcessKeyBySymbol)(ET9KDBInfoPtr, ET9U16 s, ET9U32 a2, ET9U8 a3, void* a4, ET9U8 a5);
XT9KDB_API ET9STATUS XT9KDB(ProcessTap)(ET9KDBInfoPtr, ET9U16 x, ET9U16 y);            /* @0xca358 */
XT9KDB_API ET9STATUS XT9KDB(ModifyCurrentKey)(ET9KDBInfoPtr, ET9SYMB);                 /* @0xbfdc8 */
XT9KDB_API ET9STATUS XT9KDB(NextDiacritic)(ET9KDBInfoPtr);                             /* @0xc0224 */

/* ---- trace (gesture) path — THE KERNEL ----------------------------------- */
XT9KDB_API ET9STATUS XT9KDB(SetTraceInput)(ET9KDBInfoPtr, ET9BOOL on);                 /* @0xbaeec */
XT9KDB_API ET9STATUS XT9KDB(ClearTraceInput)(ET9KDBInfoPtr);                           /* @0xbaf70 */
/* JNI shim passes (handle, pointerId(long), float x, float y, long time); coords are
 * floats in keyboard space (VERIFIED on-device — sample array stores float x/y). */
/* Touch* take ARRAYS, not scalars. Re-derived 2026-08-03 from the blob's own JNI shims
 * (Java_..._touchStart @0x22918, touchEnd @0x22a08), which do:
 *     stp s1, s0, [sp,#0x10] ; add x2,sp,#0x14 ; add x3,sp,#0x10 ; add x4,sp,#0xc
 *     mov w5,#1              ; mov w6,wzr  (touchStart only)
 * so x2/x3/x4 are POINTERS to one-element x/y/time arrays, w5 is the count and w6 the start
 * index. The previous 5-arg scalar declaration read s0/s1 directly and got the right
 * coordinates ONLY BY ACCIDENT -- the shim's stp leaves them live across the call -- while the
 * timestamp came from w2, the low half of a stack address (observed on device: 0xd4abe094 where
 * the shim had passed 58601342). touchId is 64-bit (Java widens PointerTracker.mPointerId to
 * jlong); never dereference it. */
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
/* ProcessTrace is a COMPLETE standalone recognizer, not a "store the path" helper: it builds
 * record[0] itself, arms the dispatch lock and drives ProcessStoredTouch. Verified 6 args --
 * 0xca4e8 `uxtb w26, w4` reads the 5th. It has ZERO callers inside the blob (whole-.text scan),
 * i.e. it is purely host-facing, so calling it carries no reentrancy risk.
 * GATE: 0xca588/0xca58c load result+0x374 and return 0x66 unless it is ZERO. */
typedef struct { ET9U32 x, y; } ET9KdbTracePt;   /* 8 bytes; 0xca608/0xca610 use ucvtf => UNSIGNED */
XT9KDB_API ET9STATUS XT9KDB(ProcessTrace)(ET9KDBInfoPtr, const ET9KdbTracePt* pts, ET9U32 count,
                                          const ET9U32* times, ET9U8 mode, ET9U16* out); /* @0xca4cc */

/* ---- KDB loading (XML/text -> key model) --------------------------------- */
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

/* ---- debug identity (underscore-prefixed in the blob) -------------------- */
XT9KDB_API const void* XT9KDB(_GetLastTrace)(ET9KDBInfoPtr);                            /* @0xcaf3c -> _ET9KDB_GetLastTrace */
XT9KDB_API const void* XT9KDB(_GetLastTraceDbg)(ET9KDBInfoPtr);                         /* @0xcb1b8 -> _ET9KDB_GetLastTraceDbg */

#ifdef __cplusplus
}
#endif
#endif /* XT9_ET9KDB_H */
