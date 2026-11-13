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
import dev.bbkb.ime.core.engine.EngineThreads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
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
 * The system user dictionary (AUD) <-> personal dictionary (BASL) bridge, end to end over a fake
 * `user_dictionary` provider: outside changes reach BASL and our own adds reach AUD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.blackberry.nuanceshim"])
class AudSyncTest {

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
        pdu = PersonalDictionaryUtil.getInstance(
            context, context.filesDir.absolutePath, "substitution_macros", sdk
        )
    }

    @After
    fun tearDown() {
        if (this::pdu.isInitialized) pdu.shutDown(true)
        AudSyncer.getInstance().shutDown(true)
        pduDir().deleteRecursively()
    }

    private fun pduDir() = File(context.filesDir, "dev.bbkb.ime.basl.pdu")

    private fun loadAndRegisterObserver(locales: List<Locale> = listOf(Locale.US)) {
        val loaded = CountDownLatch(1)
        pdu.load(locales, CompletionListener { loaded.countDown() }, true, false)
        assertTrue("PDU load never completed", loaded.await(15, TimeUnit.SECONDS))
        // load() registers the observer just after reporting completion; wait for it, don't race it.
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

    /** A word added to AUD from outside (Settings, the framework "Add to dictionary" popup) reaches BASL. */
    @Test
    fun aWordInsertedIntoAudReachesBasl() {
        loadAndRegisterObserver()

        insertIntoAud(word = "zzqqx", shortcut = null, locale = null)

        waitFor("zzqqx to reach the personal dictionary") {
            pdu.personalDictionary.containsKey("zzqqx")
        }
    }

    @Test
    fun aShortcutInsertedIntoAudReachesBasl() {
        loadAndRegisterObserver()

        insertIntoAud(word = "on my way", shortcut = "omw", locale = null)

        waitFor("omw to reach the substitutions") {
            pdu.getWordSubstitutions().containsKey("omw")
        }
    }

    /** Every keyboard language passes the sync's locale filter; other languages stay out. */
    @Test
    fun aShortcutForAnExtraKeyboardLanguageReachesBasl_andOneForAnotherLanguageDoesNot() {
        loadAndRegisterObserver(listOf(Locale.US, Locale.GERMAN))

        // fr first: by the time the de row has come through, a sync has seen both.
        insertIntoAud(word = "s'il te plait", shortcut = "zzfr", locale = "fr")
        insertIntoAud(word = "mit freundlichen Gruessen", shortcut = "zzde", locale = "de")

        waitFor("zzde to reach the substitutions") {
            pdu.getWordSubstitutions().containsKey("zzde")
        }
        assertFalse("a shortcut for a language not on the keyboard came through",
            pdu.getWordSubstitutions().containsKey("zzfr"))
    }

    /** A one-tap add lands a row in AUD; a failed AUD write makes `add` throw (system dialog fallback). */
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

    /** An add from the main thread posts the engine `addWord` to the engine thread (held busy here). */
    @Test
    fun anAddFromTheMainThread_postsTheEngineAddWordToTheEngineThread() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        val calledOn = java.util.concurrent.atomic.AtomicReference<String?>(null)
        doAnswer { calledOn.set(Thread.currentThread().name); true }.`when`(sdk).addWord(anyString())

        val hold = CountDownLatch(1)
        EngineThreads.engineHandler().post { hold.await(15, TimeUnit.SECONDS) }
        try {
            pdu.add(WordSubstitution.createUserSubstitution("en_US", "zzqqx", "zzqqx", false))
            verify(sdk, never()).addWord(anyString())
            assertNull(calledOn.get())
        } finally {
            hold.countDown()
        }
        verify(sdk, timeout(5_000)).addWord("zzqqx")
        assertEquals("EngineThread", calledOn.get())
    }

    /**
     * AUD is the master in `syncDifferencesToBasl`, so BASL-only words must be pushed to AUD once
     * before the first diff sync or that sync deletes them.
     */
    @Test
    fun strandedBaslWordsArePushedToAudBeforeTheFirstDiffSync() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        pdu.addToBasl(WordSubstitution.createUserSubstitution("en_US", "zzqqx", "zzqqx", false), true)

        assertEquals(1, pdu.pushUserWordsToAud())
        assertTrue(provider.snapshot().any { it.word == "zzqqx" && it.shortcut == "zzqqx" })

        // A refused batch reports -1 so the one-shot flag is not latched and the push retries.
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

    /** An unreachable provider aborts the sync; read as an empty AUD it would delete all of BASL. */
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
     * Under package-visibility filtering (API 30+) an undeclared provider is invisible: `query()`
     * returns null and `applyBatch()` throws "Unknown authority". Only the manifest can fix that.
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
