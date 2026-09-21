#!/usr/bin/env bash
# getkeys_probe.sh — the §4.3 experiment, offline: does reading `getKeys` during a gesture
# perturb anything?
#
# Replays the W6 sitting-1 corpus (240 labeled real KEY2 swipes, SENSOR space) through the real
# touch ABI with N getKeys-equivalent reads interleaved between every pair of gesture stages, for
# N in {0, 1, 20}, and compares the arms. Then runs a negative control (--drop=4, every fourth
# TouchMove skipped — the shape the timing hypothesis predicts) to prove the metrics can move.
#
# PASS means: the three N arms are identical in every number AND in the per-gesture digest, and
# the negative control is not. FAIL means a getKeys read perturbs the engine — in which case do
# NOT remove the one-shot capture latch from CkbKeyGridCapture / KeyboardSwitcher.
#
# Run standalone, or as the run_tests.sh stage "getKeys probe (one-shot capture rule, audit §4.3)".
set -o pipefail
cd "$(dirname "$0")/.."                                   # -> cpp/

CORPUS="${CORPUS:-test/data/w6_s1_corpus.txt}"
KDB="${KDB:-../../app/src/main/assets/kdb/athena/qwerty_pkb.xml}"   # the KEY2 layout these ran on
WORDS="${WORDS:-test/data/w24_words.txt}"
CC="${CC:-cc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# kdb_decode.c + kdb_rank.c are NOT in run_tests.sh's shared SRC list (the other stages exercise
# the superseded first-cut decoder); this experiment needs the shipped decoder and ranker.
SRC="src/kdb_geometry.c src/kdb_load_xml.c src/kdb_state.c src/kdb_tap.c src/kdb_trace.c
     src/kdb_nkl.c src/kdb_decode.c src/kdb_rank.c"
# shellcheck disable=SC2086  # $SRC is a deliberate word-split list
$CC -std=c11 -O2 -Iinclude $SRC test/getkeys_probe_drv.c -lm -o "$OUT/gkp" || {
    echo "BUILD FAILED: getkeys_probe_drv"; exit 1; }

run() {   # run <label> <extra-args...>  -> writes "$OUT/<label>.txt", echoes the summary lines
    local label="$1"; shift
    "$OUT/gkp" "$CORPUS" "$KDB" "$WORDS" "$@" 2>/dev/null > "$OUT/$label.txt" || return 1
    grep '^====' "$OUT/$label.txt"
}

echo "corpus: $CORPUS   kdb: $KDB"
for N in 0 1 20; do
    run "n$N" "--n=$N" || { echo "  ERROR: arm N=$N did not run"; exit 1; }
    echo
done
echo "negative control (every 4th TouchMove dropped):"
run "drop" "--n=0" "--drop=4" || { echo "  ERROR: control did not run"; exit 1; }
echo

d0=$(grep '^==== digest' "$OUT/n0.txt")
d1=$(grep '^==== digest' "$OUT/n1.txt")
d20=$(grep '^==== digest' "$OUT/n20.txt")
dctl=$(grep '^==== digest' "$OUT/drop.txt")

rc=0
if [ "$d0" = "$d1" ] && [ "$d0" = "$d20" ]; then
    echo "  OK: N=0, N=1 and N=20 are byte-identical ($d0)"
else
    echo "  FAIL: the arms differ — a getKeys read PERTURBS the engine. Keep the one-shot rule."
    echo "    N=0  $d0"; echo "    N=1  $d1"; echo "    N=20 $d20"
    rc=1
fi
if [ "$dctl" != "$d0" ]; then
    echo "  OK: the negative control moves the digest, so the metrics are not merely insensitive"
else
    echo "  FAIL: the negative control did NOT move — the harness cannot see a real perturbation"
    rc=1
fi
exit $rc
