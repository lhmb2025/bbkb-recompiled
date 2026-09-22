package dev.bbkb.ime.core.languagepack

import android.content.Context
import com.blackberry.nuanceshim.languagepack.LanguagePackInstaller
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import dev.bbkb.ime.core.distribution.DistributionManifest
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import dev.bbkb.ime.core.locale.LocaleUtils

/**
 * The published catalogue merged with what this phone actually has.
 *
 * [from] is **pure**: it takes a parsed manifest, a snapshot of what is installed and a snapshot
 * of what is downloading, and answers with rows. No disk, no network, no `Context` — which is
 * what lets it be tested against the real `dist/manifest.json` with any installed set you like,
 * and what keeps "is this pack installed" a single decision made in one place rather than one
 * made again in every row of a `LazyColumn`.
 *
 * ### Rows, not a flat list
 *
 * The catalogue is not flat: four entries carry a `group` ([PackEntry.isVariant]) and load *in
 * place of* a base language rather than as a language of their own — Swiss German, Swiss French,
 * Swiss Italian and Belgian Dutch. Showing them as peers of German and French would offer the
 * user two "German" dictionaries with no hint that picking one replaces the other. So a [Row] is
 * one *language*: the base entry, plus the variants that can occupy its slot. A base language
 * with no variants is a row with an empty [Row.variants].
 *
 * A variant whose base language is not in the catalogue at all (which the published manifest has
 * never contained) becomes a row of its own rather than being dropped, so nothing can silently
 * disappear from the list.
 *
 * ### Provenance
 *
 * [fromCache] and [fetchedAt] come straight off the manifest and exist so the screen can say
 * "catalogue from <date>" when the bytes came off disk. A cached catalogue is a complete, usable
 * catalogue — it is only the *freshness* that is unknown — so nothing here is degraded by it.
 */
