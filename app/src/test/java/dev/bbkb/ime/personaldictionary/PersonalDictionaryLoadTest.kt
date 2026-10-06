package dev.bbkb.ime.personaldictionary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.personaldictionary.storage.DefaultWSLoader
import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assume
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
 * The on-disk `personal_words` file must come back into memory on load.
 *
 * KEY2, 2026-09-21: the file held eight words and the load logged
 * "Error parsing JSON with Gson: null" — the load path's add threw
 * [InitialisationIncompleteException] on the first word because the loaded flag is (correctly)
 * set only after everything is in. Not one personal word was loaded from disk, on any start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], instrumentedPackages = ["com.blackberry.nuanceshim"])
class PersonalDictionaryLoadTest {

    private lateinit var context: Context
    private lateinit var pdu: PersonalDictionaryUtil

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val provider = FakeUserDictionaryProvider()
        provider.attachInfo(context, null)
        ShadowContentResolver.registerProviderInternal("user_dictionary", provider)
        DefaultWSLoader.invalidateCache()
        pduDir().deleteRecursively()
        pduDir().mkdirs()
        File(pduDir(), PersonalDictionaryConstants.PERSONAL_WORDS_FILE).writeText(
            """[{"version":1,"autoCapsEnabled":false,"locale":"en_US","word":"politicus"},""" +
                """{"version":1,"autoCapsEnabled":false,"locale":"en_US","word":"viewshed"}]"""
        )
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

    @Test
    fun personalWordsOnDiskAreInMemoryAfterLoad() {
        val loaded = CountDownLatch(1)
        pdu.load(listOf(Locale.US), CompletionListener { loaded.countDown() }, false, false)
        assertTrue("PDU load never completed", loaded.await(15, TimeUnit.SECONDS))

        val words = pdu.personalDictionary.keys
        assertEquals("both on-disk words must be loaded: $words", setOf("politicus", "viewshed"), words)
    }

    // ---- multi-language keyboards -----------------------------------------------------------

    /** One user shortcut per language, plus one saved for all languages. */
    private fun writeShortcuts() {
        fun ws(locale: String, key: String) =
            """{"key":"$key","type":"USER","version":16,"word":"$key word","locale":"$locale","autoCapsEnabled":false}"""
        File(pduDir(), PersonalDictionaryConstants.WORD_SUBSTITUTION_FILE).writeText(
            "[" + listOf(
                ws("en_US", "zzen"),
                ws("de", "zzde"),
                ws("fr", "zzfr"),
                ws(PersonalDictionaryConstants.LOCALE_ALL, "zzall"),
            ).joinToString(",") + "]"
        )
    }

    private fun load(locales: List<Locale>) {
        val loaded = CountDownLatch(1)
        pdu.load(locales, CompletionListener { loaded.countDown() }, false, false)
        assertTrue("PDU load never completed", loaded.await(15, TimeUnit.SECONDS))
    }

    private fun shortcuts() = pdu.getWordSubstitutions().keys.filter { it.startsWith("zz") }.toSet()

    @Test
    fun theShortcutsOfEveryKeyboardLanguageAreOn() {
        writeShortcuts()
        load(listOf(Locale.US, Locale.GERMAN))

        assertEquals(setOf("zzen", "zzde", "zzall"), shortcuts())
    }

    /**
     * The IME hands the manager the keyboard's layout language plus its extras (it used to hand
     * it the layout language alone), and the extra language's shortcuts come on.
     */
    @Test
    fun theDictionaryManagerSwitchesToEveryKeyboardLanguage() {
        writeShortcuts()
        load(listOf(Locale.US))
        assertEquals(setOf("zzen", "zzall"), shortcuts())
        // AUD is the master in the sync that follows a switch; publish what is on disk so that
        // sync has nothing to delete. Then mark the one-shot backfill done: the manager registers
        // the AUD observer, and a backfill then (delete, then re-add) would race the syncs its
        // own notifications start.
        assertTrue(pdu.pushUserWordsToAud() > 0)
        context.getSharedPreferences("personal_dictionary_sync_state", Context.MODE_PRIVATE)
            .edit().putBoolean("user_words_pushed_to_aud_v1", true).commit()

        val keyboard = ResourceLocaleUtils.getKeyboardLocales(Locale.US, linkedSetOf(Locale.FRENCH))
        val manager = DictionaryManager.getInstance()
        manager.attachLoadedUtilForTest(pdu)
        try {
            manager.initialiseOrSwitchLanguages(context, keyboard)
            waitFor("the switch to $keyboard") { pdu.immutableLastActiveLocales.contains(Locale.FRENCH) }
        } finally {
            manager.detachForTest()
        }

        assertEquals(setOf("zzen", "zzfr", "zzall"), shortcuts())
    }

    /**
     * The stored list has the phone's language moved to the front; the same-languages check used
     * to compare it with the list as passed, so a keyboard whose extra is the phone's language
     * reloaded (and re-synced AUD) on every switch.
     */
    @Test
    fun switchingToTheSameLanguagesAgainIsANoOp_whenThePhonesLanguageIsAnExtra() {
        val phone = Locale.getDefault()
        Assume.assumeFalse(phone.language == "de")
        load(listOf(Locale.US))

        val keyboard = listOf(Locale.GERMAN, phone)
        assertEquals(true, switchTo(keyboard))
        assertEquals(false, switchTo(keyboard))
    }

    /**
     * Two of the keyboard's languages with the same shortcut: the earlier language's wins, as it
     * does for the built-in ones. Neither is the phone's language, which would be moved first.
     */
    @Test
    fun twoLanguagesWithTheSameShortcut_theEarlierLanguageWins() {
        Assume.assumeFalse(Locale.getDefault().language in setOf("de", "fr"))
        File(pduDir(), PersonalDictionaryConstants.WORD_SUBSTITUTION_FILE).writeText(
            """[{"key":"zzboth","type":"USER","version":16,"word":"de word","locale":"de","autoCapsEnabled":false},""" +
                """{"key":"zzboth","type":"USER","version":16,"word":"fr word","locale":"fr","autoCapsEnabled":false}]"""
        )
        load(listOf(Locale.GERMAN, Locale.FRENCH))
        assertEquals("de word", pdu.getWordSubstitutions()["zzboth"]?.word)

        // Read inside the callback: the AUD sync that follows a switch would otherwise race it.
        val done = CountDownLatch(1)
        var winner: String? = null
        pdu.switchInputLanguages(listOf(Locale.FRENCH, Locale.GERMAN), CompletionListener {
            winner = pdu.getWordSubstitutions()["zzboth"]?.word
            done.countDown()
        })
        assertTrue("switch never completed", done.await(15, TimeUnit.SECONDS))
        assertEquals("fr word", winner)
    }

    private fun switchTo(locales: List<Locale>): Boolean {
        val done = CountDownLatch(1)
        var switched: Boolean? = null
        pdu.switchInputLanguages(locales, CompletionListener { switched = it; done.countDown() })
        assertTrue("switch never completed", done.await(15, TimeUnit.SECONDS))
        return switched!!
    }

    private fun waitFor(what: String, timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what")
    }
}
