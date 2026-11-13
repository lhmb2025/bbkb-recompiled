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

The same UNDEF also hands over three non-KDB exports, LDB_CONTRACT below (P-9: the LDB reader,
proof in ../cpp/src/ldb_reader.c). Reproducible from the pristine blob with cutover_deploy.sh patch:
  pristine  sha256 2182e601f81358ad576f3c6c6bb50e94d197d28c4ed2612fca81b9911d65efdc
  28-symbol cutover (before P-9)       4da6c36523406e12601ddadc5e901b6014d8cfc684dadd2ea7099612118985dd
  31-symbol cutover (with LDB_CONTRACT) 51228ca9b899d7b988c277f8e7274e59925a91a46ce606f688aedfa204575002
  P-9's bytes: the .dynsym entries of ldbReadDataAW (file 0x55a8: value 0x1e6d0 size 0x24),
  ldbTerminate (0x2ae8: value 0x1f03c size 0x80) and ldbDeleteIndex (0x2890: value 0x1efb8
  size 0x4c) get st_shndx 0x0a -> 0, st_value -> 0, st_size -> 0; nothing else changes.

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
    # Pass-through to the blob's orphaned original by address (kdb_trace.c): the blob still
    # decodes, we only observe the arguments and the record it reads.
    "ProcessStoredTouch",
]

# The tap -> ET9AW input path stays with the blob: own the KDB content, leave plumbing to the blob.
# Our implementations commit through kdb_tap.c's g_aw_commit, which only DIFF-mode JNI wires, so in
# CUTOVER every typed character would be swallowed. Gestures never call ProcessKey.
# Before redirecting them (ProcessKey has 5 live call sites, ProcessKeyBySymbol 3, ModifyCurrentKey 1):
#   1. Wire the sink in CUTOVER: reach ET9AddExplicitSymb / ET9AddCustomSymbolSet on the real
#      ET9WordSymbInfo (ctx+0x18750, magic 0x1428 at +0) by trampoline-by-address.
#   2. A CUTOVER test that fails when the sink is unwired (a typed letter must land in a
#      WordSymbInfo-shaped buffer, not just return 0).
#   3. Parity with the blob's 924-byte ProcessKey, which reads the NKL at ctx+0x60: multitap cycle
#      and timer, ambiguous/discrete/regional gates, the word-full case calling GetKdbVersion at
#      0xbf680, ModifyCurrentKey's edit of the in-progress symbol. Diff against the blob's copy.
#   4. Then a device pass (the DLM and selection list are the blob's).
# ProcessKeyBySymbol tail-calls ProcessKey through the PLT (0xc08f0): both move or neither does.
NOT_REDIRECTED = ["ProcessKey", "ProcessKeyBySymbol", "ModifyCurrentKey"]

# Exports with no reference of any kind in the blob: no PLT call, no direct branch, no GOT/data
# reloc or adrp+add, no name string, and no dlopen/dlsym import. UNDEFing them changes nothing that
# runs. A property of this blob: run_tests.sh re-checks it (tools/abi_callsites.py --check).
#   * ProcessTrace / ProcessTap: the only recognizer entry is TouchEnd -> our process_trace_impl.
#   * TouchEndAll / TouchTimeOut are never invoked (our copies still release the session for the
#     DIFF mirror and the offline suite).
#   * GetKeyPositionByStoredTouch is the only caller of GetKeyPositionByTap (tail-call at 0xc06d0),
#     so ByTap, though in CONTRACT, is unreachable on device too.
INERT_NO_CALLERS = [
    "ProcessTap", "ProcessTrace", "TouchEndAll", "TouchTimeOut", "SetTraceInput",
    "ClearTraceInput", "GetKeyPositionByStoredTouch", "GetSwitchedKeys", "GetMultiTapSequence",
    "GetTouchInfo", "SetAmbigMode", "SetMultiTapMode", "SetRegionality", "GetRegionality",
    "GetPageNum", "Validate", "NextDiacritic", "Load_TextKDB",
]

# The blob's LDB reader (ownership proof in ../cpp/src/ldb_reader.c). ldbReadDataAW is reached only
# through its GLOB_DAT (alphaInit's ET9AWLdbInit read callback; ldbReadDataCP keeps branching into
# the original body), the other two only through their PLT slots. Lets libkb.so map stored APK
# assets instead of a malloc'd copy of each 5 to 6 MB dictionary.
LDB_CONTRACT = ["ldbReadDataAW", "ldbTerminate", "ldbDeleteIndex"]

CONTRACT_SYMS = {"ET9KDB_" + n for n in CONTRACT} | set(LDB_CONTRACT)

OLD_NEEDED = b"libdl.so\x00"     # dead DT_NEEDED we repurpose
NEW_NEEDED = b"libkb.so\x00"     # our owned lib (must fit in OLD_NEEDED's slot)
SHN_UNDEF  = 0
SYM_SZ     = 24                  # ELF64 Elf64_Sym
DYN_SZ     = 16                  # ELF64 Elf64_Dyn
DT_NEEDED  = 1


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
    n = len(CONTRACT_SYMS)
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
        print(f"Restored: DT_NEEDED libkb.so -> libdl.so; {len(CONTRACT_SYMS)} symbols re-defined from {args.orig}")
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