data class PackCatalog(
    /** Every language in the catalogue, sorted by display name. */
    val rows: List<Row>,
    /** The catalogue version the manifest stated, e.g. `"1902.01"`. */
    val version: String,
    /** True when the manifest these rows came from was served out of the on-disk cache. */
    val fromCache: Boolean,
    /** Epoch millis of the fetch (or re-validation) that produced the manifest; `0` if unknown. */
    val fetchedAt: Long,
) {

    /**
     * Rows with something installed: the base pack, or any of its variants. These are the
     * languages the user already has, and the ones whose variant radio list is worth showing.
     */
    val installedRows: List<Row> get() = rows.filter { it.hasAnythingInstalled }

    /**
     * Rows with something still to download — the catalogue minus what is installed. A row
     * appears here when its base pack is not installed *or* when any of its variants is not,
     * which is why a row can legitimately be in both lists: German installed, Swiss German not.
     */
    val availableRows: List<Row> get() = rows.filter { it.hasAnythingAvailable }

    /** Every entry, base and variant, in row order. */
    val items: List<Item> get() = rows.flatMap { listOf(it.base) + it.variants }

    /** The item for an exact locale identifier, or `null`. */
    fun item(locale: String): Item? = items.firstOrNull { it.entry.locale == locale }

    /**
     * One language: its own dictionary, and the regional dictionaries that can take its place.
     */
    data class Row(val base: Item, val variants: List<Item> = emptyList()) {
        val locale: String get() = base.entry.locale
        val displayName: String get() = base.displayName

        val hasAnythingInstalled: Boolean
            get() = base.state is PackState.Installed || variants.any { it.state is PackState.Installed }

        val hasAnythingAvailable: Boolean
            get() = base.state !is PackState.Installed || variants.any { it.state !is PackState.Installed }

        /** The variants of this language that are not installed yet. */
        val availableVariants: List<Item> get() = variants.filter { it.state !is PackState.Installed }
    }

    /** One catalogue entry and what this phone currently makes of it. */
    data class Item(val entry: PackEntry, val state: PackState) {
        /** The name the catalogue gives this pack, e.g. `"German (Switzerland)"`. */
        val displayName: String get() = entry.name
        val locale: String get() = entry.locale
        val isVariant: Boolean get() = entry.isVariant
        val sizeBytes: Long get() = entry.size
    }

    companion object {

        /**
         * Merge [manifest]'s packs with [installed] and [downloads].
         *
         * @param downloads live state from [PackDownloadManager], keyed by locale. It wins over
         *   [PackState.Available] — a pack that is downloading or that just failed says so — but
         *   never over [PackState.Installed], so a stale failure cannot hide a pack the user has.
         * @return `null` when the manifest carries no `packs` object at all, which is a manifest
         *   that publishes no dictionaries rather than an error.
         */
        fun from(
            manifest: DistributionManifest,
            installed: InstalledPacks,
            downloads: Map<String, PackState> = emptyMap(),
        ): PackCatalog? {
            val packs = manifest.packs ?: return null
            return from(packs, installed, downloads)
                .copy(fromCache = manifest.fromCache, fetchedAt = manifest.fetchedAt)
        }

        /** [from] against a bare [Packs], for callers that already unwrapped the manifest. */
        fun from(
            packs: Packs,
            installed: InstalledPacks,
            downloads: Map<String, PackState> = emptyMap(),
        ): PackCatalog {
            val items = packs.items.map { Item(it, stateOf(it, installed, downloads)) }
            val byLocale = items.associateBy { it.entry.locale }
            val variantsByGroup = items
                .filter { it.isVariant }
                .groupBy { requireNotNull(it.entry.group) }

            val rows = items
                .filterNot { it.isVariant && byLocale.containsKey(it.entry.group) }
                .map { item ->
                    Row(
                        base = item,
                        variants = variantsByGroup[item.entry.locale]
                            ?.sortedBy { it.displayName }
                            .orEmpty(),
                    )
                }
                // By display name, and by locale when two packs share a name, so the order is
                // total and the list does not shuffle between two equal rows.
                .sortedWith(compareBy({ it.displayName }, { it.locale }))

            return PackCatalog(
                rows = rows,
                version = packs.version,
                fromCache = false,
                fetchedAt = 0L,
            )
        }

        /**
         * What one entry is, right now. Installed beats downloading beats failed beats available:
         * an installed pack is a fact on disk, and everything else is a claim about the future.
         */
        private fun stateOf(
            entry: PackEntry,
            installed: InstalledPacks,
            downloads: Map<String, PackState>,
        ): PackState {
            installed.stateOf(entry)?.let { return it }
            val live = downloads[entry.locale]
            if (live is PackState.Downloading || live is PackState.Failed) return live
            return PackState.Available(entry.size)
        }
    }
}

/**
 * What the Language packs screen shows for one catalogue entry.
 *
 * Deliberately a small closed set. "Installed" has four shapes because the user can act on them
 * differently — a shipped dictionary cannot be deleted, a downloaded one can, and a variant is
 * either the one loading for its language or one of the alternatives to it — but everything else
 * is a single fact plus, at most, the reason.
 */
sealed class PackState {

    /** On this phone. [origin] decides what the row offers. */
    data class Installed(val origin: Origin) : PackState()

    /** In the catalogue, not on this phone. [sizeBytes] is what downloading it would cost. */
    data class Available(val sizeBytes: Long) : PackState()

    /**
     * Downloading now. A pack waiting behind another download is reported as
     * `Downloading(0, size)` — it is queued, which the progress bar renders as 0 %.
     */
    data class Downloading(val bytes: Long, val totalBytes: Long) : PackState() {
        /** `0f..1f`, or `0f` while the total is unknown. */
        val progress: Float
            get() = if (totalBytes > 0L) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /**
     * The last attempt failed and nothing was installed. [error] is the
     * [dev.bbkb.ime.core.distribution.DistributionException] the transport raised or the
     * [PackInstallException] the installer raised, so the row can say *why* without parsing a
     * message string. Clearing it is the user's next action, not a timer's.
     */
    data class Failed(val error: Throwable) : PackState()

    /** How an installed pack got here, which is what decides whether it can be removed. */
    enum class Origin {
        /** In the APK. Cannot be deleted. */
        SHIPPED,

