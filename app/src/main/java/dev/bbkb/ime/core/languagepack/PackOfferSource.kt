package dev.bbkb.ime.core.languagepack

import android.content.Context
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import dev.bbkb.ime.core.locale.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The thin adapter that gives [PackOffer.decide] its three inputs off this phone.
 *
 * Kept separate from [PackOffer] on purpose: the *decision* is pure and exhaustively tested,
 * while this is the part that has a `Context`, a catalogue to fetch and a registry to ask. A
 * screen calls [firstMissing] once, after it has enabled something.
 *
 * ### Why the catalogue is fetched here rather than on screen entry
 *
 * The prompt exists because the user just enabled a language. Fetching the manifest when the
 * screen *opens* would put a network request behind every visit to a settings screen that has
 * nothing to do with dictionaries, and would make a screen's first frame depend on connectivity.
 * [ManifestSource] serves a cache younger than six hours without a request at all, so asking at
 * the moment of the decision is usually free and always correct.
 */
object PackOfferSource {

    /**
     * The first of [locales] whose dictionary is missing and downloadable, or `null`.
     *
     * One offer, not a queue of them: a multi-language keyboard can enable four languages at
     * once, and four stacked dialogs is not an offer, it is an obstacle. The Language packs
     * screen lists the rest.
     *
     * @param locales the locales just enabled, in the order they should be offered (primary
     *   first). `Locale.toString()` form.
     * @param declined locales the user has already waved away.
     */
    suspend fun firstMissing(
        context: Context,
        locales: List<String>,
        declined: Set<String> = emptySet(),
    ): Target? = withContext(Dispatchers.IO) {
        val packs = packs(context) ?: return@withContext null
        val manager = LanguagePackManager.getInstance(context)
        val supported = { locale: String -> manager.isSupportedLocale(locale) }
        val installed = { locale: String -> manager.isInstalledLocale(locale) }
        val entry = locales.firstNotNullOfOrNull { locale ->
            PackOffer.decide(locale, packs, supported, installed, declined)
        } ?: return@withContext null
        Target(entry, packs)
    }

    /**
     * Every catalogue pack that [locales] need and this phone lacks, one per pack, in order.
     * Blocking (registry lookups) — call from a worker. For the Languages screen, which fetches
     * dictionaries as part of adding a language instead of asking first.
     *
     * Keyboard locales may carry a layout suffix (`zh_HK_cangjie`); only language and region
     * decide the pack.
     */
    fun missingPacks(context: Context, locales: List<String>, packs: Packs): List<PackEntry> {
        val manager = LanguagePackManager.getInstance(context)
        val supported = { locale: String -> manager.isSupportedLocale(locale) }
        val installed = { locale: String -> manager.isInstalledLocale(locale) }
        return locales
            .map { it.split('_').take(2).joinToString("_") }
            .mapNotNull { PackOffer.decide(it, packs, supported, installed) }
            .distinctBy { it.locale }
    }

    /** The catalogue's packs (cache first, then network), or null when neither is available. */
    suspend fun loadPacks(context: Context): Packs? = withContext(Dispatchers.IO) { packs(context) }

    /**
     * The catalogue, from the cache when it is fresh enough and from the network otherwise. A
     * failure is `null`: there is no offer to make if we do not know what is published, and the
     * user did not ask for a catalogue, so there is nothing to report either.
     */
    private suspend fun packs(context: Context): Packs? {
        val source = ManifestSource(context)
        val manifest = source.fetch(force = false).getOrNull() ?: source.cached()
        return manifest?.packs
    }

    /** A pack worth offering, and the catalogue it came from (which carries the base URL). */
    data class Target(val entry: PackEntry, val packs: Packs)

    /**
     * `isSupported`/`isInstalled` take a `java.util.Locale`; the catalogue speaks in identifiers.
     * An identifier that is not a locale at all is neither supported nor installed.
     */
    private fun LanguagePackManager.isSupportedLocale(locale: String): Boolean {
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        return isSupported(parsed)
    }

    private fun LanguagePackManager.isInstalledLocale(locale: String): Boolean {
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        return isInstalled(parsed)
    }
}
