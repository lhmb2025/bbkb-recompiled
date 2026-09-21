#!/usr/bin/env bash
# cutover_deploy.sh — toggle the blob between ORIGINAL and CUTOVER (owned-KDB) state.
#
# CUTOVER hands the blob's 28-symbol ET9KDB_* contract to our libkb.so:
#   * elf_cutover.py UNDEFs those 28 symbols in libnative-lib.so and repurposes the dead
#     libdl.so DT_NEEDED slot -> "libkb.so" (in place; no section growth).
#   * the CUTOVER Gradle build (the default; opt out with -Pxt9Diff) compiles the owned KDB
#     as libkb.so and packages it.
#   * at load, System.loadLibrary("native-lib") pulls libkb.so via DT_NEEDED; all 28 JUMP_SLOTs
#     land in our code. ET9KDB_Init stays with the blob (not in the contract).
# The count is len(elf_cutover.CONTRACT) and `./cutover_deploy.sh status` prints the live number —
# update this comment from that, not from memory. The 22 exports outside the contract are Init,
# the three deliberate tap-path ones (NOT_REDIRECTED) and the 18 the blob never calls at all
# (INERT_NO_CALLERS; re-measured by tools/abi_callsites.py --check).
#
# A/B on device:
#   A (baseline): ./cutover_deploy.sh restore && ./gradlew installDebug -Pxt9Diff
#   B (cutover):  ./cutover_deploy.sh patch   && ./gradlew installDebug
# Both states log XT9NKL/XT9KEYS the same way; the owned NKL was validated byte-equal to the
# blob's across probes A/B/U/C/D, so a transparent cutover shows identical dumps + recognition.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNI="$HERE/../../app/src/main/jniLibs/arm64-v8a"
BLOB="$JNI/libnative-lib.so"
ORIG="$HERE/../kdb_backup/libnative-lib.orig.so"  # pristine backup (never patched; kept out of jniLibs so it isn't packaged into the APK)
PATCHER="$HERE/elf_cutover.py"

cmd="${1:-status}"
case "$cmd" in
  patch)
    [ -f "$ORIG" ] || cp "$BLOB" "$ORIG"          # one-time pristine backup
    python3 "$PATCHER" --in "$ORIG" --out "$BLOB"  # always patch FROM pristine
    echo
    echo "Blob patched in jniLibs. Now build+install the CUTOVER APK (the default build):"
    echo "    ./gradlew installDebug"
    ;;
  restore)
    [ -f "$ORIG" ] || { echo "No backup ($ORIG); blob may already be pristine."; exit 0; }
    cp "$ORIG" "$BLOB"
    python3 "$PATCHER" --in "$BLOB" --verify
    echo
    echo "Blob restored. Build+install the baseline (DIFF) APK:"
    echo "    ./gradlew installDebug -Pxt9Diff"
    ;;
  status)
    python3 "$PATCHER" --in "$BLOB" --verify
    [ -f "$ORIG" ] && echo "  (pristine backup present: libnative-lib.orig.so)" \
                   || echo "  (no backup yet — 'patch' will make one)"
    ;;
  *)
    echo "usage: $0 {patch|restore|status}"; exit 2 ;;
esac
