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
 * The settings editors keep one [DictionaryRepository] for as long as the screen is open, and the
 * keyboard service, which runs in the same process, replaces [DictionaryManager]'s singleton when
 * it starts (`BlackBerryIME.initializeSingletons` calls `DictionaryManager.shutdownInstance()`)
 * and shuts it down when it stops (`onDestroy`). After an install or an update the service is
 * first created by the first keyboard shown, which is typically the add form on this screen.
 *
 * The repository used to keep the manager it was built with, so from then on every add on the
 * screen went to a shut-down manager, which answered "not loaded", and the screen said "Failed to
 * add word" / "Failed to add shortcut" until it was left (emulator, 2026-11-05).
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
