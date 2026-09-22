package dev.bbkb.ime.core.languagepack

import dev.bbkb.ime.core.distribution.Packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PackOffer.decide] — the whole "you enabled a language whose dictionary is missing" rule.
 *
 * Pure, so every case is a line. The one that matters most is the Swiss case: enabling a Swiss
 * German keyboard must offer **German**, because the Swiss pack has no locale of its own in the
 * engine's table and installing it would replace the German dictionary as a side effect of adding
 * a keyboard.
 */
class PackOfferTest {

    private val packs: Packs = PackFixtures.realPacks()

    private val nothingInstalled: (String) -> Boolean = { false }
    private val everythingSupported: (String) -> Boolean = { true }

    private fun decide(
        locale: String,
        installed: Set<String> = emptySet(),
        supported: Set<String>? = null,
        declined: Set<String> = emptySet(),
        catalogue: Packs? = packs,
    ) = PackOffer.decide(
        locale = locale,
        packs = catalogue,
        supported = if (supported == null) everythingSupported else { l -> l in supported },
        installed = { it in installed },
        declined = declined,
    )

    // ── The ordinary case ─────────────────────────────────────────────────────────────────────

    @Test
    fun offersThePackForAnEnabledLanguageThatHasNone() {
        val offer = decide("cy")

        assertEquals("cy", offer?.locale)
        assertEquals("Welsh", offer?.name)
    }

    @Test
    fun offersNothingWhenThePackIsAlreadyInstalled() {
        assertNull(decide("cy", installed = setOf("cy")))
    }

    @Test
    fun offersNothingForALanguageTheEngineCannotLoad() {
        // isSupported is false for a locale absent from the engine's 105-entry table: a download
        // would install a dictionary nothing can ever read.
        assertNull(decide("cy", supported = emptySet()))
    }

    @Test
    fun offersNothingWithoutACatalogue() {
        assertNull(decide("cy", catalogue = null))
    }

    @Test
    fun offersNothingForALanguageThatIsNotPublished() {
        assertNull(decide("tlh"))
        assertNull(decide(""))
        assertNull(decide("_"))
    }

    @Test
    fun aDeclinedLanguageStaysQuiet() {
        assertNull(decide("cy", declined = setOf("cy")))
        // Declining the pack the language resolves to counts too.
        assertNull(decide("de_CH", declined = setOf("de")))
    }

    // ── Regional keyboards ────────────────────────────────────────────────────────────────────

    @Test
    fun aSwissKeyboardIsOfferedTheBaseLanguagesPackNotTheSwissVariant() {
        val offer = decide("de_CH")

        assertEquals("de", offer?.locale)
        assertEquals("German", offer?.name)
    }

    @Test
    fun aSwissKeyboardIsOfferedNothingOnceTheBasePackIsInstalled() {
        // The Swiss variant remains available on the Language packs screen; it is an extra, not a
        // requirement, so enabling the keyboard does not push it.
        assertNull(decide("de_CH", installed = setOf("de")))
    }

    @Test
    fun anUnknownCountryFallsBackToTheBareLanguage() {
        // fr_BF has no entry; the engine's registry resolves such a country to French, and so
        // does the offer.
        assertEquals("fr", decide("fr_BF")?.locale)
    }

    @Test
    fun aCountryWithItsOwnPackGetsThatPack() {
        assertEquals("en_US", decide("en_US")?.locale)
        assertEquals("pt_BR", decide("pt_BR")?.locale)
    }

    @Test
    fun theTableLocalePacksAreOfferedUnderTheirOwnLocale() {
        for (locale in PackFixtures.TABLE_LOCALE_PACKS) {
            assertEquals(locale, decide(locale)?.locale)
        }
    }

    @Test
    fun aBcp47StyleTagIsAcceptedToo() {
        // Locale.toLanguageTag() produces dashes and more than one caller has one in hand.
        assertEquals("en_US", decide("en-US")?.locale)
    }

    // ── candidateFor on its own ───────────────────────────────────────────────────────────────

    @Test
    fun candidateForNamesThePackThatWouldServeALanguage() {
        assertEquals("de", PackOffer.candidateFor("de_CH", packs)?.locale)
        assertEquals("cy", PackOffer.candidateFor("cy", packs)?.locale)
        assertNull(PackOffer.candidateFor("tlh", packs))
    }
}
