#!/usr/bin/env python3
"""elf_cutover.py — static ELF surgery to hand the blob's ET9KDB_* ABI to our owned lib.

Dependency-free: parses the ELF64 section/symbol/dynamic tables with `struct` only (no pyelftools),
so it runs on stock Python 3 anywhere (macOS included).

The Nuance blob (libnative-lib.so) DEFINES all ~50 ET9KDB_* functions and calls them through its own
PLT/GOT. To make it call OUR implementation instead, two edits:

  1. UNDEF the contract symbols in .dynsym (st_shndx=UNDEF, value=0, size=0). The dead code bodies stay
     in .text (harmless; kept for rollback). With the local defs gone, each JUMP_SLOT reloc for these
     names must resolve from a dependency at load time.
  2. Give the blob a DT_NEEDED on our lib so it's in scope. .dynstr has ZERO slack, so we REPURPOSE the
     dead `libdl.so` DT_NEEDED (the blob imports 0 symbols from libdl; Android's linker provides libdl
     regardless). We overwrite its 9-byte string in place with "libkb.so\0" — hence the owned lib MUST be
     named libkb.so (<=8 chars to fit the slot).

Net: no section growth, no offset shifts. Reversible with --restore --orig <original blob>.

Symbols intentionally NOT in the contract (see CONTRACT / NOT_REDIRECTED / INERT_NO_CALLERS below):
  * ET9KDB_Init            — the blob's own Init sets up state our module reads.
  * ProcessKey, ProcessKeyBySymbol, ProcessStoredTouch, ModifyCurrentKey
                           — the tap -> ET9AW input path; redirecting them broke autocomplete.
  * 18 more (INERT_NO_CALLERS) — measured to have NO reference of any kind inside the blob, so
                           redirecting them would change nothing that runs. See that list.

A symbol is only "handed over" by this surgery if the blob reaches it THROUGH THE PLT. Use
tools/abi_callsites.py --in <pristine blob> before adding anything to CONTRACT.

Usage:
  elf_cutover.py --in libnative-lib.so --out libnative-lib.patched.so           # apply
  elf_cutover.py --in libnative-lib.patched.so --out libnative-lib.so --restore --orig libnative-lib.so
  elf_cutover.py --in <file> --verify                                            # inspect only
"""
import argparse, struct, sys

CONTRACT = [
    "InvalidateLoadedKdbInfo","GetKdbNum","GetKdbVersion","SetKdbNum","SetPageNum",
    "SetRegionalMode","SetDiscreteMode","TimeOut","Load_Reset","Load_SetProperties",
    "Load_AddKey","Load_AttachBias","Load_AttachMultitapInfo","Load_AttachShiftedChars",
    "Load_SetSmartTouch","Load_SetSmartTouchProtectiveArea","Load_XmlKDB","GetKeyPositions",
    "GetKeyPositionByTap","GetKeyboardSize","GetKeyboardDefaultSize","SetKeyboardSize",
    "SetKeyboardOffset","TouchStart","TouchMove","TouchEnd","TouchCancel",
    # 2026-08-12 INVESTIGATION: redirected to observe the trace->AW handoff. Our definition is a
    # pass-through that calls the blob's orphaned original by address (see kdb_trace.c), so the
    # blob keeps doing the decoding; we only get to see the arguments and the record it reads.
    # Revert by moving this back to NOT_REDIRECTED and re-running cutover_deploy.sh patch.
    "ProcessStoredTouch",
]

