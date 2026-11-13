/* kdb_state.c: lifecycle and active-KDB selection. SetKdbNum selects by (primaryId, secondaryId)
 * plus an optional device variant registered by the shim (Xt9KdbVariant.apply); the blob-built
 * index serves the root layouts. Selector = (secondaryId<<8) | primaryId. */
#include "et9kdb.h"
#include "et9_context.h"
#include "kdb_internal.h"
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

/* secondaryId convention: 6 = PhonePad (physical keyboard, PKB); 1 = default (on-screen, VKB). */
#define ET9_KDB_SID_PKB 6

/* Device-variant registry. The blob scans assets/kdb/ flat (AAssetDir does not recurse) into the
 * index at ctx+0x18, whose 0x28-byte nodes have no variant field. Keyed by (variant, pid, sid) and
 * consulted before the blob index, so a variant overrides root per (pid,sid). */
typedef struct Xt9VariantNode {
    ET9U32 pid, sid;
    char   variant[XT9KDB_VARIANT_NAME_MAX];
    ET9U8* contents;                 /* malloc'd copy, owned by the registry */
    ET9U32 size;
    struct Xt9VariantNode* next;
} Xt9VariantNode;
static Xt9VariantNode* g_variant_head;

/* Requested device variant from the matched device config; empty = blob-index match. */
static char g_req_variant[XT9KDB_VARIANT_NAME_MAX];

/* g_active_src = the loaded node (registry or blob index); SetKdbNum reloads only when it changes. */
static const void* g_active_src;
static ET9U32      g_active_selector;
static int         g_active_valid;

/* Guards the registry, requested variant and active marker: the shim re-registers variants
 * mid-session (freeing bytes) while the engine thread parses them in SetKdbNum. Never held across
 * a call back into the shim. */
static pthread_mutex_t g_variant_mu = PTHREAD_MUTEX_INITIALIZER;

void xt9kdb_set_requested_variant(const char* v) {
    char nv[sizeof(g_req_variant)] = {0};
    if (v) { strncpy(nv, v, sizeof(nv) - 1); }
    pthread_mutex_lock(&g_variant_mu);
    if (strcmp(nv, g_req_variant) != 0) {
        memcpy(g_req_variant, nv, sizeof(nv));
        /* Variant changed: force a re-pick + reload on the next SetKdbNum. */
        g_active_src = 0; g_active_valid = 0;
    }
    pthread_mutex_unlock(&g_variant_mu);
}

int xt9kdb_register_variant_kdb(const char* variant, const ET9U8* xml, ET9U32 len) {
    ET9U32 pid, sid;
    if (!variant || !variant[0] || !xml || !len) return -1;
    if (strlen(variant) >= XT9KDB_VARIANT_NAME_MAX) return -1;   /* refused, never cut short */
    if (xt9kdb_scan_kdb_ids(xml, len, &pid, &sid) != 0) return -1;
    ET9U8* copy = (ET9U8*)malloc(len);
    if (!copy) return -1;
    memcpy(copy, xml, len);
    pthread_mutex_lock(&g_variant_mu);
    Xt9VariantNode* n;
    for (n = g_variant_head; n; n = n->next)
        if (n->pid == pid && n->sid == sid && strcmp(n->variant, variant) == 0) break;
    if (!n) {
        n = (Xt9VariantNode*)calloc(1, sizeof(*n));
        if (!n) { pthread_mutex_unlock(&g_variant_mu); free(copy); return -1; }
        strncpy(n->variant, variant, sizeof(n->variant) - 1);
        n->pid = pid; n->sid = sid;
        n->next = g_variant_head; g_variant_head = n;
    } else {
        free(n->contents);
        if (g_active_src == n) { g_active_src = 0; g_active_valid = 0; }  /* content changed */
    }
    n->contents = copy; n->size = len;
    pthread_mutex_unlock(&g_variant_mu);
    return 0;
}

static Xt9VariantNode* pick_variant(ET9U32 pid, ET9U32 sid) {
    if (!g_req_variant[0]) return 0;
    for (Xt9VariantNode* n = g_variant_head; n; n = n->next)
        if (n->pid == pid && n->sid == sid && strcmp(n->variant, g_req_variant) == 0) return n;
    return 0;
}

/* Letter-layout variants ("layout:", LetterLayoutKdb.VARIANT_PREFIX) may carry on-screen KDBs the
 * VKB must load; device variants describe keypad geometry only and never reach the VKB. */
static int is_letter_layout_variant(const char* v) {
    return strncmp(v, "layout:", 7) == 0;
}

/* Stored for API completeness; not consumed by the owned decoder (AW scoring stays in the blob). */
static ET9U16 g_page;
static ET9U32 g_regionality;
static ET9U8  g_mode_regional, g_mode_discrete, g_mode_ambig, g_mode_multitap;

ET9STATUS XT9KDB(Init)(ET9KDBInfoPtr ctx) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    pthread_mutex_lock(&g_variant_mu);
    g_active_src = 0; g_active_selector = 0; g_active_valid = 0;
    pthread_mutex_unlock(&g_variant_mu);
    g_page = 0; g_regionality = 0;
    g_mode_regional = g_mode_discrete = g_mode_ambig = g_mode_multitap = 0;
    /* Registry and requested variant survive Init (registered once per process). */
    return ET9STATUS_NONE;
}

