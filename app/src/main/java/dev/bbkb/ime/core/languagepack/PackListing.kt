package dev.bbkb.ime.core.languagepack

import java.text.Collator
import java.util.Locale

/**
 * Order and shape of the two lists on the Language packs screen, pure so a JVM test can check them
 * against the real manifest. One flat list (a regional variant is an ordinary row whose effect is
 * said by [AvailablePack.replaces]), sorted by the displayed name with the user's locale [Collator].
 */
object PackListing {
    /** A [locale] collator at `SECONDARY` strength: accents distinct, case ignored. */
    fun collator(locale: Locale = Locale.getDefault()): Collator =
        // Clone per call: Collator is not thread-safe, so the cached one is never handed out.
        prototypes.getOrPut(locale) {
            Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        }.clone() as Collator

    /**
     * Configured prototypes: the screen re-sorts on every state change and `Collator.getInstance`
     * costs far more than a clone. A [getOrPut] race at worst builds an equal instance twice.
     */
    private val prototypes = java.util.concurrent.ConcurrentHashMap<Locale, Collator>()

    /** [items] sorted by the name [name] puts on the row (the two lists use different models). */
    fun <T> byDisplayName(
        items: List<T>,
        locale: Locale = Locale.getDefault(),
        name: (T) -> String,
    ): List<T> {
        val collator = collator(locale)
        return items.sortedWith(compareBy(collator) { name(it) })
    }

    /**
     * One "Available to download" row.
     *
     * @property replaces display name of the base language a variant loads in place of, else `null`
     */
    data class AvailablePack(val item: PackCatalog.Item, val replaces: String? = null) {
        val displayName: String get() = item.displayName
        val locale: String get() = item.locale
    }

    /**
     * Every uninstalled base pack and variant in [rows], flattened and alphabetized. Ties break on
     * locale so the list cannot shuffle between recompositions.
     */
    fun available(
        rows: List<PackCatalog.Row>,
        locale: Locale = Locale.getDefault(),
    ): List<AvailablePack> {
        val flat = rows.flatMap { row ->
            val base = if (row.base.state is PackState.Installed) {
                emptyList()
            } else {
                listOf(AvailablePack(row.base))
            }
            base + row.availableVariants.map { AvailablePack(it, replaces = row.displayName) }
        }
        val collator = collator(locale)
        return flat.sortedWith(
            compareBy<AvailablePack, String>(collator) { it.displayName }.thenBy { it.locale }
        )
    }
}
