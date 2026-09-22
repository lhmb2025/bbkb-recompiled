#!/usr/bin/env python3
"""Publish the Nuance XT9 language-pack catalogue as one GitHub release.

    python3 dist/publish_packs.py --source <dir> --version 1902.01 [--dry-run] [--upload]

Each .ldb becomes ONE asset of the release tagged `packs-<version>`, under its
own original filename, so a client downloads exactly the one pack it needs.

What "dry run" means here: no release is created, nothing is uploaded, nothing
on GitHub is touched. Hashing and `dist/manifest.json` still happen -- the
manifest is a repo file the maintainer commits and pushes, and computing it is
the point of the dry run. Pass --upload to also do the GitHub half.

Idempotent: re-running with an unchanged catalogue leaves the manifest byte for
byte identical (`generated` is only bumped when the content moves), and the
uploads use --clobber.
"""

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib import catalogue                                      # noqa: E402
from lib import manifest as manifest_lib                       # noqa: E402
from lib.gh import Gh, GhError                                 # noqa: E402
from lib.hashing import digest_and_size, human_size            # noqa: E402
from lib.ldb_names import EngineTable                          # noqa: E402

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

STATUS_LABEL = {
    catalogue.PUBLISHED: "PUBLISH",
    catalogue.REFUSED: "SKIP/refused",
    catalogue.UNSUPPORTED: "SKIP/unsupported",
    catalogue.DUPLICATE: "SKIP/duplicate",
}


def parse_args(argv):
    parser = argparse.ArgumentParser(
        prog="publish_packs.py",
        description="Publish the .ldb language-pack catalogue and write dist/manifest.json.")
    parser.add_argument("--source", required=True,
                        help="folder holding the .ldb files (its 'new' subfolder is scanned too)")
    parser.add_argument("--version", required=True,
                        help="catalogue version, e.g. 1902.01 -- becomes the tag packs-<version>")
    parser.add_argument("--dry-run", action="store_true",
                        help="the default: print what would be uploaded, touch nothing on GitHub")
    parser.add_argument("--upload", action="store_true",
                        help="actually create/update the release and upload the assets")
    parser.add_argument("--manifest", default=os.path.join(REPO_ROOT, "dist", "manifest.json"),
                        help="manifest to update (default: dist/manifest.json)")
    parser.add_argument("--repo", default=manifest_lib.DEFAULT_REPO,
                        help="owner/name of the GitHub repo (default: %s)" % manifest_lib.DEFAULT_REPO)
    parser.add_argument("--batch-size", type=int, default=20,
                        help="assets per `gh release upload` call (default: 20)")
    parser.add_argument("--repo-root", default=REPO_ROOT,
                        help="repo root, for reading the engine locale table")
    return parser.parse_args(argv)


def print_table(decisions, echo=print):
    width = max(len(d.file_name) for d in decisions) if decisions else 10
    echo("")
    echo("%-*s  %-16s  %-8s  %s" % (width, "FILE", "DECISION", "LOCALE", "NAME / REASON"))
    echo("-" * (width + 60))
    for decision in decisions:
        echo("%-*s  %-16s  %-8s  %s"
             % (width, decision.file_name, STATUS_LABEL[decision.status],
                decision.locale or "-",
                decision.name if decision.published else decision.reason))
    echo("")


def print_summary(decisions, echo=print):
    tally = catalogue.counts(decisions)
    published = [d for d in decisions if d.published]
    total_bytes = sum(d.size or 0 for d in published)
    echo("scanned   %d files" % len(decisions))
    echo("published %d%s" % (tally[catalogue.PUBLISHED],
                             (" (%s)" % human_size(total_bytes)) if total_bytes else ""))
    echo("skipped   %d refused, %d unsupported, %d duplicate"
         % (tally[catalogue.REFUSED], tally[catalogue.UNSUPPORTED], tally[catalogue.DUPLICATE]))
    variants = [d for d in published if d.group]
    if variants:
        echo("variants  %s (published under their base language's group)"
             % ", ".join("%s->%s" % (d.locale, d.group) for d in variants))


def verify_uploads(gh, tag, published, echo=print):
    """`gh release view --json assets`: is every published file there, right size?"""
    assets = gh.assets(tag)
    if assets is None:
        echo("  could not read the release's assets -- verify by hand: "
             "gh release view %s --json assets" % tag)
        return False
    missing = [d.file_name for d in published if d.file_name not in assets]
    wrong = [(d.file_name, assets[d.file_name], d.size)
             for d in published
             if d.file_name in assets and assets[d.file_name] not in (None, d.size)]
    if missing:
        echo("  MISSING from the release (%d): %s" % (len(missing), ", ".join(missing[:10])))
    for name, remote, local in wrong:
        echo("  SIZE MISMATCH %s: release has %s, local file is %s" % (name, remote, local))
    if not missing and not wrong:
        echo("  verified: all %d assets present with the right size" % len(published))
        return True
    return False


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    dry_run = not args.upload or args.dry_run
    if args.upload and args.dry_run:
        print("--dry-run and --upload both given: dry run wins, nothing will be uploaded.")

    engine_table = EngineTable.load(args.repo_root)
    if not os.path.isdir(args.source):
        print("source folder does not exist: %s" % args.source)
        return 2

    decisions = catalogue.classify_all(args.source, engine_table)
    if not decisions:
        print("no .ldb files found under %s" % args.source)
        return 2

    published = [d for d in decisions if d.published]
    print("hashing %d files..." % len(published))
    for decision in published:
        decision.sha256, decision.size = digest_and_size(decision.path)

    print_table(decisions)
    print_summary(decisions)

    tag = "packs-%s" % args.version
    packs = {
        "version": args.version,
        "baseUrl": manifest_lib.packs_base_url(args.version, args.repo),
        # Sorted by locale: the manifest is a repo file the maintainer reviews
        # and diffs, and a stable, readable order keeps a catalogue refresh's
        # diff to the packs that actually moved. Uploads stay in file order.
        "items": [d.item() for d in sorted(published, key=lambda d: d.locale)],
    }
    previous = manifest_lib.load(args.manifest)
    document = manifest_lib.merge_section(previous, "packs", packs)
    wrote = manifest_lib.save(args.manifest, document, previous)
    print("")
    print("%s %s (app section preserved: %s)"
          % ("wrote" if wrote else "unchanged", args.manifest,
             ", ".join(sorted(document["app"])) or "empty"))

    print("")
    print("release %s on %s:" % (tag, args.repo))
    gh = Gh(args.repo, dry_run=dry_run)
    if dry_run and not gh.available():
        print("  (gh is not on PATH -- the commands below are what the maintainer runs)")
    try:
        gh.ensure_release(tag, "Language packs %s" % args.version,
                          "XT9 language packs, catalogue %s. %d packs."
                          % (args.version, len(published)))
        paths = [d.path for d in published]
        batches = (len(paths) + args.batch_size - 1) // args.batch_size
        print("  uploading %d assets in %d batch(es) of up to %d, with --clobber"
              % (len(paths), batches, args.batch_size))
        gh.upload(tag, paths, args.batch_size)
        if dry_run:
            print("  would then verify: gh release view %s --repo %s --json assets"
                  % (tag, args.repo))
        else:
            verify_uploads(gh, tag, published)
    except GhError as error:
        print("")
        print("gh failed: %s" % error)
        return 1

    if dry_run:
        print("")
        print("DRY RUN: nothing was created or uploaded. Re-run with --upload to publish.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
