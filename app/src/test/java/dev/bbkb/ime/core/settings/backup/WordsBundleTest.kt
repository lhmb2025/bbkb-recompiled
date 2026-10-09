package dev.bbkb.ime.core.settings.backup

import dev.bbkb.ime.core.settings.data.DictionaryEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The words document: the round trip, and the shapes it refuses. Robolectric for Android's `org.json`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WordsBundleTest {

    private fun words() = WordsBundle.Words(
        dictionary = listOf(
            DictionaryEntry(word = "BBKB", locale = "", fixedCase = true),
            DictionaryEntry(word = "on my way", shortcut = "omw", locale = "en_US"),
            // A shortcut equal to the word is how the store spells "no shortcut".
            DictionaryEntry(word = "athena", shortcut = "athena", locale = "en_US"),
        ),
        learnedWords = listOf("keypad", "Athena", "swipe"),
    )

    @Test
    fun roundTripKeepsEveryEntryAndSortsForStableOutput() {
        val json = WordsBundle.serialize(words(), "5.0.0-test", 1, 1_700_000_000_000L)
        val root = JSONObject(json)
        assertEquals(WordsBundle.FORMAT, root.getString("format"))
        assertEquals("2023-11-14T22:13:20Z", root.getString("created"))

        val parsed = WordsBundle.parse(json).getOrThrow()
        val dictionary = parsed.dictionary!!
        assertEquals(3, dictionary.size)
        val bbkb = dictionary.first { it.word == "BBKB" }
        assertTrue(bbkb.fixedCase)
        assertNull(bbkb.shortcut)
        assertEquals("omw", dictionary.first { it.word == "on my way" }.shortcut)
        // The word-equals-shortcut entry comes back as a plain word.
        assertNull(dictionary.first { it.word == "athena" }.shortcut)
        assertEquals(listOf("Athena", "keypad", "swipe"), parsed.learnedWords)
        // Byte-stable: a second export of the same words is the same document.
        assertEquals(json, WordsBundle.serialize(words(), "5.0.0-test", 1, 1_700_000_000_000L))
    }

    @Test
    fun aSectionLeftOutStaysNull() {
        val json = WordsBundle.serialize(WordsBundle.Words(learnedWords = listOf("one")), "t", 1)
        val parsed = WordsBundle.parse(json).getOrThrow()
        assertNull(parsed.dictionary)
        assertEquals(listOf("one"), parsed.learnedWords)
    }

    @Test
    fun aDocumentWithNoWordsIsRefused() {
        val failure = WordsBundle.parse("""{"format":"bbkb-words","version":1}""").exceptionOrNull()
        assertTrue(failure is WordsBundle.InvalidWordsException)
        assertTrue(failure!!.message!!.contains("no words"))
    }

    @Test
    fun theWrongFormatOrAFutureVersionIsRefused() {
        assertTrue(WordsBundle.parse("""{"format":"bbkb-settings","version":1,"settings":{}}""").isFailure)
        assertTrue(WordsBundle.parse("""{"format":"bbkb-words","version":2,"learnedWords":["a"]}""").exceptionOrNull()!!.message!!.contains("version"))
        assertTrue(WordsBundle.parse("not json").exceptionOrNull()!!.message!!.contains("not JSON"))
    }

    @Test
    fun anUnknownFieldIsRefused() {
        val failure = WordsBundle.parse("""{"format":"bbkb-words","version":1,"learnedWords":["a"],"counts":{}}""").exceptionOrNull()
        assertTrue(failure!!.message!!.contains("counts"))
    }

    @Test
    fun aDictionaryThatRepeatsAKeyIsRefused() {
        val json = """{"format":"bbkb-words","version":1,"dictionary":[
            {"word":"one","locale":""},{"word":"uno","shortcut":"one","locale":""}]}"""
        val failure = WordsBundle.parse(json).exceptionOrNull()
        assertTrue(failure!!.message!!.contains("repeats"))
    }

    @Test
    fun theSameKeyInTwoLocalesIsTwoEntries() {
        val json = """{"format":"bbkb-words","version":1,"dictionary":[
            {"word":"one","locale":""},{"word":"one","locale":"en_US"}]}"""
        assertEquals(2, WordsBundle.parse(json).getOrThrow().dictionary!!.size)
    }

    @Test
    fun badEntriesAreRefusedWithTheFieldNamed() {
        fun message(entry: String) = WordsBundle.parse("""{"format":"bbkb-words","version":1,"dictionary":[$entry]}""").exceptionOrNull()!!.message!!
        assertTrue(message("""{"word":"","locale":""}""").contains("dictionary[0].word"))
        assertTrue(message("""{"word":"a\u0007b","locale":""}""").contains("control character"))
        assertTrue(message("""{"word":"ok","locale":"en US"}""").contains("locale"))
        assertTrue(message("""{"word":"ok","locale":"","fixedCase":"yes"}""").contains("fixedCase"))
        val long = "x".repeat(WordsBundle.MAX_WORD_LENGTH + 1)
        assertTrue(message("""{"word":"$long","locale":""}""").contains("longer than"))
    }

    @Test
    fun aLearnedWordWithWhitespaceIsRefusedAndDuplicatesCollapse() {
        assertTrue(WordsBundle.parse("""{"format":"bbkb-words","version":1,"learnedWords":["two words"]}""").exceptionOrNull()!!.message!!.contains("whitespace"))
        assertEquals(listOf("a", "b"), WordsBundle.parse("""{"format":"bbkb-words","version":1,"learnedWords":["a","b","a"]}""").getOrThrow().learnedWords)
    }
}
