/* et9_context.h: the ET9 context ABI shared between libxt9core.so (blob) and libxt9kdb.so (owned).
 * Every field the owned KDB writes that the blob's ET9AW/ET9core reads must match byte for byte.
 *   - The JNI handle is the ET9 context, sizeof 0xae238, malloc'd in native init (@0x1ff0c).
 *   - kdbLoad (@0x1f848) reads the KDB index via *(ctx+0x18); index[0] = list head.
 *   - Active language pair lives near ctx+0xae1e0 (used by loadKeyboardLayout).
 */
#ifndef XT9_ET9_CONTEXT_H
#define XT9_ET9_CONTEXT_H

#include "et9_types.h"

/* KDB index node, allocated and freed by the blob (kdbIndexAssetManager/kdbTerminate) at exactly
 * 0x28 bytes: do not extend it or read past +0x27. The blob indexes assets/kdb/ flat; device-variant
 * KDBs live in the owned registry in kdb_state.c instead. */
typedef struct ET9KdbNode {
    ET9U32              primaryId;     /* +0x00  language id (XML primaryId)   */
    ET9U32              secondaryId;   /* +0x04  form id     (XML secondaryId) */
    char*               name;          /* +0x08  strdup'd asset filename       */
    ET9U8*              contents;      /* +0x10  raw XML bytes (NULL until load)*/
    ET9U32              size;          /* +0x18  contents length               */
    ET9U32              _pad1c;        /* +0x1c  (alignment)                   */
    struct ET9KdbNode*  next;          /* +0x20  singly-linked, head-inserted  */
} ET9KdbNode;                          /* sizeof 0x28 (blob layout, fixed)     */

typedef struct ET9KdbIndex {
    ET9KdbNode*         head;          /* +0x00  list head (kdb_struct[0])     */
} ET9KdbIndex;

/* Per-key symbol capacity in the owned model. The blob's Load_AddKey (@0xb95d4) allows 0x3fd
 * symbols per key (status 0x3b) and 0xa00 per KDB (NKL+0x2ae8, status 0x39); the owned model uses
 * fixed arrays, and the widest shipped key has 10. Overflow is truncated and counted
 * (xt9kdb_key_code_truncations). */
#define ET9_KDB_MAX_KEY_CODES 16

/* One key of the owned model, fields as in the shim's KeyInfo (GetKeyPositions). */
typedef struct ET9KdbKey {
    ET9U16  keyCode;                   /* ASCII, or ET9 fn code (8=BS,13=CR,4070=fn); equals
                                          codes[0] whenever codeCount > 0 (the primary symbol). */
    ET9U8   type;                      /* NKL key type (+0x04): 1=regional 2=nonRegional 5=function.
                                          Regional+nonRegional take part in expanded-region grow;
                                          function keys neither grow nor are grown toward. */
    ET9S16  left, top, right, bottom;  /* bounding rect, authored KDB units (0..1079/0..383) */
    ET9S16  cx, cy;                    /* center */
    ET9S16  biasX, biasY;              /* smart-touch center bias (Load_AttachBias), 0 = none;
                                          clamped to +/-(half-width, half-height). */
    /* Full symbol list in Load_AddKey order: codes[0] is the primary, codes[1..] the XML's
     * keyCodes alternates. codeCount == 0 means primary only (static fixtures). */
    ET9U8   codeCount;
    ET9SYMB codes[ET9_KDB_MAX_KEY_CODES];
} ET9KdbKey;

/* Smart-touch model (ByTap @0xc02d8, Load_* @0xb94c4/0xb953c/0xba0b0). Per NKL (ctx+0x60):
 *   NKL+0x1e (30)  u8    smart-touch enabled (Load_SetSmartTouch; sentinel 2 = supported)
 *   NKL+0x20 (32)  f32   protective-area X fraction [0..1]  (Load_SetSmartTouchProtectiveArea)
 *   NKL+0x24 (36)  f32   protective-area Y fraction [0..1]
 *   NKL+0x38 (56)  i32   protective param px (X)   } radius^2 = (p56^2 + p60^2) / 4
 *   NKL+0x3c (60)  i32   protective param px (Y)   }   (ByTap's snap radius)
 *   NKL+0x64 (100) u32   keyCount
 * Per key (Load_AttachBias): signed (biasX,biasY), clamped to +/-((right-left+1)/2,
 *   (bottom-top+1)/2), shifting the effective center.
 * ByTap: dx=tap_x-keyRefX (optionally affine-scaled by NKL[26]/keyScaleX), dy likewise, then the
 *   resolver @0x5cbe8, which rounds the tap (fcvtzu(tap+0.5)), reads the bbox at key+128/130/132/134
 *   (top/left/bottom/right), tests containment, else nearest within the protective radius weighted
 *   by bias. point_to_key implements this; its exact FP weighting and tie-breaks are not matched. */

