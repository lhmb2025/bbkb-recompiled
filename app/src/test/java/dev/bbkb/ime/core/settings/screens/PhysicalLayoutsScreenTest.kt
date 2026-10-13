package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutXml
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two pieces of the Physical layouts screen that decide what a reader sees without drawing
 * anything: the order of the built-in list ([orderBuiltInLayouts]) and the line under each row
 * ([layoutSummary]). What the screen renders is pinned by `SettingsScreenRenderTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhysicalLayoutsScreenTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val letterKeys = listOf("Q", "W", "E", "R", "T", "Y")

    private fun layout(
        id: String,
        name: String = id,
        languages: String = "",
        keypad: String = "any",
        keypadType: String = "any",
        keys: Int = 2,
    ): LetterLayout = LetterLayoutXml.parse(
        """<letter-layout version="1" id="$id" name="$name">
            <bind languages="$languages" keypad="$keypad" keypadType="$keypadType"/>
            ${letterKeys.take(keys).joinToString("\n") { """<key code="KEYCODE_$it" keySpec="${it.lowercase()}"/>""" }}
        </letter-layout>""",
        LetterLayout.Origin.BUILT_IN,
    )

    private fun ids(layouts: List<LetterLayout>) = layouts.map { it.id }

    private val names = mapOf("de" to "German", "ru" to "Russian", "uk" to "Ukrainian")

    private fun summary(layout: LetterLayout, phoneKeypad: String? = null, phoneKeypadType: String? = null) =
        layoutSummary(context, layout, phoneKeypad, phoneKeypadType) { names[it] ?: it }

    // ── order ────────────────────────────────────────────────────────────────

    @Test
    fun theKeyboardsLanguageComesFirstWithTheDocumentItWouldUseAtTheTop() {
        val all = listOf(
            layout("ar", "Arabic", languages = "ar"),
            layout("ru-uk", "Cyrillic, shared", languages = "kk,ky,ru,uk"),
            layout("colemak", "Colemak"),
            layout("ru", "Russian", languages = "ru"),
            layout("de", "German", languages = "de"),
        )
        assertEquals(
            listOf("ru", "ru-uk", "ar", "colemak", "de"),
            ids(orderBuiltInLayouts(all, keyboardLocale = "ru", chosenId = "ru")),
        )
        // The chosen one leads even when its name would not.
        assertEquals(
            listOf("ru-uk", "ru", "ar", "colemak", "de"),
            ids(orderBuiltInLayouts(all, keyboardLocale = "ru", chosenId = "ru-uk")),
        )
    }

    @Test
    fun withNoKeyboardLanguageEveryDocumentIsListedByName() {
        val all = listOf(layout("ru", "Russian", languages = "ru"), layout("ar", "Arabic", languages = "ar"), layout("colemak", "Colemak"))
        assertEquals(listOf("ar", "colemak", "ru"), ids(orderBuiltInLayouts(all, keyboardLocale = null, chosenId = null)))
        assertEquals(listOf("ar", "colemak", "ru"), ids(orderBuiltInLayouts(all, keyboardLocale = "", chosenId = null)))
    }

    @Test
    fun aDocumentForEveryKeyboardIsNoLanguagesOwn() {
        // Colemak applies to every keyboard, but it is a layout to choose, not this language's.
        val all = listOf(layout("colemak", "Colemak"), layout("en", "English", languages = "en"), layout("ar", "Arabic", languages = "ar"))
        assertEquals(listOf("en", "ar", "colemak"), ids(orderBuiltInLayouts(all, keyboardLocale = "en_US", chosenId = null)))
    }

    @Test
    fun aRegionNarrowsWhichDocumentsAreTheKeyboards() {
        val all = listOf(
            layout("zh-tw", "Chinese (Taiwan)", languages = "zh_TW"),
            layout("zh", "Chinese", languages = "zh"),
            layout("ar", "Arabic", languages = "ar"),
        )
        assertEquals(listOf("zh", "ar", "zh-tw"), ids(orderBuiltInLayouts(all, keyboardLocale = "zh_CN", chosenId = null)))
        assertEquals(listOf("zh", "zh-tw", "ar"), ids(orderBuiltInLayouts(all, keyboardLocale = "zh_TW", chosenId = null)))
    }

    @Test
    fun hebrewMatchesInEitherSpelling() {
        val all = listOf(layout("ar", "Arabic", languages = "ar"), layout("iw", "Hebrew", languages = "iw"))
        assertEquals(listOf("iw", "ar"), ids(orderBuiltInLayouts(all, keyboardLocale = "he", chosenId = null)))
    }

    @Test
    fun equalNamesFallBackToTheId() {
        val all = listOf(layout("b", "Same"), layout("a", "Same"), layout("c", "Same"))
        assertEquals(listOf("a", "b", "c"), ids(orderBuiltInLayouts(all, keyboardLocale = null, chosenId = null)))
    }

    // ── summary ──────────────────────────────────────────────────────────────

    @Test
    fun aLayoutForEveryKeyboardSaysSo() {
        assertEquals("All keyboards · 2 keys", summary(layout("colemak", keys = 2)))
    }

    @Test
    fun theLanguagesAreNamedInTheOrderTheDocumentGivesThem() {
        assertEquals("Ukrainian, Russian · 4 keys", summary(layout("uk-ru", languages = "uk,ru", keys = 4)))
    }

    @Test
    fun aBoundKeypadIsNamedAndOnAnotherPhoneSaysItDoesNothing() {
        val qwertz = layout("de", languages = "de", keypad = "qwertz", keys = 3)
        assertEquals("German · QWERTZ keypad · 3 keys", summary(qwertz, phoneKeypad = "qwertz"))
        assertEquals("German · QWERTZ keypad · 3 keys", summary(qwertz, phoneKeypad = "QWERTZ"))
        assertEquals(
            "German · QWERTZ keypad · 3 keys\nDoes nothing on this phone's keypad",
            summary(qwertz, phoneKeypad = "qwerty"),
        )
    }

    @Test
    fun anUnknownPhoneKeypadIsNotHeldAgainstTheLayout() {
        val qwertz = layout("de", languages = "de", keypad = "qwertz", keys = 3)
        assertEquals("German · QWERTZ keypad · 3 keys", summary(qwertz, phoneKeypad = null))
        val threeRow = layout("ru3", languages = "ru", keypadType = "3row")
        assertEquals("Russian · three-row keypad · 2 keys", summary(threeRow, phoneKeypadType = null))
    }

    @Test
    fun aThreeRowLayoutSaysWhetherThisPhoneHasThreeRows() {
        val threeRow = layout("ru3", languages = "ru", keypadType = "3row")
        assertEquals("Russian · three-row keypad · 2 keys", summary(threeRow, phoneKeypadType = "3row"))
        assertEquals("Russian · three-row keypad · 2 keys", summary(threeRow, phoneKeypadType = "3"))
        assertEquals(
            "Russian · three-row keypad · 2 keys\nDoes nothing on this phone's keypad",
            summary(threeRow, phoneKeypadType = "4row"),
        )
    }

    @Test
    fun aLayoutBoundToBothIsNamedWithBoth() {
        val both = layout("x", keypad = "azerty", keypadType = "3row", keys = 6)
        assertEquals("All keyboards · AZERTY keypad · three-row keypad · 6 keys", summary(both, "azerty", "3row"))
    }
}
