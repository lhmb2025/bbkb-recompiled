package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.LetterLayout.Target
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
        LetterLayoutChoice.resetMigrationForTest()
        LetterLayoutChoice.enabledLanguages = { emptyList() }
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
    fun twoKeysPerLanguage_emptyMeaningTheKeyboardsDefault() {
        assertEquals("pref_letter_layout_vkb_en", LetterLayoutChoice.prefKey("en", Target.VKB))
        assertEquals("pref_letter_layout_pkb_en", LetterLayoutChoice.prefKey("en", Target.PKB))
        assertTrue(LetterLayoutChoice.isPrefKey("pref_letter_layout_pkb_iw"))
        assertTrue("a legacy single choice is one of these too", LetterLayoutChoice.isPrefKey("pref_letter_layout_iw"))
        assertFalse(LetterLayoutChoice.isPrefKey("pref_letter_layout_"))
        assertFalse(LetterLayoutChoice.isPrefKey("pref_pkb_active_layout"))
        assertFalse(LetterLayoutChoice.isPrefKey(null))
        assertEquals("", LetterLayoutChoice.chosenId(prefs, "en", Target.VKB))
        assertEquals("", LetterLayoutChoice.chosenId(prefs, null, Target.PKB))

        LetterLayoutChoice.choose(context, "en", Target.VKB, "colemak")
        assertEquals("colemak", prefs.getString("pref_letter_layout_vkb_en", null))
        assertEquals("colemak", LetterLayoutChoice.chosenId(prefs, "en", Target.VKB))
        assertEquals("the physical choice is its own", "", LetterLayoutChoice.chosenId(prefs, "en", Target.PKB))
        assertEquals("another language is untouched", "", LetterLayoutChoice.chosenId(prefs, "de", Target.VKB))
        LetterLayoutChoice.choose(context, "en", Target.VKB, "")
        assertFalse("back to the default removes the key", prefs.contains("pref_letter_layout_vkb_en"))
    }

    @Test
    fun aLegacySingleChoice_isSplitIntoTheTwo_onceTheyAreRead() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("mine"))
        registry.save(layout("both", targets = "pkb,vkb"))
        prefs.edit()
            .putString("pref_letter_layout_en", "colemak")
            .putString("pref_letter_layout_de", "mine")
            .putString("pref_letter_layout_fr", "both")
            .putString("pref_letter_layout_es", "gone")
            .commit()
        assertEquals("colemak", LetterLayoutChoice.onScreenLayout(context, "en")!!.id)
        assertEquals("Colemak draws the screen only", null, LetterLayoutChoice.forTyping(context, "en"))
        assertFalse(prefs.contains("pref_letter_layout_en"))
        assertEquals("colemak", prefs.getString("pref_letter_layout_vkb_en", null))
        assertNull(prefs.getString("pref_letter_layout_pkb_en", null))
        assertEquals("a user layout for the keys", "mine", prefs.getString("pref_letter_layout_pkb_de", null))
        assertNull(prefs.getString("pref_letter_layout_vkb_de", null))
        // A user's file for both keyboards cannot draw the screen yet: the keys only.
        assertEquals("both", prefs.getString("pref_letter_layout_pkb_fr", null))
        assertNull(prefs.getString("pref_letter_layout_vkb_fr", null))
        assertFalse("a choice whose layout is gone is dropped", prefs.all.keys.any { it.endsWith("_es") })
    }

    @Test
    fun theOneActiveLayout_becomesThePhysicalChoiceOfTheLanguagesItIsBoundTo() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("mine", languages = "de,fr"))
        registry.save(layout("all"))
        prefs.edit()
            .putString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, "mine")
            .putString("pref_letter_layout_pkb_fr", "all")
            .commit()
        LetterLayoutChoice.enabledLanguages = { listOf("en", "de", "fr") }
        LetterLayoutChoice.migrate(context)
        assertNull("not bound to English", prefs.getString("pref_letter_layout_pkb_en", null))
        assertEquals("mine", prefs.getString("pref_letter_layout_pkb_de", null))
        assertEquals("a language with a choice keeps it", "all", prefs.getString("pref_letter_layout_pkb_fr", null))
        assertFalse(prefs.contains(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT))

        // One bound to no language: every enabled language.
        LetterLayoutChoice.resetMigrationForTest()
        prefs.edit().clear().putString(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP, "all").commit()
        LetterLayoutChoice.migrate(context)
        assertEquals("all", prefs.getString("pref_letter_layout_pkb_en", null))
        assertEquals("all", prefs.getString("pref_letter_layout_pkb_de", null))
        assertFalse(prefs.contains(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP))
    }

    @Test
    fun theAlphabetSwitchOff_becomesTheKeypadsLettersForTheScriptLanguages() {
        prefs.edit().putBoolean(LetterLayoutChoice.LEGACY_ALPHABET_PREF, false).commit()
        LetterLayoutChoice.enabledLanguages = { listOf("en", "ar", "ru", "th") }
        LetterLayoutChoice.migrate(context)
        assertEquals(LetterLayoutChoice.KEYPAD_LETTERS, prefs.getString("pref_letter_layout_pkb_ar", null))
        assertEquals(LetterLayoutChoice.KEYPAD_LETTERS, prefs.getString("pref_letter_layout_pkb_ru", null))
        assertNull("English's document is Latin: the switch never changed it", prefs.getString("pref_letter_layout_pkb_en", null))
        assertNull("Thai has no document of its own", prefs.getString("pref_letter_layout_pkb_th", null))
        assertFalse(prefs.contains(LetterLayoutChoice.LEGACY_ALPHABET_PREF))
        assertTrue(LetterLayoutChoice.keypadLettersChosen(context, "ar"))
        assertFalse(LetterLayoutChoice.keypadLettersChosen(context, "en"))
        assertNull("the keypad's letters name no layout", LetterLayoutChoice.forTyping(context, "ar"))

        // On: nothing to carry, the key just goes.
        LetterLayoutChoice.resetMigrationForTest()
        prefs.edit().clear().putBoolean(LetterLayoutChoice.LEGACY_ALPHABET_PREF, true).commit()
        LetterLayoutChoice.migrate(context)
        assertFalse(prefs.contains(LetterLayoutChoice.LEGACY_ALPHABET_PREF))
        assertFalse(prefs.contains("pref_letter_layout_pkb_ar"))
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
        LetterLayoutChoice.choose(context, "en", Target.PKB, "mine")
        val first = LetterLayoutChoice.forTyping(context, "en")!!
        assertSame("kept", first, LetterLayoutChoice.forTyping(context, "en"))
        assertNull(LetterLayoutChoice.forTyping(context, "de"))
        // Re-saved under the same id: the registry's invalidate drops the copy.
        registry.save(layout("mine", name = "Edited"))
        val second = LetterLayoutChoice.forTyping(context, "en")!!
        assertNotSame(first, second)
        assertEquals("Edited", second.name)
        // Written behind its back (a settings restore): the id read on each call notices.
        prefs.edit().putString("pref_letter_layout_pkb_en", "dvorak").commit()
        assertEquals("dvorak", LetterLayoutChoice.forTyping(context, "en")!!.id)
        // A choice whose layout is gone types the keyboard's own.
        prefs.edit().putString("pref_letter_layout_pkb_en", "gone").commit()
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
        LetterLayoutChoice.choose(context, "en", Target.VKB, "colemak")
        assertEquals("colemak", LetterLayoutChoice.keyboardLayoutSet(context, english))
        assertEquals("colemak", LetterLayoutChoice.keyboardLayoutSet(context, subtype("en_GB", "qwerty")))
        assertEquals("French has its own", "azerty", LetterLayoutChoice.keyboardLayoutSet(context, french))
        LetterLayoutChoice.choose(context, "fr", Target.VKB, "dvorak")
        assertEquals("dvorak", LetterLayoutChoice.keyboardLayoutSet(context, french))

        // A custom layout changes the physical keys only, for now.
        LetterLayoutRegistry(context).save(layout("mine", targets = "pkb,vkb", rows = "qwerty"))
        LetterLayoutChoice.choose(context, "en", Target.VKB, "mine")
        assertEquals("qwerty", LetterLayoutChoice.keyboardLayoutSet(context, english))
        assertNull(LetterLayoutChoice.onScreenLayout(context, "en"))
        assertEquals("dvorak", LetterLayoutChoice.onScreenLayout(context, "fr")!!.id)
    }

    // ── what the Language screen offers ──────────────────────────────────────

    @Test
    fun theOnScreenList_isTheShippedLayoutsThatDrawIt_onALatinKeyboard() {
        val registry = LetterLayoutRegistry(context)
        val builtIns = registry.builtInIds().mapNotNull { registry.builtIn(it) }
        val custom = listOf(layout("a-english", name = "Alpha", languages = "en"), layout("screen-only", name = "Screen", targets = "vkb"))
        val draws = { it: LetterLayout -> LetterLayoutChoice.drawsOnScreen(context, it) }
        fun ids(options: List<LetterLayoutChoice.Option>) = options.map { it.id }
        assertEquals(
            "default first, then the shipped ones by name, less the keyboard's own set; a user's file cannot draw the screen",
            listOf("", "azerty", "colemak", "dvorak", "qwertz"),
            ids(LetterLayoutChoice.options(builtIns, custom, "en", Target.VKB, latin = true, drawsOnScreen = draws, ownLayoutSet = "qwerty")),
        )
        assertEquals(
            listOf("", "colemak", "dvorak", "qwerty", "qwertz"),
            ids(LetterLayoutChoice.options(builtIns, custom, "fr", Target.VKB, latin = true, drawsOnScreen = draws, ownLayoutSet = "azerty")),
        )
        assertEquals("nothing shipped draws a Thai keyboard", listOf(""), ids(LetterLayoutChoice.options(builtIns, custom, "th", Target.VKB, latin = false, drawsOnScreen = draws)))
    }

    @Test
    fun thePhysicalList_isTheOptInLayoutsForTheLanguage_andTheUsersForIt() {
        val registry = LetterLayoutRegistry(context)
        val builtIns = registry.builtInIds().mapNotNull { registry.builtIn(it) }
        val custom = listOf(
            layout("zz-german", name = "Zebra", languages = "de"),
            layout("b-everywhere", name = "Beta"),
            layout("a-english", name = "Alpha", languages = "en"),
            layout("screen-only", name = "Screen", targets = "vkb"),
            layout("t-thai", name = "Thai mine", languages = "th"),
        )
        val draws = { it: LetterLayout -> LetterLayoutChoice.drawsOnScreen(context, it) }
        fun ids(options: List<LetterLayoutChoice.Option>) = options.map { it.id }
        assertEquals(
            "the keypad's letters, then the user's layouts for the language or for every language, by name; the shipped Colemak is for the screen",
            listOf(LetterLayoutChoice.KEYPAD_LETTERS, "a-english", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "en", Target.PKB, latin = true, drawsOnScreen = draws, ownLayoutSet = "qwerty")),
        )
        assertEquals(
            "the Thai keypad's layout is offered to Thai, never applied by itself",
            listOf(LetterLayoutChoice.KEYPAD_LETTERS, "thai-keypad", "b-everywhere", "t-thai"),
            ids(LetterLayoutChoice.options(builtIns, custom, "th", Target.PKB, latin = false, drawsOnScreen = draws, ownLayoutSet = "thai")),
        )
        assertNull("not a Thai keyboard's default", registry.builtIn("th", "qwerty", "3row", "thai"))
        assertEquals(
            "the Zhuyin keypad's layout only on the Zhuyin keyboard",
            listOf(LetterLayoutChoice.KEYPAD_LETTERS, "zhuyin-keypad", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "zh", Target.PKB, latin = false, drawsOnScreen = draws, ownLayoutSet = "zhuyin")),
        )
        assertEquals(
            listOf(LetterLayoutChoice.KEYPAD_LETTERS, "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "zh", Target.PKB, latin = false, drawsOnScreen = draws, ownLayoutSet = "pinyin")),
        )
        // A Greek keyboard's own alphabet document is its second row, right after the keypad's
        // letters, and the row chosen for a keyboard with no choice.
        val greek = registry.builtIn("el", "qwerty", null, "greek")!!
        assertEquals(
            listOf(LetterLayoutChoice.KEYPAD_LETTERS, "el", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "el", Target.PKB, latin = false, drawsOnScreen = draws, ownLayoutSet = "greek", languageDocument = greek)),
        )
        assertEquals("el", LetterLayoutChoice.defaultPhysicalId(greek))
        assertEquals(LetterLayoutChoice.KEYPAD_LETTERS, LetterLayoutChoice.defaultPhysicalId(null))
        // An English keyboard's document types the keypad's own letters, so it stands in for them:
        // one row for the keyboard's letters, not two that mean the same.
        val english = registry.builtIn("en", "qwerty", null, "qwerty")!!
        assertEquals(
            listOf("en", "a-english", "b-everywhere"),
            ids(LetterLayoutChoice.options(builtIns, custom, "en", Target.PKB, latin = true, drawsOnScreen = draws, ownLayoutSet = "qwerty", languageDocument = english)),
        )
        assertEquals("en", LetterLayoutChoice.defaultPhysicalId(english))
    }

    @Test
    fun alwaysShowingTheOnScreenKeyboard_isOneSwitchPerLanguage() {
        assertFalse(OnScreenKeyboardChoice.isOn(prefs, "en"))
        assertFalse(OnScreenKeyboardChoice.isOn(prefs, null))
        OnScreenKeyboardChoice.choose(context, "en", true)
        assertTrue(OnScreenKeyboardChoice.isOn(prefs, "en"))
        assertFalse(OnScreenKeyboardChoice.isOn(prefs, "fr"))
        assertTrue(OnScreenKeyboardChoice.isPrefKey("pref_always_on_screen_en"))
        assertFalse(OnScreenKeyboardChoice.isPrefKey("pref_always_on_screen_"))
        assertFalse(OnScreenKeyboardChoice.isPrefKey("pref_letter_layout_pkb_en"))
        OnScreenKeyboardChoice.choose(context, "en", false)
        assertFalse("off is the absence of the key", prefs.contains("pref_always_on_screen_en"))
    }
}
