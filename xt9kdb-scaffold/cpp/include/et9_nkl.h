/* et9_nkl.h — the blob's in-memory loaded-KDB ("NKL") geometry format, at ctx+0x60.
 *
 * RE-confirmed from the live XT9NKL dump (docs/2026-06_re-parity-checklist_reference.md, P3 extraction) cross-checked
 * against GetKeyPositions (0xbb314), ByTap (0xc02d8), Load_AttachBias (0xba0b0) and the smart-touch
 * resolver (0x5cbe8). This is what the blob's gesture recognizer (ET9_CP_Trace) and TouchMove/End read.
 *
 * Owning the geometry = emitting this structure from our parsed KDB; path storage + recognition stay
 * the blob's. All multi-byte fields little-endian. Offsets are exact (verified against the 324 KDB dump
 * where the per-key bbox of 'a' = (0,108,107,215) appeared at key+0x18..0x1e).
 */
#ifndef ET9_NKL_H
#define ET9_NKL_H
#include "et9kdb.h"

/* ---- NKL header (first 0x68 bytes; key array begins at +0x68) ------------------------------------ */
#define NKL_OFF_FORMAT      0x00   /* u32 0x00000101 (format/version)                                 */
#define NKL_OFF_PID         0x04   /* u16 primaryId                                                   */
#define NKL_OFF_SID         0x06   /* u16 secondaryId                                                 */
#define NKL_OFF_FMTMAGIC    0x0c   /* u32 0xAF5B3D8D (KDB format magic)                                */
#define NKL_OFF_WIDTH       0x1a   /* u16 authored width  (1080)                                      */
#define NKL_OFF_HEIGHT      0x1c   /* u16 authored height (324/384)                                   */
#define NKL_OFF_CORE_FRAC   0x20   /* f32 0.6 — protective-core fraction (inner region = bbox*0.6)    */
#define NKL_OFF_CORE_FRAC2  0x24   /* f32 0.6 (y)                                                     */
/* Header geometry aggregates — derived by empirical fit across 3 layouts (see RE_NKL_FullStruct_Findings) */
#define NKL_OFF_AGG2C       0x2c   /* u32 round(row2H/3)                                              */
#define NKL_OFF_MAXW2       0x30   /* u32 max key width  (dup of RADX)                                */
#define NKL_OFF_ROW1H       0x34   /* u32 first-row height                                            */
#define NKL_OFF_RADX        0x38   /* u32 smart-touch radius param X (= max key width)                */
#define NKL_OFF_RADY        0x3c   /* u32 smart-touch radius param Y (= ROW2/home-row height); rad²=(x²+y²)/4 */
#define NKL_OFF_UNITF       0x40   /* f32 1.0                                                         */
#define NKL_OFF_AGG44       0x44   /* u32 round(maxW*4/3)                                             */
#define NKL_OFF_AGG48       0x48   /* u32 (13*row2H+36)/10 (empirical exact fit)                      */
#define NKL_OFF_AGG4C       0x4c   /* u32 smart-touch Y bound; formula UNCRACKED (computed pre-init) —
                                      banked captured values + ±3 fit (agg4c in kdb_nkl.c)            */
#define NKL_OFF_DIAG50      0x50   /* f32 sqrt(maxW² + ((H-row1H)/2)²)                                */
#define NKL_OFF_AGG54       0x54   /* u32 constant 322                                                */
#define NKL_OFF_AGG58       0x58   /* u32 3*row2H - 2                                                 */
#define NKL_OFF_DIAG60      0x60   /* f32 sqrt(maxW² + (2*row2H + row3H/2 - 1.5*row1H)²)              */
#define NKL_OFF_KEYCOUNT    0x64   /* u32 number of keys                                              */
#define NKL_OFF_KEYS        0x68   /* key array base                                                  */
#define NKL_KEY_STRIDE      136    /* 0x88 bytes per key                                              */

/* ---- per-key struct (136 B, at NKL+0x68 + i*136) ------------------------------------------------- */
/* Verified field offsets (key-relative). Rects are INCLUSIVE (l,t,r,b). The two centers at +0x30 and
 * +0x38 are the geometric and smart-touch-biased centers — identical when per-key bias is 0 (the case
 * for the shipped KDB). The protective "core" (+0x20) is the bbox shrunk to CORE_FRAC; the "expanded"
 * region (+0x28) is the bbox grown for smart-touch reach. */
#define KEY_OFF_INDEX       0x00   /* u32 load index (0..keyCount-1)                                  */
#define KEY_OFF_TYPE        0x04   /* u32 key type/flags (1 letter, 2/5 function)                     */
#define KEY_OFF_FLAG8       0x08   /* u32 (1 normal)                                                  */
#define KEY_OFF_CX          0x0c   /* u32 center x                                                    */
#define KEY_OFF_CY          0x10   /* u32 center y                                                    */
#define KEY_OFF_BBOX_L      0x18   /* u16 left                                                        */
#define KEY_OFF_BBOX_T      0x1a   /* u16 top                                                         */
#define KEY_OFF_BBOX_R      0x1c   /* u16 right                                                       */
#define KEY_OFF_BBOX_B      0x1e   /* u16 bottom                                                      */
#define KEY_OFF_CORE_L      0x20   /* u16 core left  (bbox inset to CORE_FRAC)                        */
#define KEY_OFF_CORE_T      0x22   /* u16 core top                                                    */
#define KEY_OFF_CORE_R      0x24   /* u16 core right                                                  */
#define KEY_OFF_CORE_B      0x26   /* u16 core bottom                                                 */
#define KEY_OFF_EXP_L       0x28   /* u16 expanded left  (bbox grown for reach)                       */
#define KEY_OFF_EXP_T       0x2a   /* u16 expanded top                                                */
#define KEY_OFF_EXP_R       0x2c   /* u16 expanded right                                              */
#define KEY_OFF_EXP_B       0x2e   /* u16 expanded bottom                                             */
#define KEY_OFF_CTR_GEO     0x30   /* u16 x, u16 y — geometric center (resolver +0x98)                */
#define KEY_OFF_CTR_BIAS    0x38   /* u16 x, u16 y — smart-touch-biased center (resolver +0xa0)        */
#define KEY_OFF_WEIGHT      0x40   /* u32 base weight                                                 */
#define KEY_OFF_LABEL_PTR   0x48   /* u64 label/data pointer (blob-managed; 0 when owned-emitted)     */

/* Emit our parsed KDB (geometry) into a caller-provided NKL buffer (>= 0x68 + keyCount*136 bytes).
 * Populates the header geometry + every key's bbox/core/expanded/centers/weight. Opaque blob fields
 * (label pointers, format housekeeping) are zeroed — the recognizer reads geometry, not those. Returns
 * the number of bytes written, or 0 on error. Defined in kdb_nkl.c. */
ET9U32 xt9kdb_emit_nkl(const ET9KdbLoaded* m, void* nkl_buf, ET9U32 buf_size);

#if defined(XT9KDB_CUTOVER)
/* CUTOVER: emit the owned NKL into the context arena (ctx+0x68) and activate it (ctx+0x60), stashing
 * the active ids (ctx+0x04/0x08). Defined in kdb_nkl.c. UNTESTED until on-device cutover. */
ET9STATUS xt9kdb_install_nkl(void* ctx);
#endif

#endif /* ET9_NKL_H */
