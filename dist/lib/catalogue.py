"""Which .ldb files get published, and why.

Four decisions, in this order, per file:

1. REFUSED  -- the app's own filename parser yields no language. Three-letter
   blocks (`BRX`, `DOI`, `MAI`, `MNI`, `SAT`) and codes outside the app's
   allowlist (`HL`, `SD`) land here. Publishing one would be publishing a file
   no device can install.
2. UNSUPPORTED -- the parsed locale is not in the engine's 105-entry locale
   table and neither is its base language. The engine cannot load it on this
   build however it is named.
3. DUPLICATE -- another file already claims the locale. Nothing is silently
   overwritten; both files are printed and the loser says who beat it.
4. PUBLISHED -- either the locale is in the engine table (name comes from it),
   or the locale is a REGION VARIANT of a language that is (`de_CH`, `fr_CH`,
   `it_CH`, `nl_BE`): the engine resolves those onto the base language's slot,
   so they ship with `"group": "<base>"` and the client can present them under
   their base language.

The duplicate tie-break is deliberate, not alphabetical:

  a. a file the ENGINE TABLE itself names for this locale wins. Without this,
     plain `zh` would be decided by sort order between the GB2312 pack (which
     the table registers as `zh`) and the Big5 one (which it registers as
     `zh_TW` but whose filename carries no region).
  b. otherwise a file with NO leftover marker after `UN` wins. `_ENubUNZH_`
     (English for the Chinese market) and `_ESusUNlatam_` both collapse onto a
     base language whose own pack exists, so they lose to it.
  c. otherwise the filename, so the outcome is stable across runs.
"""

import os

from . import ldb_names

PUBLISHED = "published"
REFUSED = "refused"
UNSUPPORTED = "unsupported"
DUPLICATE = "duplicate"

#: Statuses that keep the file out of the release.
SKIPPED = (REFUSED, UNSUPPORTED, DUPLICATE)


class Decision(object):
    def __init__(self, file_name, path, source):
        self.file_name = file_name
        self.path = path
        self.source = source          # "" or "new" -- which folder it came from
        self.language = None
        self.country = None
        self.locale = None
        self.marker = None            # leftover token after UN, if any
        self.engine_locale = None     # what the engine table calls this FILE
        self.status = None
        self.reason = ""
        self.name = None
        self.group = None
        self.sha256 = None
        self.size = None

    @property
    def published(self):
        return self.status == PUBLISHED

    def item(self):
        """The `packs.items[]` entry for a published file."""
        entry = {
            "locale": self.locale,
            "name": self.name,
            "file": self.file_name,
            "sha256": self.sha256,
            "size": self.size,
        }
        if self.group:
            entry["group"] = self.group
        return entry


def scan(source_dir):
    """Every .ldb in `source_dir` and `source_dir/new`, as (name, path, source).

    `manifest.json` in the source folder is NOT read: the one that ships there
    is broken, and the engine's locale table lives in the app's assets instead.
    """
    found = []
    seen = set()
    for sub in ("", "new"):
        directory = os.path.join(source_dir, sub) if sub else source_dir
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".ldb"):
                continue
            if name in seen:
                continue
            seen.add(name)
            found.append((name, os.path.join(directory, name), sub))
    return sorted(found, key=lambda triple: triple[0])


def classify(file_name, path, source, engine_table):
    """Steps 1, 2 and 4 for one file. Duplicates are settled by `resolve`."""
    decision = Decision(file_name, path, source)
    decision.engine_locale = engine_table.locale_for_file(file_name)
    decision.marker = ldb_names.marker_after_un(file_name)

    language = ldb_names.extract_language(file_name)
    if not language:
        block = ldb_names.language_block(file_name)
        decision.status = REFUSED
        if block and len(block) == 3:
            decision.reason = ("language block '%s' is a three-letter code; the app refuses it "
                               "rather than truncating it onto another language" % block)
        else:
            decision.reason = ("language block '%s' is not a code the app accepts"
                               % (block or "?"))
        # The cross-check that makes the refusal safe to trust: these codes are
        # absent from the engine's locale table as well, so no device on this
        # build could load the file even if the name were spelled differently.
        if block and block.lower() not in engine_table:
            decision.reason += (" (and '%s' is absent from the engine's %d-entry locale table)"
                                % (block.lower(), len(engine_table)))
        return decision

    decision.language = language
    decision.country = ldb_names.extract_country(file_name, language)
    decision.locale = "%s_%s" % (language, decision.country) if decision.country else language

    if decision.locale in engine_table:
        decision.status = PUBLISHED
        decision.name = engine_table.name_of(decision.locale)
        return decision

    if decision.country and language in engine_table:
        decision.status = PUBLISHED
        decision.name = ldb_names.variant_name(engine_table, language, decision.country)
        decision.group = language
        return decision

    decision.status = UNSUPPORTED
    decision.reason = ("locale '%s' is not in the engine's %d-entry locale table; this build "
                       "cannot load it" % (decision.locale, len(engine_table)))
    return decision


def _rank(decision, engine_table):
    engine_owns_it = decision.engine_locale == decision.locale
    return (0 if engine_owns_it else 1,
            0 if decision.marker is None else 1,
            decision.file_name)


def resolve(decisions, engine_table):
    """Settle locale collisions in place. Returns the same list."""
    claims = {}
    for decision in decisions:
        if decision.status == PUBLISHED:
            claims.setdefault(decision.locale, []).append(decision)

    for locale, contenders in claims.items():
        if len(contenders) < 2:
            continue
        contenders.sort(key=lambda d: _rank(d, engine_table))
        winner = contenders[0]
        for loser in contenders[1:]:
            loser.status = DUPLICATE
            why = []
            if loser.marker:
                why.append("its '%s' suffix is a market/script marker the app does not read as a "
                           "region, so it collapses onto '%s'" % (loser.marker, locale))
            if loser.engine_locale and loser.engine_locale != locale:
                why.append("the engine table registers this file as '%s', but that region lives "
                           "in the asset path, not the filename" % loser.engine_locale)
            loser.reason = ("locale '%s' is already published from %s%s"
                            % (locale, winner.file_name,
                               (" -- " + "; ".join(why)) if why else ""))
            loser.name = None
            loser.group = None


def classify_all(source_dir, engine_table):
    decisions = [classify(name, path, source, engine_table)
                 for name, path, source in scan(source_dir)]
    resolve(decisions, engine_table)
    return decisions


def classify_names(file_names, engine_table):
    """Same decisions from a LIST of names -- no files on disk needed.

    This is what the fixture-driven tests use, and what makes the catalogue's
    rules checkable without the 411 MB of .ldb that are not in the repo.
    """
    decisions = [classify(name, name, "", engine_table) for name in sorted(file_names)]
    resolve(decisions, engine_table)
    return decisions


def counts(decisions):
    tally = {PUBLISHED: 0, REFUSED: 0, UNSUPPORTED: 0, DUPLICATE: 0}
    for decision in decisions:
        tally[decision.status] = tally.get(decision.status, 0) + 1
    return tally
