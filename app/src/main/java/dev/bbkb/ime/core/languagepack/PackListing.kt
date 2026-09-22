package dev.bbkb.ime.core.languagepack

import java.text.Collator
import java.util.Locale

/**
 * The order and the shape of the two lists on the Language packs screen.
 *
 * Pure, and deliberately outside the composable: what the screen shows is "these rows, in this
 * order", and both halves of that are decidable from data alone — which is what lets a JVM test
 * assert the whole ordering against the real `dist/manifest.json` instead of a rendered tree.
 *
 * Two rules, and they are the same rule for both lists:
 *
 *  - **One flat list.** A downloadable regional dictionary ([PackCatalog.Row.variants] — Swiss
 *    German, Belgian Dutch and the other two) used to be drawn indented under the language it
 *    replaces. It is an ordinary row now, in its own alphabetical place; what it does is said in
 *    words by [AvailablePack.replaces] rather than by an indent the user has to interpret.
 *  - **Alphabetical by the name actually on the row**, compared with a [Collator] for the user's
 *    own locale, so "Ärzte"-style accents and case sort where that language expects them rather
 *    than where their UTF-16 code units fall.
 */
object PackListing {

    /**
     * Case- and accent-insensitive enough to be alphabetical, in [locale]'s own alphabet.
     *
     * `SECONDARY` keeps accents apart (so `a` and `ä` do not tie) while ignoring case, which is
     * the ordering a list of language names wants: "Åland" next to "Albanian", not after "Zulu".
     */
    fun collator(locale: Locale = Locale.getDefault()): Collator =
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    /**
     * [items] by the name [name] puts on the row.
     *
     * Generic because the two lists carry different row models — the installed half has the
     * screen's own record, the available half has [AvailablePack] — and only the displayed name
     * decides the order.
     */
    fun <T> byDisplayName(
        items: List<T>,
        locale: Locale = Locale.getDefault(),
        name: (T) -> String,
    ): List<T> {
        val collator = collator(locale)
        return items.sortedWith(compareBy(collator) { name(it) })
    }

    /**
     * One row of **Available to download**: the pack, and the language it would load in place of.
     *
     * @property replaces the display name of the base language this pack takes the slot of, or
     *   `null` for a language's own dictionary. Only a catalogue `group` entry has one.
     */
    data class AvailablePack(val item: PackCatalog.Item, val replaces: String? = null) {
        val displayName: String get() = item.displayName
        val locale: String get() = item.locale
    }

    /**
     * Everything in [rows] that is not installed yet, flattened and alphabetized.
     *
     * A row contributes its base pack when that is not installed, plus every variant of it that
     * is not installed. An installed base language contributes nothing of its own: it is in the
     * *Installed* list, and repeating it here as an un-actionable header only existed to explain
     * the nesting that is gone.
     *
     * Ties break on the locale identifier so two identically named packs keep a fixed order and
     * the list cannot shuffle between recompositions.
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
