#!/usr/bin/env python3
"""abi_callsites.py — who, inside the blob, actually calls each ET9KDB_* export?

The cutover (elf_cutover.py) redirects a symbol by UNDEF'ing it in libnative-lib.so's .dynsym so
its JUMP_SLOT resolves from our libkb.so instead. That only redirects calls that go THROUGH THE
PLT. A symbol with no PLT call site is not "ours" after redirection — it is simply never called,
and adding it to CONTRACT changes nothing on device. Before 2026-09-20 nothing measured this, so
the contract list could only grow on faith.

This tool measures it. For every ET9KDB_* symbol DEFINED in the given ELF it reports four kinds of
internal reference, of which only the first is what the cutover actually redirects:

  pltBL   bl/b to the symbol's PLT stub                -> REDIRECTED by the cutover
  dirBL   bl/b straight to the symbol's own .text body -> NOT redirected (would silently stay blob)
  dataRel a JUMP_SLOT/GLOB_DAT/ABS64 on the name, or an R_AARCH64_RELATIVE whose addend is the
          symbol's address (a function-pointer table)  -> only the JUMP_SLOT kind is redirected
  strRef  the symbol NAME as a C string outside .dynstr — i.e. a dlsym() lookup

Dependency-free on purpose, exactly like elf_cutover.py: it parses ELF64 with `struct` and decodes
the four AArch64 instructions it needs (bl, b, adrp, add-immediate) itself, so it runs on stock
Python 3 with no NDK, no pyelftools and no llvm-objdump.

Usage:
  abi_callsites.py --in <blob.so>                       # table for every ET9KDB_* symbol
  abi_callsites.py --in <blob.so> --sym TouchEndAll,...  # just these
  abi_callsites.py --in <blob.so> --sites                # ...with every call-site address
  abi_callsites.py --in <blob.so> --check                # assert elf_cutover.INERT_NO_CALLERS
                                                         #   really has zero references (exit 1)
"""
import argparse, os, struct, sys

SYM_SZ = 24
RELA_SZ = 24
R_AARCH64_ABS64 = 257
R_AARCH64_GLOB_DAT = 1025
R_AARCH64_JUMP_SLOT = 1026
R_AARCH64_RELATIVE = 1027


# ---- minimal ELF64 (little-endian) reader ------------------------------------------------
class Elf:
    def __init__(self, data):
        if data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
            sys.exit("ERROR: not a little-endian ELF64 file.")
        self.d = data
        e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
        e_shentsz = struct.unpack_from("<H", data, 0x3a)[0]
        e_shnum = struct.unpack_from("<H", data, 0x3c)[0]
        e_shstrndx = struct.unpack_from("<H", data, 0x3e)[0]
        self.secs = []
        for i in range(e_shnum):
            b = e_shoff + i * e_shentsz
            nm = struct.unpack_from("<I", data, b)[0]
            addr = struct.unpack_from("<Q", data, b + 0x10)[0]
            off = struct.unpack_from("<Q", data, b + 0x18)[0]
            size = struct.unpack_from("<Q", data, b + 0x20)[0]
            self.secs.append((nm, addr, off, size))
        shstr = self.secs[e_shstrndx][2]
        self.byname = {}
        for nm, addr, off, size in self.secs:
            end = data.index(b"\x00", shstr + nm)
            self.byname[data[shstr + nm:end].decode()] = (addr, off, size)

    def sec(self, name):
        return self.byname.get(name)

    def bytes_of(self, name):
        s = self.sec(name)
        return self.d[s[1]:s[1] + s[2]] if s else b""


