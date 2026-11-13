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
 * The published catalogue merged with what the phone has. [from] is pure (no disk, network or
 * Context), so "is this installed" is decided once, here. A [Row] is one language: its base entry
 * plus the regional variants that load in place of it (Swiss German etc.), so the user is not shown
 * two "German" peers. A variant whose base is missing from the catalogue becomes its own row.
 */
data class PackCatalog(
    val rows: List<Row>,
    val version: String,
    val fromCache: Boolean,
    /** `0` if unknown. */
    val fetchedAt: Long,
) {
    /** Rows with the base pack or any variant installed. */
    val installedRows: List<Row> get() = rows.filter { it.hasAnythingInstalled }

    /** Rows with anything not installed; a row can be in both lists (German yes, Swiss German no). */
    val availableRows: List<Row> get() = rows.filter { it.hasAnythingAvailable }

    val items: List<Item> get() = rows.flatMap { listOf(it.base) + it.variants }

    fun item(locale: String): Item? = items.firstOrNull { it.entry.locale == locale }

    data class Row(val base: Item, val variants: List<Item> = emptyList()) {
        val locale: String get() = base.entry.locale
        val displayName: String get() = base.displayName

        val hasAnythingInstalled: Boolean
            get() = base.state is PackState.Installed || variants.any { it.state is PackState.Installed }

        val hasAnythingAvailable: Boolean
            get() = base.state !is PackState.Installed || variants.any { it.state !is PackState.Installed }

        val availableVariants: List<Item> get() = variants.filter { it.state !is PackState.Installed }
    }

    data class Item(val entry: PackEntry, val state: PackState) {
        val displayName: String get() = entry.name
        val locale: String get() = entry.locale
        val isVariant: Boolean get() = entry.isVariant
        val sizeBytes: Long get() = entry.size
    }

    companion object {

        /**
         * @param downloads live state keyed by locale; beats [PackState.Available] but never
         *   [PackState.Installed], so a stale failure cannot hide an installed pack
         * @return `null` when the manifest has no `packs` object (publishes no dictionaries)
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
                // Tie-break on locale so the order is total and rows do not shuffle.
                .sortedWith(compareBy({ it.displayName }, { it.locale }))

            return PackCatalog(
                rows = rows,
                version = packs.version,
                fromCache = false,
                fetchedAt = 0L,
            )
        }

        /** Installed beats downloading/failed beats available. */
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

/** What the Language packs screen shows for one entry; installed has four shapes because each allows different actions. */
sealed class PackState {
    data class Installed(val origin: Origin) : PackState()

    data class Available(val sizeBytes: Long) : PackState()

    /** A pack queued behind another download is `Downloading(0, size)`. */
    data class Downloading(val bytes: Long, val totalBytes: Long) : PackState() {
        /** `0f` while the total is unknown. */
        val progress: Float
            get() = if (totalBytes > 0L) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    /**
     * Nothing was installed. [error] is a [dev.bbkb.ime.core.distribution.DistributionException] or
     * [PackInstallException]; it clears on the user's next action, not a timer.
     */
    data class Failed(val error: Throwable) : PackState()

    /** Decides whether an installed pack can be removed. */
    enum class Origin {
        /** In the APK; cannot be deleted. */
        SHIPPED,

        CUSTOM,

        /** The variant currently loading for its base language. */
        VARIANT_ACTIVE,

        VARIANT_INACTIVE,
    }
}

/**
 * A snapshot of installed packs, a value type so every "is this installed" decision is pure; only
 * [read] touches disk.
 *
 * @property shipped in the APK; never deletable or offered for download
 * @property custom under `no_backup/nuance/<locale>/`; [read] excludes shipped ones (an active
 *   variant also writes into its base's directory)
 * @property variants variant groups keyed by base locale
 */
data class InstalledPacks(
    val shipped: Set<String> = emptySet(),
    val custom: Set<String> = emptySet(),
    val variants: Map<String, Group> = emptyMap(),
) {
    data class Group(val activeTag: String, val tags: Set<String>)

    /** `null` when not installed. A variant's tag is its locale id (`de_CH`), as [LanguageVariantStore] names it. */
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

    fun isInstalled(locale: String): Boolean = locale in shipped || locale in custom

    companion object {

        /**
         * Blocking I/O. `shipped` comes from [LanguagePackManager] because `LanguagePackInfo` is
         * package-private to the shim; only the catalogue's locales are asked about.
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
