/* kdb_internal.h: non-exported helpers shared between the owned KDB sources. */
#ifndef XT9_KDB_INTERNAL_H
#define XT9_KDB_INTERNAL_H

#include "et9_context.h"

/* Loud-failure channel for conditions that are otherwise silent (a status the engine swallows,
 * or an invariant the blob enforces without reporting). */
#ifdef __ANDROID__
#include <android/log.h>
#define XT9KDB_LOGE(msg) __android_log_print(ANDROID_LOG_ERROR, "XT9KDB", "%s", msg)
/* log.tag.XT9Trace gate, shared so the key-path log turns on with the trace dump. */
int xt9kdb_trace_dump_enabled(void);
#define XT9KDB_LOGF(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, "XT9KDB", fmt, __VA_ARGS__)
#define XT9KDB_LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  "XT9KDB", fmt, __VA_ARGS__)
#else
#include <stdio.h>
#define XT9KDB_LOGE(msg) fprintf(stderr, "XT9KDB: %s\n", msg)
#define XT9KDB_LOGF(fmt, ...) fprintf(stderr, "XT9KDB: " fmt "\n", __VA_ARGS__)
#define XT9KDB_LOGI(fmt, ...) fprintf(stderr, "XT9KDB: " fmt "\n", __VA_ARGS__)
#endif

/* Per-tap / per-swipe diagnostics (about 2 KB of logd traffic per swipe). On in debug builds and
 * on the host; in release (NDEBUG) only while log.tag.XT9Trace is set. Read once per process.
 * Check xt9kdb_diag_enabled() before building a long line, not just before printing it. */
int xt9kdb_diag_enabled(void);
#define XT9KDB_LOGD(fmt, ...) do { if (xt9kdb_diag_enabled()) XT9KDB_LOGI(fmt, __VA_ARGS__); } while (0)

/* A set debug property is ON unless its first byte is this marker (`setprop <flag> 0` is OFF). */
#define XT9_PROP_OFF_CHAR '0'
int xt9kdb_prop_flag_parse(const char* value, char off_char);

/* Active-KDB selection state (ctx+0x04/+0x08), see et9_context.h. CUTOVER only: in DIFF builds
 * the blob owns the context, so these are no-ops there (like xt9kdb_install_nkl). */
#if defined(XT9KDB_CUTOVER)
static inline ET9U32 xt9kdb_ctx_get_selector(const void* ctx) {
    return *(const ET9U32*)((const char*)ctx + ET9_OFF_KDB_SELECTOR);
}
static inline ET9U16 xt9kdb_ctx_get_flags(const void* ctx) {
    return *(const ET9U16*)((const char*)ctx + ET9_OFF_KDB_FLAGS);
}
static inline void xt9kdb_ctx_set_selection(void* ctx, ET9U32 selector, ET9U16 flags) {
    *(ET9U32*)((char*)ctx + ET9_OFF_KDB_SELECTOR) = selector;
    *(ET9U16*)((char*)ctx + ET9_OFF_KDB_FLAGS)    = flags;
}
/* Set or clear one bit of the ctx+0x00 mode bitfield (ET9_OFF_KDB_MODE), leaving the others. */
static inline void xt9kdb_ctx_set_mode_bit(void* ctx, ET9U32 mask, int on) {
    ET9U32* p = (ET9U32*)((char*)ctx + ET9_OFF_KDB_MODE);
    *p = on ? (*p | mask) : (*p & ~mask);
}

/* The view->authored scale lives in the context (ctx+0xfc34/36): the blob's tap resolver and
 * getKeys writer read it there, and each engine context (IME, spellchecker) has its own size.
 * Only CUTOVER writes it; under DIFF the blob's own SetKeyboardSize does. */
static inline void xt9kdb_ctx_set_scale(void* ctx, ET9U16 w, ET9U16 h) {
    if (!ctx) return;
    *(ET9U16*)((char*)ctx + ET9_OFF_KDB_SCALE_W) = w;
    *(ET9U16*)((char*)ctx + ET9_OFF_KDB_SCALE_H) = h;
}