#define ET9_KDB_AUTHORED_W  1080
/* Authored letter-grid height: 3 rows * 128px (athena). Sensor->authored is pure translation, no
 * vertical scaling. Per-variant KDBs may differ. */
#define ET9_KDB_AUTHORED_H  384

typedef struct ET9KdbLoaded {
    ET9U32     primaryId;              /* active (pid,sid) of loaded KDB        */
    ET9U32     secondaryId;
    ET9U16     authoredWidth;          /* 1080                                  */
    ET9U16     authoredHeight;         /* 324                                   */
    ET9U16     activeWidth;            /* device size from SetKeyboardSize      */
    ET9U16     activeHeight;
    ET9U16     keyCount;               /* 30 for English qwerty_pkb             */
    ET9U16     authoredRadiusY;        /* optional radiusY= from the layout XML; 0 = unset
                                          (NKL RADY then defaults to home-row height) */
    ET9KdbKey* keys;                   /* keyCount entries                      */
    ET9U32     generation;             /* bumped on every change to a loaded model; 0 for static
                                          fixtures. Key caches on it, not the pointer: a
                                          context's model is reloaded in place. */
} ET9KdbLoaded;

/* Native ABI of GetKeyPositions (@0xbb314) + converter (@0xb9200), what the blob's JNI getKeys reads:
 *   ctx + 0x60 (96)            -> pointer to the in-context loaded-KDB struct  (NKL)
 *   NKL + 0x64 (100)           -> u32 keyCount   (30 for English qwerty_pkb)
 *   NKL + 0x68 (104)           -> internal key array, stride 0x88 (136 bytes) per key
 *   NKL + 0x1a/0x1c (26/28)    -> active keyboard width/height; used as (val>>1) when clamping
 *   GetKeyPositions(ctx, out, maxCount, &countOut):
 *     - validates magic (ctx+0x58==0x1428) and gate (ctx+0x38==0), ensures KDB loaded,
 *     - errors: 9 (null/!loaded), 0x3f (gate set), 0x27 (magic mismatch), 0x1a (count>max),
 *     - writes countOut = keyCount, and `out[i]` for i in 0..keyCount as a 0x30 (48-byte) record.
 *   OUTPUT record (0x30 bytes), offsets written by the converter @0xb9200:
 *     +0x00 u32   (internal+4)          +0x04 u32 (internal+8)
 *     +0x08 u16   keyCode/symbol        +0x0a u16  +0x0c u16   +0x0e u8
 *     +0x10 u32   (internal+64)         +0x18 ptr (internal+72: symbol/alt data)
 *     +0x20 u32   (internal+12)         +0x24 u32 (internal+16)
 *     +0x28..+0x2e : 4x u16 = key RECT corners, scaled to the active keyboard size
 *                    (computed vs (NKL[26]>>1)/(NKL[28]>>1)) -> left/top/right/bottom
 *   The KeyInfo field<->offset binding comes from getKeys' cached JNI fieldIDs (@0x20fc0). */

