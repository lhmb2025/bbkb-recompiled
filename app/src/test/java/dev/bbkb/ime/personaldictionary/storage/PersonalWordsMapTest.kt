package dev.bbkb.ime.personaldictionary.storage

import dev.bbkb.ime.personaldictionary.model.PersonalWord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock-free word lookup follows every change to the map. */
class PersonalWordsMapTest {

    private fun word(text: String) = PersonalWord(text, "en_US", false)

    @Test
    fun containsWord_followsPutRemoveAndClear() {
        val map = PersonalWordsMap("personal_words.json")
        assertFalse(map.containsWord("frobnicate"))

        map.putIfAbsent(word("frobnicate"))
        map.put(word("Zarquon"))
        assertTrue(map.containsWord("frobnicate"))
        assertTrue(map.containsWord("Zarquon"))
        assertFalse("exact spelling only", map.containsWord("zarquon"))

        map.removeIfPresent(word("frobnicate"))
        assertFalse(map.containsWord("frobnicate"))

        map.clear()
        assertFalse(map.containsWord("Zarquon"))
    }
}
