#!/usr/bin/env bash
# run_tests.sh — build + run the owned ET9KDB self-tests and the DIFF harness self-check.
# These run WITHOUT the blob (pure owned C), so they work in CI / the dev sandbox.
#
#   ./test/run_tests.sh                  # uses test/qwerty_pkb_even.xml
#   ./test/run_tests.sh path/to/kdb.xml
#
# Exit status is honest: EVERY stage runs even when an earlier one fails (the old `set -e` let one
# red stage silently switch off every stage after it), a per-stage PASS/FAIL table is printed at
# the end, and the script exits non-zero if any stage failed. A compile failure is fatal up front.
set -o pipefail            # a stage piped through `tail` must still report the binary's exit code
cd "$(dirname "$0")/.."                                   # -> cpp/
# kdb_decode_legacy.c is NOT in the shipped library (CUTOVER); it is built here and in the
# DIFF branch only, because decode_drv + owned_selftest still exercise the first-cut decoder.
SRC="src/kdb_geometry.c src/kdb_load_xml.c src/kdb_state.c src/kdb_tap.c src/kdb_trace.c src/kdb_nkl.c src/kdb_decode_legacy.c"
# Unit tests run against a GENERIC even-row layout matching the kQwertyPkbEn fixture; the live athena
# KDB uses the firmware's UNEVEN sensor bands and is checked separately (kdb_band_selftest).
XML="${1:-test/qwerty_pkb_even.xml}"
CC="${CC:-cc}"; OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
echo "KDB: $XML"

build() {  # build <out-name> [extra cc args...] <test source>
    local out="$1"; shift
    # shellcheck disable=SC2086  # $SRC is a deliberate word-split list
    $CC -std=c11 -Iinclude $SRC "$@" -lm -o "$OUT/$out" || { echo "BUILD FAILED: $out"; exit 1; }
}
build selftest test/owned_selftest.c
build diff     test/diff_harness.c
build decode   test/decode_drv.c
build nkl      test/kdb_nkl_selftest.c
build band     test/kdb_band_selftest.c
build recall   test/recall_index_selftest.c
build touchabi test/touch_abi_selftest.c
# Same source in CUTOVER mode: section 3b (SetKeyboardSize stores the scale in the CONTEXT) is
# about a context field only the cutover build may write — under DIFF the blob's own
# SetKeyboardSize owns it and ours must not, so that section compiles out of the build above.
build touchabicut -DXT9KDB_CUTOVER test/touch_abi_selftest.c
# CUTOVER-mode build: the ctx+0x04/+0x08 selection contract. The other tests build in DIFF mode,
# where the context accessors are no-ops, so this is the only coverage of the blob-facing state.
build cutsel   -DXT9KDB_CUTOVER test/cutover_selection_selftest.c

RESULTS=(); FAILED=0
stage() {  # stage <label> <command...>: run it, record PASS/FAIL, never abort the suite
    local label="$1"; shift
    echo "== $label =="
    "$@"; local rc=$?
    if [ $rc -eq 0 ]; then RESULTS+=("PASS  $label")
    else RESULTS+=("FAIL  $label (exit $rc)"); FAILED=$((FAILED + 1)); fi
}

# validate against the preserved 324 KDB (the live root is now the uneven-band geometry).
nkl_stage()  { "$OUT/nkl" ../kdb_backup/qwerty_pkb_root_324.xml | tail -3; }
# The on-screen layout, where a wide FUNCTION key (the 540px SPACE) must NOT size the regional
# search box. Its own process: one KDB per context, as in the live code.
nkl_vkb_stage() { "$OUT/nkl" --vkb ../../app/src/main/assets/kdb/qwerty_vkb.xml | tail -9; }
diff_stage() { "$OUT/diff" "$XML" > "$OUT/baseline.csv" && "$OUT/diff" "$XML" "$OUT/baseline.csv"; }
diff_negative_stage() {
    [ -s "$OUT/baseline.csv" ] || { echo "  ERROR: no baseline (self-check stage failed)"; return 1; }
    sed 's/^113,0,0,107,127/999,0,0,107,127/' "$OUT/baseline.csv" > "$OUT/bad.csv"
    if "$OUT/diff" "$XML" "$OUT/bad.csv" >/dev/null; then echo "  ERROR: did not detect mismatch"; return 1
    else echo "  OK: mismatch detected"; fi
}

