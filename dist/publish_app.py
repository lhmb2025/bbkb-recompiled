#!/usr/bin/env python3
"""Publish one app build as an asset of the release tagged `v<versionName>`.

    python3 dist/publish_app.py --apk <path> --build-type release [--notes "..."] [--upload]

Only RELEASE builds are published (owner decision, 2026-09-21): every GitHub
release carries release-signed APKs, and `dist/manifest.json` has an
`app.release` entry and no `app.debug` entry, ever. `--build-type debug` is
accepted for local dry runs -- so the version/APK checks can be exercised
against a debug build -- but it refuses `--upload` and never writes to the
manifest.

Dry run is the default: it prints the gh commands it would run and touches
nothing on GitHub. Unlike publish_packs.py it does not write the manifest in
dry-run mode either, because an app entry that points at a release which was
never created would be a live 404 for every client.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import appbuild                                       # noqa: E402
from lib import manifest as manifest_lib                       # noqa: E402
from lib.gh import Gh, GhError                                 # noqa: E402
from lib.hashing import digest_and_size, human_size            # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PUBLISHABLE_BUILD_TYPES = ("release",)


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="publish_app.py",
        description="Publish an APK and update dist/manifest.json's app section.")
    parser.add_argument("--apk", required=True, help="path to the built .apk")
    parser.add_argument("--build-type", required=True, choices=("debug", "release"),
                        help="release is the only publishable channel; debug is dry-run only")
    parser.add_argument("--notes", default="", help="short release notes for the manifest entry")
    parser.add_argument("--dry-run", action="store_true",
                        help="the default: print what would happen, change nothing")
    parser.add_argument("--upload", action="store_true",
                        help="create/update the release, upload the APK, write the manifest")
    parser.add_argument("--manifest", default=os.path.join(REPO_ROOT, "dist", "manifest.json"))
    parser.add_argument("--repo", default=manifest_lib.DEFAULT_REPO)
    parser.add_argument("--repo-root", default=REPO_ROOT)
    parser.add_argument("--gradle", default=None,
                        help="app/build.gradle (default: <repo-root>/app/build.gradle)")
    return parser.parse_args(argv)


def check_version(apk_info, gradle, build_type, echo=print):
    """Does the APK match app/build.gradle? Returns False on a hard mismatch."""
    expected_name = gradle.version_name_for(build_type)
    echo("gradle    versionCode=%d versionName=%s (base %s, %s suffix %r)"
         % (gradle.version_code, expected_name, gradle.version_name, build_type,
            gradle.suffixes.get(build_type, "")))
    if apk_info.warning:
        echo("WARNING:  %s" % apk_info.warning)
        return True
    echo("apk       versionCode=%s versionName=%s package=%s (via %s)"
         % (apk_info.version_code, apk_info.version_name, apk_info.package, apk_info.source))
    ok = True
    if apk_info.version_code != gradle.version_code:
        echo("MISMATCH: the APK's versionCode (%s) is not app/build.gradle's (%d). "
             "Rebuild, or bump the gradle file." % (apk_info.version_code, gradle.version_code))
        ok = False
    if apk_info.version_name and apk_info.version_name != expected_name:
        echo("MISMATCH: the APK's versionName (%s) is not the expected %s."
             % (apk_info.version_name, expected_name))
        ok = False
    return ok


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    dry_run = not args.upload or args.dry_run

    if args.build_type not in PUBLISHABLE_BUILD_TYPES:
        if args.upload:
            print("refusing to upload a %s build." % args.build_type)
            print("Only release-signed builds are published: every GitHub release carries "
                  "release APKs only, and dist/manifest.json has no app.%s entry."
                  % args.build_type)
            print("Build with `./gradlew assembleRelease` (release signing properties set) and "
                  "publish that, or drop --upload to dry-run this one locally.")
            return 2
        print("NOTE: %s is not a published channel. This is a local dry run only -- "
              "dist/manifest.json will NOT be written." % args.build_type)

    if not os.path.isfile(args.apk):
        print("no such APK: %s" % args.apk)
        return 2

    gradle_path = args.gradle or os.path.join(args.repo_root, "app", "build.gradle")
    gradle = appbuild.read_gradle_version(gradle_path)
    apk_info = appbuild.read_apk(args.apk, args.repo_root)
    versions_ok = check_version(apk_info, gradle, args.build_type)
    if not versions_ok and args.upload:
        print("refusing to publish a mismatched APK.")
        return 1

    digest, size = digest_and_size(args.apk)
    tag = gradle.tag()
    asset_name = gradle.asset_name(args.build_type)
    entry = {
        "versionCode": gradle.version_code,
        "versionName": gradle.version_name_for(args.build_type),
        "url": manifest_lib.app_asset_url(gradle.version_name, asset_name, args.repo),
        "sha256": digest,
        "size": size,
        "minSdk": apk_info.min_sdk or gradle.min_sdk,
        "notes": args.notes,
    }
    print("")
    print("asset     %s (%s)" % (asset_name, human_size(size)))
    print("sha256    %s" % digest)
    print("url       %s" % entry["url"])
    print("manifest  app.%s" % args.build_type)

    gh = Gh(args.repo, dry_run=dry_run)
    print("")
    print("release %s on %s:" % (tag, args.repo))
    try:
        gh.ensure_release(tag, "BBKB %s" % gradle.version_name,
                          args.notes or "BBKB %s" % gradle.version_name)
        if not dry_run:
            # Copy the APK under its published name first: `gh release upload`
            # names the asset after the file on disk, and the build output is
            # app-release.apk, not bbkb-<version>-release.apk.
            import shutil
            import tempfile
            staging = tempfile.mkdtemp(prefix="bbkb-publish-")
            staged = os.path.join(staging, asset_name)
            shutil.copy2(args.apk, staged)
            gh.upload(tag, [staged], batch_size=1)
            assets = gh.assets(tag)
            if assets is None:
                print("  could not read the release's assets -- verify by hand")
            elif assets.get(asset_name) in (None, size):
                print("  verified: %s is on the release" % asset_name)
            else:
                print("  SIZE MISMATCH %s: release has %s, local file is %s"
                      % (asset_name, assets.get(asset_name), size))
            shutil.rmtree(staging, ignore_errors=True)
        else:
            print("  would upload the APK as %s (--clobber)" % asset_name)
    except GhError as error:
        print("")
        print("gh failed: %s" % error)
        return 1

    if dry_run:
        print("")
        print("DRY RUN: nothing was created, uploaded or written. "
              "Re-run with --upload to publish.")
        return 0

    previous = manifest_lib.load(args.manifest)
    app_section = dict(previous.get("app", {}))
    app_section[args.build_type] = entry
    document = manifest_lib.merge_section(previous, "app", app_section)
    wrote = manifest_lib.save(args.manifest, document, previous)
    print("")
    print("%s %s (packs section preserved: %s items)"
          % ("wrote" if wrote else "unchanged", args.manifest,
             len(document.get("packs", {}).get("items", []))))
    print("Now commit dist/manifest.json and push to main -- the app reads the manifest "
          "from main, so the release is not visible to clients until you do.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