/* Trace/tap result region (the hand-off to ET9AW), see TRACE_RESULT_REGION.md:
 *  - x0 to every ET9KDB_* is the full ET9 context (SetKdbNum @0xba654 checks [x0+0x58]==0x1428).
 *  - ctx + 0x30  -> traceObj   (separately allocated, >= 0x49000 bytes)
 *  - ctx + 0x58  : u16 magic  == 0x1428      (struct-validity gate)
 *  - ctx + 0x5a  : u16 secondary magic (== traceObj[0])
 *  - ctx + 0x38  : u8  gate flag (nonzero => ProcessTrace refuses, status 0x3f)
 *  - ctx + 0x4c  : u8  trace-input-active (SetTraceInput=1 / ClearTraceInput=0)
 *  - ctx + 0xfc38: u32 loaded-kdb dirty/id (validator 0xba7e0 syncs it)
 *
 *  - traceObj + 0x49000 : the RESULT block (== the touch-info region). ProcessTrace (@0xca4cc)
 *    writes here; records accumulate across taps (the active input sequence), not cleared at touchEnd:
 *        +0x370 u32 round-robin cursor       +0x374 u32 counter (== last assigned seqId)
 *        record array @ +0x360, stride 0x7568 (30056 B), each record:
 *           +0x00..0x17 all zero (writer memsets body from +0x18)
 *           +0x28 u32  = loaded-KDB content checksum (== NKL+0x0c; per-KDB, e.g. uneven=0xBB786DC7,
 *                        uniform-108=0xAF5B3D8D), not a constant
 *           +0x30 u32  timestamp            +0x58 u32 timestamp (dup of +0x30)
 *           +0x3c u32  sequence index (0,1,2,...)
 *           +0x40 u32  finalize flags 0x00000101 (cleared to 0 when recognition consumes the record)
 *           +0x44 u32  state: 0=free / 2=finalized
 *           +0x48 u32  sampleCount
 *           +0x50 f32  X (keyboard coords)  +0x54 f32 Y
 */
#define ET9_OFF_TRACEOBJ          0x30u
/* Active-KDB selection state. SetKdbNum (0xba654) stores its arguments verbatim, not decomposed ids:
 *      0x0ba700  str  w21, [x19, #4]   ; ctx+0x04 = arg1 = the full u32 selector
 *      0x0ba704  strh w22, [x19, #8]   ; ctx+0x08 = uxth(arg2) = the flags word
 * ProcessKeyBySymbol reads them back at those widths. ctx+0x04 is load-bearing on every keystroke:
 * the internal loader (0xba264) keeps the installed NKL only when NKL+0x04 == ctx+0x04, else it
 * reloads from its own flat index (blind to variant KDBs). Writing pid alone breaks typing. */
#define ET9_OFF_KDB_MODE          0x00u   /* u32 recognition-mode bitfield, written by ET9KDB_Init
                                             (=1) and by the four mode setters:
                                               bit0 (0x1) AMBIG   : SetAmbigMode    @0xbeb80: &~2 |1
                                               bit1 (0x2) MULTITAP: SetMultiTapMode @0xbec60: &~1 |2
                                               bit3 (0x8) DISCRETE: SetDiscreteMode @0xbae1c: |8
                                                                     SetRegionalMode @0xbad84: &~8
                                             ET9KDB_ProcessKey (0xbf5d0/0xbf580) branches on bit0
                                             vs bit1; bit3 clear = regional (key-proximity) input. */
#define ET9_KDB_MODE_AMBIG        0x1u
#define ET9_KDB_MODE_MULTITAP     0x2u
#define ET9_KDB_MODE_DISCRETE     0x8u
#define ET9_OFF_KDB_SELECTOR      0x04u   /* u32 (secondaryId<<8)|primaryId, matches NKL+0x04      */
#define ET9_OFF_KDB_FLAGS         0x08u   /* u16 SetKdbNum's flags argument (not the secondaryId)  */
#define ET9_OFF_NKL               0x60u   /* ptr to active loaded-KDB (NKL)                         */
#define ET9_OFF_ARENA             0x68u   /* bump-alloc head inside the context arena               */
#define ET9_OFF_MAGIC             0x58u   /* u16 == ET9KDB_MAGIC */
#define ET9_OFF_MAGIC2            0x5au   /* u16 */
#define ET9_OFF_TRACE_GATE        0x38u   /* u8  */
#define ET9_OFF_TRACE_ACTIVE      0x4cu   /* u8  */
/* View<->authored projection, per context: SetKeyboardOffset writes 0xfc30/0xfc32, SetKeyboardSize
 * (@0xbb668) writes 0xfc34/0xfc36; getKeys' per-key writer (@0xb9200) and the tap resolver (@0xc02d8)
 * read them. Scale 0 on an axis = no scaling. Each engine context (IME, spellchecker) has its own. */
