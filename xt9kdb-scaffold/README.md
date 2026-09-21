# xt9kdb-scaffold

The owned reimplementation of the Nuance engine's `ET9KDB_*` keyboard-database subsystem, and
the tooling that puts it in front of the closed blob.

| Path | What it is |
|---|---|
| `cpp/src/`, `cpp/include/` | The owned module: XML layout loading, key geometry, tap resolution, swipe-path capture, the gesture decoder and ranker. Compiled into the app by `app/src/main/cpp/CMakeLists.txt`. |
| `cpp/test/` | Offline self-tests and the DIFF harness. `run_tests.sh` is the native gate (15 stages); `data/abi_audit/` holds the committed NKL dumps a stage re-diffs. |
| `tools/` | Cutover tooling: `elf_cutover.py` patches the blob so the contract symbols resolve to the owned module; `cutover_deploy.sh patch|restore`; two gate helpers. See `tools/README.md`. |
| `kdb_backup/` | `qwerty_pkb_root_324.xml`, the reference layout the NKL emitter stage compares against. The pristine blob copy (`libnative-lib.orig.so`) is written here by `cutover_deploy.sh` and is gitignored. |
| `gradle/app-build.gradle.snippet` | The `externalNativeBuild` wiring, already merged into `app/build.gradle`; kept as the reference. |

## Build modes

- **CUTOVER** (the shipped default): the module exports `ET9KDB_*` and replaces the blob's
  KDB subsystem. `-DXT9KDB_CUTOVER=ON` in CMake.
- **DIFF** (`./gradlew -Pxt9Diff …`): the module exports `xt9kdb_*` and runs as a passive
  self-check beside an unpatched blob, the A/B control for recognition debugging. Run
  `tools/cutover_deploy.sh restore` first; `… patch` returns to CUTOVER.

The reverse-engineering notes, probes and plans that produced this module were retired from
the repository on 2026-09-21 and live in the maintainer's local attic.
