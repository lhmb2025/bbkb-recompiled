/* kdb_internal.h — non-exported helpers shared between the owned KDB sources. */
#ifndef XT9_KDB_INTERNAL_H
#define XT9_KDB_INTERNAL_H

#include "et9_context.h"

/* Shared loud-failure channel. Used for conditions that are otherwise SILENT — a status the engine
 * swallows, or an invariant the blob enforces without reporting. Both of the 2026-07 cutover bugs
 * (unwired commit sink, mismatched selector) were invisible for exactly that reason. */
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

/* Debug-property sense (L11): a set property is ON unless its first byte is this marker, so
 * `setprop <flag> 0` is OFF and `setprop <flag> 1` is ON for every flag. Host-testable. */
#define XT9_PROP_OFF_CHAR '0'
int xt9kdb_prop_flag_parse(const char* value, char off_char);

/* ---- Active-KDB selection state (ctx+0x04/+0x08) ---------------------------------------------
 * CUTOVER-only: in DIFF builds the context belongs to the blob and we must never write it, so
 * these degrade to no-ops exactly like xt9kdb_install_nkl. See et9_context.h for the field map and
 * docs/libnative-documentation.md §5 for the blob's own SetKdbNum trace. */
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
/* ctx+0x00 recognition-mode bitfield — see ET9_OFF_KDB_MODE. Set/clear one bit, leaving the
 * others alone, exactly as the blob's own setters do. */
static inline void xt9kdb_ctx_set_mode_bit(void* ctx, ET9U32 mask, int on) {
    ET9U32* p = (ET9U32*)((char*)ctx + ET9_OFF_KDB_MODE);
    *p = on ? (*p | mask) : (*p & ~mask);
}

/* ---- L2/L3: the view->authored SCALE lives with the CONTEXT ----------------------------------
 * The blob stores SetKeyboardSize's two numerators at ctx+0xfc34/36, and both its tap resolver and
 * its getKeys writer read them back from there. Ours used to keep them in a process-global only,
 * which is wrong twice over: two engine contexts exist (primary IME + spellchecker) and each syncs
 * its own keyboard size, and our own GetKeyPositions was already READING the context — so after a
 * VKB setKeyboardSize the recognition path scaled correctly while getKeys kept handing out
 * unprojected authored rects.
 *
 * CUTOVER is the only mode that may write: there we own SetKeyboardSize, so the context field is
 * ours to maintain and nothing else writes it. Under DIFF the blob owns the context AND its own
 * SetKeyboardSize, so it writes the field itself and we must not — the reader below is then still
 * correct, it is just reading the blob's value instead of ours. Same discipline (and the same
 * reason) as xt9kdb_ctx_set_selection above. */
static inline void xt9kdb_ctx_set_scale(void* ctx, ET9U16 w, ET9U16 h) {
    if (!ctx) return;
    *(ET9U16*)((char*)ctx + ET9_OFF_KDB_SCALE_W) = w;
    *(ET9U16*)((char*)ctx + ET9_OFF_KDB_SCALE_H) = h;
}

/* The one invariant the blob's internal loader (0xba264) enforces on every keystroke:
 *     *(u32*)(NKL+0x04) == *(u32*)(ctx+0x04)
 * When it holds, the loader early-outs and keeps our NKL. When it does not, the loader reloads
 * from the blob's own flat asset index — which cannot see assets/kdb/<variant>/ — and the typed
 * symbol is never committed to the word engine. Failure is otherwise completely silent, so this
 * reports rather than merely aborting. */
