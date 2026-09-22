/* kdb_load_xml.c — parse an asset KDB (XML) into the in-memory key model.
 *
 * Owns Load_XmlKDB (@0xc39d0) + the Load_* builder family. A hand-rolled attribute
 * scanner (no libxml — same approach as the blob's kdbIndexAssetManager) reads:
 *   <keyboard primaryId secondaryId defaultLayoutWidth defaultLayoutHeight ...>
 *   <key keyType keyLabel|keyCodes|keyName keyTop keyLeft keyWidth keyHeight [biasX biasY] />
 * and builds an ET9KdbLoaded (key rects + codes + per-key smart-touch bias). Rects are
 * INCLUSIVE, matching the blob's getKeys: right=left+width-1, bottom=top+height-1,
 * center=(left+(width-1)/2, top+(height-1)/2) — verified equal to the device's XT9KEYS output.
 *
 * Memory: a static builder (single active KDB at a time, as on-device).
 */
#include "et9kdb.h"
#include "et9_context.h"
#include "et9_nkl.h"
#include "kdb_internal.h"
#include <string.h>
#include <pthread.h>

/* 80, the blob's own ceiling (Load_AddKey @0xb95d4: `cmp w2,#0x4f` -> status 0x38). Ours was 64
 * (L12): a 65..80-key layout the blob accepts loaded here silently truncated. */
#define XT9_MAX_KEYS 80
/* The blob's status for "too many keys" (audit §5 L12 / L13 census). */
#define ET9STATUS_KDB_TOO_MANY_KEYS ((ET9STATUS)0x38)

/* Per-engine-context model slots (2026-08-16). The primary IME engine AND the
 * spellchecker's secondary engine both load KDBs through this module; the old single
 * static buffer let whichever loaded LAST clobber the other's geometry process-wide.
 * Latent on the KEY2 only because both contexts happen to load the same XML there — on
 * the W2 emulator the spellchecker's qwerty_vkb silently replaced the gesture path's
 * qwerty_pkb and row-shifted every owned decode. Each ctx now owns a slot; ctx-aware
 * consumers resolve via xt9kdb_model_for_ctx(), and the touch path re-publishes its own
 * ctx's model to the legacy global (used by ctx-less debug/JNI readers) at gesture start,
 * so a foreign load can never stick across a gesture. */
#define XT9_MODEL_SLOTS 4
typedef struct {
    ET9KDBInfoPtr ctx;                 /* slot owner; NULL = free */
    ET9KdbKey     keys[XT9_MAX_KEYS];
    ET9KdbLoaded  model;
    ET9U16        count;
} Xt9ModelSlot;
static Xt9ModelSlot g_slots[XT9_MODEL_SLOTS];
static pthread_mutex_t g_slots_mu = PTHREAD_MUTEX_INITIALIZER;

static Xt9ModelSlot* slot_for(ET9KDBInfoPtr ctx) {
    Xt9ModelSlot* s = 0;
    pthread_mutex_lock(&g_slots_mu);
    for (int i = 0; i < XT9_MODEL_SLOTS && !s; i++)
        if (g_slots[i].ctx == ctx) s = &g_slots[i];
    for (int i = 0; i < XT9_MODEL_SLOTS && !s; i++)
        if (!g_slots[i].ctx) { s = &g_slots[i]; s->ctx = ctx; }
    if (!s) {   /* > XT9_MODEL_SLOTS live engine contexts: evict slot 0 (never expected) */
        XT9KDB_LOGF("model slots exhausted — evicting slot 0 for ctx %p", (void*)ctx);
        s = &g_slots[0]; s->ctx = ctx; s->count = 0; memset(&s->model, 0, sizeof(s->model));
    }
    pthread_mutex_unlock(&g_slots_mu);
    return s;
}

const ET9KdbLoaded* xt9kdb_model_for_ctx(ET9KDBInfoPtr ctx) {
    const ET9KdbLoaded* m = 0;
    pthread_mutex_lock(&g_slots_mu);
    for (int i = 0; i < XT9_MODEL_SLOTS; i++)
        if (g_slots[i].ctx == ctx && g_slots[i].model.keyCount) { m = &g_slots[i].model; break; }
    pthread_mutex_unlock(&g_slots_mu);
    return m;
}

/* Builder header props passed to Load_SetProperties. */
typedef struct { ET9U32 pid, sid; ET9U16 w, h, radiusY; } Xt9KdbProps;