/* The blob's internal loader (0xba264) checks on every keystroke that
 *     *(u32*)(NKL+0x04) == *(u32*)(ctx+0x04)
 * If not, it reloads from its own flat asset index (blind to assets/kdb/<variant>/) and the typed
 * symbol is never committed. That failure is silent, so this logs it. */
static inline void xt9kdb_check_selection_invariant(const void* ctx) {
    const void* nkl = *(const void* const*)((const char*)ctx + ET9_OFF_NKL);
    if (!nkl) return;                       /* nothing installed yet, not a violation */
    ET9U32 in_nkl = *(const ET9U32*)((const char*)nkl + 0x04u);
    ET9U32 in_ctx = xt9kdb_ctx_get_selector(ctx);
    if (in_nkl != in_ctx) {
        XT9KDB_LOGF("SELECTOR MISMATCH — NKL+0x04=0x%04x ctx+0x04=0x%04x; the blob will reload "
                    "its own KDB and typed symbols will be dropped", in_nkl, in_ctx);
    }
}
#define XT9KDB_ASSERT_SELECTION_MATCHES_NKL(ctx) xt9kdb_check_selection_invariant(ctx)
#else
static inline ET9U32 xt9kdb_ctx_get_selector(const void* ctx) { (void)ctx; return 0u; }
static inline ET9U16 xt9kdb_ctx_get_flags(const void* ctx)    { (void)ctx; return 0u; }
static inline void   xt9kdb_ctx_set_selection(void* ctx, ET9U32 s, ET9U16 f) {
    (void)ctx; (void)s; (void)f;            /* DIFF: the blob owns this context */
}
static inline void   xt9kdb_ctx_set_mode_bit(void* ctx, ET9U32 m, int on) {
    (void)ctx; (void)m; (void)on;           /* DIFF: the blob owns this context */
}
static inline void   xt9kdb_ctx_set_scale(void* ctx, ET9U16 w, ET9U16 h) {
    (void)ctx; (void)w; (void)h;            /* DIFF: the blob's own SetKeyboardSize writes it */
}
#define XT9KDB_ASSERT_SELECTION_MATCHES_NKL(ctx) ((void)(ctx))
#endif

/* Loaded key models are stored per engine context (IME and spellchecker both load KDBs here).
 * ctx-aware code resolves via xt9kdb_model_for_ctx(); the global is for ctx-less debug/JNI
 * readers and is re-published from the owning ctx at every TouchStart / ProcessTap. */
extern const ET9KdbLoaded* g_kdb_model;
void xt9kdb_set_model(const ET9KdbLoaded* m);
const ET9KdbLoaded* xt9kdb_model_for_ctx(ET9KDBInfoPtr ctx);   /* NULL if ctx has no loaded model */
/* The context's own model, else the global; NULL only if nothing is loaded. Does not re-publish. */
const ET9KdbLoaded* xt9kdb_model_for_ctx_or_global(ET9KDBInfoPtr ctx);

/* Loaded-KDB content stamp written at NKL+0x0c. The engine does not validate it (SetKdbNum copies it
 * to ctx+0x44; trace records carry it at +0x28), so any deterministic per-KDB value works. Defaults
 * to the uneven KDB's value so the offline trace selftest (no emit_nkl) sees a fixed tag. */
extern ET9U32 g_kdb_checksum;

/* Device-variant KDBs (kdb_state.c). XMLs in assets/kdb/<variant>/ override same-named root ones
 * per (pid,sid). SetKdbNum prefers a registered (variant,pid,sid) node, else the blob-built index.
 * Changing the requested variant ("" / NULL = default) invalidates the active KDB. Register
 * copies the bytes; returns 0, or -1 on bad args, unparsable header, OOM, or a name too long.
 * Generated letter layouts register as "layout:<id>" (ids up to 64 chars), hence the max; long
 * names are refused rather than cut so two names can never collide. */
