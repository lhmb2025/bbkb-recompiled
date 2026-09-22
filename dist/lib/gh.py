"""The only place this tooling touches the network.

Every side-effecting call goes through `Gh.run`, which in dry-run mode PRINTS
the argv it would have executed and returns without spawning anything. That is
the whole safety story: a dry run cannot create a release, cannot upload and
cannot clobber, because nothing below ever shells out on its own.

Read-only probes (`gh release view`) still run in dry-run mode -- they are how a
dry run can tell you whether the release already exists -- but they are routed
through `run_readonly`, and a failure there is never fatal.
"""

import json
import shutil
import subprocess


class GhError(RuntimeError):
    pass


def quote(argument):
    """Shell-quote for the copy-pasteable command lines a dry run prints."""
    if argument and all(c.isalnum() or c in "-_=/.:,@+" for c in argument):
        return argument
    return "'" + argument.replace("'", "'\\''") + "'"


def render(argv):
    return " ".join(quote(a) for a in argv)


class Gh(object):
    def __init__(self, repo, dry_run=True, echo=print):
        self.repo = repo
        self.dry_run = dry_run
        self.echo = echo

    # -- plumbing ---------------------------------------------------------

    def available(self):
        return shutil.which("gh") is not None

    def _argv(self, args):
        return ["gh"] + list(args) + ["--repo", self.repo]

    def run(self, args, what=""):
        """A side-effecting gh call. Skipped (and printed) in dry-run mode."""
        argv = self._argv(args)
        if self.dry_run:
            self.echo("  would run: %s" % render(argv))
            return None
        self.echo("  running:   %s" % render(argv))
        result = subprocess.run(argv, capture_output=True, text=True)
        if result.returncode != 0:
            raise GhError("%s failed (%d):\n%s%s"
                          % (what or render(argv), result.returncode,
                             result.stdout, result.stderr))
        return result.stdout

    def run_readonly(self, args):
        """A read-only gh call. Runs in dry-run mode too; None if it fails."""
        if not self.available():
            return None
        result = subprocess.run(self._argv(args), capture_output=True, text=True)
        if result.returncode != 0:
            return None
        return result.stdout

    # -- releases ---------------------------------------------------------

    def release_exists(self, tag):
        return self.run_readonly(["release", "view", tag, "--json", "tagName"]) is not None

    def ensure_release(self, tag, title, notes):
        """Create the release if `gh release view` says it is not there yet."""
        if self.release_exists(tag):
            self.echo("  release %s already exists" % tag)
            return False
        self.run(["release", "create", tag, "--title", title, "--notes", notes],
                 what="gh release create %s" % tag)
        return True

    def upload(self, tag, paths, batch_size=20):
        """`gh release upload --clobber`, in batches. Idempotent by --clobber."""
        uploaded = 0
        for start in range(0, len(paths), batch_size):
            batch = paths[start:start + batch_size]
            self.run(["release", "upload", tag] + list(batch) + ["--clobber"],
                     what="gh release upload %s (batch %d)" % (tag, start // batch_size + 1))
            uploaded += len(batch)
        return uploaded

    def assets(self, tag):
        """{name: size} for the release's assets, or None if it cannot be read."""
        raw = self.run_readonly(["release", "view", tag, "--json", "assets"])
        if raw is None:
            return None
        try:
            payload = json.loads(raw)
        except ValueError:
            return None
        return {asset["name"]: asset.get("size") for asset in payload.get("assets", [])}
