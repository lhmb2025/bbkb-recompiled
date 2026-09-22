# `dist/` — publishing the app and the language packs

Three things are published, all from this repo's GitHub releases:

| What | Where | Asset name |
| --- | --- | --- |
| Language packs (Nuance XT9 `.ldb`) | release `packs-<version>`, one asset per pack | the pack's original filename |
| App builds (APK) | release `v<versionName>` | `bbkb-<versionName>-release.apk` |
| The manifest the app fetches | `dist/manifest.json` on `main` | — |

The app reads the manifest from
`https://raw.githubusercontent.com/lhmb2025/bbkb-recompiled/main/dist/manifest.json`,
so **a release is invisible to clients until `dist/manifest.json` is committed and
pushed to `main`.**

The `.ldb` files themselves are not in the repo (411 MB, excluded from version
control). The maintainer's source folder is the publishing source; the catalogue's
filenames are committed as `tests/fixtures/catalogue.tsv` so the rules stay testable
without the files.

## The manifest contract (schema 1)

The client is written against exactly this shape:

```json
{
  "schema": 1,
  "generated": "2026-09-22T00:00:00Z",
  "app": {
    "release": {"versionCode": 1430, "versionName": "5.0.0-beta.18", "url": "https://github.com/lhmb2025/bbkb-recompiled/releases/download/v5.0.0-beta.18/bbkb-5.0.0-beta.18-release.apk", "sha256": "<hex lowercase>", "size": 12345678, "minSdk": 23, "notes": "short release notes"}
  },
  "packs": {
    "version": "1902.01",
    "baseUrl": "https://github.com/lhmb2025/bbkb-recompiled/releases/download/packs-1902.01/",
    "items": [
      {"locale": "af", "name": "Afrikaans", "file": "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb", "sha256": "<hex>", "size": 4400000},
      {"locale": "de_CH", "name": "German (Switzerland)", "file": "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb", "sha256": "<hex>", "size": 3843297, "group": "de"}
    ]
  }
}
```

`app` holds a `release` key and nothing else. (The original schema sketch also
showed a `debug` key; per the owner's decision of 2026-09-21 there is no debug
channel, so `publish_app.py` never writes one — see **Channels and signing**.)

Field notes:

* `packs.items[].file` is appended to `baseUrl` to form the download URL.
* `sha256` is lower-case hex of the asset as uploaded; `size` is its byte count.
  The client verifies both before installing.
* `locale` is **the identifier the app's own filename parser produces** —
  `language` or `language_COUNTRY`. It is not chosen here; it is ported from
  `app/src/main/java/dev/bbkb/ime/core/settings/screens/LanguagePacksScreen.kt`
  (`extractLanguageFromFileName` / `extractCountryFromFileName`) into
  `lib/ldb_names.py`, and `tests/test_ldb_names.py` re-asserts every expectation in
  the app's own `LdbFilenameParsingTest`. A pack published under a locale the app
  does not produce installs into a directory nothing ever looks in.
* `group` appears only on a region variant the engine has no entry of its own for
  (`de_CH`, `fr_CH`, `it_CH`, `nl_BE`): the engine resolves those onto the base
  language's slot, and the client can present them under that base language.

## Which packs are published, and why some are not

`publish_packs.py` prints a decision table, one row per file. Four outcomes:

* **PUBLISH** — the parsed locale is in the engine's 105-entry locale table
  (`app/src/main/assets/ldb/manifest.json`, a mirror of the table in the shipped
  blob) and `name` comes from that table; or it is a region variant of a language
  that is, and ships with `group`.
* **SKIP/refused** — the app's filename parser refuses the name. The five
  three-letter codes (`BRX`, `DOI`, `MAI`, `MNI`, `SAT`) plus `HL` and `SD`: seven
  files, none of whose languages exist in the engine table either, so no device on
  this build could load them.
* **SKIP/unsupported** — the locale parses but is absent from the engine table.
* **PUBLISH/override** — a table-locale override (below).
* **SKIP/duplicate** — another file already claims the locale. Nothing is
  silently overwritten; the loser's row names the winner. The current catalogue
  has none.

Current catalogue: **116 scanned, 109 published (4 of them overrides), 7 refused,
0 duplicate.** The 109 cover every one of the engine table's 105 locales plus the
four region variants it has no entry for.

### Table-locale overrides

Four packs have a region the *filename* cannot express, so the app's parser reads
them as a bare language: `*_ESusUNlatam_*` → `es`, `*_ENubUNZH_*` → `en`, and both
Big5 Chinese packs → `zh`. The engine table names all four (`es_419`, `en_ZH`,
`zh_TW`, `zh_HK`) but carries the region in the *asset path*, which a downloaded
file does not have.