/* First (pid,sid) match in the blob index (root layout). Its 0x28-byte nodes have no variant field. */
static ET9KdbNode* pick_node(ET9KdbIndex* idx, ET9U32 pid, ET9U32 sid) {
    for (ET9KdbNode* n = idx ? idx->head : 0; n; n = n->next)
        if (n->primaryId == pid && n->secondaryId == sid) return n;
    return 0;
}

static ET9STATUS set_kdb_num_locked(ET9KDBInfoPtr ctx, ET9U32 selector, ET9U32 flags);

ET9STATUS XT9KDB(SetKdbNum)(ET9KDBInfoPtr ctx, ET9U32 selector, ET9U32 flags) {
    if (!ctx) return ET9STATUS_INVALID_MEMORY;
    /* Held from the pick through the parse: the node's bytes must outlive Load_XmlKDB. */
    pthread_mutex_lock(&g_variant_mu);
    ET9STATUS st = set_kdb_num_locked(ctx, selector, flags);
    pthread_mutex_unlock(&g_variant_mu);
    return st;
}

static ET9STATUS set_kdb_num_locked(ET9KDBInfoPtr ctx, ET9U32 selector, ET9U32 flags) {
    ET9U32 pid = selector & 0xff, sid = (selector >> 8) & 0xff;
    const int is_pkb = (sid == ET9_KDB_SID_PKB);
    /* Registry first, blob index as the per-(pid,sid) fallback. A device <kdb-variant> is keypad
     * geometry (athena 1080x450, RADY 180), so it applies only to the PKB; the VKB uses root
     * unless the variant is a letter layout. */
    Xt9VariantNode* vn = (is_pkb || is_letter_layout_variant(g_req_variant)) ? pick_variant(pid, sid) : 0;
    ET9KdbNode*     n  = vn ? 0 : pick_node(et9_ctx_kdb_index(ctx), pid, sid);
    if (!vn && !n) return ET9STATUS_KDB_NOT_LOADED; /* 0x15 */
    XT9KDB_LOGD("SetKdbNum sel=0x%x pid=%u sid=%u pkb=%d req_variant='%s' variantApplied=%d",
                (unsigned)selector, (unsigned)pid, (unsigned)sid, is_pkb, g_req_variant, vn != 0);
    /* touch offset applies to the PKB only */
    xt9kdb_set_active_pkb(is_pkb);

    /* Publish the selection into ctx+0x04/+0x08 before loading, roll back on failure (blob 0xba654,
     * restore at 0xba77c). Do it on every call: each keystroke (0xba264) compares ctx+0x04 with
     * NKL+0x04 and reloads from the variant-blind index on mismatch, starving the word engine. */
    ET9U32 prev_selector = xt9kdb_ctx_get_selector(ctx);
    ET9U16 prev_flags    = xt9kdb_ctx_get_flags(ctx);
    xt9kdb_ctx_set_selection(ctx, selector, (ET9U16)flags);

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

    /* the invariant the blob checks on every keystroke (violation froze suggestions) */
    XT9KDB_ASSERT_SELECTION_MATCHES_NKL(ctx);
    return ET9STATUS_NONE;
}

ET9U32    XT9KDB(GetKdbNum)(ET9KDBInfoPtr ctx) {
    (void)ctx;
    pthread_mutex_lock(&g_variant_mu);
    ET9U32 num = g_active_valid ? g_active_selector : 0;
    pthread_mutex_unlock(&g_variant_mu);
    return num;
}

ET9STATUS XT9KDB(InvalidateLoadedKdbInfo)(ET9KDBInfoPtr ctx) {
    (void)ctx;
    pthread_mutex_lock(&g_variant_mu);
    g_active_src = 0; g_active_valid = 0;
    pthread_mutex_unlock(&g_variant_mu);
    return ET9STATUS_NONE;
}

ET9STATUS XT9KDB(SetPageNum)(ET9KDBInfoPtr ctx, ET9U16 p) { (void)ctx; g_page = p; return ET9STATUS_NONE; }
ET9U16    XT9KDB(GetPageNum)(ET9KDBInfoPtr ctx) { (void)ctx; return g_page; }
/* ctx[0] bit 3 decides whether typed symbols expand into key neighbours before scoring (clear =
 * regional; 0x5d864 skips the 0x5d5a4 loop when set). Both setters ignore the ET9BOOL (0xbad84 /
 * 0xbae1c). Init stamps ctx[0]=1 (regional); only ko (languageIds[0] low byte 0x12) stays regional. */
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
/* No version string: empty. Clear the out-length before validating ctx (blob 0xbecac) so a caller
 * ignoring the status reads 0. */
ET9STATUS XT9KDB(GetKdbVersion)(ET9KDBInfoPtr ctx, ET9SYMB* out, ET9U16 maxLen, ET9U16* outLen) {
    if (outLen) *outLen = 0;
    if (!ctx)   return ET9STATUS_INVALID_MEMORY;
    if (out && maxLen) out[0] = 0;
    return ET9STATUS_NONE;
}
/* Valid once this context has a model loaded (per-ctx: the spell checker's must not vouch for the IME's). */
ET9STATUS XT9KDB(Validate)(ET9KDBInfoPtr ctx) {
    return xt9kdb_model_for_ctx(ctx) ? ET9STATUS_NONE : ET9STATUS_KDB_NOT_LOADED;
}
void      XT9KDB(TimeOut)(ET9KDBInfoPtr ctx) { (void)ctx; }
