"""Read / merge / write `dist/manifest.json`.

The two publishers own one section each (`app`, `packs`) of a single file that
the app fetches from `main`. Neither may clobber the other's section: an app
release must not blank the catalogue, and a catalogue refresh must not blank the
app entry. Every write here goes through `merge_section`, which copies the whole
document forward and replaces exactly one key.

Idempotence: `save` rewrites the file only when `app` or `packs` actually
changed. `generated` is a timestamp, so bumping it unconditionally would make
every dry run dirty the working tree and every re-run produce a fresh commit.
"""

import datetime
import json
import os

SCHEMA = 1
MANIFEST_RELATIVE_PATH = os.path.join("dist", "manifest.json")
DEFAULT_REPO = "lhmb2025/bbkb-recompiled"


def empty_manifest():
    return {"schema": SCHEMA, "generated": utc_now(), "app": {}, "packs": {}}


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load(path):
    """The manifest at `path`, or a fresh one with an empty `app` if absent."""
    if not os.path.exists(path):
        return empty_manifest()
    with open(path, "r") as handle:
        document = json.load(handle)
    document.setdefault("schema", SCHEMA)
    document.setdefault("app", {})
    document.setdefault("packs", {})
    return document


def merge_section(document, key, value):
    """A copy of `document` with `key` replaced and every other key preserved."""
    merged = dict(document)
    merged[key] = value
    merged.setdefault("schema", SCHEMA)
    merged.setdefault("app", {})
    merged.setdefault("packs", {})
    return merged


def _ordered(document):
    """schema, generated, app, packs first; anything else after, in key order."""
    ordered = {}
    for key in ("schema", "generated", "app", "packs"):
        if key in document:
            ordered[key] = document[key]
    for key in sorted(document):
        if key not in ordered:
            ordered[key] = document[key]
    return ordered


def changed(previous, document):
    """Did anything but `generated` change?"""
    return (previous.get("app") != document.get("app")
            or previous.get("packs") != document.get("packs")
            or previous.get("schema") != document.get("schema"))


def save(path, document, previous=None):
    """Write `document`, stamping `generated` only if the content moved.

    Returns True when the file was written.
    """
    if previous is None:
        previous = load(path) if os.path.exists(path) else None
    if previous is not None and not changed(previous, document):
        return False
    document = dict(document)
    document["generated"] = utc_now()
    directory = os.path.dirname(os.path.abspath(path))
    if directory and not os.path.isdir(directory):
        os.makedirs(directory)
    with open(path, "w") as handle:
        json.dump(_ordered(document), handle, indent=2)
        handle.write("\n")
    return True


def packs_base_url(version, repo=DEFAULT_REPO):
    return "https://github.com/%s/releases/download/packs-%s/" % (repo, version)


def app_asset_url(version_name, asset_name, repo=DEFAULT_REPO):
    return "https://github.com/%s/releases/download/v%s/%s" % (repo, version_name, asset_name)
