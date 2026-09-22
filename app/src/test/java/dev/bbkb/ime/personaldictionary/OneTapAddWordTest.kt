package dev.bbkb.ime.personaldictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.util.Locale

/** The plus on the add-to-dictionary highlight adds the word the way the settings editor does. */
class OneTapAddWordTest {

    private val manager: DictionaryManager = mock(DictionaryManager::class.java)

    @Test
    fun aWordIsAddedAsAPlainPersonalWordInTheDictionaryLocale_andSaved() {
        `when`(manager.addWordSubstitution("asdfgh", "asdfgh", "en_US", false)).thenReturn(true)
        assertTrue(OneTapAddWord.add(manager, "asdfgh", Locale.US))
        verify(manager).addWordSubstitution("asdfgh", "asdfgh", "en_US", false)
        verify(manager).save()
    }

    @Test
    fun aRefusedAdd_isReportedAndNotSaved() {
        `when`(manager.addWordSubstitution("asdfgh", "asdfgh", "en_US", false)).thenReturn(false)
        assertFalse(OneTapAddWord.add(manager, "asdfgh", Locale.US))
        verify(manager, never()).save()
    }

    @Test
    fun noLocale_addsToAllLanguages() {
        `when`(manager.addWordSubstitution("asdfgh", "asdfgh", PersonalDictionaryConstants.LOCALE_ALL, false)).thenReturn(true)
        assertTrue(OneTapAddWord.add(manager, "asdfgh", null))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed_andAnEmptyWordDoesNothing() {
        `when`(manager.addWordSubstitution("word", "word", "en_US", false)).thenReturn(true)
        assertTrue(OneTapAddWord.add(manager, " word ", Locale.US))
        assertFalse(OneTapAddWord.add(manager, "   ", Locale.US))
        verify(manager, never()).addWordSubstitution("   ", "   ", "en_US", false)
    }
}
