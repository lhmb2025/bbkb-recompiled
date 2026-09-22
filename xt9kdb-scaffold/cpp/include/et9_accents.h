/* et9_accents.h — English/Latin accent tables extracted VERBATIM from the blob rodata
 * @0x187d12 (libnative-lib.so). Null-delimited u16 ET9SYMB groups keyed by base letter.
 * Each entry = base letter + its long-press/accent variants, matching the live NKL char lists
 * (counts verified against the XT9NKL capture). Letters absent here (m,p,q,v,x) have no
 * variants -> the loader emits a 1-element list (base only). AUTO-GENERATED; do not edit. */
#ifndef ET9_ACCENTS_H
#define ET9_ACCENTS_H
#include "et9kdb.h"

typedef struct { ET9SYMB base; ET9U8 count; const ET9SYMB* chars; } ET9AccentEntry;

static const ET9SYMB kAcc_a[11] = { 0x0061, 0x00e0, 0x00e1, 0x00e2, 0x00e3, 0x00e4, 0x00e5, 0x00e6, 0x0101, 0x0103, 0x0105 };
static const ET9SYMB kAcc_b[2] = { 0x0062, 0x0253 };
static const ET9SYMB kAcc_c[6] = { 0x0063, 0x00e7, 0x0107, 0x0109, 0x010b, 0x010d };
static const ET9SYMB kAcc_d[5] = { 0x0064, 0x00f0, 0x010f, 0x0111, 0x0257 };
static const ET9SYMB kAcc_e[12] = { 0x0065, 0x00e8, 0x00e9, 0x00ea, 0x00eb, 0x0113, 0x0115, 0x0117, 0x0119, 0x011b, 0x025b, 0x1eb9 };
static const ET9SYMB kAcc_f[2] = { 0x0066, 0x0192 };
static const ET9SYMB kAcc_g[5] = { 0x0067, 0x011d, 0x011f, 0x0121, 0x0123 };
static const ET9SYMB kAcc_h[3] = { 0x0068, 0x0125, 0x0127 };
static const ET9SYMB kAcc_i[12] = { 0x0069, 0x00ec, 0x00ed, 0x00ee, 0x00ef, 0x0129, 0x012b, 0x012d, 0x012f, 0x0131, 0x0133, 0x1ecb };
static const ET9SYMB kAcc_j[2] = { 0x006a, 0x0135 };
static const ET9SYMB kAcc_k[4] = { 0x006b, 0x0137, 0x0138, 0x0199 };
static const ET9SYMB kAcc_l[6] = { 0x006c, 0x013a, 0x013c, 0x013e, 0x0140, 0x0142 };
static const ET9SYMB kAcc_n[8] = { 0x006e, 0x00f1, 0x0144, 0x0146, 0x0148, 0x0149, 0x014b, 0x1e45 };
static const ET9SYMB kAcc_o[14] = { 0x006f, 0x00f2, 0x00f3, 0x00f4, 0x00f5, 0x00f6, 0x00f8, 0x014d, 0x014f, 0x0151, 0x0153, 0x01a1, 0x0254, 0x1ecd };
static const ET9SYMB kAcc_r[4] = { 0x0072, 0x0155, 0x0157, 0x0159 };
static const ET9SYMB kAcc_s[9] = { 0x0073, 0x00df, 0x015b, 0x015d, 0x015f, 0x0161, 0x017f, 0x1e63, 0x0219 };
static const ET9SYMB kAcc_t[6] = { 0x0074, 0x00fe, 0x0163, 0x0165, 0x0167, 0x021b };
static const ET9SYMB kAcc_u[13] = { 0x0075, 0x00f9, 0x00fa, 0x00fb, 0x00fc, 0x0169, 0x016b, 0x016d, 0x016f, 0x0171, 0x0173, 0x01b0, 0x1ee5 };
static const ET9SYMB kAcc_w[2] = { 0x0077, 0x0175 };
static const ET9SYMB kAcc_y[5] = { 0x0079, 0x00fd, 0x00ff, 0x0177, 0x01b4 };
static const ET9SYMB kAcc_z[4] = { 0x007a, 0x017a, 0x017c, 0x017e };

static const ET9AccentEntry kAccentTable[] = {
    { 0x0061, 11, kAcc_a },   /* a */
    { 0x0062, 2, kAcc_b },   /* b */
    { 0x0063, 6, kAcc_c },   /* c */
    { 0x0064, 5, kAcc_d },   /* d */
    { 0x0065, 12, kAcc_e },   /* e */
    { 0x0066, 2, kAcc_f },   /* f */
    { 0x0067, 5, kAcc_g },   /* g */
    { 0x0068, 3, kAcc_h },   /* h */
    { 0x0069, 12, kAcc_i },   /* i */
    { 0x006a, 2, kAcc_j },   /* j */
    { 0x006b, 4, kAcc_k },   /* k */
    { 0x006c, 6, kAcc_l },   /* l */
    { 0x006e, 8, kAcc_n },   /* n */
    { 0x006f, 14, kAcc_o },   /* o */
    { 0x0072, 4, kAcc_r },   /* r */
    { 0x0073, 9, kAcc_s },   /* s */
    { 0x0074, 6, kAcc_t },   /* t */
    { 0x0075, 13, kAcc_u },   /* u */
    { 0x0077, 2, kAcc_w },   /* w */
    { 0x0079, 5, kAcc_y },   /* y */
    { 0x007a, 4, kAcc_z },   /* z */
};
#define ET9_ACCENT_TABLE_N 21

#endif /* ET9_ACCENTS_H */
