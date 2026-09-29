package dev.bbkb.ime.core.subtypeswitcher

import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Runtime subtypes for the engine-supported languages `method.xml` does not declare.
 *
 * The engine's locale table covers 94 languages; `method.xml` declares 66. Without this, the other
 * 28 install correctly and can never be selected — the pack is real, the dictionary loads, and the
 * language does not appear anywhere a user can reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SideloadedSubtypesTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = PrefsManager.getPrefs(context)

    @Before
    fun clean() {
        prefs.edit().remove("sideloaded_subtypes").commit()
        // SubtypeFactory drops any subtype whose name resolves to subtype_generic, and the
        // layout -> name table that decides that is built by this call. RichInputMethodManager
        // makes it before reading the preference; a test that skips it sees every subtype filtered
        // out and would "prove" the pref format is wrong.
        ResourceLocaleUtils.init(context)
    }

    // ── the layout table ────────────────────────────────────────────────────────

    @Test
    fun everyLatinScriptLanguageGetsQwerty() {
        for (l in listOf("ha", "ig", "ku", "ln", "mg", "st", "sw", "tk", "xh", "yo", "zu")) {
            assertEquals("layout for $l", "qwerty", SideloadedSubtypes.layoutFor(l))
        }
    }

    @Test
    fun eachNonLatinLanguageGetsALayoutThatWritesItsOwnScript() {
        // Not qwerty: a Burmese dictionary behind a Latin keyboard is unusable, and the point of
        // the table is that every entry can actually type the language it claims.
        assertEquals("bengali", SideloadedSubtypes.layoutFor("as"))
        assertEquals("east_slavic", SideloadedSubtypes.layoutFor("kk"))
        assertEquals("east_slavic", SideloadedSubtypes.layoutFor("tt"))
        assertEquals("myanmar", SideloadedSubtypes.layoutFor("my"))
        assertEquals("sinhala", SideloadedSubtypes.layoutFor("si"))
        assertEquals("nepali_traditional", SideloadedSubtypes.layoutFor("ne"))
        assertEquals("hindi", SideloadedSubtypes.layoutFor("sa"))
        assertEquals("farsi", SideloadedSubtypes.layoutFor("ur"))
        assertEquals("arabic", SideloadedSubtypes.layoutFor("ug"))
    }

    @Test
    fun everyLayoutNamedInTheTableActuallyShips() {
        // Guards a typo turning into a subtype that resolves to no keyboard at all. Checked against
        // the real resource, not a hardcoded list of layout names.
        val res = context.resources
        for (lang in listOf("ha", "as", "kk", "tt", "ks", "sa", "ne", "my", "si", "ps", "ur", "ug", "tg", "am", "bo", "gu", "or", "pa")) {
            val layout = SideloadedSubtypes.layoutFor(lang)
            assertNotNull("no layout for $lang", layout)
            val id = res.getIdentifier("keyboard_layout_set_$layout", "xml", context.packageName)
            assertTrue("layout $layout for $lang does not ship", id != 0)
        }
    }

    @Test
    fun theFiveFormerlyExcludedLanguagesGetTheirOwnScriptLayouts() {
        // Until 2026-09-28 these were refused because the tree had no layout for their scripts.
        val expected = mapOf("am" to "ethiopic", "bo" to "tibetan", "gu" to "gujarati", "or" to "oriya", "pa" to "gurmukhi")
        val res = context.resources
        for ((l, layout) in expected) {
            assertEquals("layout for $l", layout, SideloadedSubtypes.layoutFor(l))
            assertTrue(SideloadedSubtypes.canOfferSubtypeFor(l))
            assertTrue("layout $layout does not ship", res.getIdentifier("keyboard_layout_set_$layout", "xml", context.packageName) != 0)
        }
    }

    @Test
    fun noLanguageMethodXmlAlreadyDeclaresIsInTheTable() {
        // The table is for the GAP only. An entry duplicating a built-in subtype would register a
        // second, competing subtype for the same language.
        for (builtIn in listOf("en", "fr", "de", "es", "it", "ru", "ja", "ko", "zh", "ar", "he", "iw", "jv", "nb", "fil", "in")) {
            assertFalse("$builtIn is declared in method.xml and must not be here",
                SideloadedSubtypes.canOfferSubtypeFor(builtIn))
        }
    }

    // ── the preference ──────────────────────────────────────────────────────────

    @Test
    fun addStoresThePairInTheFormatSubtypeFactoryParses() {
        assertTrue(SideloadedSubtypes.add(context, "sw"))
        assertEquals("sw:qwerty", SideloadedSubtypes.readRaw(prefs))
        // and it must survive the round trip into real subtypes
        val subtypes = SubtypeFactory.createSubtypesFromPref(SideloadedSubtypes.readRaw(prefs))
        assertEquals(1, subtypes.size)
        assertEquals("sw", subtypes[0].locale)
        assertTrue("must be flagged additional", SubtypeFactory.isAdditionalSubtype(subtypes[0]))
    }

    @Test
    fun addIsIdempotent() {
        SideloadedSubtypes.add(context, "sw")
        SideloadedSubtypes.add(context, "sw")
        assertEquals(1, SideloadedSubtypes.read(prefs).size)
    }

    @Test
    fun severalLanguagesAccumulateAndRemoveIndependently() {
        SideloadedSubtypes.add(context, "sw")
        SideloadedSubtypes.add(context, "zu")
        SideloadedSubtypes.add(context, "my")
        assertEquals(3, SideloadedSubtypes.read(prefs).size)
        SideloadedSubtypes.remove(context, "zu")
        assertEquals(listOf("sw:qwerty", "my:myanmar"), SideloadedSubtypes.read(prefs))
    }

    @Test
    fun removingSomethingAbsentIsHarmless() {
        SideloadedSubtypes.add(context, "sw")
        SideloadedSubtypes.remove(context, "xx")
        assertEquals("sw:qwerty", SideloadedSubtypes.readRaw(prefs))
    }

    @Test
    fun aLanguageWhoseNameIsAPrefixOfAnotherIsNotRemovedByAccident() {
        // "s" vs "sw": the entry match is on "<lang>:" precisely so that removing one language
        // cannot take a longer-named one with it.
        SideloadedSubtypes.add(context, "sw")
        SideloadedSubtypes.add(context, "st")
        SideloadedSubtypes.remove(context, "s")
        assertEquals(2, SideloadedSubtypes.read(prefs).size)
    }

    @Test
    fun anEmptyOrMissingPreferenceReadsAsNoEntries() {
        assertEquals(0, SideloadedSubtypes.read(prefs).size)
        prefs.edit().putString("sideloaded_subtypes", "").commit()
        assertEquals(0, SideloadedSubtypes.read(prefs).size)
        assertEquals(0, SubtypeFactory.createSubtypesFromPref(SideloadedSubtypes.readRaw(prefs)).size)
    }

    // ── the non-Latin layouts survive the factory's filter ────────────────────────────────────

    @Test
    fun aRuntimeSubtypeOnANonLatinLayoutIsKeptAndOneOnAnUnknownLayoutIsDropped() {
        val kept = SubtypeFactory.createSubtypesFromPref("as:bengali;ne:nepali_traditional;ur:farsi;xx:nonsense_layout")
        assertEquals(listOf("as", "ne", "ur"), kept.map { it.locale })
        for (s in kept) assertEquals(R.string.subtype_generic, s.nameResId)
        assertTrue(SideloadedSubtypes.isSideloadLayout("bengali"))
        assertFalse(SideloadedSubtypes.isSideloadLayout("nonsense_layout"))
        assertFalse(SideloadedSubtypes.isSideloadLayout(null))
    }
}