# DELIBERATELY NOT REDIRECTED — the tap -> ET9AW input path stays with the blob.
#   ProcessKey, ProcessKeyBySymbol, ProcessStoredTouch, ModifyCurrentKey
# Same rule as ET9KDB_Init below: own the KDB *content* (geometry), leave *plumbing* to the blob.
# Redirecting these shipped a keyboard that could not autocomplete: our implementations forward
# through a commit sink (kdb_tap.c g_aw_commit) that is only ever wired by DIFF-mode JNI glue, so in
# CUTOVER every typed character was silently swallowed and ET9 fell back to next-word predictions
# (2026-07-24; docs/2026-07_frozen-suggestions_investigation.md). The blob's own ProcessKey is 924
# bytes of multitap/mode/validator logic that reads the NKL at ctx+0x60 — OUR byte-identical NKL —
# so leaving it in place keeps the geometry win and restores typing. Note ProcessKeyBySymbol
# tail-calls ProcessKey through the PLT, so both must stay with the blob or neither does.
# ProcessKey/ProcessKeyBySymbol were briefly redirected (2026-08-12) as logging pass-throughs to
# see the decoder's key sequence for a gesture. The capture proved gestures NEVER call ProcessKey —
# its two call sites inside ProcessStoredTouch do not execute on the trace path — so redirecting it
# buys nothing for swipe work and only puts our code back on the typing path that regressed in July.
# Reverted. ModifyCurrentKey stays with the blob for the same reason as always.
#
# WHAT IT WOULD TAKE TO REDIRECT THEM (written down 2026-09-20 so the next attempt starts from
# evidence rather than from optimism). Since the census below proved every OTHER un-redirected
# symbol is uncalled, these three are the only touch-ABI symbols a future cutover could change at
# all — ProcessKey has 5 live call sites, ProcessKeyBySymbol 3, ModifyCurrentKey 1. The 2026-07
# regression was not "our implementation was wrong"; it was that our implementation commits
# through g_aw_commit, a sink only DIFF-mode JNI glue ever wires, so in CUTOVER every typed
# character was silently swallowed. Four things must hold before trying again, and the first
# three are checkable offline:
#   1. The sink is wired in CUTOVER. kdb_tap.c's commit path must reach ET9AddExplicitSymb /
#      ET9AddCustomSymbolSet on the real ET9WordSymbInfo (ctx+0x18750, magic 0x1428 at +0), by
#      the same trampoline-by-address technique kdb_tap.c already uses for the blob's ProcessKey.
#      Today it is NULL in CUTOVER and only logs "commit sink NOT WIRED" on the first keystroke.
#   2. A test that fails when the sink is unwired. The July build passed validation precisely
#      because an unwired sink was indistinguishable from "the engine had nothing to say": a
#      CUTOVER-mode stage must assert that a typed letter lands in a WordSymbInfo-shaped buffer,
#      not merely that ProcessKeyBySymbol returned 0.
#   3. Parity on the 924 bytes of ProcessKey. It is multitap + mode + validator logic reading the
#      NKL at ctx+0x60 (OUR NKL). Redirecting it means reimplementing: the multitap cycle and its
#      timer, the ambiguous/discrete/regional mode gates, the word-full case that calls
#      GetKdbVersion at 0xbf680, and ModifyCurrentKey's edit of the in-progress symbol. A
#      differential stage against the blob's own copy (still present in .text, callable by
#      address) over a swept input space is the only honest evidence.
#   4. THEN a device pass. Nothing offline can prove autocomplete works end to end, because the
#      DLM and the selection list are the blob's.
# Absent 1-3, leave them here. Note ProcessKeyBySymbol tail-calls ProcessKey through the PLT
# (0xc08f0), so both move together or neither does.
NOT_REDIRECTED = ["ProcessKey", "ProcessKeyBySymbol", "ModifyCurrentKey"]

# NOT REDIRECTED BECAUSE REDIRECTING THEM WOULD DO NOTHING — measured, not assumed.
# 2026-09-20: tools/abi_callsites.py censused every internal reference to every ET9KDB_* export in
# the pristine blob (kdb_backup/libnative-lib.orig.so). Each name below has ZERO of all four kinds:
# no PLT call site, no direct branch into its body, no GOT/data relocation or adrp+add that takes
# its address, and its name appears nowhere as a C string — and the blob imports neither dlopen nor
# dlsym (llvm-nm --dynamic --undefined-only), so there is no name-based path to them either. The
# cutover redirects PLT call sites; a symbol with none is not "handed over" by being UNDEF'd, it is
# simply never called. Adding one to CONTRACT would grow the list without changing one instruction
# executed on device, and would cost a real thing: the contract would stop being a statement about
# what our code actually serves.
#
# This is a property of THIS blob. `run_tests.sh` re-checks it (stage "blob call-site evidence")
# whenever the pristine backup is present:  tools/abi_callsites.py --in <blob> --check
#
# Consequences worth knowing, since they are easy to re-litigate:
#   * ProcessTrace / ProcessTap have no caller in the blob, so on the shipped build the ONLY
#     recognizer entry is TouchEnd -> our own process_trace_impl. They are already effectively
#     ours; the exported names are dead either way.
#   * TouchEndAll / TouchTimeOut are never invoked, so audit L9's "the engine core abandons a
#     session behind our back" cannot happen on this blob. (Our copies still release the session,
#     because the DIFF mirror and the offline suite do call them.)
#   * GetKeyPositionByStoredTouch is dead, and it is the ONLY caller of GetKeyPositionByTap
#     (tail-call `b` at 0xc06d0) — so ByTap, though it IS in CONTRACT, is likewise unreachable on
#     device today. Keep it: it costs nothing and the JNI surface may grow a reader.
INERT_NO_CALLERS = [
    "ProcessTap", "ProcessTrace", "TouchEndAll", "TouchTimeOut", "SetTraceInput",
    "ClearTraceInput", "GetKeyPositionByStoredTouch", "GetSwitchedKeys", "GetMultiTapSequence",
    "GetTouchInfo", "SetAmbigMode", "SetMultiTapMode", "SetRegionality", "GetRegionality",
    "GetPageNum", "Validate", "NextDiacritic", "Load_TextKDB",
]

