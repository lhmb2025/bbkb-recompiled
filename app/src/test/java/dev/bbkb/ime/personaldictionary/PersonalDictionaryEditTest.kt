package dev.bbkb.ime.personaldictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyAlreadyDefinedException
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import dev.bbkb.ime.personaldictionary.storage.DefaultWSLoader
import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What adds that are refused, edits and deletes leave behind: in the system user dictionary
 * (AUD, shared with every other app), in the stored dictionary, and in the prediction engine
 * (emulator, 2026-11-05):
 *
 * - a refused duplicate ("ZORBIX" beside "zorbix") was written to AUD anyway, and the AUD sync
 *   then swapped the two entries on every pass;
 * - an edit onto another entry's name was refused only after the edited entry had been deleted
 *   from AUD and from the stored map, so it was gone after a restart;
 * - an edit did not reach the engine, which kept correcting "quixotally" back to "quixotal";
 * - a deleted dictionary word stayed in the engine's learned words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.blackberry.nuanceshim"])
class PersonalDictionaryEditTest {

    private lateinit var context: Context
    private lateinit var provider: FakeUserDictionaryProvider
    private lateinit var pdu: PersonalDictionaryUtil
    private val sdk: NuanceSDK = mock(NuanceSDK::class.java)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FakeUserDictionaryProvider()
        provider.attachInfo(context, null)
        ShadowContentResolver.registerProviderInternal("user_dictionary", provider)
        DefaultWSLoader.invalidateCache()
        pduDir().deleteRecursively()
        pdu = PersonalDictionaryUtil.getInstance(context, context.filesDir.absolutePath, "substitution_macros", sdk)
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue("PDU load never completed", loaded.await(15, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        if (this::pdu.isInitialized) pdu.shutDown(true)
        AudSyncer.getInstance().shutDown(true)
        pduDir().deleteRecursively()
    }

    private fun pduDir() = File(context.filesDir, "dev.bbkb.ime.basl.pdu")

    private fun word(w: String) = WordSubstitution.createUserSubstitution("all", w, w, true)

    private fun shortcut(key: String, phrase: String) = WordSubstitution.createUserSubstitution("all", key, phrase, true)

    private fun audRows(key: String) = provider.snapshot().filter { it.shortcut.equals(key, ignoreCase = true) }

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("not refused")
        } catch (e: KeyAlreadyDefinedException) {
            // expected
        }
    }

    // ---- fault 2: a refused add writes nothing ----------------------------------------------

    @Test
    fun aRefusedDuplicateAdd_writesNothingToAud() {
        pdu.add(word("zorbix"))
        refused { pdu.add(word("ZORBIX")) }

        assertEquals(listOf("zorbix"), audRows("zorbix").map { it.word })
        assertEquals("zorbix", pdu.getWordSubstitutions()["zorbix"]?.word)
    }

    /**
     * A row beside one of ours that differs only in case, as a refused add used to leave: the
     * sync leaves our entry as it is, pass after pass, and leaves the row (AUD is shared).
     */
    @Test
    fun anAudRowBesideOurEntryInAnotherCase_doesNotReplaceItOnSync() {
        pdu.add(word("zorbix"))
        provider.seed(FakeUserDictionaryProvider.Row(null, "ZORBIX", "ZORBIX"))

        repeat(3) { pass ->
            val counts = AudSyncer.getInstance().doSyncDifferencesToBasl(context, pdu)
            assertEquals("pass $pass: $counts", 0, counts.added)
            assertEquals("pass $pass", "zorbix", pdu.getWordSubstitutions()["zorbix"]?.word)
        }
        assertEquals("the other row is left in AUD", 2, audRows("zorbix").size)
    }

    /** ...but a changed entry arriving from AUD (ours deleted there, the new one added) still comes in. */
    @Test
    fun anEntryChangedInAud_stillComesIn() {
        pdu.add(shortcut("zqk", "old phrase"))
        // Edited elsewhere (another app, or Android's own user dictionary settings): our row goes,
        // a row under the same name in another case with a new phrase arrives.
        context.contentResolver.delete(
            android.provider.UserDictionary.Words.CONTENT_URI,
            FakeUserDictionaryProvider.AudWrapperSelections.ALL_LOCALE_WITH_SHORTCUT, arrayOf("old phrase", "zqk")
        )
        assertEquals(0, audRows("zqk").size)
        provider.seed(FakeUserDictionaryProvider.Row(null, "Zqk", "new phrase"))

        AudSyncer.getInstance().doSyncDifferencesToBasl(context, pdu)

        assertEquals("new phrase", pdu.getWordSubstitutions()["zqk"]?.word)
    }

    // ---- fault 1 (edit half): a refused edit changes nothing -------------------------------

    @Test
    fun anEditOntoAnotherEntrysName_isRefusedAndTheEditedEntrySurvives() {
        pdu.add(word("alphaqo"))
        pdu.add(word("betaqo"))

        refused { pdu.update(word("alphaqo"), word("BETAQO")) }

        assertEquals("still in AUD", 1, audRows("alphaqo").size)
        assertTrue("still stored", pdu.allUserAndEnabledDefaultWs.values.any { it.containsKey("alphaqo") })
        assertEquals("betaqo", pdu.getWordSubstitutions()["betaqo"]?.word)
    }

    // ---- fault 3: an edit reaches the engine ------------------------------------------------

    @Test
    fun editingAWord_forgetsTheOldWordAndLearnsTheNewOneInTheEngine() {
        pdu.add(word("quixotal"))
        verify(sdk, timeout(5_000)).addWord("quixotal")

        pdu.update(word("quixotal"), word("quixotally"))

        verify(sdk, timeout(5_000)).deleteDLMWord("quixotal")
        verify(sdk, timeout(5_000)).addWord("quixotally")
    }

    /** A shortcut edit: the old name stops expanding, the new one expands to the new phrase. */
    @Test
    fun editingAShortcut_movesTheExpansionToTheNewNameAndPhrase() {
        pdu.add(shortcut("skq", "skone phrase"))

        pdu.update(shortcut("skq", "skone phrase"), shortcut("skw", "sktwo phrase"))

        val caps = PersonalDictionaryUtil.CapsMode.NO_CAPS
        assertEquals("skq", pdu.getWordSubstitutionForInput("skq", caps, Locale.US))
        assertEquals("sktwo phrase", pdu.getWordSubstitutionForInput("skw", caps, Locale.US))
        verify(sdk, timeout(5_000)).addWord("sktwo phrase")
        // A shortcut's phrase is not a dictionary word: it is not deleted from the engine.
        Thread.sleep(200)
        verify(sdk, never()).deleteDLMWord(anyString())
    }

    // ---- fault 4: a deleted dictionary word leaves the engine's learned words ---------------

    @Test
    fun deletingADictionaryWord_deletesItFromTheEngine() {
        pdu.add(word("plimsoq"))

        pdu.remove(pdu.getWordSubstitutions().getValue("plimsoq"))

        verify(sdk, timeout(5_000)).deleteDLMWord("plimsoq")
        assertEquals(null, pdu.getWordSubstitutions()["plimsoq"])
    }

    @Test
    fun deletingAShortcut_leavesItsPhraseInTheEngine() {
        pdu.add(shortcut("skq", "skone phrase"))

        pdu.remove(pdu.getWordSubstitutions().getValue("skq"))

        Thread.sleep(200)
        verify(sdk, never()).deleteDLMWord(anyString())
    }
}
