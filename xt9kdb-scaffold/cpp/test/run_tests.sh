#!/usr/bin/env bash
# Builds and runs the owned ET9KDB self-tests (pure C, no blob needed).
#   ./test/run_tests.sh [path/to/kdb.xml]      # default test/qwerty_pkb_even.xml
# Every stage runs even after a failure (no `set -e`); exits non-zero if any failed.
# A compile failure is fatal up front.
set -o pipefail            # a stage piped through `tail` must still report the binary's exit code
cd "$(dirname "$0")/.."                                   # -> cpp/
# kdb_decode_legacy.c is not shipped but decode_drv and owned_selftest still use it.
# kdb_rank.c: kdb_trace.c's xt9kdb_recall_top reaches the ranker.
SRC="src/kdb_geometry.c src/kdb_load_xml.c src/kdb_state.c src/kdb_tap.c src/kdb_trace.c src/kdb_nkl.c src/kdb_decode_legacy.c src/kdb_decode.c src/kdb_rank.c"
# A generic even-row layout matching the kQwertyPkbEn fixture; the athena KDB's uneven sensor
# bands are checked separately (kdb_band_selftest).
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
# CUTOVER mode for section 3b (SetKeyboardSize stores the scale in the context): under DIFF the
# blob's SetKeyboardSize owns that field, so the section compiles out of the build above.
build touchabicut -DXT9KDB_CUTOVER test/touch_abi_selftest.c
# The ctx+0x04/+0x08 selection contract; the only coverage of that state, since the context
# accessors are no-ops in DIFF mode.
build cutsel   -DXT9KDB_CUTOVER test/cutover_selection_selftest.c
# The shipped ranker against the per-candidate scorer kept in the test as the oracle.
build rankprep test/rank_prepare_selftest.c
build freqtab  test/freq_table_selftest.c
build recalldump test/recall_dump_drv.c
build ldbread  src/ldb_reader.c test/ldb_reader_selftest.c

RESULTS=(); FAILED=0
stage() {  # stage <label> <command...>: run it, record PASS/FAIL, never abort the suite
    local label="$1"; shift
    echo "== $label =="
    "$@"; local rc=$?
    if [ $rc -eq 0 ]; then RESULTS+=("PASS  $label")
    else RESULTS+=("FAIL  $label (exit $rc)"); FAILED=$((FAILED + 1)); fi
}

# The preserved 324 KDB (the live root uses uneven bands).
nkl_stage()  { "$OUT/nkl" ../kdb_backup/qwerty_pkb_root_324.xml | tail -3; }
# On-screen layout: a wide function key (the 540px SPACE) must not size the regional search
# box. Own process: one KDB per context.
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
# Multi-code layouts as golden files. They pin our emitter only: the blob cannot run on the host.
# Regenerate with `nkl --dump <xml>` and review the diff by hand.
multicode_stage() {
    local rc=0 d=test/data/abi_audit l
    for l in greek_pkb arabic_pkb arabic_vkb; do
        # Falls back to the reference copy for KDBs the app now builds itself.
        local f="../../app/src/main/assets/kdb/$l.xml"
        [ -f "$f" ] || f="../reference/kdb_${l##*_}/$l.xml"
        if ! "$OUT/nkl" --dump "$f" > "$OUT/$l.txt"; then
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
stage "athena uneven row bands (90/180/180)"                      "$OUT/band" ../../app/src/main/assets/kdb/keypad_pkb_athena_test.xml
stage "owned-recall index (O(N) builder == reference; negative control)" "$OUT/recall"
stage "gesture decoder (synthetic curved paths; shallow corners must resolve)" "$OUT/decode"
# Every corpus gesture x a slice of the recall table: prepared == one-shot == oracle, as bits.
rank_prepare_stage() { "$OUT/rankprep" 2>/dev/null | tail -4; }
stage "ranker path precompute (scores bit-identical to the original scorer)" rank_prepare_stage
stage "frequency table (blob + offsets == what the generator wrote; C-6)" "$OUT/freqtab"
# Every corpus gesture's recall top 8 (words and score bits) against the golden: pins the
# frequency table, its index and the recall prune. Regenerate with the same command on purpose.
recall_top8_stage() {
    local A=../../app/src/main/assets/kdb
    "$OUT/recalldump" test/qwerty_pkb_even.xml test/data/corpus_v2.txt \
        $A/keypad_pkb_athena.xml test/data/parity_corpus.txt $A/keypad_pkb_athena.xml test/data/collection1_corpus.txt \
        $A/keypad_pkb_athena_test.xml test/data/w6_s1_corpus.txt \
        $A/qwerty_vkb.xml test/data/vkb_s1_corpus.txt $A/qwerty_vkb.xml test/data/vkb_s2_corpus.txt \
        > "$OUT/recall_top8.txt" 2>/dev/null || { echo "  FAIL: recall dump exited non-zero"; return 1; }
    if diff -u test/data/recall_top8_golden.txt "$OUT/recall_top8.txt" > "$OUT/recall_top8.diff"; then
        echo "  OK: $(wc -l < "$OUT/recall_top8.txt" | tr -d ' ') gestures, recall top 8 identical to the golden"
    else
        echo "  FAIL: recall top 8 differs from test/data/recall_top8_golden.txt:"; head -20 "$OUT/recall_top8.diff"; return 1
    fi
}
stage "owned-recall top 8 over every corpus == committed golden"  recall_top8_stage
stage "owned LDB reader (mapped stored assets; blob-identical heap paths; P-9)" "$OUT/ldbread"
# Noisy (every TouchStart logs), so only the tail is shown.
touch_abi_stage() { "$OUT/touchabi" | tail -8; }
stage "touch/gesture ABI contracts (session, admission, size, getKeys, tap)" touch_abi_stage
touch_abi_cut_stage() { "$OUT/touchabicut" | tail -8; }
stage "touch/gesture ABI contracts, CUTOVER (per-context keyboard scale)" touch_abi_cut_stage
# Fails if N getKeys reads during a gesture (N in {0,1,20}) change the result; has its own
# negative control. ~1s.
getkeys_probe_stage() { test/getkeys_probe.sh | tail -6; }
stage "getKeys probe (one-shot capture rule, audit §4.3)"         getkeys_probe_stage
stage "DIFF harness self-check (model -> baseline -> diff must MATCH)" diff_stage
stage "DIFF harness negative control (corrupt a row -> must FAIL)" diff_negative_stage
# elf_cutover.py's INERT_NO_CALLERS is a claim about the binary; re-measure it whenever the
# (gitignored) pristine backup is present.
callsite_stage() {
    blob=../kdb_backup/libnative-lib.orig.so
    [ -f "$blob" ] || { echo "SKIP: no pristine blob backup; nothing to measure."; return 0; }
    python3 ../tools/abi_callsites.py --in "$blob" --check
}
stage "blob call-site evidence (INERT_NO_CALLERS has no callers)"  callsite_stage
# Needs a built CUTOVER APK; SKIPs otherwise.
stage "symbol-interposition guard"                                ../tools/check_interposition.sh   # cwd is cpp/

echo
echo "==== stage results ===="
for r in "${RESULTS[@]}"; do echo "  $r"; done
if [ $FAILED -ne 0 ]; then
    echo "$FAILED of ${#RESULTS[@]} STAGES FAILED"
    exit 1
fi
echo "ALL ${#RESULTS[@]} STAGES PASSED"
