package dev.bbkb.ime.personaldictionary

import android.content.ContentValues
import android.content.Context
import android.provider.UserDictionary
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import dev.bbkb.ime.personaldictionary.storage.DefaultWSLoader
import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The system user dictionary (AUD) <-> personal dictionary bridge, end to end over a fake
 * `user_dictionary` provider.
 *
 * Found dead on the owner's KEY2 on 2026-09-21: `content://user_dictionary/words` was empty
 * while the on-disk personal dictionary held eight words, and an external insert produced no
 * `AudContentObserver` line at all. These tests pin the two directions that were unobserved:
 * a change arriving from outside reaches BASL, and our own add reaches AUD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.blackberry.nuanceshim"])
class AudSyncTest {

    private lateinit var context: Context
    private lateinit var provider: FakeUserDictionaryProvider
    private lateinit var pdu: PersonalDictionaryUtil

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = FakeUserDictionaryProvider()
        provider.attachInfo(context, null)
        ShadowContentResolver.registerProviderInternal("user_dictionary", provider)
        DefaultWSLoader.invalidateCache()
        pduDir().deleteRecursively()
        pdu = PersonalDictionaryUtil.getInstance(
            context, context.filesDir.absolutePath, "substitution_macros", mock(NuanceSDK::class.java)
        )
    }

    @After
    fun tearDown() {
        if (this::pdu.isInitialized) pdu.shutDown(true)
        AudSyncer.getInstance().shutDown(true)
        pduDir().deleteRecursively()
    }

    private fun pduDir() = File(context.filesDir, "dev.bbkb.ime.basl.pdu")

    private fun loadAndRegisterObserver() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, true, false)
        assertTrue("PDU load never completed", loaded.await(15, TimeUnit.SECONDS))
        // load() registers the observer immediately after reporting completion, on the same
        // coroutine; wait for the registration itself rather than racing it.
        waitFor("AUD content observer registered") { pdu.isAudContentObserverRegisteredForTest }
    }

    private fun insertIntoAud(word: String, shortcut: String?, locale: String?) {
        val values = ContentValues().apply {
            put("word", word)
            put("frequency", 250)
            put("locale", locale)
            put("shortcut", shortcut)
        }
        context.contentResolver.insert(UserDictionary.Words.CONTENT_URI, values)
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /**
     * The break the owner saw from the outside: a word added to the system dictionary by
     * something else (Settings, the framework's "Add to dictionary" popup, `adb content insert`)
     * has to reach our personal dictionary, because that is the only route by which that popup
     * can teach the engine.
     */
    @Test
    fun aWordInsertedIntoAudReachesBasl() {
        loadAndRegisterObserver()

        insertIntoAud(word = "zzqqx", shortcut = null, locale = null)

        waitFor("zzqqx to reach the personal dictionary") {
            pdu.personalDictionary.containsKey("zzqqx")
        }
    }

    /** ...and a shortcut arriving from AUD becomes a word substitution. */
    @Test
    fun aShortcutInsertedIntoAudReachesBasl() {
        loadAndRegisterObserver()

        insertIntoAud(word = "on my way", shortcut = "omw", locale = null)

        waitFor("omw to reach the substitutions") {
            pdu.getWordSubstitutions().containsKey("omw")
        }
    }

    /**
     * The other direction, i.e. the one-tap add: `OneTapAddWord` -> `addWordSubstitution` ->
     * `PersonalDictionaryUtil.add` has to land a row in AUD. On the KEY2 it did not, and
     * `PersonalDictionaryUtil.add` reports that by throwing, so a silent AUD would have made
     * every one-tap add fall back to the system dialog.
     */
    @Test
    fun aOneTapAddWritesToAud() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue(loaded.await(15, TimeUnit.SECONDS))

        val manager = DictionaryManager.getInstance()
        manager.attachLoadedUtilForTest(pdu)
        try {
            assertTrue("one-tap add refused", OneTapAddWord.add(manager, "zzqqx", Locale.US))
        } finally {
            manager.detachForTest()
        }

        val row = provider.snapshot().singleOrNull { it.word == "zzqqx" }
        assertEquals("zzqqx", row?.word)
        assertEquals("zzqqx", row?.shortcut)
        assertEquals("en_US", row?.locale)
    }

    /**
     * The words stranded by the dead write path. AUD is the master in `syncDifferencesToBasl`,
     * so without the one-shot push the first sync that finally works would *delete* the eight
     * words the owner's device has on disk instead of publishing them.
     */
    @Test
    fun strandedBaslWordsArePushedToAudBeforeTheFirstDiffSync() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        // A word that exists only in BASL, exactly like the eight on the device.
        pdu.addToBasl(WordSubstitution.createUserSubstitution("en_US", "zzqqx", "zzqqx", false), true)

        assertEquals(1, pdu.pushUserWordsToAud())
        assertTrue(provider.snapshot().any { it.word == "zzqqx" && it.shortcut == "zzqqx" })

        // A refused batch reports -1 so the one-shot flag is not latched and the words keep
        // their chance on the next registration.
        provider.failInserts = true
        assertEquals(-1, pdu.pushUserWordsToAud())
        provider.failInserts = false
        assertEquals(1, pdu.pushUserWordsToAud())

        // ...and once they are in AUD, the diff sync keeps them.
        loadAndRegisterObserver()
        insertIntoAud(word = "other", shortcut = null, locale = null)
        waitFor("the diff sync to run") { pdu.personalDictionary.containsKey("other") }
        assertTrue("stranded word was wiped by the diff sync",
            pdu.getWordSubstitutions().containsKey("zzqqx"))
    }

    /**
     * An unreachable provider must abort the reconciliation, not present itself as an empty
     * AUD. `AudWrapper.getAudWordsWithoutSpacedKeys` used to flatten a null cursor to an empty
     * list, and "missing from AUD" means "delete from BASL" - so the failure mode of the KEY2
     * bug was one working sync away from erasing the whole personal dictionary.
     */
    @Test
    fun anUnreachableAudDoesNotWipeBasl() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        pdu.addToBasl(WordSubstitution.createUserSubstitution("en_US", "zzqqx", "zzqqx", false), true)

        ShadowContentResolver.registerProviderInternal("user_dictionary", null)

        val synced = CountDownLatch(1)
        val result = booleanArrayOf(true)
        AudSyncer.getInstance().syncDifferencesToBasl(context, pdu, CompletionListener { ok ->
            result[0] = ok
            synced.countDown()
        })
        assertTrue(synced.await(15, TimeUnit.SECONDS))

        assertEquals("the sync must report failure, not success", false, result[0])
        assertTrue("BASL was wiped by an unreachable AUD",
            pdu.getWordSubstitutions().containsKey("zzqqx"))
    }

    /**
     * The root cause of the KEY2 breakage, as a manifest contract.
     *
     * The original APK targeted API 28, which predates package-visibility filtering. This port
     * targets 36, where an undeclared provider is invisible: `query()` returns null and
     * `applyBatch()` throws `IllegalArgumentException("Unknown authority user_dictionary")`.
     * Nothing at runtime can compensate, so the declaration is the fix and this is its guard.
     */
    @Test
    fun theManifestDeclaresTheUserDictionaryProviderAsQueryable() {
        val manifest = sequenceOf(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "../app/src/main/AndroidManifest.xml"
        ).map(::File).firstOrNull { it.isFile }
        assertTrue("AndroidManifest.xml not found from ${File(".").canonicalPath}", manifest != null)

        val text = manifest!!.readText()
        val queries = text.substringAfter("<queries>", "").substringBefore("</queries>", "")
        assertTrue(
            "<queries> must declare the system user dictionary provider, or the AUD <-> personal " +
                "dictionary sync is silently dead on API 30+",
            Regex("""<provider[^>]*android:authorities\s*=\s*"user_dictionary"""").containsMatchIn(queries)
        )
    }
}
