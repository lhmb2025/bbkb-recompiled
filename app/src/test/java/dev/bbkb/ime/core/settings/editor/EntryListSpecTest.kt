package dev.bbkb.ime.core.settings.editor

import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.screens.shortcutsSpec
import dev.bbkb.ime.core.settings.screens.userDictionarySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording the two dictionary editors show, pinned to what the hand-written screens said.
 *
 * [EntryListSpec] derives most of its strings from a noun rather than listing them, which is right
 * — in both editors they are the same sentence about a different thing — but it means one wrong
 * character in `noun` would silently reword six snackbars at once. Several of those strings appear
 * only after an add, edit or delete, i.e. only after a dialog, and no dialog in this project can be
 * driven from a unit test, so `DictionaryEditorScreensTest` cannot reach them. This file reaches
 * them the other way, through the spec.
 *
 * Every expected string below is user-visible. Most were read off the two screens as they were
 * before Wave 3 collapsed them; the 2026-11 Learned-words alignment changed the ones it had to (a
 * singular count, sentence case, a placeholder without dots, "Deleted 1 shortcut" with Undo).
 */
class EntryListSpecTest {

    private val dictionarySpec = userDictionarySpec("User dictionary")
    @Suppress("PrivatePropertyName")
    private val ShortcutsSpec = shortcutsSpec("Text shortcuts")

    private fun entry(word: String, shortcut: String?, locale: String = "en_US") =
        DictionaryEntry(word = word, shortcut = shortcut, locale = locale)

    // ------------------------------------------------------------------ shortcuts

    @Test
    fun theShortcutsEditorWordsItselfAsItAlwaysDid() {
        assertEquals("0 shortcuts", ShortcutsSpec.counter(0))
        assertEquals("one is singular", "1 shortcut", ShortcutsSpec.counter(1))
        assertEquals("3 shortcuts", ShortcutsSpec.counter(3))
        assertEquals("1 of 3 shortcuts", ShortcutsSpec.searchCounter(1, 3))
        assertEquals("Search shortcuts", ShortcutsSpec.searchHint)
        assertEquals("Add shortcut", ShortcutsSpec.addDescription)
        assertEquals("Edit shortcut", ShortcutsSpec.editDescription)
        assertEquals("Shortcut added", ShortcutsSpec.added)
        assertEquals("Failed to add shortcut", ShortcutsSpec.addFailed)
        assertEquals("Shortcut updated", ShortcutsSpec.updated)
        assertEquals("Failed to update shortcut", ShortcutsSpec.updateFailed)
        assertEquals("Deleted 1 shortcut", ShortcutsSpec.deleted(1))
        assertEquals("Deleted 2 shortcuts", ShortcutsSpec.deleted(2))
        assertEquals("Failed to delete shortcut", ShortcutsSpec.deleteFailed)
        assertEquals("Couldn't bring back every shortcut", ShortcutsSpec.restoreFailed)
        assertEquals("No text shortcuts", ShortcutsSpec.emptyTitle)
        assertEquals(
            "Tap Add shortcut to create shortcuts that expand to phrases",
            ShortcutsSpec.emptyHint
        )
        assertEquals(
            "Failed to load shortcuts: store unavailable",
            ShortcutsSpec.loadFailure("store unavailable")
        )
        assertEquals(
            "an exception with no message still produced this wording",
            "Failed to load shortcuts: null",
            ShortcutsSpec.loadFailure(null)
        )
    }

    @Test
    fun theShortcutsEditorRefusesAShortcutContainingASpace() {
        assertEquals(
            "Shortcut cannot contain spaces",
            ShortcutsSpec.validate(entry("Best regards", "my sig"))
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
        assertEquals("0 words", dictionarySpec.counter(0))
        assertEquals("1 word", dictionarySpec.counter(1))
        assertEquals("3 words", dictionarySpec.counter(3))
        assertEquals("2 of 6 words", dictionarySpec.searchCounter(2, 6))
        assertEquals("Search words", dictionarySpec.searchHint)
        assertEquals("Add word", dictionarySpec.addDescription)
        assertEquals("Edit word", dictionarySpec.editDescription)
        assertEquals("Word added", dictionarySpec.added)
        assertEquals("Failed to add word", dictionarySpec.addFailed)
        assertEquals("Word updated", dictionarySpec.updated)
        assertEquals("Failed to update word", dictionarySpec.updateFailed)
        assertEquals("Deleted 1 word", dictionarySpec.deleted(1))
        assertEquals("Deleted 4 words", dictionarySpec.deleted(4))
        assertEquals("Failed to delete word", dictionarySpec.deleteFailed)
        assertEquals("No personal words", dictionarySpec.emptyTitle)
        assertEquals("Tap Add word to add custom words to your dictionary", dictionarySpec.emptyHint)
        assertEquals(
            "Failed to load dictionary: store unavailable",
            dictionarySpec.loadFailure("store unavailable")
        )
    }

    @Test
    fun theDictionaryEditorNeverRefusesAWord() {
        // The hand-written screen validated nothing; only the shortcuts editor did.
        assertNull(dictionarySpec.validate(entry("Landrum", null)))
        assertNull(dictionarySpec.validate(entry("two words", null)))
    }

    @Test
    fun theDictionaryEditorOwnsPersonalWordsAndKeysThemByWordAndLocale() {
        assertTrue(dictionarySpec.belongsHere(entry("Landrum", null)))
        assertTrue(
            "the stored form of a personal word is a substitution of the word to itself",
            dictionarySpec.belongsHere(entry("Landrum", "Landrum"))
        )
        assertFalse(dictionarySpec.belongsHere(entry("Best regards", "sig")))

        assertEquals("Landrum_en_US", dictionarySpec.identity(entry("Landrum", null)))
        assertEquals("Landrum", dictionarySpec.label(entry("Landrum", null)))
        assertEquals("landrum", dictionarySpec.sortKey(entry("Landrum", null)))
    }

    @Test
    fun theDictionarySearchLooksAtTheWordOnly() {
        val e = entry("Landrum", null)
        assertTrue(dictionarySpec.matches(e, "LAND"))
        assertFalse(dictionarySpec.matches(e, "zzz"))
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
                ShortcutsSpec.belongsHere(it) != dictionarySpec.belongsHere(it)
            )
        }
    }
}