`catalogue.TABLE_LOCALE_OVERRIDES` maps those four filenames to the table's
locale, so they publish as ordinary items (with the table's `name`) instead of
colliding with their base language. This is sound because the **download** path
installs a pack under the manifest's `locale` — into `nuance/<locale>/` — and
never re-parses the filename. The map is keyed by exact filename, every entry is
verified against the engine table at classification time (an entry the table
lacks is ignored, and the file falls back to its parsed locale), and it must
never be used to invent a locale the table does not have.

One consequence worth knowing: those four locales are reachable by download only.
Side-loading the same file by hand still lands on the base language, because that
path *does* re-parse the filename.

## The two commands

Both default to a dry run. `--upload` is what actually touches GitHub, and
`gh` must be on `PATH` and authenticated for it.

### Language packs — once per catalogue version

```sh
python3 dist/publish_packs.py --source <dir> --version 1902.01 [--dry-run] [--upload]
```

Scans `<dir>` and `<dir>/new`, applies the filename rules, computes sha256 + size,
writes the `packs` section of `dist/manifest.json` (creating the file with
`schema: 1` and an empty `app` if absent, and **preserving an existing `app`
section**), and prints the decision table.

With `--upload` it runs `gh release view packs-<version>` and, if the release is
not there, `gh release create packs-<version> --title "Language packs <version>"
--notes ...`; then `gh release upload packs-<version> <files...> --clobber` in
batches of 20; then verifies with `gh release view --json assets` that every
published file is present with the right size.

A dry run prints each of those commands instead of running it. It *does* still
hash the files and write `dist/manifest.json` — that manifest is a repo file the
maintainer reviews and commits, and computing it is the point of the dry run.
Idempotent either way: an unchanged catalogue leaves the manifest byte for byte
identical (`generated` is only stamped when the content moves), and the uploads
use `--clobber`.

### An app build — once per release

```sh
python3 dist/publish_app.py --apk <path> --build-type release [--notes "..."] [--dry-run] [--upload]
```

Reads `versionCode` / `versionName` from `app/build.gradle`, checks the APK's own
`versionCode` and `versionName` against them (`aapt2 dump badging`, found on
`PATH` or under the SDK named by `sdk.dir` in `local.properties`; without it the
APK is only confirmed to be an APK and a loud warning says the version was **not**
verified), computes sha256 + size, and with `--upload` creates/updates the release
`v<versionName>`, uploads `bbkb-<versionName>-release.apk --clobber`, and writes
`dist/manifest.json`'s `app.release`.

Unlike the packs publisher, a dry run here writes nothing: an `app` entry pointing
at a release that was never created is a live 404 for every client.

## Publish order

Packs, once per catalogue version:

1. `python3 dist/publish_packs.py --source <dir> --version <v>` — read the table.
2. Same command with `--upload`.
3. Commit `dist/manifest.json`, push to `main`.

An app build, once per release:

1. Bump `versionCode` / `versionName` in `app/build.gradle` and commit.
2. Build: `./gradlew assembleRelease` (with the release signing properties set).
3. `python3 dist/publish_app.py --apk app/build/outputs/apk/release/app-release.apk --build-type release --notes "..."` — check the dry run.
4. Same command with `--upload`.
5. Commit `dist/manifest.json` and **push to `main`** — the app reads the manifest
   from `main`, so until this lands no client sees the release.

## Channels and signing

There is one published channel: **release**. Every GitHub release carries
release-signed APKs only, and `dist/manifest.json` has an `app.release` entry and
never an `app.debug` one.

Release builds are signed with the maintainer's release key, supplied through the
`releaseStoreFile` / `releaseStorePassword` / `releaseKeyAlias` /
`releaseKeyPassword` Gradle properties. Android only installs an update signed
with that same key, so the key must not change between releases; a rotated key
means every user has to uninstall and reinstall, losing their learned dictionary.

Debug builds install as a separate package (`dev.bbkb.ime.debug`) and have no
update channel at all. `publish_app.py --build-type debug` is accepted only for a
local dry run — it refuses `--upload` and never writes to the manifest.

## Gate

```sh
python3 -m unittest discover -s dist/tests -t .
```

Standard library only, fully offline: the CLI tests run the real scripts in a
subprocess with an empty `PATH`, so neither `gh` nor `aapt2` can be found and
nothing can reach GitHub. Run it before any `--upload`.

## Layout

```
dist/
  publish_packs.py        language-pack catalogue -> release packs-<version> + manifest packs
  publish_app.py          one APK -> release v<versionName> + manifest app.release
  manifest.json           what the app fetches from main
  lib/
    ldb_names.py          the app's filename rules, ported (locale, region, aliases, refusals)
    catalogue.py          the publish/refuse/unsupported/duplicate decisions
    manifest.py           manifest read / section merge / idempotent write
    hashing.py            sha256 + size
    appbuild.py           app/build.gradle and APK version reading
    gh.py                 the only place that shells out to gh; dry run prints instead
  tests/                  unittest suite + fixtures/catalogue.tsv (the 116 catalogue names)
```
