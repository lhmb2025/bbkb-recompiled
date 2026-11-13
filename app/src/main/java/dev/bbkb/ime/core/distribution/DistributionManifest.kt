package dev.bbkb.ime.core.distribution

/**
 * Parsed `dist/manifest.json`. All fields are network data: a `url` is only safe to hand to
 * [Downloader], which enforces scheme and redirect rules.
 *
 * @property generated ISO-8601 instant, display only, never used for freshness
 * @property apps update channels keyed by build type (`"debug"`, `"release"`)
 * @property fromCache not wire data: stamped by [ManifestSource] (parser output is `false`)
 * @property fetchedAt not wire data: epoch millis of the fetch, `0L` straight from the parser
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
     * The channel for [buildType] (`BuildConfig.BUILD_TYPE`). `null` is normal (no release channel
     * without a release key) and means "no updates for this build", not an error.
     */
    fun appFor(buildType: String): AppBuild? = apps[buildType]

    /** Exact, case-sensitive match on the app's own locale ids (`"de_CH"`), no language fallback. */
    fun packFor(locale: String): PackEntry? = packs?.forLocale(locale)

    fun withProvenance(fromCache: Boolean, fetchedAt: Long): DistributionManifest =
        copy(fromCache = fromCache, fetchedAt = fetchedAt)

    companion object {
        const val SCHEMA = 1
    }
}

/**
 * One downloadable app build under the manifest's `app` object.
 *
 * @property sha256 hex SHA-256 of the APK, the only integrity check (no manifest signature)
 * @property size checked before the hash, so truncation fails fast
 * @property minSdk devices below this must not be offered the update
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
    /** Strictly greater, so a re-published build never prompts. This is the whole update rule. */
    fun isNewerThan(versionCode: Int): Boolean = this.versionCode > versionCode

    fun isInstallableOn(deviceSdk: Int): Boolean = deviceSdk >= minSdk
}

/**
 * The language-pack catalogue.
 *
 * @property version opaque catalogue version matching the release tag; do not parse
 * @property baseUrl prefix for [PackEntry.file], with or without trailing slash
 * @property items unique by locale (the parser keeps the first duplicate)
 */
data class Packs(
    val version: String,
    val baseUrl: String,
    val items: List<PackEntry>,
) {
    private val byLocale: Map<String, PackEntry> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        items.associateBy { it.locale }
    }

    fun forLocale(locale: String): PackEntry? = byLocale[locale]

    /** Variant packs that load in place of base language [group] (e.g. `"de"` gives de_CH, de_AT). */
    fun variantsOf(group: String): List<PackEntry> = items.filter { it.group == group }
}

/**
 * One downloadable language pack.
 *
 * @property locale `language` or `language_COUNTRY` with an underscore (`"es_419"`), not BCP-47
 * @property name English display name, the fallback when no localised name is derived
 * @property group set only on a variant that loads in place of base language `group` (`"de"`)
 */
data class PackEntry(
    val locale: String,
    val name: String,
    val file: String,
    val sha256: String,
    val size: Long,
    val group: String? = null,
) {
    /** [Packs.baseUrl] + [file] with exactly one slash between them. */
    fun url(packs: Packs): String {
        val base = packs.baseUrl
        return if (base.endsWith("/")) base + file else "$base/$file"
    }

    val isVariant: Boolean get() = group != null
}