#define XT9KDB_VARIANT_NAME_MAX 80
int  xt9kdb_register_variant_kdb(const char* variant, const ET9U8* xml, ET9U32 len);
void xt9kdb_set_requested_variant(const char* v);

/* Light scan of a KDB XML header for primaryId/secondaryId (kdb_load_xml.c); 0 on success. */
int xt9kdb_scan_kdb_ids(const ET9U8* xml, ET9U32 len, ET9U32* pid, ET9U32* sid);

/* Authored-space point to key index (-1 if model empty): point-in-rect, then nearest center. */
int xt9kdb_point_to_key(const ET9KdbLoaded* m, float x, float y);

/* Raw SENSOR coordinate to AUTHORED space by pure translation; call before xt9kdb_point_to_key.
 * The offset (SetKeyboardOffset) applies only to the physical keyboard; zero for a VKB. */
void xt9kdb_sensor_to_authored(float sx, float sy, float* ax, float* ay);
/* Same mapping for a one-shot query, with model and scale from `ctx` rather than the globals the
 * gesture path re-publishes (GetKeyPositionByTap / ByStoredTouch). NULL ctx uses the globals. */
void xt9kdb_sensor_to_authored_ctx(ET9KDBInfoPtr ctx, float sx, float sy, float* ax, float* ay);

/* Whether the active KDB is a physical keyboard; the sensor offset applies only when true.
 * Set by SetKdbNum from the selected layout's secondaryId. */
void xt9kdb_set_active_pkb(int isPkb);
int  xt9kdb_active_pkb(void);   /* 1 = physical keyboard active; 0 = on-screen VKB */

/* View->authored scale numerators (SetKeyboardSize; 0,0 = none). The blob stores them at
 * ctx+0xfc34/0xfc36 and maps authored = (view-offset)*authoredDim/scale. */
void xt9kdb_set_scale(int sw, int sh);
/* Copy `ctx`'s scale into the global the ctx-less xt9kdb_sensor_to_authored reads. Called at
 * gesture start so another context's layout sync can't rescale this gesture. No-op under DIFF. */
void xt9kdb_publish_ctx_scale(ET9KDBInfoPtr ctx);

/* idx-th RAW sensor sample of the current gesture (kdb_trace.c); 1 if in range, else 0. */
int xt9kdb_stored_sample(ET9U32 idx, float* x, float* y);
/* Read-only view of the same buffer with timestamps; count may be NULL. */
const ET9KdbSample* xt9kdb_stored_path(ET9U32* count);
/* Monotonic, never-reset count of touch events from a contact other than the one owning the live
 * path (or a TouchStart that abandoned one). Diagnostic only; nothing in-band reports it. */
ET9U32 xt9kdb_foreign_contact_drops(void);
/* TouchEnd always reports success, so these monotonic counters are the only non-logcat signal:
 *   unavailable: no recognizer reached (NULL ctx, or blob symbol unbound). Non-zero = swipe is dead.
 *   failures:    a recognizer returned a non-NONE status that TouchEnd swallowed.
 * Surfaced to Java by Xt9KdbVariant.recognizerUnavailable() / recognizerFailures(). */
ET9U32 xt9kdb_recognizer_unavailable(void);
ET9U32 xt9kdb_recognizer_failures(void);
/* Monotonic count of <key> elements whose keyCodes overflowed ET9_KDB_MAX_KEY_CODES (each also
 * logs). Shipped layouts max out at 10 of 16; non-zero means a new KDB needs the capacity raised. */
ET9U32 xt9kdb_key_code_truncations(void);
/* RAW SENSOR coordinate to key CODE (-1 if none); authored point via axo/ayo (either may be NULL). */
int xt9kdb_key_code_at(float sx, float sy, float* axo, float* ayo);
/* Deposit-time snapshot of the gesture path, stable while the bridge holds the gesture lock through
 * ranking. The only path source the owned ranker may use. */
