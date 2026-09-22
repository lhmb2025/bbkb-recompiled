package dev.bbkb.ime.core.distribution

/**
 * The parsed `dist/manifest.json` — the single contract between what the release tooling
 * publishes and what the app offers the user. See the package documentation
 * (`dev.bbkb.ime.core.distribution`) for the wire format and the hosting decisions behind it.
 *
 * Every field here is *data the app read off the network*. Nothing in this model is trusted to
 * be well formed beyond what [DistributionManifestParser] already checked: in particular a
 * `url` is a string the manifest author chose, so a consumer that does anything but hand it to
 * [Downloader] (which enforces scheme and redirect rules) must validate it itself.
 *
 * ### Provenance
 *
 * [fromCache] and [fetchedAt] are **not** part of the wire format. They are stamped by
 * [ManifestSource] so a caller can tell a freshly fetched manifest from one served out of the
 * on-disk cache, and can say "as of <time>" in the UI. [DistributionManifestParser] always
 * produces `fromCache = false, fetchedAt = 0L`; treat those values as "unknown provenance".
 *
 * @property schema the manifest schema version. Always `1` — the parser rejects anything else.
 * @property generated the ISO-8601 instant the manifest was generated, verbatim and unparsed
 *   (it is display metadata, never used for freshness decisions). `null` when absent.
 * @property apps update channels keyed by Gradle build type (`"debug"`, `"release"`). A build
 *   type that is absent means "no update channel for this build" — see [appFor].
 * @property packs the downloadable language-pack catalogue, or `null` when the manifest carries
 *   no `packs` object at all.
 * @property fromCache true when these bytes came off disk rather than off the network.
 * @property fetchedAt epoch millis of the fetch (or re-validation) that produced this object;
 *   `0L` when the object came straight from the parser.
 */
data class DistributionManifest(
    val schema: Int,
    val generated: String?,
    val apps: Map<String, AppBuild>,
    val packs: Packs?,
    val fromCache: Boolean = false,
    val fetchedAt: Long = 0L,
) {
    /**
     * The update channel for [buildType], or `null` when this manifest publishes no build of
     * that type. Callers pass `BuildConfig.BUILD_TYPE`; `null` is an ordinary, expected answer
     * (the release channel does not exist until a release signing key does) and must be shown
     * as "no updates available for this build", not as an error.
     */
    fun appFor(buildType: String): AppBuild? = apps[buildType]

    /**
     * The pack for an exact locale identifier (`"en_US"`, `"de_CH"`, `"jv"`), or `null`.
     *
     * The match is exact and case-sensitive: these are the identifiers the app's own
     * language-pack code uses, not BCP-47 tags, so no normalisation or fallback from
     * `language_COUNTRY` to `language` happens here. A caller that wants that fallback should
     * ask for the specific locale first and the bare language second.
     */
    fun packFor(locale: String): PackEntry? = packs?.forLocale(locale)

    /** A copy stamped with where these bytes came from. Used by [ManifestSource]. */
    fun withProvenance(fromCache: Boolean, fetchedAt: Long): DistributionManifest =
        copy(fromCache = fromCache, fetchedAt = fetchedAt)

    companion object {
        /** The only schema version this app understands. */
        const val SCHEMA = 1
    }
}

/**
 * One downloadable app build, i.e. one entry under the manifest's `app` object.
 *
 * @property versionCode the published build's `versionCode`; compared against
 *   `BuildConfig.VERSION_CODE` by [isNewerThan].
 * @property versionName the human-readable version, shown in the update prompt.
 * @property url absolute HTTPS URL of the APK (a GitHub release asset, which redirects to
 *   `objects.githubusercontent.com` — [Downloader] handles that).
 * @property sha256 lowercase hex SHA-256 of the APK. This is the *only* integrity check: there
 *   is no manifest signature, so the chain is "HTTPS to a host we trust" plus "the bytes hash to
 *   what that host said". Never install a download whose hash was not verified.
 * @property size the APK size in bytes, checked before the hash so a truncated download fails
 *   fast and so a progress bar has a denominator.
 * @property minSdk the minimum API level of the published build. A device below it must not be
 *   offered the update.
 * @property notes short release notes, or `null`.
 */
data class AppBuild(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val minSdk: Int,
    val notes: String?,
) {
    /**
     * Whether this published build is newer than [versionCode] — strictly greater, so a
     * re-published build with the same code never prompts. This is the *whole* "is an update
     * available" rule; do not add version-name comparisons on top of it.
     */
    fun isNewerThan(versionCode: Int): Boolean = this.versionCode > versionCode

    /** Whether a device at [deviceSdk] can install this build. */
    fun isInstallableOn(deviceSdk: Int): Boolean = deviceSdk >= minSdk
}

/**
 * The language-pack catalogue: a catalogue [version], the [baseUrl] every pack file hangs off,
 * and the [items] themselves (~100 Nuance LDB packs, each independently downloadable).
 *
 * @property version the catalogue version (e.g. `"1902.01"`), matching the release tag the
 *   assets were uploaded under. Useful as a cache key; it is opaque and must not be parsed.
 * @property baseUrl the prefix a pack's [PackEntry.file] is appended to. May or may not carry a
 *   trailing slash — [PackEntry.url] normalises it.
 * @property items every published pack, in manifest order. Duplicate locales are dropped by the
 *   parser (first wins), so this list is unique by [PackEntry.locale].
 */
data class Packs(
    val version: String,
    val baseUrl: String,
    val items: List<PackEntry>,
) {
    private val byLocale: Map<String, PackEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        items.associateBy { it.locale }
    }

    /** The pack with exactly this locale identifier, or `null`. */
    fun forLocale(locale: String): PackEntry? = byLocale[locale]

    /**
     * The variant packs that load in place of base language [group] — e.g. `group("de")`
     * returns the Swiss and Austrian German packs. Packs with no `group` are never returned.
     */
    fun variantsOf(group: String): List<PackEntry> = items.filter { it.group == group }
}

/**
 * One downloadable language pack.
 *
 * @property locale the identifier the app's language-pack code uses: `language` or
 *   `language_COUNTRY` (`"af"`, `"en_US"`, `"es_419"`, `"de_CH"`, `"jv"`). Not a BCP-47 tag —
 *   the separator is an underscore.
 * @property name the English display name (`"German (Switzerland)"`). The catalogue UI is free
 *   to prefer a localised name it derives from [locale] itself; this is the fallback.
 * @property file the asset file name, appended to [Packs.baseUrl] by [url].
 * @property sha256 lowercase hex SHA-256 of the `.ldb` file — the only integrity check.
 * @property size the file size in bytes.
 * @property group present only on a variant that loads *in place of* a base language (the Swiss
 *   and Belgian packs); the value is the base language's locale (`"de"`, `"fr"`). `null` on an
 *   ordinary pack.
 */
data class PackEntry(
    val locale: String,
    val name: String,
    val file: String,
    val sha256: String,
    val size: Long,
    val group: String? = null,
) {
    /**
     * The absolute download URL: [Packs.baseUrl] + [file], with exactly one slash between them
     * however the manifest spelled the base URL.
     */
    fun url(packs: Packs): String {
        val base = packs.baseUrl
        return if (base.endsWith("/")) base + file else "$base/$file"
    }

    /** Whether this pack replaces a base language rather than adding one of its own. */
    val isVariant: Boolean get() = group != null
}
