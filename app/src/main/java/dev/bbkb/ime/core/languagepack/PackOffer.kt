package dev.bbkb.ime.core.languagepack

import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs

/**
 * "You just enabled a language whose dictionary is missing — shall I fetch it?"
 *
 * The owner's rule is short: **a pack is offered when the user enables a keyboard language whose
 * pack is not installed.** [decide] is that rule and nothing else — a pure function of the
 * locale, the catalogue and two predicates, with no `Context`, no disk and no UI, so the decision
 * can be tested exhaustively and the screens that ask it cannot each grow their own version of
 * it.
 *
 * ### Which pack a language actually needs
 *
 * Not always the one with the matching locale. Three cases, in order:
 *
 *  1. **An exact entry that is a language of its own** (`de`, `en_US`, `cy`) — that is the pack.
 *  2. **An exact entry that is a *variant*** (`de_CH`, `nl_BE`) — that pack has no locale of its
 *     own in the engine's table and loads only *in place of* its base language, so enabling a
 *     Swiss German keyboard needs the **German** dictionary, not the Swiss one. The Swiss one is
 *     an extra the user can choose later on the Language packs screen; offering it here would
 *     silently replace their German dictionary as a side effect of adding a keyboard.
 *  3. **No exact entry** — fall back to the bare language (`fr_CA` → `fr`), which is what the
 *     engine's own registry does for a country it does not know.
 *
 * ### What is deliberately *not* here
 *
 * No notification, no automatic download, no retry timer. The offer is a Settings affordance: if
 * the user says "not now", [declined] keeps it quiet for that locale and the Language packs
 * screen remains the way back. And nothing is offered for a language the engine cannot load at
 * all ([supported] is false) — a download would install a dictionary that can never be read.
 */
object PackOffer {

    /**
     * The pack to offer for a newly enabled [locale], or `null` when there is nothing to offer.
     *
     * @param locale the enabled keyboard's locale, `language` or `language_COUNTRY` with an
     *   underscore (`Locale.toString()`'s shape, which is what the app's own code uses).
     * @param packs the catalogue, or `null` when it has not loaded — in which case nothing is
     *   offered, because an offer with no size and no URL behind it is not one.
     * @param supported whether the engine can load this locale at all
     *   (`LanguagePackManager.isSupported`). A language absent from the engine's locale table
     *   gets no offer.
     * @param installed whether a locale's dictionary is already on the phone
     *   (`LanguagePackManager.isInstalled`, or [InstalledPacks.isInstalled]). Asked about the
     *   enabled locale *and* about the pack that would serve it, since those can differ.
     * @param declined locales the user has already said "not now" to in this session.
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

    /**
     * The catalogue entry that would serve [locale] — see the three cases in the class docs.
     * Exposed for tests and for a caller that wants the size of the pack behind a language
     * without asking whether to offer it.
     */
    fun candidateFor(locale: String, packs: Packs): PackEntry? {
        val exact = packs.forLocale(locale)
        if (exact != null && !exact.isVariant) return exact
        val base = exact?.group ?: locale.substringBefore('_')
        if (base == locale && exact == null) return null
        return packs.forLocale(base)?.takeUnless { it.isVariant }
    }

    /**
     * `en_US`, `de`, `es_419` — the app's own spelling. A tag with a dash (`en-US`) is accepted
     * because `Locale.toLanguageTag()` produces those and more than one caller has one in hand;
     * anything with no language part at all is not a locale.
     */
    private fun normalise(locale: String): String? {
        val trimmed = locale.trim().replace('-', '_')
        if (trimmed.isEmpty()) return null
        val language = trimmed.substringBefore('_')
        if (language.isEmpty()) return null
        return trimmed
    }
}