def _load(path, prefix="ET9KDB_"):
    data = open(path, "rb").read()
    elf = Elf(data)
    dynstr_addr, dynstr_off, dynstr_sz = elf.sec(".dynstr")
    dynsym_addr, dynsym_off, dynsym_sz = elf.sec(".dynsym")

    def sname(idx):
        p = dynstr_off + idx
        return data[p:data.index(b"\x00", p)].decode()

    syms = {}           # name -> (value, size)
    sym_at_index = {}   # dynsym index -> name
    for i in range(dynsym_sz // SYM_SZ):
        b = dynsym_off + i * SYM_SZ
        st_name, _info, _other, shndx, val, size = struct.unpack_from("<IBBHQQ", data, b)
        nm = sname(st_name)
        sym_at_index[i] = nm
        if nm.startswith(prefix) and shndx != 0:
            syms[nm] = (val, size)
    return data, elf, syms, sym_at_index, (dynstr_off, dynstr_off + dynstr_sz)


# ---- AArch64: the four instructions this tool needs --------------------------------------
def _branch_target(word, addr):
    """bl/b -> (target, mnemonic); anything else -> (None, None). b.cond is a different opcode."""
    top = word >> 26
    if top not in (0b000101, 0b100101):
        return None, None
    imm = word & 0x03FFFFFF
    if imm & 0x02000000:
        imm -= 0x04000000
    return addr + imm * 4, ("b" if top == 0b000101 else "bl")


def _adrp(word, addr):
    if (word >> 31) & 1 != 1 or (word >> 24) & 0x1F != 0b10000:
        return None, None
    rd = word & 0x1F
    immlo = (word >> 29) & 3
    immhi = (word >> 5) & 0x7FFFF
    imm = (immhi << 2) | immlo
    if imm & (1 << 20):
        imm -= (1 << 21)
    return rd, (addr & ~0xFFF) + (imm << 12)


def _add_imm(word):
    """64-bit ADD (immediate) -> (rd, rn, imm); None otherwise."""
    if (word >> 23) != 0b100100010:
        return None
    sh = (word >> 22) & 1
    imm = (word >> 10) & 0xFFF
    return (word & 0x1F, (word >> 5) & 0x1F, imm << (12 if sh else 0))


def _writes_x(word):
    """Coarse: which X register this instruction clobbers, for the adrp+add tracker."""
    return word & 0x1F


def plt_stub_map(data, elf, sym_at_index):
    """symbol name -> its PLT stub address, by decoding each PLT entry's GOT slot.

    An AArch64 PLT entry is  adrp x16,<page> ; ldr x17,[x16,#off] ; add x16,x16,#off ; br x17.
    The GOT slot it loads is the r_offset of one JUMP_SLOT relocation, which names the symbol —
    so no assumption about the PLT header size or entry stride is needed.
    """
    got_to_sym = {}
    s = elf.sec(".rela.plt")
    if s:
        for i in range(s[2] // RELA_SZ):
            off, info, _add = struct.unpack_from("<QQq", data, s[1] + i * RELA_SZ)
            if (info & 0xFFFFFFFF) == R_AARCH64_JUMP_SLOT:
                got_to_sym[off] = sym_at_index.get(info >> 32, "?")

    stubs = {}
    p = elf.sec(".plt")
    if not p:
        return stubs
    addr, off, size = p
    page = {}
    for i in range(0, size - 3, 4):
        w = struct.unpack_from("<I", data, off + i)[0]
        a = addr + i
        rd, pg = _adrp(w, a)
        if rd is not None:
            page[rd] = (pg, a)
            continue
        # ldr x17, [x16, #imm] -- unsigned offset, 64-bit: 1111100101 imm12 Rn Rt
        if (w >> 22) == 0b1111100101:
            rn = (w >> 5) & 0x1F
            if rn in page:
                slot = page[rn][0] + (((w >> 10) & 0xFFF) << 3)
                if slot in got_to_sym:
                    # the stub begins at the adrp
                    stubs[got_to_sym[slot]] = page[rn][1]
    return stubs


def scan(path, prefix="ET9KDB_"):
    data, elf, syms, sym_at_index, dynstr_range = _load(path, prefix)
    stubs = plt_stub_map(data, elf, sym_at_index)
    stub_owner = {a: n for n, a in stubs.items()}

    # symbol body ranges, for the direct-branch check
    bodies = sorted((v, v + (sz or 4), n) for n, (v, sz) in syms.items())

    def body_of(target):
        for lo, hi, n in bodies:
            if lo <= target < hi:
                return n
        return None

    plt_calls = {n: [] for n in syms}
    direct = {n: [] for n in syms}
    addr_taken = {n: [] for n in syms}

    t = elf.sec(".text")
    if t:
        taddr, toff, tsize = t
        page = {}
        for i in range(0, tsize - 3, 4):
            w = struct.unpack_from("<I", data, toff + i)[0]
            a = taddr + i
            tgt, mn = _branch_target(w, a)
            if tgt is not None:
                owner = stub_owner.get(tgt)
                if owner in plt_calls:
                    plt_calls[owner].append((a, mn))
                else:
                    b = body_of(tgt)
                    # a branch INTO a symbol's body from inside that same body is its own
                    # control flow, not a call to it
                    if b is not None and body_of(a) != b:
                        direct[b].append((a, mn))
                continue
            rd, pg = _adrp(w, a)
            if rd is not None:
                page[rd] = pg
                continue
            add = _add_imm(w)
            if add:
                rd, rn, imm = add
                if rn in page:
                    v = page[rn] + imm
                    for n, (val, _sz) in syms.items():
                        if v == val:
                            addr_taken[n].append((a, "adrp+add"))
                page.pop(rd, None)
                continue
            page.pop(_writes_x(w), None)

    # data relocations
    rel_by_sym = {n: [] for n in syms}
    by_addend = {}
    for secn in (".rela.dyn", ".rela.plt"):
        s = elf.sec(secn)
        if not s:
            continue
        for i in range(s[2] // RELA_SZ):
            off, info, add = struct.unpack_from("<QQq", data, s[1] + i * RELA_SZ)
            rtype = info & 0xFFFFFFFF
            rsym = info >> 32
            if rtype == R_AARCH64_RELATIVE:
                by_addend.setdefault(add, []).append((secn, off))
            elif rsym and rtype in (R_AARCH64_ABS64, R_AARCH64_GLOB_DAT, R_AARCH64_JUMP_SLOT):
                nm = sym_at_index.get(rsym)
                if nm in rel_by_sym:
                    rel_by_sym[nm].append((secn, off, rtype))

    lo, hi = dynstr_range
    out = {}
    for n, (val, size) in syms.items():
        strrefs = []
        needle = n.encode() + b"\x00"
        start = 0
        while True:
            p = data.find(needle, start)
            if p < 0:
                break
            if not (lo <= p < hi):
                strrefs.append(p)
            start = p + 1
        out[n] = {
            "addr": val,
            "size": size,
            "plt": plt_calls[n],
            "direct": direct[n],
            "addr_taken": addr_taken[n],
            # the JUMP_SLOT on the symbol's own name is the PLT machinery, not a caller: count it
            # separately from a real function-pointer reference.
            "jump_slot": [r for r in rel_by_sym[n] if r[2] == R_AARCH64_JUMP_SLOT],
            "data_rel": ([r for r in rel_by_sym[n] if r[2] != R_AARCH64_JUMP_SLOT]
                         + by_addend.get(val, [])),
            "str_refs": strrefs,
        }
    return out


def total_refs(e):
    return len(e["plt"]) + len(e["direct"]) + len(e["addr_taken"]) + len(e["data_rel"]) + len(e["str_refs"])


def main():
    ap = argparse.ArgumentParser(description="ET9KDB_* internal reference census")
    ap.add_argument("--in", dest="inp", required=True, help="the ELF to scan (the PRISTINE blob)")
    ap.add_argument("--sym", help="comma-separated names (with or without the ET9KDB_ prefix)")
    ap.add_argument("--sites", action="store_true", help="print every call-site address")
    ap.add_argument("--check", action="store_true",
                    help="assert elf_cutover.INERT_NO_CALLERS really has zero references")
    args = ap.parse_args()

    info = scan(args.inp)
    if not info:
        sys.exit("ERROR: no defined ET9KDB_* symbols in this file.")

    if args.check:
        # tools/__pycache__ is (unhelpfully) tracked, so importing must not rewrite a .pyc there
        # and leave the tree dirty after a test run.
        sys.dont_write_bytecode = True
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import elf_cutover
        bad = []
        missing = []
        for n in elf_cutover.INERT_NO_CALLERS:
            full = "ET9KDB_" + n
            e = info.get(full)
            if e is None:
                missing.append(full)
            elif total_refs(e):
                bad.append((full, e))
        for full in missing:
            print(f"  ERROR: {full} is not defined in {args.inp}")
        for full, e in bad:
            print(f"  ERROR: {full} is listed INERT but has {total_refs(e)} reference(s): "
                  f"plt={len(e['plt'])} direct={len(e['direct'])} "
                  f"addrTaken={len(e['addr_taken'])} dataRel={len(e['data_rel'])} "
                  f"strRef={len(e['str_refs'])}")
        # the contract's own members must still be reachable through the PLT, or redirecting
        # THEM was inert too; report (do not fail) so the table stays honest as the blob changes.
        inert_contract = [n for n in elf_cutover.CONTRACT
                          if not info.get("ET9KDB_" + n, {}).get("plt")]
        if inert_contract:
            print(f"  note: {len(inert_contract)} CONTRACT symbol(s) have no PLT call site "
                  f"either: {', '.join(sorted(inert_contract))}")
        if bad or missing:
            print(f"FAIL: {len(bad) + len(missing)} problem(s)")
            return 1
        print(f"  OK: all {len(elf_cutover.INERT_NO_CALLERS)} INERT_NO_CALLERS symbols have "
              f"zero references of any kind")
        return 0

    names = sorted(info)
    if args.sym:
        want = {s if s.startswith("ET9KDB_") else "ET9KDB_" + s for s in args.sym.split(",")}
        names = [n for n in names if n in want]
    print(f"[{args.inp}]")
    print(f"{'symbol':<40}{'addr':>9}{'pltBL':>7}{'dirBL':>7}{'addrTk':>8}{'dataRel':>9}{'strRef':>8}")
    for n in names:
        e = info[n]
        print(f"{n:<40}{e['addr']:>9x}{len(e['plt']):>7}{len(e['direct']):>7}"
              f"{len(e['addr_taken']):>8}{len(e['data_rel']):>9}{len(e['str_refs']):>8}")
        if args.sites:
            for a, mn in e["plt"] + e["direct"] + e["addr_taken"]:
                print(f"      {mn:>8} from 0x{a:x}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
