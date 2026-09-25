package dev.bbkb.ime.core.locale

import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Languages screen offers under "Add a language". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyboardLanguagesTest {

    private fun subtype(locale: String, extra: String = "EmojiCapable") =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale(locale)
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue(extra)
            .build()

    private fun pack(locale: String, name: String, group: String? = null) =
        PackEntry(locale, name, "$locale.ldb", "0".repeat(64), 1_000_000L, group)

    private val english = subtype("en_US")
    private val korean = subtype("ko")
    private val alphabet = subtype("zz")
    private val combined = subtype("en_US", "KeyboardLayoutSet=qwerty,AdditionalLocales=fr")

    private val packs = Packs(
        "1", "https://example.invalid/",
        listOf(pack("ko", "Korean"), pack("sw", "Swahili"), pack("am", "Amharic"), pack("de_CH", "Swiss German", "de")),
    )

    private fun build(enabled: Set<Int>) = KeyboardLanguages.buildCandidates(
        all = listOf(english, korean, alphabet, combined),
        enabled = enabled,
        packs = packs,
        nameOf = { mapOf("en_US" to "English (US)", "ko" to "Korean")[it.locale] ?: it.locale },
        missing = { locale -> packs.items.firstOrNull { it.locale == locale } },
        canOfferSubtypeFor = { it == "sw" },
    )

    @Test
    fun offersKeyboardsThatAreNotOnAndLeavesOutTheOnesThatAre() {
        val names = build(enabled = setOf(english.hashCode())).map { it.name }
        assertEquals(listOf("Korean", "Swahili"), names)
    }

    @Test
    fun aKeyboardCarriesTheDictionaryAddingItWouldDownload() {
        val koreanCandidate = build(emptySet()).first { it.locale == "ko" }
        assertEquals("ko", koreanCandidate.pack?.locale)
        assertEquals(korean.hashCode(), koreanCandidate.subtype?.hashCode())
    }

    @Test
    fun aCatalogueLanguageWithoutAKeyboardIsOfferedOnlyWhenOneCanBeMadeForIt() {
        // Swahili gets a runtime keyboard when its pack installs; Amharic has no layout, and
        // Swiss German is a variant that loads in German's place.
        val swahili = build(emptySet()).single { it.locale == "sw" }
        assertNull(swahili.subtype)
        assertEquals("sw", swahili.pack?.locale)
        assertEquals(emptyList<String>(), build(emptySet()).filter { it.locale == "am" || it.locale == "de_CH" }.map { it.locale })
    }

    @Test
    fun takingTheChoiceBackDoesNotDuplicateAParkedKeyboardsLanguage() {
        // Parked: English + French. Android picked plain English meanwhile. Only one English.
        val merged = KeyboardLanguages.mergeRestored(current = listOf(english), restored = listOf(combined))
        assertEquals(listOf(combined.hashCode()), merged.map { it.hashCode() })
    }

    @Test
    fun takingTheChoiceBackKeepsLanguagesAndroidAddedThatWereNotParked() {
        val merged = KeyboardLanguages.mergeRestored(current = listOf(english, korean), restored = listOf(combined))
        assertEquals(listOf(combined.hashCode(), korean.hashCode()), merged.map { it.hashCode() })
    }

    @Test
    fun takingTheChoiceBackWithNothingParkedKeepsAndroidsPicks() {
        val merged = KeyboardLanguages.mergeRestored(current = listOf(english, korean), restored = emptyList())
        assertEquals(listOf(english.hashCode(), korean.hashCode()), merged.map { it.hashCode() })
    }

    @Test
    fun neverOffersTheAlphabetOrAMultiLanguageKeyboard() {
        val locales = build(emptySet()).map { it.subtype?.hashCode() }
        assert(alphabet.hashCode() !in locales)
        assert(combined.hashCode() !in locales)
    }
}
