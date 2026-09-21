#!/usr/bin/env bash
# check_interposition.sh — guard against the ELF symbol-interposition trap.
#
# THE BUG THIS EXISTS FOR (2026-08-03). ET9KDB_ProcessTrace is absent from elf_cutover.py's
# CONTRACT, so the patched blob still exported it. libkb.so ALSO defined it and called it
# internally through the PLT — and global symbol lookup gives the blob priority, so our own
# TouchEnd's call landed in the blob's copy, which read gesture state we had intercepted and never
# populated. Our store_trace_record was dead code at runtime for months and CKB swipe was silently
# broken. See PROJECT_CURRENT.MD.
#
# THE TRAP SET is the intersection of three things:
#   1. symbols libkb.so calls through the PLT (has a JUMP_SLOT/GLOB_DAT reloc for), AND
#   2. symbols libkb.so also DEFINES itself, AND
#   3. symbols the PATCHED blob still defines.
# Any symbol in all three is one where we think we are calling our own code and are not.
#
# Usage: check_interposition.sh [libkb.so] [libnative-lib.so]     (exit 1 if the trap set is non-empty)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/../.."
KB="${1:-}"
BLOB="${2:-$ROOT/app/src/main/jniLibs/arm64-v8a/libnative-lib.so}"

if [ -z "$KB" ]; then
  APK=$(ls "$ROOT"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)
  [ -n "$APK" ] || { echo "SKIP: no debug APK built; nothing to check."; exit 0; }
  TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
  unzip -p "$APK" lib/arm64-v8a/libkb.so > "$TMP/libkb.so" 2>/dev/null || {
    echo "SKIP: APK has no libkb.so (a -Pxt9Diff build?)."; exit 0; }
  KB="$TMP/libkb.so"
fi
[ -f "$BLOB" ] || { echo "SKIP: blob not found at $BLOB"; exit 0; }

nm_d()      { llvm-nm -D --defined-only "$1" 2>/dev/null || nm -D --defined-only "$1"; }
objdump_R() { llvm-objdump -R "$1" 2>/dev/null || objdump -R "$1"; }

imports=$(objdump_R "$KB" | grep -E "JUMP_SLOT|GLOB_DAT" | awk '{print $3}' | sort -u)
kbdef=$(nm_d "$KB"   | awk '{print $3}' | sort -u)
blobdef=$(nm_d "$BLOB" | awk '{print $3}' | sort -u)

trap_set=$(comm -12 <(echo "$imports") <(echo "$kbdef") | comm -12 - <(echo "$blobdef"))

if [ -n "$trap_set" ]; then
  echo "FAIL: symbol-interposition trap — libkb.so calls these through the PLT, defines them"
  echo "      itself, and the blob ALSO defines them, so the blob's copy wins at runtime:"
  echo "$trap_set" | sed 's/^/        /'
  echo
  echo "  Fix: call a static internal implementation instead of the exported name"
  echo "  (see process_trace_impl in kdb_trace.c), or add the symbol to elf_cutover.py's CONTRACT."
  exit 1
fi
echo "==== interposition guard: no trapped symbols ===="
