"""The app's LDB-filename rules, ported to Python.

This is a line-by-line port of what the APP does, not an improvement on it. The
publisher's `locale` for a file must be exactly the identifier
`LanguagePackInstaller` / `LanguagePacksScreen` produce on device, or the pack
downloads into a directory nothing ever looks in.

Sources of truth (read them before changing anything here):

  app/src/main/java/dev/bbkb/ime/core/settings/screens/LanguagePacksScreen.kt
      extractLanguageFromFileName / extractCountryFromFileName,
      LANGUAGE_BLOCK, LANGUAGE_CODE_ALIASES, KNOWN_REGION_CODES,
      isValidLanguageCode
  app/src/test/java/com/blackberry/nuanceshim/languagepack/LdbFilenameParsingTest.kt
      the app's own expectations; dist/tests/test_ldb_names.py re-asserts every
      one of them against this port.
  app/src/main/assets/ldb/manifest.json
      the engine's complete 105-entry locale table (a byte-for-byte mirror of
      the table in the shipped blob). A locale that is not in it cannot load.
"""

import json
import os
import re

# ---------------------------------------------------------------------------
# The filename grammar
# ---------------------------------------------------------------------------

#: The catalogue's language block: the UPPER-CASE run opening the locale group,
#: e.g. `EN` in `_ENubUNUS_` and `SAT` in `_SATlsUNdevanagari_`. Its LENGTH is
#: the exact test of which code space the file uses.
LANGUAGE_BLOCK = re.compile(r"_([A-Z]{2,3})[a-z]{2}UN")

#: The region suffix: an UPPER-CASE run immediately after the `UN` marker.
#: Matched against the ORIGINAL case, which is what separates a region from
#: vendor/script noise (`_ZHsbUNps_`, `_ESusUNlatam_`).
COUNTRY_SUFFIX = re.compile(r"_[A-Z]{2,3}[a-z]{2}UN([A-Z0-9]{2,3})_")

#: Everything after the `UN` marker up to the next `_`, in either case. Not part
#: of the app's rules -- the publisher uses it to EXPLAIN a decision (see
#: `marker_after_un`).
ANY_SUFFIX = re.compile(r"_([A-Z]{2,3})([a-z]{2})UN([A-Za-z0-9]*)_")

#: Filename spelling -> the spelling the rest of the stack uses.
LANGUAGE_CODE_ALIASES = {
    "jw": "jv",   # Javanese: `jw` retired 1989
    "he": "iw",   # Hebrew: Java's Locale normalises he -> iw
    "id": "in",   # Indonesian: likewise id -> in
    "tl": "fil",  # Tagalog -> Filipino
    "no": "nb",   # Norwegian -> Bokmal
}

#: Regions the app is prepared to name a pack directory after.
KNOWN_REGION_CODES = frozenset({
    "US", "UK", "GB", "CA", "AU", "NZ", "IE", "ZA", "SG", "IN",
    "ES", "MX", "419", "BR", "PT", "FR", "DE", "IT", "NL", "BE",
    "CH", "AT", "TW", "HK", "CN", "JP", "KR", "RU",
})

#: `isValidLanguageCode`'s allowlist, copied verbatim from the Kotlin.
VALID_LANGUAGE_CODES = frozenset({
    "af", "am", "ar", "as", "az", "be", "bg", "bn", "bo", "bs", "ca", "cs", "cy", "da",
    "de", "el", "en", "es", "et", "eu", "fa", "fi", "fr", "ga", "gl", "gu", "ha", "he",
    "hi", "hr", "hu", "hy", "id", "ig", "is", "it", "ja", "jw", "ka", "kk", "km", "kn",
    "ko", "ks", "ku", "ky", "ln", "lo", "lt", "lv", "mg", "mk", "ml", "mn", "mr", "ms", "my",
    "ne", "nl", "no", "or", "pa", "pl", "ps", "pt", "ro", "ru", "sa", "si", "sk", "sl",
    "sq", "sr", "st", "su", "sv", "sw", "ta", "te", "tg", "th", "tk", "tl", "tr", "tt", "ug",
    "uk", "ur", "uz", "vi", "xh", "yo", "zh", "zu",
})

#: Region code -> the word used in a published variant's display name.
REGION_NAMES = {
    "US": "United States", "UK": "United Kingdom", "GB": "United Kingdom",
    "CA": "Canada", "AU": "Australia", "NZ": "New Zealand", "IE": "Ireland",
    "ZA": "South Africa", "SG": "Singapore", "IN": "India", "ES": "Spain",
    "MX": "Mexico", "419": "Latin America", "BR": "Brazil", "PT": "Portugal",
    "FR": "France", "DE": "Germany", "IT": "Italy", "NL": "Netherlands",
    "BE": "Belgium", "CH": "Switzerland", "AT": "Austria", "TW": "Taiwan",
    "HK": "Hong Kong", "CN": "China", "JP": "Japan", "KR": "Korea", "RU": "Russia",
}

