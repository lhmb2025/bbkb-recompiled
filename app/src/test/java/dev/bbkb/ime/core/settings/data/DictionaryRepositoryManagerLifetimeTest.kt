package dev.bbkb.ime.core.settings.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil
import dev.bbkb.ime.personaldictionary.attachLoadedForTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A settings editor keeps one [DictionaryRepository] while open, but the keyboard service (same
 * process) replaces [DictionaryManager]'s singleton on start and shuts it down on stop, often while
 * this screen's add form is up. The repository must follow the current manager, not keep its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DictionaryRepositoryManagerLifetimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DictionaryManager.shutdownInstance()
    }

    /** PersonalDictionaryUtil.add calls on [pdu] (Mockito's any() is null, which Kotlin refuses here). */
    private fun adds(pdu: PersonalDictionaryUtil) =
        mockingDetails(pdu).invocations.count { it.method.name == "add" }

    private fun loadedManager(pdu: PersonalDictionaryUtil): DictionaryManager =
        DictionaryManager.getInstance().also { it.attachLoadedForTest(pdu) }

    /** The keyboard starts while the screen is open: the add goes to the keyboard's manager. */
    @Test
    fun anAddAfterTheKeyboardReplacedTheManager_reachesTheNewManager() = runBlocking {
        val before = mock(PersonalDictionaryUtil::class.java)
        loadedManager(before)
        val repository = DictionaryRepository(context)

        // What BlackBerryIME.onCreate does, then its own load completing.
        DictionaryManager.shutdownInstance()
        val after = mock(PersonalDictionaryUtil::class.java)
        loadedManager(after)

        val shortcut = repository.addEntry(DictionaryEntry(word = "simple phrase", shortcut = "tst9", locale = ""))
        val word = repository.addEntry(DictionaryEntry(word = "zorbix", shortcut = null, locale = ""))

        assertTrue("shortcut add failed: $shortcut", shortcut.isSuccess)
        assertTrue("word add failed: $word", word.isSuccess)
        assertEquals(2, adds(after))
        assertEquals(0, adds(before))
    }

    /** The keyboard stops while the screen is open: the repository brings the dictionary up. */
    @Test
    fun anAddAfterTheKeyboardShutTheManagerDown_loadsTheDictionaryFirst() = runBlocking {
        loadedManager(mock(PersonalDictionaryUtil::class.java))
        val after = mock(PersonalDictionaryUtil::class.java)
        var loads = 0
        val repository = DictionaryRepository(context) { _, manager ->
            // Stands in for initialiseOrSwitchLanguages: the real load needs the engine.
            loads++
            manager.attachLoadedForTest(after)
        }

        // What BlackBerryIME.onDestroy does; nothing loads the manager that replaces it.
        DictionaryManager.getInstance().shutdown()

        val result = repository.addEntry(DictionaryEntry(word = "zorbix", shortcut = null, locale = ""))

        assertTrue("add failed: $result", result.isSuccess)
        assertEquals("loads of the replacement manager", 1, loads)
        assertEquals(1, adds(after))
    }

    /** ...and the list read after the keyboard stopped is the dictionary's, not an empty one. */
    @Test
    fun aListAfterTheKeyboardShutTheManagerDown_loadsTheDictionaryFirst() = runBlocking {
        loadedManager(mock(PersonalDictionaryUtil::class.java))
        val after = mock(PersonalDictionaryUtil::class.java)
        val word = dev.bbkb.ime.personaldictionary.model.WordSubstitution.createUserSubstitution("all", "zorbix", "zorbix", true)
        org.mockito.Mockito.`when`(after.getWordSubstitutions()).thenReturn(mapOf("zorbix" to word))
        org.mockito.Mockito.`when`(after.personalDictionary).thenReturn(emptyMap())
        val repository = DictionaryRepository(context) { _, manager -> manager.attachLoadedForTest(after) }

        DictionaryManager.getInstance().shutdown()

        assertEquals(listOf("zorbix"), repository.getAllEntries("").map { it.word })
    }
}
