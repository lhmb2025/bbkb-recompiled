package dev.bbkb.ime.core.languagepack

import com.blackberry.nuanceshim.languagepack.LanguagePackRegistry

import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs

/**
 * Offers a pack when the user enables a keyboard language whose pack is not installed. Pure, so
 * every screen asks the same rule. The pack that serves a locale, in order: an exact non-variant
 * entry; for an exact variant (`de_CH`), its base language (a variant loads only in place of the
 * base, so offering it would silently replace the user's dictionary); a region the engine registry
 * substitutes (`en_NZ` to `en_AU`, `es_MX` to `es_419`); else the bare language (`fr_CA` to `fr`).
 * Nothing is offered for an unsupported locale, and [declined] keeps a "not now" quiet.
 */
object PackOffer {

    /**
     * The pack to offer for a newly enabled [locale], or `null`.
     *
     * @param locale `language` or `language_COUNTRY` (a dash is also accepted)
     * @param packs the catalogue; `null` (not loaded) offers nothing
     * @param installed asked about both the enabled locale and the pack that would serve it
     * @param declined locales the user said "not now" to this session
     */
    fun decide(
        locale: String,
        packs: Packs?,
        supported: (String) -> Boolean,
        installed: (String) -> Boolean,
        declined: Set<String> = emptySet(),
    ): PackEntry? {
        if (packs == null) return null
        val normalised = normalise(locale) ?: return null
        if (!supported(normalised)) return null
        if (installed(normalised)) return null

        val candidate = candidateFor(normalised, packs) ?: return null
        if (installed(candidate.locale)) return null
        if (candidate.locale in declined || normalised in declined) return null
        return candidate
    }

    /** The catalogue entry that would serve [locale] (rules in the class docs). */
    fun candidateFor(locale: String, packs: Packs): PackEntry? {
        val exact = packs.forLocale(locale)
        if (exact != null && !exact.isVariant) return exact
        if (exact == null) {
            val substitute = LanguagePackRegistry.fallbackFor(locale.replace('_', '-'))?.replace('-', '_')
            if (substitute != null) {
                packs.forLocale(substitute)?.takeUnless { it.isVariant }?.let { return it }
            }
        }
        val base = exact?.group ?: locale.substringBefore('_')
        if (base == locale && exact == null) return null
        return packs.forLocale(base)?.takeUnless { it.isVariant }
    }

    /** To the underscore form (`en_US`); dash tags from `toLanguageTag()` are accepted. */
    private fun normalise(locale: String): String? {
        val trimmed = locale.trim().replace('-', '_')
        if (trimmed.isEmpty()) return null
        val language = trimmed.substringBefore('_')
        if (language.isEmpty()) return null
        return trimmed
    }
}