static inline void xt9kdb_check_selection_invariant(const void* ctx) {
    const void* nkl = *(const void* const*)((const char*)ctx + ET9_OFF_NKL);
    if (!nkl) return;                       /* nothing installed yet — not a violation */
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

/* Currently-loaded key model (set by Load_XmlKDB / SetKdbNum). Defaults to the
 * verified English qwerty_pkb fixture during bring-up.
 *
 * OWNERSHIP (2026-08-16): models are stored PER ENGINE CONTEXT (the primary IME and the
 * spellchecker's secondary engine both load KDBs through this module — a single global
 * let whichever loaded last clobber the other's geometry). ctx-aware code must resolve
 * via xt9kdb_model_for_ctx(); the legacy global remains for ctx-less debug/JNI readers
 * and is re-published from the owning ctx at every gesture TouchStart / ProcessTap. */
extern const ET9KdbLoaded* g_kdb_model;
void xt9kdb_set_model(const ET9KdbLoaded* m);
const ET9KdbLoaded* xt9kdb_model_for_ctx(ET9KDBInfoPtr ctx);   /* NULL if ctx has no loaded model */
/* The model a ctx-aware QUERY should answer with: the context's own, else the legacy global.
 * Never NULL unless nothing is loaded anywhere. Does not re-publish (see GetKeyPositions). */
const ET9KdbLoaded* xt9kdb_model_for_ctx_or_global(ET9KDBInfoPtr ctx);

/* Active loaded-KDB content stamp = NKL+0x0c. RE-confirmed the engine does NOT validate it: SetKdbNum
 * only copies NKL+0x0c -> ctx+0x44, and it flows into each trace record's +0x28. So the exact blob hash
 * is unnecessary — a deterministic per-KDB value suffices. emit_nkl sets this to what it wrote at
 * NKL+0x0c; the trace-record writer stamps it into +0x28. Defaults to the uneven KDB's value so the
 * offline trace selftest (which never calls emit_nkl) still sees a fixed tag. */
extern ET9U32 g_kdb_checksum;

/* ---- Device-variant KDBs (kdb_state.c) -------------------------------------------------------
 * XMLs in assets/kdb/<variant>/ override the same-named root assets/kdb/ ones per (pid,sid). The shim registers
 * each variant file's raw bytes (copied) and then requests the variant; SetKdbNum prefers a
 * registered (variant,pid,sid) node and falls back to the blob-built index (the root layouts).
 * Setting a different variant (or "" / NULL for default) invalidates the active KDB so the next
 * SetKdbNum reloads. register returns 0 on success, -1 on bad args / unparsable header / OOM. */
int  xt9kdb_register_variant_kdb(const char* variant, const ET9U8* xml, ET9U32 len);
void xt9kdb_set_requested_variant(const char* v);

/* Light scan of a KDB XML header for primaryId/secondaryId (kdb_load_xml.c); 0 on success. */
int xt9kdb_scan_kdb_ids(const ET9U8* xml, ET9U32 len, ET9U32* pid, ET9U32* sid);

/* Resolve a touch point (AUTHORED space) to a key index (-1 if model empty). Point-in-rect
 * first, then nearest key center. (Smart-touch bias is a later refinement.) */
int xt9kdb_point_to_key(const ET9KdbLoaded* m, float x, float y);

/* Map a raw SENSOR coordinate (the physical capacitive space) to AUTHORED space via pure
 * translation (no scaling). Tap and trace MUST call this before xt9kdb_point_to_key. The
 * translation is the per-device touch offset (SetKeyboardOffset) and applies ONLY to the
 * physical keyboard (PKB); for a VKB the offset is forced to zero. */
void xt9kdb_sensor_to_authored(float sx, float sy, float* ax, float* ay);
/* L4: the same mapping for a ONE-SHOT QUERY on a handle — model and scale both from `ctx` rather
 * than from the globals the gesture path re-publishes at TouchStart. Used by
 * GetKeyPositionByTap / ByStoredTouch, which otherwise answered a query on the spellchecker's
 * handle with the IME's keyboard. NULL ctx falls back to the globals. */
void xt9kdb_sensor_to_authored_ctx(ET9KDBInfoPtr ctx, float sx, float sy, float* ax, float* ay);

/* Tell geometry whether the active KDB is a physical keyboard (PKB). The sensor->authored
 * offset is applied only when this is true; set false for VKB so the calibration never leaks
 * onto the on-screen keyboard. Set by SetKdbNum from the selected layout's secondaryId. */
void xt9kdb_set_active_pkb(int isPkb);
int  xt9kdb_active_pkb(void);   /* 1 = physical keyboard active; 0 = on-screen VKB */

/* Set the view->authored scale numerators (SetKeyboardSize; 0,0 = no scaling). RE-confirmed: the blob
 * stores these at ctx+0xfc34/0xfc36 and scales authored = (view-offset)*authoredDim/scale. */
void xt9kdb_set_scale(int sw, int sh);
/* L2: copy `ctx`'s scale (ctx+0xfc34/36 — the authoritative per-context copy) into the process
 * global that the ctx-less xt9kdb_sensor_to_authored reads. Called at gesture start, exactly like
 * the model re-publish, so a layout sync on ANOTHER engine context (the spellchecker's) can never
 * leave the gesturing context's touches scaled by someone else's keyboard. CUTOVER only: under
 * DIFF the blob owns SetKeyboardSize and the global is the only scale we ever set, so this is a
 * no-op there rather than a clobber. */
void xt9kdb_publish_ctx_scale(ET9KDBInfoPtr ctx);

/* Read the idx-th RAW sensor sample of the current gesture (owned by kdb_trace.c). Returns 1 and
 * fills the x and y out-params if idx is in range, else 0. Used by GetKeyPositionByStoredTouch. */
int xt9kdb_stored_sample(ET9U32 idx, float* x, float* y);
/* Read-only view of the same buffer, timestamps included: returns the base pointer and writes the
 * sample count through `count` (may be NULL). Owned by kdb_trace.c. */
const ET9KdbSample* xt9kdb_stored_path(ET9U32* count);
/* L1 (audit docs/2026-09_kdb-touch-abi_audit.md): monotonic count of touch events that did NOT
 * belong to the contact owning the live path — a TouchMove/TouchEnd/TouchCancel from another
 * pointer, or a TouchStart that abandoned an in-flight one. Diagnostic: the shipped path has no
 * in-band channel for it (L10), so this is what a TRACEPT dump or a test correlates against.
 * Never reset; it counts for the life of the process. */
ET9U32 xt9kdb_foreign_contact_drops(void);
/* L10: TouchEnd always reports success (shim parity, §1.3), so a recognizer that could not run is
 * otherwise visible only in logcat — which is how a KEY2 build shipped with swipe dead for the
 * life of every process. Both counters are monotonic and never reset.
 *   unavailable: process_trace_impl reached no recognizer at all (NULL ctx, or the blob symbol
 *                did not bind). Non-zero means swipe is DEAD, not merely inaccurate.
 *   failures:    a recognizer ran and returned a non-NONE status that TouchEnd then swallowed.
 * Surfaced to Java by Xt9KdbVariant.recognizerUnavailable() / recognizerFailures(). */
ET9U32 xt9kdb_recognizer_unavailable(void);
ET9U32 xt9kdb_recognizer_failures(void);
/* L13: monotonic count of <key> elements whose keyCodes list did not fit ET9_KDB_MAX_KEY_CODES,
 * i.e. alternates were dropped. Every truncation also logs to XT9KDB. Never reset. A shipped
 * layout should never reach this (the widest is 10 codes against a capacity of 16); a non-zero
 * value means a new KDB needs the capacity raised, not that recognition has failed. */
ET9U32 xt9kdb_key_code_truncations(void);
/* Resolve a RAW SENSOR coordinate to its key CODE (-1 if none), also returning the authored-space
 * mapping through axo/ayo (either may be NULL). sensor->authored + point_to_key in one step. */
int xt9kdb_key_code_at(float sx, float sy, float* axo, float* ayo);
/* deposit-time snapshot of the gesture path, stable while the bridge holds the gesture lock
 * through ranking (kdb_trace.c §8.7.10) — the ONLY path source the owned ranker may use */
void xt9kdb_rank_snapshot_store(void);
int  xt9kdb_rank_sample(ET9U32 idx, float* x, float* y);
ET9U32 xt9kdb_rank_seq(void);   /* deposit sequence number; bridge consumes each seq once */
/* W1 (§8.7.14): take the deposit-ranked gesture word for `seq` (consume-once). Returns length
 * written (0 if none for this seq / not enabled). Filled synchronously in owned_pst_v2. */
int  xt9kdb_take_gesture_word(ET9U32 seq, char* out, int outsz);

/* Decode the accumulated gesture path + feed AW via the commit hooks (no result-block write).
 * xt9kdb_decode_scratch() runs it on a NULL context — the W3a scratch/DIFF path: TouchStart/TouchMove
 * accumulate points, then this commits the per-position candidate sets to the installed sinks.
 *
 * These are the SUPERSEDED first-cut decoder (kdb_decode_legacy.c) and are NOT LINKED INTO THE
 * SHIPPED libkb.so — only into the DIFF build and the host test suite. The shipped decode path is
 * owned_pst_v2 in kdb_trace.c. Do not call these from anything that compiles under XT9KDB_CUTOVER. */
ET9STATUS xt9kdb_decode_and_commit(ET9KDBInfoPtr ctx);
ET9STATUS xt9kdb_decode_scratch(void);

/* Attach a smart-touch per-key center bias (clamped to +/- half key size), reversed from
 * Load_AttachBias. Called by the XML loader for keys that carry bias data. */
void xt9kdb_attach_bias(ET9KdbLoaded* m, ET9U16 keyIdx, ET9S16 biasX, ET9S16 biasY);

/* ---- AW interop boundary (the symbol hand-off to ET9AW) -------------------------------------
 * Recognized symbols are committed to AW's ET9WordSymbInfo (at ctx+0x52) via the blob export
 * ET9AddExplicitSymb(ctx+0x52, symb) — verified: that function takes the WordSymbInfo pointer
 * directly (magic u16 at offset 0), not the KDB context. ET9AWSelLstBuild then reads that buffer to
 * build candidates. The buffer is AW's (the blob's), so the owned KDB does NOT reimplement it — it
 * decodes and calls this commit hook. Integration sets the hook to a thunk over ET9AddExplicitSymb
 * (passing ctx+0x52); standalone leaves it NULL (inert) or installs a mock (see owned_selftest). */
typedef ET9STATUS (*xt9kdb_commit_fn)(void* ctx, ET9SYMB symb);
extern xt9kdb_commit_fn g_aw_commit;
void      xt9kdb_set_aw_commit(xt9kdb_commit_fn fn);
ET9STATUS xt9kdb_commit_symbol(void* ctx, ET9SYMB symb);

/* AMBIGUOUS commit: the owned KDB computes a per-position candidate set (xt9kdb_point_to_candidates)
 * and commits it; integration wires this hook to ET9AddCustomSymbolSet(ctx+0x52, symbs, freqs, n).
 * If unset, xt9kdb_commit_candidates falls back to the exact commit of the top candidate. */
typedef ET9STATUS (*xt9kdb_commit_ambig_fn)(void* ctx, const ET9SYMB* symbs, const ET9U8* freqs, int count);
extern xt9kdb_commit_ambig_fn g_aw_commit_ambig;
void      xt9kdb_set_aw_commit_ambig(xt9kdb_commit_ambig_fn fn);
ET9STATUS xt9kdb_commit_candidates(void* ctx, float ax, float ay);

/* Nearest LETTER keys to (ax,ay) in authored space, with descending freqs (the smart-touch set). */
int xt9kdb_point_to_candidates(const ET9KdbLoaded* m, float x, float y,
                               ET9SYMB* symbs, ET9U8* freqs, int max);

/* The on-device-verified English qwerty_pkb layout (30 keys, 1080x324). Ground
 * truth for the differential harness and the bring-up default model. */
extern const ET9KdbLoaded XT9_QWERTY_PKB_EN;

/* ---- owned gesture decoder (kdb_decode.c) — see TRACE_RESULT_REGION.md + remediation §8.7 ---- */
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
    float rady;                /* global RADY (blob parity mode) */
    int   per_row;             /* 1 = vertical thresholds from the row band, not global RADY */
    int   dwell_min;           /* min samples in a tight kept cluster = dwell (double letter);
                                  0 disables; default = XT9_DEC_DWELL_MIN (0 — A/B lever) */
    int   feed_minimal;        /* 1 = emit ONLY inflection positions (start/turns/dwells/end),
                                  no interpolated crossings. The blob-parity default is 0; the
                                  owned feed uses 1 — positions serve the candidate GENERATOR
                                  now (the ranker reads the raw path), and generator recall
                                  degrades with junk positions (§8.7.7: done absent, hello
                                  pools polluted on over-segmented traces). W4 re-test (2026-08-16,
                                  180 long-word live pools): dense feed 53.0%% recall vs minimal
                                  67.9%% — holds under the W3 ranker too. */
    float wp_dev;              /* waypoint-emission deviation threshold, key units; default
                                  XT9_DEC_WAYPOINT_DEV (0.65). W4 live-sweep lever
                                  (debug.et9.ownwpdev, centi). */
    int   cross_min;           /* KEY-CROSSING recovery (W6 sitting-1 decode robustness): in a
                                  minimal-feed gap, emit a waypoint for each distinct key cell the
                                  path dwells in for >= cross_min samples (key != flanking
                                  positions). Recovers COLLINEAR pass-through letters (coffee's f,
                                  o-f-e straight -> ~0 chord deviation, missed by wp_dev) without the
                                  dense feed's fixed-distance over-segmentation. 0 = DISABLED
                                  (debug.et9.owncross, A/B lever). */
    int   nrows;
    float row_top[8], row_h[8];/* ascending row bands from the model */
} Xt9DecParams;
void xt9kdb_decode_params_from_model(const ET9KdbLoaded* m, Xt9DecParams* p, int per_row);
int  xt9kdb_decode_gesture(const Xt9DecPt* pts, ET9U32 n, const ET9KdbLoaded* m,
                           const Xt9DecParams* p, Xt9DecPos* out, int max_out);

/* ---- owned gesture ranker (kdb_rank.c) — §8.7.6: score a candidate word's ideal key-path
 * against the raw swipe (key-unit distances, per-row y normalization). Lower = better. */
float xt9kdb_rank_score(const ET9KdbLoaded* m, const Xt9DecParams* p,
                        const Xt9DecPt* path, ET9U32 pathN,
                        const char* word, int listIndex);

/* ---- owned recall index selfcheck (kdb_trace.c): the live O(N) (first,last)-letter bucket
 * builder must reproduce the reference O(26*26*N) builder byte for byte. corrupt != 0 flips one
 * reference entry (negative control). Returns 0 on match; 1 count, 2 offsets, 3 list; -1 oom.
 * Host tests + debug builds only in practice; costs two 128 KB heap scratch buffers. */
int xt9kdb_recall_index_selfcheck(int corrupt, unsigned* out_words);

#endif
