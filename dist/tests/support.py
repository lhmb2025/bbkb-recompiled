"""Shared bits for the publishing tests. Not a test module itself."""

import os

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")
CATALOGUE_TSV = os.path.join(FIXTURES, "catalogue.tsv")


def catalogue_rows():
    """(file_name, expected_locale_or_REJECTED, expected_decision) for all 116."""
    rows = []
    with open(CATALOGUE_TSV, "r") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            assert len(parts) == 3, "bad fixture row: %r" % line
            rows.append(tuple(parts))
    return rows


def catalogue_names():
    return [row[0] for row in catalogue_rows()]


def empty_path_env():
    """An env whose PATH holds nothing, so `gh` and `aapt2` cannot be found.

    Every test here is offline by construction: with these two absent the
    publishers take their no-tool paths and cannot reach GitHub even by
    accident.
    """
    env = dict(os.environ)
    env["PATH"] = os.path.join(os.path.sep, "nonexistent-for-tests")
    env.pop("ANDROID_HOME", None)
    env.pop("ANDROID_SDK_ROOT", None)
    return env