CONTRACT_SYMS = {"ET9KDB_" + n for n in CONTRACT}

OLD_NEEDED = b"libdl.so\x00"     # dead DT_NEEDED we repurpose
NEW_NEEDED = b"libkb.so\x00"     # our owned lib (must fit in OLD_NEEDED's slot)
SHN_UNDEF  = 0
SYM_SZ     = 24                  # ELF64 Elf64_Sym
DYN_SZ     = 16                  # ELF64 Elf64_Dyn
DT_NEEDED  = 1


# ---- minimal ELF64 (little-endian) reader ------------------------------------------------
class Elf:
    def __init__(self, data):
        if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
            sys.exit("ERROR: not a little-endian ELF64 file.")
        self.d = data
        e_shoff    = struct.unpack_from("<Q", data, 0x28)[0]
        e_shentsz  = struct.unpack_from("<H", data, 0x3a)[0]
        e_shnum    = struct.unpack_from("<H", data, 0x3c)[0]
        e_shstrndx = struct.unpack_from("<H", data, 0x3e)[0]
        self.secs = []  # (name_off, type, offset, size, link, entsize)
        for i in range(e_shnum):
            b = e_shoff + i * e_shentsz
            nm, typ           = struct.unpack_from("<II", data, b)
            off               = struct.unpack_from("<Q", data, b + 0x18)[0]
            size              = struct.unpack_from("<Q", data, b + 0x20)[0]
            link              = struct.unpack_from("<I", data, b + 0x28)[0]
            entsz             = struct.unpack_from("<Q", data, b + 0x38)[0]
            self.secs.append([nm, typ, off, size, link, entsz])
        shstr_off = self.secs[e_shstrndx][2]
        self.byname = {}
        for s in self.secs:
            end = data.index(b"\x00", shstr_off + s[0])
            self.byname[data[shstr_off + s[0]:end].decode()] = s

    def sec(self, name):
        if name not in self.byname:
            sys.exit(f"ERROR: section {name} not found.")
        return self.byname[name]

    def str_at(self, strtab_off, idx):
        p = strtab_off + idx
        return self.d[p:self.d.index(b"\x00", p)].decode()


