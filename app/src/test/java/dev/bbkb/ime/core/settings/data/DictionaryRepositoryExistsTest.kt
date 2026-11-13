package dev.bbkb.ime.core.settings.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.editor.failureMessage
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyAlreadyDefinedException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil
import dev.bbkb.ime.personaldictionary.attachLoadedForTest
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TreeMap

/**
 * An add or edit onto a name another entry already has is refused with a message naming what is
 * in the way, not a generic failure. Words and shortcuts share names: a word is stored as a
 * shortcut for itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DictionaryRepositoryExistsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val held = TreeMap<String, WordSubstitution>(String.CASE_INSENSITIVE_ORDER)
    private lateinit var repository: DictionaryRepository

    @Before
    fun setUp() {
        held["zorbix"] = WordSubstitution.createUserSubstitution("all", "zorbix", "zorbix", true)
        held["bb"] = WordSubstitution.createUserSubstitution("all", "bb", "BlackBerry", false)
        val pdu = mock(PersonalDictionaryUtil::class.java)
        `when`(pdu.getWordSubstitutions()).thenReturn(held)
        // What the real dictionary does with a name it already holds.
        doAnswer { invocation ->
            val ws = invocation.getArgument<WordSubstitution>(0)
            if (held.containsKey(ws.key)) throw KeyAlreadyDefinedException("${ws.key} already defined")
            null
        }.`when`(pdu).add(any(WordSubstitution::class.java) ?: held.getValue("bb"))
        DictionaryManager.getInstance().attachLoadedForTest(pdu)
        repository = DictionaryRepository(context)
    }

    @After
    fun tearDown() {
        DictionaryManager.shutdownInstance()
    }

    private fun Result<Unit>.inTheWay() = exceptionOrNull() as? EntryExistsException

    @Test
    fun aWordAlreadyInTheDictionary_isNamedAsAWord() = runBlocking {
        val result = repository.addEntry(DictionaryEntry(word = "ZORBIX", locale = ""))

        val e = result.inTheWay()
        assertTrue("not reported as already there: $result", e != null)
        assertEquals("zorbix", e!!.name)
        assertEquals(true, e.isWord)
        assertEquals("zorbix is already in the user dictionary", failureMessage(context, e, R.string.settings_dict_word_add_failed))
    }

    @Test
    fun aShortcutNameInTheWay_isNamedAsAShortcut_evenForAWord() = runBlocking {
        val asShortcut = repository.addEntry(DictionaryEntry(word = "Bye bye", shortcut = "BB", locale = ""))
        val asWord = repository.addEntry(DictionaryEntry(word = "bb", locale = ""))

        for (result in listOf(asShortcut, asWord)) {
            val e = result.inTheWay()
            assertTrue("not reported as already there: $result", e != null)
            assertEquals("bb", e!!.name)
            assertEquals(false, e.isWord)
            assertEquals("A shortcut named bb already exists", failureMessage(context, e, R.string.settings_shortcuts_add_failed))
        }
    }

    /** An edit onto another entry's name is refused the same way; one keeping its own name is not. */
    @Test
    fun anEditOntoAnotherName_isNamed_andOneKeepingItsOwnNameIsNot() = runBlocking {
        val other = repository.updateEntry(
            DictionaryEntry(word = "Bye bye", shortcut = "byb", locale = ""),
            DictionaryEntry(word = "Bye bye", shortcut = "Zorbix", locale = "")
        )
        assertEquals("zorbix", other.inTheWay()?.name)

        val own = repository.updateEntry(
            DictionaryEntry(word = "zorbix", locale = ""),
            DictionaryEntry(word = "Zorbix", locale = "")
        )
        assertEquals(null, own.inTheWay())
    }

    /** Anything else still says "Failed to ..." */
    @Test
    fun anyOtherFailure_keepsTheFailedMessage() {
        assertEquals("Failed to add word", failureMessage(context, Exception("Failed to add entry"), R.string.settings_dict_word_add_failed))
        assertEquals("Failed to add shortcut", failureMessage(context, null, R.string.settings_shortcuts_add_failed))
    }
}
