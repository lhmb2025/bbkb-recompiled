# BBKB

BBKB is a maintained Android input method for physical-keyboard phones, grown out of a
recompilation of the BlackBerry Keyboard. It runs daily on a BlackBerry KEY2 and is built for
the Minimal Phone, the Unihertz Titan Pocket and the Light Phone III as well. It installs as
`dev.bbkb.ime` (debug builds as `dev.bbkb.ime.debug`) and can live alongside the original
`com.blackberry.keyboard`.

**Status:** `5.0.0-beta` · actively developed.

## What is in the tree

- **The IME** — `app/src/main/java/dev/bbkb/ime/`: the service (`core/`), the text pipeline
  (`core/textinput/`), the capacitive-keyboard gesture engine (`core/gesture/arbiter/`), device
  profiles and hardware-key handling (`core/device/`), Jetpack Compose settings with search
  (`core/settings/`), the keyboard views, suggestion strip and unified input boards
  (`keyboard/`), and the personal dictionary (`personaldictionary/`).
- **The Nuance bridge** — `app/src/main/java/com/blackberry/nuanceshim/`: the JNI bridge to the
  closed Nuance XT9 engine. This is the one package that keeps its original name: the engine
  blob binds to these classes by name (see its `package-info.java`).
- **The owned keyboard-database engine** — `xt9kdb-scaffold/cpp/`: a C reimplementation of
  the engine's `ET9KDB_*` subsystem (key geometry, tap resolution, swipe capture and decoding),
  with its offline self-tests in `cpp/test/`. `xt9kdb-scaffold/tools/` patches the blob so the
  contract symbols resolve to it ("cutover"), which is the default build.
- **Layouts and dictionaries** — `app/src/main/assets/kdb/` (XT9 keyboard layouts, with
  per-device variant folders holding measured key geometry) and `app/src/main/assets/ldb/`
  (the bundled language databases).

## Requirements

| | |
|---|---|
| JDK | 21 |
| Gradle | 9.3.1 (wrapper) · AGP 9.1.1 |
| Kotlin | 2.2.10 |
| SDK | compileSdk/targetSdk 36 · minSdk 23 |
| NDK/CMake | arm64-v8a only |

The wrapper jar is not committed; `gradle wrapper --gradle-version 9.3.1` restores it.
`gradle.properties` carries two load-bearing opt-outs (`android.builtInKotlin=false`,
`android.newDsl=false`); read the comment there before touching them.

## Build, test, install

```bash
./gradlew installDebug
```

The pre-commit gate is the JVM tests plus the build, and the native self-tests:

```bash
set -o pipefail; ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

```bash
xt9kdb-scaffold/cpp/test/run_tests.sh
```

Never judge a Gradle pipe by its tail without `set -o pipefail`.

Then enable the keyboard: Settings → System → Keyboard → On-screen keyboard → "BBKB". The
"BBKB Helper" accessibility service is optional; it intercepts the Sym, emoji and mic keys on
phones whose ROM swallows them (the Minimal Phone needs it, the KEY2 does not).

`-Pxt9Diff` builds the DIFF variant: the owned module runs as a passive self-check next to an
unpatched blob, the A/B control when debugging recognition. Run
`xt9kdb-scaffold/tools/cutover_deploy.sh restore` first, and `... patch` to come back.

## Documentation

The engineering history, audits and trackers are kept outside this repository. What a
contributor needs is in the code: every non-obvious decision carries a comment saying what it
fixes and when, and the tests describe the behaviours they pin.

## License

No license is granted at this time. The original BlackBerry Keyboard is proprietary software
of BlackBerry Limited and the Nuance XT9 engine blob and language databases remain proprietary;
this project is provided as-is for learning and personal use.