        /** Downloaded or side-loaded into `nuance/<locale>/`. */
        CUSTOM,

        /** A regional variant, and the one currently loading for its base language. */
        VARIANT_ACTIVE,

        /** A regional variant that is installed but not the one loading. */
        VARIANT_INACTIVE,
    }
}

/**
 * A snapshot of the packs on this phone, in the three shapes the app stores them in.
 *
 * A value type on purpose: every decision about "is this installed" is then a pure function of
 * this plus the catalogue, and a test can describe any phone it likes in three lines. [read] is
 * the only part that touches disk.
 *
 * @property shipped locales whose dictionary is in the APK (`preinstalled` in the shipped
 *   registry). These can never be deleted and are never offered for download.
 * @property custom locales with a dictionary under `no_backup/nuance/<locale>/`. Note that an
 *   active variant also puts a file in its *base* language's directory, so a base locale can be
 *   in both [shipped] and [custom]; [shipped] wins when reporting what a row is.
 * @property variants variant groups keyed by base locale — which variants are installed for a
 *   language and which of them is loading.
 */
data class InstalledPacks(
    val shipped: Set<String> = emptySet(),
    val custom: Set<String> = emptySet(),
    val variants: Map<String, Group> = emptyMap(),
) {

    /** One language's variant group: the installed variant tags, and the active one. */
    data class Group(val activeTag: String, val tags: Set<String>)

    /**
     * The [PackState.Installed] for [entry], or `null` when this phone does not have it.
     *
     * A variant is installed when its own tag appears in its group; the tag *is* its locale
     * identifier (`de_CH`), which is how [LanguageVariantStore] names it.
     */
    fun stateOf(entry: PackEntry): PackState.Installed? {
        val group = entry.group
        if (group != null) {
            val installedGroup = variants[group] ?: return null
            if (entry.locale !in installedGroup.tags) return null
            return PackState.Installed(
                if (installedGroup.activeTag == entry.locale) {
                    PackState.Origin.VARIANT_ACTIVE
                } else {
                    PackState.Origin.VARIANT_INACTIVE
                }
            )
        }
        return when (entry.locale) {
            in shipped -> PackState.Installed(PackState.Origin.SHIPPED)
            in custom -> PackState.Installed(PackState.Origin.CUSTOM)
            else -> null
        }
    }

    /** Whether the dictionary for this exact locale identifier is on the phone. */
    fun isInstalled(locale: String): Boolean = locale in shipped || locale in custom

    companion object {

        /**
         * Read the three sources off disk. Blocking I/O — call it from a worker.
         *
         * `shipped` is asked of [LanguagePackManager] rather than read out of the shipped
         * manifest, because `LanguagePackInfo` is package-private to the shim: the registry's
         * own answer to "is this preinstalled" comes through `LanguagePackInstaller`. Only the
         * locales the catalogue actually offers are asked about, so this is at most ~110 cached
         * lookups rather than a walk of the whole table.
         */
        fun read(context: Context, catalogueLocales: Collection<String>): InstalledPacks {
            val manager = LanguagePackManager.getInstance(context)
            val shipped = LinkedHashSet<String>()
            for (locale in catalogueLocales) {
                val parsed = LocaleUtils.constructLocaleFromString(locale) ?: continue
                val status = try {
                    manager.getStatus(parsed)
                } catch (unavailable: RuntimeException) {
                    null
                } ?: continue
                if (status.isSupported && status.isPreinstalled) shipped += locale
            }
            val onDisk = try {
                LanguagePackInstaller.getInstalledLocales(context)
            } catch (unreadable: RuntimeException) {
                emptySet<String>()
            }
            val groups = LanguageVariantStore.read(context).mapValues { (_, group) ->
                Group(
                    activeTag = group.activeTag,
                    tags = group.members.map { it.tag }.filterNot { it == LanguageVariantStore.TAG_SHIPPED }.toSet(),
                )
            }
            return InstalledPacks(
                shipped = shipped,
                custom = onDisk - shipped,
                variants = groups,
            )
        }
    }
}
