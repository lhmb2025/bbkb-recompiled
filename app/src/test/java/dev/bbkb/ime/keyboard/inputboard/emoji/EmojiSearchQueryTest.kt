package dev.bbkb.ime.keyboard.inputboard.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emoji board's query owner: pure state, no Android. */
class EmojiSearchQueryTest {

    @Test
    fun startsEmptyWithNoOrigin() {
        val q = EmojiSearchQuery()
        assertEquals(EmojiSearchQuery.Origin.NONE, q.origin())
        assertTrue(q.isEmpty)
        assertFalse(q.isSearchable)
        assertFalse(q.isTypedInBoard)
        assertFalse(q.isFromEditor)
        assertEquals(-1, q.categoryBeforeSearch())
    }

    @Test
    fun theFirstTypedCharacterStartsTheQueryAndRemembersTheCategory() {
        val q = EmojiSearchQuery()
        assertTrue(q.append("h", 3))
        assertTrue(q.isTypedInBoard)
        assertEquals("h", q.text())
        assertEquals(3, q.categoryBeforeSearch())
        assertFalse("one character is not searchable", q.isSearchable)

        assertFalse("a second character does not start the query again", q.append("e", 5))
        assertEquals("he", q.text())
        assertEquals("the category is the one current at the first character", 3, q.categoryBeforeSearch())
        assertTrue(q.isSearchable)
    }

    @Test
    fun spaceIsACharacterOfTheQuery() {
        val q = EmojiSearchQuery()
        q.append("red", 1)
        q.append(" ", 1)
        q.append("heart", 1)
        assertEquals("red heart", q.text())
    }

    @Test
    fun backspaceRemovesOneCodePoint() {
        val q = EmojiSearchQuery()
        q.append("a😀", 1) // a + 😀 (a surrogate pair)
        assertTrue(q.backspace())
        assertEquals("a", q.text())
        assertTrue(q.backspace())
        assertTrue(q.isEmpty)
        assertTrue("an empty typed query is still a typed query until cleared", q.isTypedInBoard)
        assertFalse("nothing left to remove: the key passes through", q.backspace())
    }

    @Test
    fun backspaceNeverEditsAQueryFromTheEditor() {
        val q = EmojiSearchQuery()
        q.setFromEditor("smile")
        assertFalse(q.backspace())
        assertEquals("smile", q.text())
    }

    @Test
    fun aQueryFromTheEditorIsSearchableFromTwoCharacters() {
        val q = EmojiSearchQuery()
        q.setFromEditor(null)
        assertTrue(q.isFromEditor)
        assertTrue(q.isEmpty)
        assertFalse(q.isSearchable)
        q.setFromEditor("a")
        assertFalse(q.isSearchable)
        q.setFromEditor("ab")
        assertTrue(q.isSearchable)
        assertEquals(-1, q.categoryBeforeSearch())
    }

    @Test
    fun typingOverAQueryFromTheEditorStartsATypedQuery() {
        val q = EmojiSearchQuery()
        q.setFromEditor("smile")
        assertTrue(q.append("h", 2))
        assertTrue(q.isTypedInBoard)
        assertEquals("the editor's word is not kept", "h", q.text())
        assertEquals(2, q.categoryBeforeSearch())
    }

    @Test
    fun clearEndsTheQueryAndReturnsTheCategoryToRestore() {
        val q = EmojiSearchQuery()
        q.append("he", 4)
        assertEquals(4, q.clear())
        assertEquals(EmojiSearchQuery.Origin.NONE, q.origin())
        assertTrue(q.isEmpty)
        assertEquals(-1, q.categoryBeforeSearch())
        assertEquals("nothing to restore after a query from the editor", -1, run { q.setFromEditor("x"); q.clear() })
    }

    @Test
    fun everyChangeBumpsTheGeneration() {
        val q = EmojiSearchQuery()
        val g0 = q.generation()
        q.append("a", 1)
        val g1 = q.generation()
        q.backspace()
        val g2 = q.generation()
        q.setFromEditor("ab")
        val g3 = q.generation()
        q.clear()
        val g4 = q.generation()
        assertTrue(g0 < g1 && g1 < g2 && g2 < g3 && g3 < g4)
    }
}