void xt9kdb_rank_snapshot_store(void);
int  xt9kdb_rank_sample(ET9U32 idx, float* x, float* y);
ET9U32 xt9kdb_rank_seq(void);   /* deposit sequence number; bridge consumes each seq once */
/* Take (consume-once) the deposit-ranked gesture word for `seq`; returns length, 0 if none.
 * Filled synchronously in owned_pst_v2. */
int  xt9kdb_take_gesture_word(ET9U32 seq, char* out, int outsz);
/* zipf*10 of a lowercase ASCII word from kdb_freq_en.h; 0 = absent. The Java typed-word guard uses
 * it to weigh apostrophe insertion (well->we'll stays "well", cant->can't goes through). */
int  xt9kdb_word_zipf10(const char* w);

/* Superseded first-cut decoder (kdb_decode_legacy.c): decodes the accumulated path and feeds AW via
 * the commit hooks. Linked only into the DIFF build and host tests, NOT the shipped libkb.so (which
 * uses owned_pst_v2); never call from XT9KDB_CUTOVER code. decode_scratch runs on a NULL context. */
ET9STATUS xt9kdb_decode_and_commit(ET9KDBInfoPtr ctx);
ET9STATUS xt9kdb_decode_scratch(void);

/* Attach a smart-touch per-key center bias (clamped to +/- half key size). Used by the XML loader. */
void xt9kdb_attach_bias(ET9KdbLoaded* m, ET9U16 keyIdx, ET9S16 biasX, ET9S16 biasY);

/* AW hand-off. Recognized symbols go to AW's ET9WordSymbInfo at ctx+0x52 via the blob export
 * ET9AddExplicitSymb(ctx+0x52, symb), which takes the WordSymbInfo pointer (magic u16 at +0), not
 * the KDB context; ET9AWSelLstBuild reads that buffer. Integration installs a thunk; standalone
 * leaves it NULL (inert) or installs a mock (owned_selftest). */
typedef ET9STATUS (*xt9kdb_commit_fn)(void* ctx, ET9SYMB symb);
extern xt9kdb_commit_fn g_aw_commit;
void      xt9kdb_set_aw_commit(xt9kdb_commit_fn fn);
ET9STATUS xt9kdb_commit_symbol(void* ctx, ET9SYMB symb);

/* Ambiguous commit of a per-position candidate set; integration wires it to
 * ET9AddCustomSymbolSet(ctx+0x52, symbs, freqs, n). Unset: exact commit of the top candidate. */
typedef ET9STATUS (*xt9kdb_commit_ambig_fn)(void* ctx, const ET9SYMB* symbs, const ET9U8* freqs, int count);
extern xt9kdb_commit_ambig_fn g_aw_commit_ambig;
void      xt9kdb_set_aw_commit_ambig(xt9kdb_commit_ambig_fn fn);
ET9STATUS xt9kdb_commit_candidates(void* ctx, float ax, float ay);

/* Nearest LETTER keys to (ax,ay) in authored space, with descending freqs (the smart-touch set). */
int xt9kdb_point_to_candidates(const ET9KdbLoaded* m, float x, float y,
                               ET9SYMB* symbs, ET9U8* freqs, int max);

/* Verified English qwerty_pkb layout (30 keys, 1080x324): differential-harness ground truth and
 * default model. */
extern const ET9KdbLoaded XT9_QWERTY_PKB_EN;

