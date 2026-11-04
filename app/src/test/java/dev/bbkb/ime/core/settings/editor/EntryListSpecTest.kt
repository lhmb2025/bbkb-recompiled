package dev.bbkb.ime.core.settings.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.screens.ShortcutsSpec
import dev.bbkb.ime.core.settings.screens.UserDictionarySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wording the two dictionary editors show, in English, read through their specs.
 *
 * Several of these strings appear only after an add, edit or delete, i.e. only after a dialog, and
 * no dialog in this project can be driven from a unit test, so `DictionaryEditorScreensTest` cannot
 * reach them. This file reaches them the other way, through the resources the spec names.
 *
 * Every expected string below is user-visible. Most were read off the two screens as they were
 * before Wave 3 collapsed them; the 2026-11 Learned-words alignment changed the ones it had to (a
 * singular count, sentence case, a placeholder without dots, "Deleted 1 shortcut" with Undo), and
 * the 2026-11 translation pass turned the noun-built English into one resource per sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryListSpecTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun count(id: Int, n: Int, vararg args: Any) =
        context.resources.getQuantityString(id, n, *(if (args.isEmpty()) arrayOf<Any>(n) else args))

    private fun entry(word: String, shortcut: String?, locale: String = "en_US") =
        DictionaryEntry(word = word, shortcut = shortcut, locale = locale)

    // ------------------------------------------------------------------ shortcuts

    @Test
    fun theShortcutsEditorWordsItselfAsItAlwaysDid() {
        val spec = ShortcutsSpec
        assertEquals("Text shortcuts", text(spec.title))
        assertEquals("0 shortcuts", count(spec.count, 0))
        assertEquals("one is singular", "1 shortcut", count(spec.count, 1))
        assertEquals("3 shortcuts", count(spec.count, 3))
        assertEquals("1 of 3 shortcuts", count(spec.searchCount, 3, 1, 3))
        assertEquals("Search shortcuts", text(spec.searchHint))
        assertEquals("Add shortcut", text(spec.add))
        assertEquals("Edit shortcut", text(spec.edit))
        assertEquals("Shortcut added", text(spec.added))
        assertEquals("Failed to add shortcut", text(spec.addFailed))
        assertEquals("Shortcut updated", text(spec.updated))
        assertEquals("Failed to update shortcut", text(spec.updateFailed))
        assertEquals("Deleted 1 shortcut", count(spec.deleted, 1))
        assertEquals("Deleted 2 shortcuts", count(spec.deleted, 2))
        assertEquals("Failed to delete shortcut", text(spec.deleteFailed))
        assertEquals("Couldn't bring back every shortcut", text(spec.restoreFailed))
        assertEquals("No text shortcuts", text(spec.emptyTitle))
        assertEquals("Tap Add shortcut to create shortcuts that expand to phrases", text(spec.emptyHint))
        assertEquals(
            "Failed to load shortcuts: store unavailable",
            text(spec.loadFailed, "store unavailable")
        )
        assertEquals(
            "an exception with no message still produced this wording",
            "Failed to load shortcuts: null",
            text(spec.loadFailed, "null")
        )
    }

    @Test
    fun theShortcutsEditorRefusesAShortcutContainingASpace() {
        assertEquals(
            "Shortcut cannot contain spaces",
            ShortcutsSpec.validate(entry("Best regards", "my sig"))?.let { text(it) }
        )
        assertNull(ShortcutsSpec.validate(entry("Best regards", "sig")))
        assertNull("a personal word has no shortcut to check", ShortcutsSpec.validate(entry("Landrum", null)))
    }

    @Test
    fun theShortcutsEditorOwnsSubstitutionsAndKeysThemByShortcutAndLocale() {
        assertTrue(ShortcutsSpec.belongsHere(entry("Best regards", "sig")))
        assertFalse(ShortcutsSpec.belongsHere(entry("Landrum", null)))
        assertFalse(ShortcutsSpec.belongsHere(entry("Landrum", "Landrum")))

        assertEquals("sig_en_US", ShortcutsSpec.identity(entry("Best regards", "sig")))
        assertEquals("a sheet is headed by the shortcut", "sig", ShortcutsSpec.label(entry("Best regards", "sig")))
        assertEquals("sig", ShortcutsSpec.sortKey(entry("Best regards", "sig")))
        assertEquals(
            "sorting is case-insensitive",
            "sig", ShortcutsSpec.sortKey(entry("Best regards", "SIG"))
        )
    }

    @Test
    fun theShortcutsSearchLooksAtBothSides() {
        val e = entry("Best regards", "sig")
        assertTrue(ShortcutsSpec.matches(e, "SIG"))
        assertTrue(ShortcutsSpec.matches(e, "regards"))
        assertFalse(ShortcutsSpec.matches(e, "zzz"))
    }

    // ----------------------------------------------------------------- dictionary

    @Test
    fun theDictionaryEditorWordsItselfAsItAlwaysDid() {
        val spec = UserDictionarySpec
        assertEquals("0 words", count(spec.count, 0))
        assertEquals("1 word", count(spec.count, 1))
        assertEquals("3 words", count(spec.count, 3))
        assertEquals("2 of 6 words", count(spec.searchCount, 6, 2, 6))
        assertEquals("Search words", text(spec.searchHint))
        assertEquals("Add word", text(spec.add))
        assertEquals("Edit word", text(spec.edit))
        assertEquals("Word added", text(spec.added))
        assertEquals("Failed to add word", text(spec.addFailed))
        assertEquals("Word updated", text(spec.updated))
        assertEquals("Failed to update word", text(spec.updateFailed))
        assertEquals("Deleted 1 word", count(spec.deleted, 1))
        assertEquals("Deleted 4 words", count(spec.deleted, 4))
        assertEquals("Failed to delete word", text(spec.deleteFailed))
        assertEquals("No personal words", text(spec.emptyTitle))
        assertEquals("Tap Add word to add custom words to your dictionary", text(spec.emptyHint))
        assertEquals(
            "Failed to load dictionary: store unavailable",
            text(spec.loadFailed, "store unavailable")
        )
    }

    @Test
    fun theDictionaryEditorNeverRefusesAWord() {
        // The hand-written screen validated nothing; only the shortcuts editor did.
        assertNull(UserDictionarySpec.validate(entry("Landrum", null)))
        assertNull(UserDictionarySpec.validate(entry("two words", null)))
    }

    @Test
    fun theDictionaryEditorOwnsPersonalWordsAndKeysThemByWordAndLocale() {
        assertTrue(UserDictionarySpec.belongsHere(entry("Landrum", null)))
        assertTrue(
            "the stored form of a personal word is a substitution of the word to itself",
            UserDictionarySpec.belongsHere(entry("Landrum", "Landrum"))
        )
        assertFalse(UserDictionarySpec.belongsHere(entry("Best regards", "sig")))

        assertEquals("Landrum_en_US", UserDictionarySpec.identity(entry("Landrum", null)))
        assertEquals("Landrum", UserDictionarySpec.label(entry("Landrum", null)))
        assertEquals("landrum", UserDictionarySpec.sortKey(entry("Landrum", null)))
    }

    @Test
    fun theDictionarySearchLooksAtTheWordOnly() {
        val e = entry("Landrum", null)
        assertTrue(UserDictionarySpec.matches(e, "LAND"))
        assertFalse(UserDictionarySpec.matches(e, "zzz"))
    }

    // --------------------------------------------------------------- both editors

    @Test
    fun theTwoEditorsPartitionTheStoreBetweenThem() {
        // Every entry belongs to exactly one of them, which is what lets both read the same
        // repository and show disjoint lists.
        listOf(
            entry("Best regards", "sig"),
            entry("Landrum", null),
            entry("Landrum", "Landrum"),
            entry("iPhone", ""),
        ).forEach {
            assertTrue(
                "${it.word}/${it.shortcut} belongs to neither or both editors",
                ShortcutsSpec.belongsHere(it) != UserDictionarySpec.belongsHere(it)
            )
        }
    }

    /** The settings title and the Learning screen's row are one string, so they agree everywhere. */
    @Test
    fun theTitlesAreTheLearningScreensRows() {
        assertEquals(R.string.settings_dict_text_shortcuts_title, ShortcutsSpec.title)
        assertEquals(R.string.settings_user_dictionary_screen_title, UserDictionarySpec.title)
    }
}