stage "owned self-test"                                           "$OUT/selftest" "$XML"
stage "cutover selection contract (ctx+0x04 selector, NKL+0x04 invariant)" "$OUT/cutsel" "$XML"
stage "NKL emitter (geometry must match the 324 XT9NKL dump)"     nkl_stage
stage "NKL vkb header (regional maxW: RADX 120, not the 540 SPACE)" nkl_vkb_stage
# L13 (audit §5): the multi-code layouts, as golden files. These pin OUR emitter, not the blob's —
# the blob is an aarch64/bionic ELF whose ET9KDB_* need a live init()-built context, so no
# host-side A/B of Load_XmlKDB is possible (see diff_harness.c's header) and no Greek/Arabic
# getKeys capture exists. Regenerate with `nkl --dump <xml>` and review the diff by hand.
multicode_stage() {
    local rc=0 d=test/data/abi_audit l
    for l in greek_pkb arabic_pkb arabic_vkb; do
        if ! "$OUT/nkl" --dump "../../app/src/main/assets/kdb/$l.xml" > "$OUT/$l.txt"; then
            echo "  FAIL $l: dump failed"; rc=1; continue
        fi
        if diff -u "$d/nkl_${l}_after.txt" "$OUT/$l.txt" > "$OUT/$l.diff"; then
            echo "  OK   $l matches $d/nkl_${l}_after.txt"
        else
            echo "  FAIL $l differs from $d/nkl_${l}_after.txt:"; head -24 "$OUT/$l.diff"; rc=1
        fi
    done
    return $rc
}
stage "multi-code keyCodes (L13): Greek/Arabic NKL == committed dumps" multicode_stage
stage "athena uneven row bands (90/180/180)"                      "$OUT/band" ../../app/src/main/assets/kdb/athena/qwerty_pkb.xml
stage "owned-recall index (O(N) builder == reference; negative control)" "$OUT/recall"
stage "gesture decoder (synthetic curved paths; shallow corners must resolve)" "$OUT/decode"
# The touch/gesture ABI contracts (the 2026-09 KDB touch-ABI audit, now in the local attic). Noisy on
# stdout (every TouchStart logs), so only the tail matters when it passes.
touch_abi_stage() { "$OUT/touchabi" | tail -8; }
stage "touch/gesture ABI contracts (session, admission, size, getKeys, tap)" touch_abi_stage
touch_abi_cut_stage() { "$OUT/touchabicut" | tail -8; }
stage "touch/gesture ABI contracts, CUTOVER (per-context keyboard scale)" touch_abi_cut_stage
# The §4.3 experiment that retired the one-shot `getKeys` capture rule (audit unknowns #1/#2,
# pinned 2026-09-21). Replays the 240-swipe W6 corpus through the touch ABI with N getKeys reads
# interleaved between stages, N in {0,1,20}, and fails if the arms diverge. It carries its own
# negative control, so it cannot pass by being insensitive. ~1s.
getkeys_probe_stage() { test/getkeys_probe.sh | tail -6; }
stage "getKeys probe (one-shot capture rule, audit §4.3)"         getkeys_probe_stage
stage "DIFF harness self-check (model -> baseline -> diff must MATCH)" diff_stage
stage "DIFF harness negative control (corrupt a row -> must FAIL)" diff_negative_stage
# The cutover only hands over a symbol the blob reaches THROUGH THE PLT, so elf_cutover.py's
# INERT_NO_CALLERS list (the 18 ET9KDB_* exports with no reference of any kind inside the blob) is
# a claim about a binary and can go stale the day that binary changes. Re-measure it whenever the
# pristine backup is present; it is gitignored, so a fresh clone SKIPs.
callsite_stage() {
    blob=../kdb_backup/libnative-lib.orig.so
    [ -f "$blob" ] || { echo "SKIP: no pristine blob backup; nothing to measure."; return 0; }
    python3 ../tools/abi_callsites.py --in "$blob" --check
}
stage "blob call-site evidence (INERT_NO_CALLERS has no callers)"  callsite_stage
# Symbol-interposition guard (needs a built CUTOVER APK; prints SKIP and passes otherwise).
stage "symbol-interposition guard"                                ../tools/check_interposition.sh   # cwd is cpp/

echo
echo "==== stage results ===="
for r in "${RESULTS[@]}"; do echo "  $r"; done
if [ $FAILED -ne 0 ]; then
    echo "$FAILED of ${#RESULTS[@]} STAGES FAILED"
    exit 1
fi
echo "ALL ${#RESULTS[@]} STAGES PASSED"
