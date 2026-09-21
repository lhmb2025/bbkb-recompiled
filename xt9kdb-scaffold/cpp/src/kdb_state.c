/* kdb_state.c — lifecycle + active-KDB selection (incl. device variants).
 *
 * Owning KDB folds in the device-variant feature for free: SetKdbNum selects by
 * (primaryId, secondaryId) AND an optional device variant — no blob splice needed.
 * Variant layouts (XMLs under assets/kdb/<variant>/) are registered into an owned side
 * registry by the shim (Xt9KdbVariant.apply, from the matched device config's
 * <kdb-variant>); the blob-built index keeps serving the root/default layouts.
 * The selector is the same 16-bit value the shim builds: (secondaryId<<8) | primaryId.
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <stdlib.h>
#include <string.h>

/* secondaryId convention: 6 = PhonePad (physical keyboard, PKB); 1 = default (on-screen, VKB). */
#define ET9_KDB_SID_PKB 6

/* ---- Device-variant KDB registry (owned) ---------------------------------------------------
 * The blob's kdbIndexAssetManager scans assets/kdb/ FLAT (AAssetDir does not recurse), so
 * variant folders (assets/kdb/<device>/) never reach the blob-built index at ctx+0x18 — and its
 * 0x28-byte nodes carry no variant field to hang one on. Variants therefore live in this owned
 * side-registry: the shim reads the XMLs under assets/kdb/<variant>/ and registers the bytes here, keyed
 * by (variant, pid, sid); SetKdbNum consults the registry BEFORE the blob index, so a variant
 * layout overrides the root copy per (pid,sid) and everything else falls back to root. */
typedef struct Xt9VariantNode {
    ET9U32 pid, sid;
    char   variant[24];
    ET9U8* contents;                 /* malloc'd copy, owned by the registry */
    ET9U32 size;
    struct Xt9VariantNode* next;
} Xt9VariantNode;
static Xt9VariantNode* g_variant_head;

/* Requested device variant, set by the shim from the matched device config.
 * Empty => default (blob-index (pid,sid) match), preserving original behaviour. */
static char g_req_variant[24];

/* Active-KDB state (owned). g_active_src points at whichever node (registry or blob index) is
 * loaded, so SetKdbNum reloads only on a real change (selector OR variant), and
 * GetKdbNum/Invalidate work. */
static const void* g_active_src;
static ET9U32      g_active_selector;
static int         g_active_valid;

void xt9kdb_set_requested_variant(const char* v) {
    char nv[sizeof(g_req_variant)] = {0};
    if (v) { strncpy(nv, v, sizeof(nv) - 1); }
    if (strcmp(nv, g_req_variant) != 0) {
        memcpy(g_req_variant, nv, sizeof(nv));
        /* Variant changed: force a re-pick + reload on the next SetKdbNum. */
        g_active_src = 0; g_active_valid = 0;
    }
}

int xt9kdb_register_variant_kdb(const char* variant, const ET9U8* xml, ET9U32 len) {
    ET9U32 pid, sid;
    if (!variant || !variant[0] || !xml || !len) return -1;
    if (xt9kdb_scan_kdb_ids(xml, len, &pid, &sid) != 0) return -1;
    Xt9VariantNode* n;
    for (n = g_variant_head; n; n = n->next)
        if (n->pid == pid && n->sid == sid && strcmp(n->variant, variant) == 0) break;
    ET9U8* copy = (ET9U8*)malloc(len);
    if (!copy) return -1;
    memcpy(copy, xml, len);
    if (!n) {
        n = (Xt9VariantNode*)calloc(1, sizeof(*n));
        if (!n) { free(copy); return -1; }
        strncpy(n->variant, variant, sizeof(n->variant) - 1);
        n->pid = pid; n->sid = sid;
        n->next = g_variant_head; g_variant_head = n;
    } else {
        free(n->contents);
        if (g_active_src == n) { g_active_src = 0; g_active_valid = 0; }  /* content changed */
    }
    n->contents = copy; n->size = len;
    return 0;
}

static Xt9VariantNode* pick_variant(ET9U32 pid, ET9U32 sid) {
    if (!g_req_variant[0]) return 0;
    for (Xt9VariantNode* n = g_variant_head; n; n = n->next)
        if (n->pid == pid && n->sid == sid && strcmp(n->variant, g_req_variant) == 0) return n;
    return 0;
}

/* Recognition mode/state held by the owned module. These are STORED faithfully but not yet consumed
 * by the owned decoder (they drive AW-side scoring, which remains the blob); kept so the API is
 * complete and state survives across queries. */
static ET9U16 g_page;
static ET9U32 g_regionality;
static ET9U8  g_mode_regional, g_mode_discrete, g_mode_ambig, g_mode_multitap;