def _layout(data):
    """Return (elf, dynsym_off, dynsym_n, dynstr_off, needed_fileoff, sym_index_by_name)."""
    elf = Elf(data)
    dsym = elf.sec(".dynsym")
    dstr = elf.sec(".dynstr")
    dyn  = elf.sec(".dynamic")
    dynsym_off, dynsym_sz = dsym[2], dsym[3]
    dynstr_off = dstr[2]
    dynsym_n = dynsym_sz // SYM_SZ

    # symbol name -> index, but only for our contract set
    idx = {}
    for i in range(dynsym_n):
        st_name = struct.unpack_from("<I", data, dynsym_off + i * SYM_SZ)[0]
        nm = elf.str_at(dynstr_off, st_name)
        if nm in CONTRACT_SYMS:
            idx[nm] = i

    # DT_NEEDED string offset for libdl.so / libkb.so
    needed_fileoff = None
    doff, dsz = dyn[2], dyn[3]
    for i in range(dsz // DYN_SZ):
        tag, val = struct.unpack_from("<qQ", data, doff + i * DYN_SZ)
        if tag == 0:  # DT_NULL -> end
            break
        if tag == DT_NEEDED:
            s = data[dynstr_off + val: data.index(b"\x00", dynstr_off + val) + 1]
            if s in (OLD_NEEDED, NEW_NEEDED):
                needed_fileoff = dynstr_off + val
    return elf, dynsym_off, dynsym_n, dynstr_off, needed_fileoff, idx


def _read_sym(data, dynsym_off, i):
    b = dynsym_off + i * SYM_SZ
    return struct.unpack_from("<IBBHQQ", data, b)  # name,info,other,shndx,value,size


def _write_sym(data, dynsym_off, i, name, info, other, shndx, value, size):
    struct.pack_into("<IBBHQQ", data, dynsym_off + i * SYM_SZ, name, info, other, shndx, value, size)


def apply_patch(data):
    data = bytearray(data)
    _, dsym_off, _, _, needed_fileoff, idx = _layout(data)
    if needed_fileoff is None:
        sys.exit("ERROR: could not find the DT_NEEDED slot (libdl.so / libkb.so).")
    missing = CONTRACT_SYMS - set(idx)
    if missing:
        sys.exit(f"ERROR: {len(missing)} contract symbols not in .dynsym: {sorted(missing)}")

    cur = bytes(data[needed_fileoff:needed_fileoff + len(OLD_NEEDED)])
    if cur != OLD_NEEDED:
        sys.exit(f"ERROR: DT_NEEDED slot holds {cur!r}, expected {OLD_NEEDED!r} (already patched?).")
    data[needed_fileoff:needed_fileoff + len(OLD_NEEDED)] = NEW_NEEDED

    changed = 0
    for name, i in idx.items():
        nm, info, other, shndx, val, size = _read_sym(data, dsym_off, i)
        if shndx != SHN_UNDEF:
            _write_sym(data, dsym_off, i, nm, info, other, SHN_UNDEF, 0, 0)
            changed += 1
    return bytes(data), changed


def restore_patch(data, orig):
    """Reverse: swap name back and re-import each symbol's (shndx,value,size) from the pristine blob."""
    data = bytearray(data)
    _, dsym_off, _, _, needed_fileoff, idx = _layout(data)
    if needed_fileoff is None:
        sys.exit("ERROR: could not find the DT_NEEDED slot.")
    data[needed_fileoff:needed_fileoff + len(NEW_NEEDED)] = OLD_NEEDED

    _, o_off, _, _, _, o_idx = _layout(orig)
    for name, i in idx.items():
        nm, info, other, _sh, _v, _s = _read_sym(data, dsym_off, i)
        _, _, _, oshndx, oval, osize = _read_sym(orig, o_off, o_idx[name])
        _write_sym(data, dsym_off, i, nm, info, other, oshndx, oval, osize)
    return bytes(data)


def verify(data):
    _, dsym_off, _, dynstr_off, needed_fileoff, idx = _layout(data)
    name = data[needed_fileoff:data.index(b"\x00", needed_fileoff)].decode()
    undef = sum(1 for n, i in idx.items() if _read_sym(data, dsym_off, i)[3] == SHN_UNDEF)
    defined = len(idx) - undef
    print(f"  DT_NEEDED repurposed slot -> {name!r}")
    print(f"  contract symbols: {undef} UNDEF, {defined} still defined")
    n = len(CONTRACT)
    state = ("CUTOVER (patched)" if (name == "libkb.so" and undef == n)
             else "ORIGINAL (blob)" if (name == "libdl.so" and defined == n)
             else "MIXED/UNKNOWN")
    print(f"  => state: {state}")
    return undef, name


def main():
    ap = argparse.ArgumentParser(description="Static ELF cutover surgery for libnative-lib.so")
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", dest="out")
    ap.add_argument("--restore", action="store_true", help="reverse the surgery (needs --orig)")
    ap.add_argument("--orig", help="pristine blob, for byte-exact --restore of symbol defs")
    ap.add_argument("--verify", action="store_true", help="inspect state only, no write")
    args = ap.parse_args()

    data = open(args.inp, "rb").read()

    if args.verify:
        print(f"[{args.inp}]")
        verify(data)
        return
    if not args.out:
        sys.exit("ERROR: --out required unless --verify")

    if args.restore:
        if not args.orig:
            sys.exit("ERROR: --restore needs --orig <pristine blob> to restore exact symbol defs.")
        out = restore_patch(data, open(args.orig, "rb").read())
        open(args.out, "wb").write(out)
        print(f"Restored: DT_NEEDED libkb.so -> libdl.so; {len(CONTRACT)} symbols re-defined from {args.orig}")
        verify(out)
        return

    out, changed = apply_patch(data)
    open(args.out, "wb").write(out)
    print(f"Patched: DT_NEEDED libdl.so -> libkb.so; UNDEF'd {changed} contract symbols.")
    print(f"Wrote {args.out} ({len(out)} bytes)")
    print("Verify:")
    verify(out)


if __name__ == "__main__":
    main()