#define ET9_OFF_KDB_OFFSET_X      0xfc30u /* u16 SetKeyboardOffset x */
#define ET9_OFF_KDB_OFFSET_Y      0xfc32u /* u16 SetKeyboardOffset y */
#define ET9_OFF_KDB_SCALE_W       0xfc34u /* u16 SetKeyboardSize w (scale numerator; 0 = none) */
#define ET9_OFF_KDB_SCALE_H       0xfc36u /* u16 SetKeyboardSize h (scale numerator; 0 = none) */
#define ET9KDB_MAGIC              0x1428u
#define ET9_RESULT_OFF            0x49000u /* traceObj + this = result block */
#define ET9_TR_BLOCK              0x360u   /* result + this = record[0]                  */
#define ET9_TR_COUNT              0x364u   /* u32 record count                           */
#define ET9_TR_IDCTR              0x368u   /* u32 next id                                */
#define ET9_TR_RECORD_STRIDE      0x7568u  /* 30056 bytes per record                     */

/* One gesture sample (12 bytes). The record body at +0x5c is an array of these: the full
 * resampled swipe path. Taps have sampleCount==1. */
typedef struct ET9KdbSample {
    float  x;             /* +0x00 */
    float  y;             /* +0x04 */
    ET9U32 timestamp;     /* +0x08 (monotonic, ~17 units/sample) */
} ET9KdbSample;           /* sizeof 0x0c */

typedef struct ET9KdbTraceRecord {       /* offsets within one 0x7568 record    */
    ET9U8   _0[0x28];
    ET9U32  sessionTag;   /* +0x28 = loaded-KDB content checksum (== NKL+0x0c), per-KDB not constant */
    ET9U8   _2c[0x30-0x2c];
    ET9U32  timestamp;    /* +0x30 gesture start time */
    ET9U8   _34[0x3c-0x34];
    ET9U32  seqId;        /* +0x3c sequence index (input order); header counter(0x374)==last seqId */
    ET9U32  flags;        /* +0x40 finalize flags 0x00000101; cleared to 0 on consume */
    ET9U32  state;        /* +0x44 0=free / 2=finalized (recognizer scans for ==2) */
    ET9U32  sampleCount;  /* +0x48 number of samples in body */
    ET9U32  _4c;          /* +0x4c */
    float   startX;       /* +0x50 first/representative point X */
    float   startY;       /* +0x54 */
    ET9U32  timestamp2;   /* +0x58 (== +0x30) */
    ET9KdbSample samples[1]; /* +0x5c, flexible: [sampleCount] */
    /* rest of the 0x7568 record: per-sample candidate keys / scratch, layout unknown */
} ET9KdbTraceRecord;
#define ET9_TR_SAMPLES_OFF  0x5cu

static inline void* et9_ctx_traceobj(void* ctx) {
    return *(void**)((char*)ctx + ET9_OFF_TRACEOBJ);
}
static inline void* et9_result_block(void* traceObj) {
    return (char*)traceObj + ET9_RESULT_OFF;
}
static inline ET9KdbTraceRecord* et9_trace_record(void* traceObj, ET9U32 i) {
    return (ET9KdbTraceRecord*)((char*)et9_result_block(traceObj)
                               + ET9_TR_BLOCK + (size_t)i * ET9_TR_RECORD_STRIDE);
}

/* The rest of the 0xae238 context stays opaque on purpose: typed access only at the offsets the
 * owned KDB touches, so nothing depends on other blob internals. */
#define ET9_CTX_SIZE            0xae238u
#define ET9_OFF_KDB_INDEX       0x18u      /* *(ctx+0x18) -> ET9KdbIndex        */
#define ET9_OFF_ACTIVE_LANG     0xae1e0u   /* active language pair (unverified) */

typedef struct ET9CtxOpaque ET9CtxOpaque;  /* the blob's context, by pointer    */

static inline ET9KdbIndex* et9_ctx_kdb_index(void* ctx) {
    return *(ET9KdbIndex**)((char*)ctx + ET9_OFF_KDB_INDEX);
}

#endif /* XT9_ET9_CONTEXT_H */