ET9STATUS XT9KDB(Load_Reset)(ET9KDBInfoPtr ctx) {
    Xt9ModelSlot* s = slot_for(ctx);
    s->count = 0;
    memset(&s->model, 0, sizeof(s->model));
    s->model.authoredWidth  = ET9_KDB_AUTHORED_W;   /* fallbacks; overwritten by SetProperties */
    s->model.authoredHeight = ET9_KDB_AUTHORED_H;
    s->model.keys = s->keys;
    return ET9STATUS_NONE;
}

ET9STATUS XT9KDB(Load_SetProperties)(ET9KDBInfoPtr ctx, void* p) {
    if (!p) return ET9STATUS_INVALID_MEMORY;
    Xt9ModelSlot* s = slot_for(ctx);
    Xt9KdbProps* pr = (Xt9KdbProps*)p;
    s->model.primaryId   = pr->pid;
    s->model.secondaryId = pr->sid;
    if (pr->w) s->model.authoredWidth  = pr->w;
    if (pr->h) s->model.authoredHeight = pr->h;
    s->model.authoredRadiusY = pr->radiusY;   /* 0 = unset -> NKL RADY = home-row height */
    return ET9STATUS_NONE;
}

ET9STATUS XT9KDB(Load_AddKey)(ET9KDBInfoPtr ctx, void* k) {
    if (!k) return ET9STATUS_INVALID_MEMORY;
    Xt9ModelSlot* s = slot_for(ctx);
    if (s->count >= XT9_MAX_KEYS) return ET9STATUS_KDB_TOO_MANY_KEYS;   /* table full: 0x38 like the blob */
    s->keys[s->count++] = *(ET9KdbKey*)k;
    return ET9STATUS_NONE;
}

/* ---- tiny scanners -------------------------------------------------------- */
static const char* find_sub(const char* p, const char* end, const char* needle) {
    size_t n = strlen(needle);
    if ((size_t)(end - p) < n) return 0;
    for (const char* q = p; q + n <= end; q++)
        if (memcmp(q, needle, n) == 0) return q;
    return 0;
}
/* value of name="..." within [p,end); returns value start + sets *vlen (-1 if absent). */
static const char* attr_val(const char* p, const char* end, const char* name, int* vlen) {
    char pat[40];
    size_t nl = strlen(name);
    if (nl + 3 >= sizeof(pat)) { *vlen = -1; return 0; }
    memcpy(pat, name, nl); pat[nl] = '='; pat[nl + 1] = '"'; pat[nl + 2] = 0;
    const char* a = find_sub(p, end, pat);
    if (!a) { *vlen = -1; return 0; }
    a += nl + 2;
    const char* q = a;
    while (q < end && *q != '"') q++;
    *vlen = (int)(q - a);
    return a;
}
/* non-negative int, stops at non-digit (so "108dp" -> 108). */
static int parse_int(const char* s, int len) {
    int v = 0, i = 0, neg = 0;
    if (len > 0 && s[0] == '-') { neg = 1; i = 1; }
    for (; i < len; i++) { char c = s[i]; if (c < '0' || c > '9') break; v = v * 10 + (c - '0'); }
    return neg ? -v : v;
}
/* One hex number out of [*p, end): skips leading blanks, accepts an optional 0x/0X prefix, and
 * leaves *p on the first character it did not consume (the separator, or end). Returns -1 when
 * there was no digit at all, so an empty field ("0x41,,0x42") is skipped rather than read as 0. */
