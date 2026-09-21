package dev.bbkb.ime.core.settings.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHARACTERISATION TEST — the rule that decides which editor an entry belongs to.
 *
 * `TextShortcutsScreen` lists the entries for which `isSubstitution` is true and
 * `UserDictionaryScreen` lists the ones for which `isPersonalWord` is true; both read the same
 * `DictionaryRepository`, which returns every entry for a locale and lets the screens sort it out.
 * So this single derived property is what keeps a personal word out of the shortcuts list and a
 * shortcut out of the dictionary — and it had no test.
 *
 * `displayKey` is the second half of the same rule: it is what `DictionaryRepository` sorts on, and
 * therefore the order the user sees.
 */
class DictionaryEntryTest {

    private fun entry(word: String, shortcut: String?) =
        DictionaryEntry(word = word, shortcut = shortcut, locale = "en_US")

    @Test
    fun anEntryWithNoShortcutIsAPersonalWord() {
        val e = entry("Landrum", null)

        assertEquals(DictionaryEntry.EntryType.WORD, e.type)
        assertTrue(e.isPersonalWord)
        assertFalse(e.isSubstitution)
    }

    @Test
    fun anEntryWhoseShortcutEqualsItsWordIsAPersonalWord() {
        // This is the shape DictionaryRepository writes for a personal word: it stores it as a
        // substitution of the word to itself, so "shortcut == word" is how it reads back.
        val e = entry("Landrum", "Landrum")

        assertEquals(DictionaryEntry.EntryType.WORD, e.type)
        assertTrue(e.isPersonalWord)
        assertFalse(e.isSubstitution)
    }

    @Test
    fun anEntryWhoseShortcutDiffersFromItsWordIsASubstitution() {
        val e = entry("Best regards,%n%D", "sig")

        assertEquals(DictionaryEntry.EntryType.SUBSTITUTION, e.type)
        assertTrue(e.isSubstitution)
        assertFalse(e.isPersonalWord)
    }

    @Test
    fun theComparisonOfShortcutToWordIsCaseSensitive() {
        // "Sig" -> "sig" is a substitution, not a personal word, so it belongs to the shortcuts
        // editor. A case-insensitive comparison here would hide it from both screens' filters.
        val e = entry("sig", "Sig")

        assertTrue(e.isSubstitution)
    }

    @Test
    fun anEmptyShortcutIsASubstitutionOfTheEmptyString() {
        // Not reachable from the UI (Save is disabled on a blank shortcut) but it is what the
        // model says, and the screens' filters depend on it.
        val e = entry("Landrum", "")

        assertTrue(e.isSubstitution)
    }

    @Test
    fun aPersonalWordSortsUnderItsWord() {
        assertEquals("Landrum", entry("Landrum", null).displayKey)
        assertEquals("Landrum", entry("Landrum", "Landrum").displayKey)
    }

    @Test
    fun aSubstitutionSortsUnderItsShortcut() {
        assertEquals("sig", entry("Best regards,%n%D", "sig").displayKey)
    }

    @Test
    fun fixedCaseDefaultsToOffAndIsCarriedOnTheEntry() {
        assertFalse(entry("Landrum", null).fixedCase)
        assertTrue(
            DictionaryEntry(word = "iPhone", shortcut = null, locale = "en_US", fixedCase = true)
                .fixedCase
        )
    }
}