_PATTERN_1 = re.compile(r"_([a-z]{2})[a-z]*_")
_PATTERN_2 = re.compile(r"_([a-z]{2})_")
_PATTERN_3 = re.compile(r"^([a-z]{2})\.ldb$")
_PATTERN_4 = re.compile(r"_([a-z]{2})[^a-z]")


def has_unsupported_three_letter_code(file_name):
    """True when the filename's language block is a three-letter code.

    None of `brx`/`doi`/`mai`/`mni`/`sat` is in the engine's locale table, and
    truncating them to two letters used to land Manipuri on top of Mongolian.
    """
    match = LANGUAGE_BLOCK.search(file_name)
    return bool(match) and len(match.group(1)) == 3


def extract_language(file_name):
    """Port of `extractLanguageFromFileName`. Returns "" for a refusal."""
    if has_unsupported_three_letter_code(file_name):
        return ""
    name = file_name.lower()
    for pattern in (_PATTERN_1, _PATTERN_2, _PATTERN_3, _PATTERN_4):
        match = pattern.search(name)
        if match:
            code = match.group(1)
            if code in VALID_LANGUAGE_CODES:
                return LANGUAGE_CODE_ALIASES.get(code, code)
    return ""


def extract_country(file_name, language_code):
    """Port of `extractCountryFromFileName`. Returns None when there is none."""
    if not language_code:
        return None
    match = COUNTRY_SUFFIX.search(file_name)
    if not match:
        return None
    suffix = match.group(1)
    return suffix if suffix in KNOWN_REGION_CODES else None


def locale_of(file_name):
    """The identifier the app produces for `file_name`, or None for a refusal."""
    language = extract_language(file_name)
    if not language:
        return None
    country = extract_country(file_name, language)
    return "%s_%s" % (language, country) if country else language


def language_block(file_name):
    """The raw upper-case language block, for printing a refusal's reason."""
    match = LANGUAGE_BLOCK.search(file_name)
    return match.group(1) if match else None


def marker_after_un(file_name):
    """The token after `UN` that the app did NOT read as a region, if any.

    `_ESusUNlatam_` -> "latam", `_ENubUNZH_` -> "ZH" (upper case but not in
    KNOWN_REGION_CODES), `_ZHsbUNps_` -> "ps", `_ENubUNUS_` -> None (that one IS
    a region). A file carrying one of these collapses onto its base language,
    which is why it loses a duplicate contest to a file that resolves cleanly.
    """
    match = ANY_SUFFIX.search(file_name)
    if not match:
        return None
    suffix = match.group(3)
    if not suffix or suffix in KNOWN_REGION_CODES:
        return None
    return suffix


# ---------------------------------------------------------------------------
# The engine's locale table
# ---------------------------------------------------------------------------

ENGINE_TABLE_PATH = os.path.join("app", "src", "main", "assets", "ldb", "manifest.json")


class EngineTable(object):
    """`assets/ldb/manifest.json`: the 105 locales this build can load."""

    def __init__(self, entries):
        self.entries = list(entries)
        self.names = {}
        self.by_file_stem = {}
        for entry in self.entries:
            identifier = entry["language"]
            if entry.get("country"):
                identifier += "_" + entry["country"]
            self.names[identifier] = entry.get("name", identifier)
            stem = os.path.splitext(os.path.basename(entry.get("path", "")))[0]
            if stem:
                self.by_file_stem[stem] = identifier

    def __contains__(self, identifier):
        return identifier in self.names

    def __len__(self):
        return len(self.names)

    def name_of(self, identifier):
        return self.names.get(identifier)

    def locale_for_file(self, file_name):
        """The locale the engine table itself registers this file under, if any.

        The table's entries point at `.zip` payloads, so compare stems.
        """
        return self.by_file_stem.get(os.path.splitext(file_name)[0])

    @classmethod
    def load(cls, repo_root):
        path = os.path.join(repo_root, ENGINE_TABLE_PATH)
        with open(path, "r") as handle:
            return cls(json.load(handle)["languages"])


def variant_name(engine_table, language, country):
    """Display name for a variant the engine table has no entry of its own for."""
    base = engine_table.name_of(language) or language
    return "%s (%s)" % (base, REGION_NAMES.get(country, country))
