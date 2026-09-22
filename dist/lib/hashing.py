"""sha256 + size, the two fields the client verifies a download against."""

import hashlib
import os

_CHUNK = 1024 * 1024


def sha256_file(path):
    """Lower-case hex sha256 of `path`, read in 1 MiB chunks.

    Chunked because the catalogue is 411 MB across ~110 files; reading one whole
    .ldb into memory per file is 4-40 MB of needless garbage each time.
    """
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(_CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def size_of(path):
    return os.path.getsize(path)


def digest_and_size(path):
    return sha256_file(path), size_of(path)


def human_size(num_bytes):
    """Only ever for printing -- never for a manifest field."""
    value = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1024.0 or unit == "GB":
            if unit == "B":
                return "%d B" % num_bytes
            return "%.1f %s" % (value, unit)
        value /= 1024.0
    return "%d B" % num_bytes