ET9STATUS XT9KDB(Init)(ET9KDBInfoPtr ctx) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    g_active_src = 0; g_active_selector = 0; g_active_valid = 0;
    g_page = 0; g_regionality = 0;
    g_mode_regional = g_mode_discrete = g_mode_ambig = g_mode_multitap = 0;
    /* The variant registry + requested variant survive Init: the shim registers them once per
     * process, and on-device our Init never runs anyway (the blob's Init is not redirected). */
    return ET9STATUS_NONE;
}

/* First (pid,sid) match in the blob-built index — the root/default layout. Variant
 * discrimination lives entirely in the owned registry (pick_variant); the blob's 0x28-byte
 * nodes have no variant field, so reading one here would be out of bounds. */
static ET9KdbNode* pick_node(ET9KdbIndex* idx, ET9U32 pid, ET9U32 sid) {
    for (ET9KdbNode* n = idx ? idx->head : 0; n; n = n->next)
        if (n->primaryId == pid && n->secondaryId == sid) return n;
    return 0;
}

ET9STATUS XT9KDB(SetKdbNum)(ET9KDBInfoPtr ctx, ET9U32 selector, ET9U32 flags) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    ET9U32 pid = selector & 0xff, sid = (selector >> 8) & 0xff;
    const int is_pkb = (sid == ET9_KDB_SID_PKB);
    /* Owned variant registry first (assets/kdb/<variant>/), blob index (assets/kdb/) as the
     * per-(pid,sid) fallback — so a variant folder only needs the layouts that differ.
     * The device-config <kdb-variant> is a PHYSICAL-keyboard geometry (athena: 1080x450,
     * home-row RADY 180). It must NOT override the on-screen VKB, whose layout + RADY come from
     * the screen: apply the variant only to the PKB; the VKB always uses the root default KDB
     * (owner directive 2026-08-19; the athena variant folder ships PKB layouts only, so this is
     * belt-and-braces against any variant that also carries a vkb/root-named layout). */
    Xt9VariantNode* vn = is_pkb ? pick_variant(pid, sid) : 0;
    ET9KdbNode*     n  = vn ? 0 : pick_node(et9_ctx_kdb_index(ctx), pid, sid);
    if (!vn && !n) return ET9STATUS_KDB_NOT_LOADED; /* 0x15, mirrors original */
    XT9KDB_LOGI("SetKdbNum sel=0x%x pid=%u sid=%u pkb=%d req_variant='%s' variantApplied=%d",
                (unsigned)selector, (unsigned)pid, (unsigned)sid, is_pkb, g_req_variant, vn != 0);
    /* Gate the touch offset to the physical keyboard only (VKB gets zero offset). */
    xt9kdb_set_active_pkb(is_pkb);

    /* Publish the requested selection into the context BEFORE loading, and roll it back if the
     * load fails — the blob's SetKdbNum (0xba654) does exactly this, saving ctx+0x04/+0x08 into
     * w25/w24 and restoring them at 0xba77c on a non-zero loader status.
     *
     * This must happen on EVERY call, not just when we reload: the blob re-reads ctx+0x04 on every
     * keystroke (ProcessKeyBySymbol -> 0xba264) and compares it against NKL+0x04 to decide whether
     * to keep our NKL or reload from its own variant-blind index. Stale or decomposed values here
     * silently starve the word engine. */
    ET9U32 prev_selector = xt9kdb_ctx_get_selector(ctx);
    ET9U16 prev_flags    = xt9kdb_ctx_get_flags(ctx);
    xt9kdb_ctx_set_selection(ctx, selector, (ET9U16)flags);

    /* (Re)load only when the selected node actually changes (covers selector AND variant). The
     * node carries the raw XML bytes; parse via the owned Load_XmlKDB. */
    const void* src = vn ? (const void*)vn : (const void*)n;
    if (src != g_active_src) {
        const ET9U8* bytes = vn ? vn->contents : n->contents;
        ET9U32       size  = vn ? vn->size     : n->size;
        if (bytes && size) {
            ET9STATUS st = XT9KDB(Load_XmlKDB)(ctx, bytes, size);
            if (st != ET9STATUS_NONE) {
                xt9kdb_ctx_set_selection(ctx, prev_selector, prev_flags);  /* rollback, per 0xba77c */
                return st;
            }
        }
        g_active_src      = src;
        g_active_selector = selector;
        g_active_valid    = 1;
    }

    /* The contract the blob's loader enforces on every keystroke. Cheap, and it is the exact
     * invariant whose violation caused the 2026-07 "suggestions frozen while typing" bug. */
    XT9KDB_ASSERT_SELECTION_MATCHES_NKL(ctx);
    return ET9STATUS_NONE;
}

ET9U32    XT9KDB(GetKdbNum)(ET9KDBInfoPtr ctx) { (void)ctx; return g_active_valid ? g_active_selector : 0; }

