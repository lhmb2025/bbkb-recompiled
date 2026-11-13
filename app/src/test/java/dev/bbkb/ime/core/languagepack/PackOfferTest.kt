package dev.bbkb.ime.core.languagepack

import dev.bbkb.ime.core.distribution.Packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [PackOffer.decide], the "enabled language has no dictionary" rule. A Swiss German keyboard
 * must offer German: the Swiss pack has no engine locale and would replace German. Robolectric
 * because [PackFixtures] parses with `org.json`, an empty stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
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
        // A locale absent from the engine's 105-entry table could never read a download.
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

    @Test
    fun aSwissKeyboardIsOfferedTheBaseLanguagesPackNotTheSwissVariant() {
        val offer = decide("de_CH")

        assertEquals("de", offer?.locale)
        assertEquals("German", offer?.name)
    }

    @Test
    fun aSwissKeyboardIsOfferedNothingOnceTheBasePackIsInstalled() {
        // The Swiss variant is an optional extra on the Language packs screen, not offered here.
        assertNull(decide("de_CH", installed = setOf("de")))
    }

    @Test
    fun anUnknownCountryFallsBackToTheBareLanguage() {
        // fr_BF has no entry; the registry resolves it to French, and so does the offer.
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
        // Callers may pass Locale.toLanguageTag() output, with dashes.
        assertEquals("en_US", decide("en-US")?.locale)
    }

    @Test
    fun candidateForNamesThePackThatWouldServeALanguage() {
        assertEquals("de", PackOffer.candidateFor("de_CH", packs)?.locale)
        assertEquals("cy", PackOffer.candidateFor("cy", packs)?.locale)
        assertNull(PackOffer.candidateFor("tlh", packs))
    }

    // The registry maps en_NZ to en_AU, es_MX to es_419 and en_GB (with en_SG/IE/ZA) to the
    // bundled en_UK; deciding from the bare language would offer nothing, since en and es ship.

    @Test
    fun aSubstitutedRegionIsOfferedThePackTheEngineWillLoad() {
        assertEquals("en_AU", PackOffer.candidateFor("en_NZ", packs)?.locale)
        assertEquals("es_419", PackOffer.candidateFor("es_MX", packs)?.locale)
        assertEquals("en_UK", PackOffer.candidateFor("en_GB", packs)?.locale)
        assertEquals("en_UK", PackOffer.candidateFor("en_ZA", packs)?.locale)
    }

    @Test
    fun newZealandIsOfferedAustralianEnglishEvenThoughBareEnglishIsBundled() {
        assertEquals("en_AU", decide("en_NZ", installed = setOf("en", "en_US", "en_UK"))?.locale)
        assertEquals("es_419", decide("es_MX", installed = setOf("es", "es_US"))?.locale)
    }

    @Test
    fun britishEnglishIsQuietOnceTheBundledUkPackCountsAsInstalled() {
        assertNull(decide("en_GB", installed = setOf("en_GB", "en_UK")))
        assertNull(decide("en_GB", installed = setOf("en_UK")))
    }
}
