#!/usr/bin/env bash
# Toggles the blob between pristine and CUTOVER (owned KDB) state.
# CUTOVER: elf_cutover.py UNDEFs the ET9KDB_* contract (28 symbols; `status` prints the live
# len(CONTRACT)) in libnative-lib.so and repoints the dead libdl.so DT_NEEDED slot at libkb.so, in
# place. ET9KDB_Init, the three NOT_REDIRECTED tap-path exports and the 18 INERT_NO_CALLERS stay.
#   A (baseline): ./cutover_deploy.sh restore && ./gradlew installDebug -Pxt9Diff
#   B (cutover):  ./cutover_deploy.sh patch   && ./gradlew installDebug
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JNI="$HERE/../../app/src/main/jniLibs/arm64-v8a"
BLOB="$JNI/libnative-lib.so"
ORIG="$HERE/../kdb_backup/libnative-lib.orig.so"  # pristine backup, kept out of jniLibs so it is not packaged
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