static int parse_hex_at(const char** p, const char* end) {
    const char* s = *p;
    while (s < end && (*s == ' ' || *s == '\t' || *s == '\n' || *s == '\r')) s++;
    if (end - s >= 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s += 2;
    int v = 0, n = 0;
    for (; s < end; s++, n++) {
        char c = *s; int d;
        if (c >= '0' && c <= '9') d = c - '0';
        else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
        else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
        else break;
        v = v * 16 + d;
    }
    *p = s;
    return n ? v : -1;
}
static int streq_n(const char* a, int alen, const char* b) {
    int bl = (int)strlen(b);
    return alen == bl && memcmp(a, b, (size_t)bl) == 0;
}

/* L13: monotonic count of keys whose symbol list did not fit ET9_KDB_MAX_KEY_CODES. Never reset;
 * non-zero means some layout lost alternates and the capacity needs raising. */
static ET9U32 g_code_truncations = 0;
ET9U32 xt9kdb_key_code_truncations(void) { return g_code_truncations; }

/* Append one symbol, skipping duplicates (the blob's Load_AddKey rejects a repeated symbol within
 * one key outright, status 0x3c @0xb9a94 — ours drops it instead of failing the whole load).
 * Returns 1 if the list was already full, i.e. the symbol was LOST. */
static int codes_append(ET9SYMB* out, int* n, int sym, int cap) {
    if (sym <= 0 || sym > 0xffff) return 0;
    for (int i = 0; i < *n; i++) if (out[i] == (ET9SYMB)sym) return 0;
    if (*n >= cap) return 1;
    out[(*n)++] = (ET9SYMB)sym;
    return 0;
}

/* Map a <key> element [s,e) to its ET9 SYMBOL LIST (0 written = not a recognition/commit key).
 *
 * out[0] is the PRIMARY — the single code this loader has always produced — and out[1..] are the
 * alternates. The blob's Load_AddKey (@0xb95d4) takes exactly this shape: `(ctx, keyId, keyType,
 * left, top, right, bottom, u32 count, const ET9SYMB* symbols)`, memcpy'ing the whole array into
 * the per-key char arena and storing count at key+0x40 and the pointer at key+0x48 (@0xb99a4,
 * 0xb99bc, 0xb9990). The live XT9NKL capture shows char[0] = the key's base letter, so the
 * primary-first ordering is device-confirmed, not inferred.
 *
 * Precedence, unchanged for the primary:
 *   keyName   -> one ET9 function code, no alternates (MIC/SYM/SHIFT map to nothing).
 *   keyLabel  -> the letter is the primary, and keyCodes then supplies its alternates. This is
 *                the `keyLabel="A" keyCodes="0x00E4, 0x00E5"` form in the Nordic PKBs: reading
 *                keyCodes[0] as the primary there would leave the A key with no 'a' on it.
 *   keyCodes  -> keyCodes[0] is the primary, the rest are alternates. Greek and Arabic.
 * Duplicates are dropped; entries past `cap` are counted and reported by the caller. */
static int key_codes_of(const char* s, const char* e, ET9SYMB* out, int cap, int* overflow) {
    int vl, n = 0, ovf = 0;
    if (overflow) *overflow = 0;
    const char* nm = attr_val(s, e, "keyName", &vl);
    if (nm && vl > 0) {
        int code = 0;
        if      (streq_n(nm, vl, "ET9KEY_BACK"))   code = 8;     /* backspace */
        else if (streq_n(nm, vl, "ET9KEY_RETURN")) code = 13;    /* enter     */
        else if (streq_n(nm, vl, "ET9KEY_ALT"))    code = 4070;  /* fn/shift  */
        else if (streq_n(nm, vl, "ET9KEY_SPACE"))  code = 32;
        /* MIC/SYM/MULTI/SHIFT etc. — non-letter, excluded from recognition */
        if (code && cap > 0) out[n++] = (ET9SYMB)code;
        return n;
    }
    const char* lbl = attr_val(s, e, "keyLabel", &vl);
    if (lbl && vl == 1) {
        char c = lbl[0];
        if (c >= 'A' && c <= 'Z') ovf |= codes_append(out, &n, c - 'A' + 'a', cap);  /* lowercase ascii */
        else if (c >= 'a' && c <= 'z') ovf |= codes_append(out, &n, c, cap);
    }
    const char* cd = attr_val(s, e, "keyCodes", &vl);
    if (cd && vl > 0) {
        const char* p = cd; const char* pe = cd + vl;
        while (p < pe) {
            int v = parse_hex_at(&p, pe);
            /* Cosmetic function glyphs: only ever a key's own code (▲ shift, ⇦ backspace), and
             * every layout that uses one also carries the keyName that was matched above — this
             * stays as the pre-L13 guard for a hypothetical bare one. */
            if (n == 0 && (v == 0x25B2 || v == 0x21E6)) return 0;
            if (v > 0) ovf |= codes_append(out, &n, v, cap);
            while (p < pe && *p != ',') p++;   /* skip whatever followed the number */
            if (p < pe) p++;                   /* and the comma itself */
        }
    }
    if (overflow) *overflow = ovf;
    return n;
}

/* Light header scan: pid/sid from <keyboard ...> WITHOUT touching the active model. Used by the
 * variant registry to key raw bytes by (pid,sid) at registration time, before any load. */
int xt9kdb_scan_kdb_ids(const ET9U8* xml, ET9U32 len, ET9U32* pid, ET9U32* sid) {
    if (!xml || !len || !pid || !sid) return -1;
    const char* p   = (const char*)xml;
    const char* end = p + len;
    const char* kb = find_sub(p, end, "<keyboard");
    if (!kb) return -1;
    const char* kbend = find_sub(kb, end, ">");
    if (!kbend) kbend = end;
    int vl; const char* a;
    a = attr_val(kb, kbend, "primaryId", &vl);   if (!a) return -1; *pid = (ET9U32)parse_int(a, vl);
    a = attr_val(kb, kbend, "secondaryId", &vl); if (!a) return -1; *sid = (ET9U32)parse_int(a, vl);
    return 0;
}

/* Top-level: parse the XML bytes and publish the model. */
ET9STATUS XT9KDB(Load_XmlKDB)(ET9KDBInfoPtr ctx, const ET9U8* xml, ET9U32 len) {
    if (!ctx || !xml || !len) return ET9STATUS_INVALID_MEMORY;
    const char* p   = (const char*)xml;
    const char* end = p + len;

    XT9KDB(Load_Reset)(ctx);

    /* <keyboard ...> header */
    const char* kb = find_sub(p, end, "<keyboard");
    if (kb) {
        const char* kbend = find_sub(kb, end, ">");
        if (!kbend) kbend = end;
        Xt9KdbProps pr; memset(&pr, 0, sizeof(pr));
        int vl; const char* a;
        a = attr_val(kb, kbend, "primaryId", &vl);           if (a) pr.pid = (ET9U32)parse_int(a, vl);
        a = attr_val(kb, kbend, "secondaryId", &vl);         if (a) pr.sid = (ET9U32)parse_int(a, vl);
        a = attr_val(kb, kbend, "defaultLayoutWidth", &vl);  if (a) pr.w   = (ET9U16)parse_int(a, vl);
        a = attr_val(kb, kbend, "defaultLayoutHeight", &vl); if (a) pr.h   = (ET9U16)parse_int(a, vl);
        a = attr_val(kb, kbend, "radiusY", &vl);             if (a) pr.radiusY = (ET9U16)parse_int(a, vl);
        XT9KDB(Load_SetProperties)(ctx, &pr);
        p = kbend;
    }

    /* iterate <key .../> elements */
    for (;;) {
        const char* ks = find_sub(p, end, "<key");
        if (!ks) break;
        const char* ke = find_sub(ks, end, ">");
        if (!ke) break;
        int vl; const char* a;
        int left = 0, top = 0, w = 0, h = 0, bx = 0, by = 0, hasb = 0;
        a = attr_val(ks, ke, "keyLeft", &vl);   if (a) left = parse_int(a, vl);
        a = attr_val(ks, ke, "keyTop", &vl);    if (a) top  = parse_int(a, vl);
        a = attr_val(ks, ke, "keyWidth", &vl);  if (a) w    = parse_int(a, vl);
        a = attr_val(ks, ke, "keyHeight", &vl); if (a) h    = parse_int(a, vl);
        a = attr_val(ks, ke, "biasX", &vl);     if (a) { bx = parse_int(a, vl); hasb = 1; }
        a = attr_val(ks, ke, "biasY", &vl);     if (a) { by = parse_int(a, vl); hasb = 1; }

        ET9KdbKey key;
        memset(&key, 0, sizeof key);
        int ovf = 0, ncodes = key_codes_of(ks, ke, key.codes, ET9_KDB_MAX_KEY_CODES, &ovf);
        if (ovf) {
            g_code_truncations++;
            XT9KDB_LOGF("keyCodes of key 0x%04x exceeds ET9_KDB_MAX_KEY_CODES=%d — alternates LOST "
                        "(truncated keys this process: %u)",
                        (unsigned)key.codes[0], ET9_KDB_MAX_KEY_CODES, g_code_truncations);
        }
        key.codeCount = (ET9U8)ncodes;
        key.keyCode = (ET9U16)(ncodes ? key.codes[0] : 0);
        /* NKL type (+0x04): regional=1, nonRegional=2, function=5 (device-confirmed). Drives both the
         * emitted type field and expanded-region grow-eligibility (function keys are inert). */
        { int tl; const char* tv = attr_val(ks, ke, "keyType", &tl);
          key.type = (tv && streq_n(tv, tl, "regional"))    ? 1 :
                     (tv && streq_n(tv, tl, "nonRegional")) ? 2 : 5; }
        key.left = (ET9S16)left;          key.top    = (ET9S16)top;
        key.right = (ET9S16)(left + w - 1);       key.bottom = (ET9S16)(top + h - 1);   /* inclusive */
        key.cx = (ET9S16)(left + (w - 1) / 2);    key.cy = (ET9S16)(top + (h - 1) / 2);
        key.biasX = 0; key.biasY = 0;
        {
            ET9STATUS ast = XT9KDB(Load_AddKey)(ctx, &key);
            if (ast != ET9STATUS_NONE) {
                /* L12: the overflow used to be discarded here and the layout shipped truncated.
                 * Fail the load the way the blob does (status 0x38) so the caller sees it. */
                XT9KDB_LOGF("Load_XmlKDB: key %u rejected (status 0x%x) — layout has more than %d keys; load FAILED",
                            (unsigned)slot_for(ctx)->count, (unsigned)ast, XT9_MAX_KEYS);
                slot_for(ctx)->count = 0;
                return ast;
            }
            if (hasb) {
                Xt9ModelSlot* s = slot_for(ctx);
                xt9kdb_attach_bias(&s->model, (ET9U16)(s->count - 1), (ET9S16)bx, (ET9S16)by);
            }
        }

        p = ke + 1;
    }

    /* Match the blob's key ordering: all regional/nonRegional keys FIRST (in document order), then the
     * function (type 5) keys appended (in document order). VERIFIED on-device — this stable partition
     * reproduces the blob's exact 30-key getKeys/NKL index sequence for qwerty_pkb (backspace/alt/enter
     * land at 27/28/29). Key indices are load-bearing: the trace records and the recognizer's candidate
     * lookup are index-based, so ours MUST line up with the blob's or recognition maps to the wrong keys.
     * (No bias remap needed — our layouts carry no per-key bias; attach any future bias AFTER this.) */
    Xt9ModelSlot* s = slot_for(ctx);
    {
        ET9KdbKey tmp[XT9_MAX_KEYS];
        ET9U16 w = 0;
        for (ET9U16 i = 0; i < s->count; i++) if (s->keys[i].type != 5) tmp[w++] = s->keys[i];
        for (ET9U16 i = 0; i < s->count; i++) if (s->keys[i].type == 5) tmp[w++] = s->keys[i];
        memcpy(s->keys, tmp, (size_t)s->count * sizeof(ET9KdbKey));
    }

    s->model.keyCount = s->count;
    s->model.keys = s->keys;
    if (s->count == 0) return ET9STATUS_KDB_NOT_LOADED;
    /* Adopt into the legacy global ONLY when it isn't owned by another live ctx (bring-up
     * default, or a re-load of the same slot). The gesture path re-publishes its own ctx's
     * model at every TouchStart, so a foreign context's load can never affect a decode. */
    if (g_kdb_model == &XT9_QWERTY_PKB_EN || g_kdb_model == &s->model)
        xt9kdb_set_model(&s->model);
#if defined(XT9KDB_CUTOVER)
    /* CUTOVER: build the owned NKL into the context arena (ctx+0x68) and activate it (ctx+0x60), so the
     * blob's core — ET9_CP_Trace, GetKeyPositions — reads OUR geometry. In DIFF mode this is skipped (the
     * blob builds its own NKL; we only compare). */
    xt9kdb_install_nkl(ctx);
#endif
    return ET9STATUS_NONE;
}

/* ---- not-yet-owned attachment data (our XML carries none; documented no-ops) -------------- */
ET9STATUS XT9KDB(Load_AttachShiftedChars)(ET9KDBInfoPtr ctx, void* p)         { (void)ctx;(void)p; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(Load_AttachMultitapInfo)(ET9KDBInfoPtr ctx, void* p)         { (void)ctx;(void)p; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(Load_AttachBias)(ET9KDBInfoPtr ctx, void* p)                 { (void)ctx;(void)p; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(Load_SetSmartTouch)(ET9KDBInfoPtr ctx, void* p)              { (void)ctx;(void)p; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(Load_SetSmartTouchProtectiveArea)(ET9KDBInfoPtr ctx, void* p){ (void)ctx;(void)p; return ET9STATUS_NONE; }
ET9STATUS XT9KDB(Load_TextKDB)(ET9KDBInfoPtr ctx, const ET9U8* t, ET9U32 n)   { (void)ctx;(void)t;(void)n; return ET9STATUS_NONE; }
