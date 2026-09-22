# xt9kdb-scaffold/tools

Build and gate tooling for the owned ET9KDB module (the "cutover"). The reverse-engineering
helpers that produced the module (Frida hooks, structure decoders, corpus extractors) were
retired from the repository on 2026-09-21; the cutover is the way forward and these four are
what it needs.

| Script | Role |
|---|---|
| `elf_cutover.py` | Patches the Nuance engine blob so the ET9KDB_* contract symbols resolve to the owned module (`libkb.so`). Holds the CONTRACT list and `INERT_NO_CALLERS`. |
| `cutover_deploy.sh` | `patch` / `restore` the blob in `app/src/main/jniLibs/arm64-v8a/`; keeps the pristine copy in `../kdb_backup/libnative-lib.orig.so` (gitignored, made on first `patch`). |
| `abi_callsites.py` | Census of every internal reference to each export in the pristine blob; `--check` is a `run_tests.sh` stage. |
| `check_interposition.sh` | Symbol-interposition guard; a `run_tests.sh` stage. |

Run the whole native gate from `xt9kdb-scaffold/cpp/test/run_tests.sh`.
