package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * [LetterLayoutChoice]: one preference per keyboard language naming a layout, what an id
 * resolves to, the typing path's copy, which layout set the on-screen keyboard is built from, and
 * what the Language screen offers each keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutChoiceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = PrefsManager.getPrefs(context)

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(context)
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        LetterLayoutRegistry.directoryOf(context).deleteRecursively()
        LetterLayoutRegistry.invalidate()
    }

    private fun layout(
        id: String,
        name: String = id,
        languages: String = "",
        targets: String = "pkb",
        rows: String = "",
        origin: LetterLayout.Origin = LetterLayout.Origin.USER,
    ) = LetterLayoutXml.parse(
        """<letter-layout version="1" id="$id" name="$name" targets="$targets">
            <bind languages="$languages"/>
            ${if (rows.isEmpty()) "" else """<rows template="$rows"/>"""}
            <key code="KEYCODE_Q" keySpec="w"/>
        </letter-layout>""",
        origin,
    )

    private fun subtype(locale: String, layoutSet: String): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale(locale)
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue("KeyboardLayoutSet=$layoutSet")
            .setIsAsciiCapable(true)
            .build()

    // ── the preference ───────────────────────────────────────────────────────

    @Test
    fun oneKeyPerLanguage_emptyMeaningTheKeyboardsOwn() {
        assertEquals("pref_letter_layout_en", LetterLayoutChoice.prefKey("en"))
        assertTrue(LetterLayoutChoice.isPrefKey("pref_letter_layout_iw"))
        assertFalse(LetterLayoutChoice.isPrefKey("pref_letter_layout_"))
        assertFalse(LetterLayoutChoice.isPrefKey("pref_pkb_active_layout"))
        assertFalse(LetterLayoutChoice.isPrefKey(null))
        assertEquals("", LetterLayoutChoice.chosenId(prefs, "en"))
        assertEquals("", LetterLayoutChoice.chosenId(prefs, null))

        LetterLayoutChoice.choose(context, "en", "colemak")
        assertEquals("colemak", prefs.getString("pref_letter_layout_en", null))
        assertEquals("colemak", LetterLayoutChoice.chosenId(prefs, "en"))
        assertEquals("another language is untouched", "", LetterLayoutChoice.chosenId(prefs, "de"))
        LetterLayoutChoice.choose(context, "en", "")
        assertFalse("back to the keyboard's own removes the key", prefs.contains("pref_letter_layout_en"))
    }

    @Test
    fun aKeyboardsLanguageIsTheAppsSpellingOfIt() {
        assertEquals("en", LetterLayoutChoice.languageOf(Locale.US))
        assertEquals("iw", LetterLayoutChoice.languageOf(Locale("he", "IL")))
        assertEquals("pt", LetterLayoutChoice.languageOf(subtype("pt_BR", "qwerty")))
        assertNull(LetterLayoutChoice.languageOf(null as Locale?))
    }

    // ── what an id names ─────────────────────────────────────────────────────

    @Test
    fun anIdNamesTheUsersLayoutFirst_thenTheBuiltInOne() {
        val registry = LetterLayoutRegistry(context)
        assertEquals(LetterLayout.Origin.BUILT_IN, LetterLayoutChoice.resolve(registry, "colemak")!!.origin)
        assertNull(LetterLayoutChoice.resolve(registry, ""))
        assertNull(LetterLayoutChoice.resolve(registry, "missing"))
        registry.save(layout("colemak", name = "My Colemak"))
        val mine = LetterLayoutChoice.resolve(registry, "colemak")!!
        assertEquals(LetterLayout.Origin.USER, mine.origin)
        assertEquals("My Colemak", mine.name)
    }

    @Test
    fun theTypingPathReadsTheFileOncePerChange() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("mine"))
        assertNull(LetterLayoutChoice.forTyping(context, "en"))
        LetterLayoutChoice.choose(context, "en", "mine")
        val first = LetterLayoutChoice.forTyping(context, "en")!!
        assertSame("kept", first, LetterLayoutChoice.forTyping(context, "en"))
        assertNull(LetterLayoutChoice.forTyping(context, "de"))
        // Re-saved under the same id: the registry's invalidate drops the copy.
        registry.save(layout("mine", name = "Edited"))
        val second = LetterLayoutChoice.forTyping(context, "en")!!
        assertNotSame(first, second)
        assertEquals("Edited", second.name)
        // Written behind its back (a settings restore): the id read on each call notices.
        prefs.edit().putString("pref_letter_layout_en", "dvorak").commit()
        assertEquals("dvorak", LetterLayoutChoice.forTyping(context, "en")!!.id)
        // A choice whose layout is gone types the keyboard's own.
        prefs.edit().putString("pref_letter_layout_en", "gone").commit()
        assertNull(LetterLayoutChoice.forTyping(context, "en"))
    }

    // ── the on-screen keyboard ───────────────────────────────────────────────

    @Test
    fun onlyAShippedLayoutWithRowsOfItsOwnDrawsTheOnScreenKeyboard() {
        val registry = LetterLayoutRegistry(context)
        assertTrue(LetterLayoutChoice.hasLayoutSet(context, "colemak"))
        assertTrue(LetterLayoutChoice.hasLayoutSet(context, "dvorak"))
        assertTrue(LetterLayoutChoice.hasLayoutSet(context, "qwerty"))
        assertFalse(LetterLayoutChoice.hasLayoutSet(context, "workman"))
        assertFalse("not a resource name", LetterLayoutChoice.hasLayoutSet(context, "my-layout"))

        assertTrue(LetterLayoutChoice.drawsOnScreen(context, registry.builtIn("colemak")!!))
        assertTrue(LetterLayoutChoice.drawsOnScreen(context, registry.builtIn("dvorak")!!))
        for (id in listOf("qwerty", "azerty", "qwertz")) {
            assertTrue("$id draws the rows the app always had", LetterLayoutChoice.drawsOnScreen(context, registry.builtIn(id)!!))
        }
        assertFalse("a language's document is for the keypad", LetterLayoutChoice.drawsOnScreen(context, registry.builtIn("en")!!))
        assertFalse(
            "a user's file with Colemak's id is a different document",
            LetterLayoutChoice.drawsOnScreen(context, layout("colemak", targets = "pkb,vkb", rows = "spanish")),
        )
        assertFalse(
            "no rows shipped for it",
            LetterLayoutChoice.drawsOnScreen(
                context,
                layout("workman", targets = "pkb,vkb", rows = "qwerty", origin = LetterLayout.Origin.BUILT_IN),
            ),
        )
    }

    @Test
    fun theOnScreenKeyboardIsBuiltFromTheChosenLayoutsSet_whenItHasOne() {
        val english = subtype("en_US", "qwerty")
        val french = subtype("fr", "azerty")
        assertEquals("qwerty", LetterLayoutChoice.keyboardLayoutSet(context, english))
        LetterLayoutChoice.choose(context, "en", "colemak")
        assertEquals("colemak", LetterLayoutChoice.keyboardLayoutSet(context, english))
        assertEquals("colemak", LetterLayoutChoice.keyboardLayoutSet(context, subtype("en_GB", "qwerty")))
        assertEquals("French has its own", "azerty", LetterLayoutChoice.keyboardLayoutSet(context, french))
        LetterLayoutChoice.choose(context, "fr", "dvorak")
        assertEquals("dvorak", LetterLayoutChoice.keyboardLayoutSet(context, french))

        // A custom layout changes the physical keys only, for now.
        LetterLayoutRegistry(context).save(layout("mine", targets = "pkb,vkb", rows = "qwerty"))
        LetterLayoutChoice.choose(context, "en", "mine")
        assertEquals("qwerty", LetterLayoutChoice.keyboardLayoutSet(context, english))
        assertNull(LetterLayoutChoice.onScreenLayout(context, "en"))
        assertEquals("dvorak", LetterLayoutChoice.onScreenLayout(context, "fr")!!.id)
    }

    // ── what the Language screen offers ──────────────────────────────────────

    @Test
    fun aLatinKeyboardIsOfferedItsOwnLayoutTheShippedOnesAndTheUsersForItsLanguage() {
        val registry = LetterLayoutRegistry(context)
        val builtIns = registry.builtInIds().mapNotNull { registry.builtIn(it) }
        val custom = listOf(
            layout("zz-german", name = "Zebra", languages = "de"),
            layout("b-everywhere", name = "Beta"),
            layout("a-english", name = "Alpha", languages = "en"),
            layout("screen-only", name = "Screen", targets = "vkb"),
        )
        val usable = { it: LetterLayout -> LetterLayoutChoice.drawsOnScreen(context, it) }
        fun ids(options: List<LetterLayoutChoice.Option>) = options.map { it.id }

        assertEquals(
            "own first, then the shipped ones by name (less the keyboard's own set), then the user's by name",
            listOf("", "azerty", "colemak", "dvorak", "qwertz", "a-english", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "en", latin = true, physicalKeys = true, usable = usable, ownLayoutSet = "qwerty")),
        )
        assertEquals(
            "a French keyboard: AZERTY is its own first entry, so the AZERTY document is left out",
            listOf("", "colemak", "dvorak", "qwerty", "qwertz", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "fr", latin = true, physicalKeys = true, usable = usable, ownLayoutSet = "azerty")),
        )
        assertEquals(
            "a keyboard whose own set is not a document (Spanish, Nordic) is offered all five",
            listOf("", "azerty", "colemak", "dvorak", "qwerty", "qwertz"),
            ids(LetterLayoutChoice.options(builtIns, emptyList(), "es", latin = true, physicalKeys = true, usable = usable, ownLayoutSet = "spanish")),
        )
        assertEquals(
            "a touch-only phone: the user's layouts change nothing there",
            listOf("", "azerty", "colemak", "dvorak", "qwertz"),
            ids(LetterLayoutChoice.options(builtIns, custom, "en", latin = true, physicalKeys = false, usable = usable, ownLayoutSet = "qwerty")),
        )
        assertEquals(
            "a keyboard in a script of its own: no Latin layouts, its own language's custom ones",
            listOf("", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "ru", latin = false, physicalKeys = true, usable = usable, ownLayoutSet = "east_slavic")),
        )
        assertEquals(
            "a user's file with a shipped id stands in for it",
            listOf("", "azerty", "dvorak", "qwertz", "colemak"),
            ids(LetterLayoutChoice.options(builtIns, listOf(layout("colemak", name = "Colemak mod")), "en", latin = true, physicalKeys = true, usable = usable, ownLayoutSet = "qwerty")),
        )
        assertTrue(LetterLayoutChoice.options(builtIns, custom, "en", latin = true, physicalKeys = true, usable = usable, ownLayoutSet = "qwerty").first().layout == null)
    }
}