/* Clear the active marker so the next SetKdbNum re-parses (e.g. after the index/asset changes). */
ET9STATUS XT9KDB(InvalidateLoadedKdbInfo)(ET9KDBInfoPtr ctx) {
    (void)ctx; g_active_src = 0; g_active_valid = 0; return ET9STATUS_NONE;
}

ET9STATUS XT9KDB(SetPageNum)(ET9KDBInfoPtr ctx, ET9U16 p) { (void)ctx; g_page = p; return ET9STATUS_NONE; }
ET9U16    XT9KDB(GetPageNum)(ET9KDBInfoPtr ctx) { (void)ctx; return g_page; }
/* Regional vs discrete is NOT bookkeeping: it is the single bit that decides whether the blob
 * expands each typed symbol into its KEY NEIGHBOURS before scoring. Both setters IGNORE their
 * ET9BOOL argument in the original (0xbad84 / 0xbae1c load w1 for the loader gate and never read
 * the parameter) — they are "switch to regional" / "switch to discrete", not setters.
 * NuanceSDK_init (0x201ec) calls SetDiscreteMode straight after ET9KDB_Init (0xbe97c/0xbe98c
 * stamp ctx[0]=1, i.e. bit3 CLEAR = regional). The per-language pick in the static helper at
 * 0x21de4 then reads the LOW BYTE OF languageIds[0] (0x223a8 `ldrb w8,[x19]`) — the id array just
 * handed to alphaSetLanguage, not the LDB — and takes regional only when it is 0x12. Decoding the
 * 105-entry locale table at 0x217c50, exactly one id matches: ko = 0x112. Regional is therefore
 * KOREAN-ONLY; English (0x109/0x809/0xb09) always branches to SetDiscreteMode. Storing these in
 * file statics and leaving ctx+0x00 alone froze the engine in REGIONAL mode for every language —
 * every typed symbol expanded into its key neighbours before scoring (0x5d864 `tbnz w0,#0x3`
 * skips the expansion loop at 0x5d5a4-0x5d63c when bit 3 is set), so "If" offered "Of".
 * See docs/libnative-documentation.md §10 and the 2026-09-15 device A/B. */
ET9STATUS XT9KDB(SetRegionalMode)(ET9KDBInfoPtr ctx, ET9BOOL b) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    g_mode_regional = b ? 1 : 0;
    xt9kdb_ctx_set_mode_bit(ctx, ET9_KDB_MODE_DISCRETE, 0);   /* 0xbad84: ctx[0] &= ~8 */
    return ET9STATUS_NONE;
}
ET9STATUS XT9KDB(SetDiscreteMode)(ET9KDBInfoPtr ctx, ET9BOOL b) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    g_mode_discrete = b ? 1 : 0;
    xt9kdb_ctx_set_mode_bit(ctx, ET9_KDB_MODE_DISCRETE, 1);   /* 0xbae1c: ctx[0] |= 8 */
    return ET9STATUS_NONE;
}
ET9STATUS XT9KDB(SetAmbigMode)(ET9KDBInfoPtr ctx, ET9BOOL b) { (void)ctx; g_mode_ambig = b ? 1 : 0; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(SetMultiTapMode)(ET9KDBInfoPtr ctx, ET9BOOL b) { (void)ctx; g_mode_multitap = b ? 1 : 0; return ET9STATUS_NONE; }
ET9U32    XT9KDB(GetRegionality)(ET9KDBInfoPtr ctx) { (void)ctx; return g_regionality; }
ET9STATUS XT9KDB(SetRegionality)(ET9KDBInfoPtr ctx, ET9U32 r) { (void)ctx; g_regionality = r; return ET9STATUS_NONE; }
/* Our KDBs carry no version string, so the honest answer is "empty", not "nothing written".
 * Mirrors the blob (0xbecac): clear the out-length BEFORE validating ctx, so a caller that ignores
 * the status still reads a defined 0 rather than whatever was on its stack. */
ET9STATUS XT9KDB(GetKdbVersion)(ET9KDBInfoPtr ctx, ET9SYMB* out, ET9U16 maxLen, ET9U16* outLen) {
    if (outLen) *outLen = 0;
    if (!ctx)   return ET9STATUS_INVALID_MEMORY;
    if (out && maxLen) out[0] = 0;
    return ET9STATUS_NONE;
}
/* A KDB is valid once THIS context has a model with keys loaded (per-ctx slots,
 * kdb_load_xml.c — the spellchecker's context must not vouch for the IME's or vice versa). */
ET9STATUS XT9KDB(Validate)(ET9KDBInfoPtr ctx) {
    return xt9kdb_model_for_ctx(ctx) ? ET9STATUS_NONE : ET9STATUS_KDB_NOT_LOADED;
}
void      XT9KDB(TimeOut)(ET9KDBInfoPtr ctx) { (void)ctx; }
