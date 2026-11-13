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
 * Feeds [PackOffer.decide] its inputs from this phone (the decision itself stays pure). The
 * catalogue is fetched at decision time, not on screen entry, so unrelated screens never wait on
 * the network; [ManifestSource] serves a cache under six hours old without a request.
 */
object PackOfferSource {

    /**
     * The first of [locales] (just enabled, primary first) whose dictionary is missing and
     * downloadable, or `null`. One offer only, never stacked dialogs.
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
     * Every pack [locales] need and this phone lacks, deduplicated, in order. Blocking: call from a
     * worker. A layout suffix (`zh_HK_cangjie`) is ignored; only language and region decide.
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

    /** Fresh cache, else network, else stale cache; failure is a silent `null`. */
    private suspend fun packs(context: Context): Packs? {
        val source = ManifestSource(context)
        val manifest = source.fetch(force = false).getOrNull() ?: source.cached()
        return manifest?.packs
    }

    /** A pack worth offering, and the catalogue it came from (which carries the base URL). */
    data class Target(val entry: PackEntry, val packs: Packs)

    /** Identifier adapters; an identifier that is not a locale is neither supported nor installed. */
    private fun LanguagePackManager.isSupportedLocale(locale: String): Boolean {
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        return isSupported(parsed)
    }

    private fun LanguagePackManager.isInstalledLocale(locale: String): Boolean {
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        return isInstalled(parsed)
    }
}