/* Owned gesture decoder (kdb_decode.c). */
typedef struct { float x, y; ET9U32 t; } Xt9DecPt;
typedef struct {
    float   cx, cy;            /* cluster centroid (model space) */
    int     first, count;      /* sample span within the filtered path */
    ET9SYMB symbs[8];          /* ambiguous smart-touch set, [0] = location-cost winner */
    ET9U8   freqs[8];
    ET9U8   nsymb;
} Xt9DecPos;
typedef struct {
    float radx;                /* NKL RADX (max key width) */
    float rady;                /* global RADY */
    int   per_row;             /* 1 = vertical thresholds from the row band, not global RADY */
    int   dwell_min;           /* min samples in a tight cluster to count as a dwell (double
                                  letter); 0 disables (default XT9_DEC_DWELL_MIN = 0) */
    int   feed_minimal;        /* 1 = emit only inflection positions (start/turns/dwells/end), no
                                  interpolated crossings. The owned feed uses 1: positions feed the
                                  candidate generator, whose recall drops with junk positions
                                  (measured 53.0%% dense vs 67.9%% minimal on long words). */
    float wp_dev;              /* waypoint-emission deviation threshold, key units; default
                                  XT9_DEC_WAYPOINT_DEV (0.65); debug.et9.ownwpdev (centi) */
    int   cross_min;           /* key-crossing recovery: in a minimal-feed gap, emit a waypoint for
                                  each distinct key the path dwells in for >= cross_min samples.
                                  Recovers collinear pass-through letters (coffee's f) that wp_dev
                                  misses. 0 = disabled (debug.et9.owncross) */
    int   nrows;
    float row_top[8], row_h[8];/* ascending row bands from the model */
} Xt9DecParams;
void xt9kdb_decode_params_from_model(const ET9KdbLoaded* m, Xt9DecParams* p, int per_row);
int  xt9kdb_decode_gesture(const Xt9DecPt* pts, ET9U32 n, const ET9KdbLoaded* m,
                           const Xt9DecParams* p, Xt9DecPos* out, int max_out);

/* Owned gesture ranker (kdb_rank.c): scores a word's ideal key path against the raw swipe
 * (key-unit distances, per-row y normalization). Lower = better. */
float xt9kdb_rank_score(const ET9KdbLoaded* m, const Xt9DecParams* p,
                        const Xt9DecPt* path, ET9U32 pathN,
                        const char* word, int listIndex);

/* Word-independent half of a score, built once per gesture and shared by its candidates
 * (rank_score = prepare + score_prepared, bit-identical). Stack-only for one gesture: it points at
 * `m` and `p`, and the path buffer it came from is reused, so never cache it across gestures. */
#define XT9_RANK_N 32
#define XT9_RANK_PREPARED 1
typedef struct { float x, y; } Xt9RankPt;
typedef struct {
    const ET9KdbLoaded* m;
    const Xt9DecParams* p;
    Xt9RankPt rp[XT9_RANK_N];   /* the path resampled to XT9_RANK_N points */
    Xt9RankPt first, last;      /* path endpoints, for the anchor term */
    float     plen;             /* path arc length in key units */
    ET9S16    letterKey[26];    /* a..z -> first key index carrying that keyCode, -1 = none */
    int       ok;               /* 0: unscorable (every score returns 1e9) */
} Xt9RankPath;
void  xt9kdb_rank_prepare(Xt9RankPath* rp, const ET9KdbLoaded* m, const Xt9DecParams* p,
                          const Xt9DecPt* path, ET9U32 pathN);
float xt9kdb_rank_score_prepared(const Xt9RankPath* rp, const char* word, int listIndex);

/* Recall-index selfcheck (kdb_trace.c): the live O(N) (first,last)-letter bucket builder must match
 * the reference O(26*26*N) builder byte for byte. corrupt != 0 flips one reference entry (negative
 * control). Returns 0 on match; 1 count, 2 offsets, 3 list; -1 oom. Uses two 128 KB heap buffers. */
int xt9kdb_recall_index_selfcheck(int corrupt, unsigned* out_words);

/* Owned recall alone (kdb_trace.c): up to 8 word indices into the frequency table and their
 * geometry scores, best first, for a path already in authored space. Host tests and tools. */
int xt9kdb_recall_top(const ET9KdbLoaded* m, const Xt9DecParams* p, const Xt9DecPt* sp, ET9U32 n,
                      int* outIdx, float* outGeo);
/* Word `i` of the frequency table (kdb_freq_en.h), NULL out of range. */
const char* xt9kdb_freq_word(int i);

/* Locale gate (kdb_trace.c): 1 when the English-lexicon refinements (owned recall, echo and
 * double heuristics) apply to a gesture in language `langid` (-1 = engine could not say). */
int xt9kdb_lang_is_english(int langgate, int langid);

#endif
