package dev.bbkb.ime.core.engine

import android.content.Context
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings
import dev.bbkb.ime.core.shared.ThreadUtils
import dev.bbkb.ime.core.suggestion.PrevWordsInfo
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * CHARACTERISATION of [DictionaryLoader]'s load/reload/threading contract — Wave 3b package G1.
 *
 * The loader runs on `ThreadUtils.getBackgroundExecutor("InitializeBinaryDictionary")` during IME
 * startup and on every locale change. These tests replace that executor with a manual queue so the
 * window *between* `initDictionary` returning and the background load finishing is observable, and
 * pin what callers see in it:
 *
 *  - the new locale is visible at once; the dictionary is null and not ready until the task runs;
 *  - the OLD dictionary is closed on the executor, before the new one is created — not inline;
 *  - a load whose locale was superseded closes its own product and still fires the callback;
 *  - the spell-checker prefix (`"spellcheck_"`) loads synchronously on the SECONDARY engine and
 *    never touches the executor;
 *  - the reload predicate: locale change, additional-locale change, force, or a kept dictionary
 *    that reports `!isInitialized()`.
 *
 * Rows marked `CHARACTERISED QUIRK` / `CHARACTERISED BUG` assert what ships today. Do not "fix" them
 * here — a rewrite must reproduce them.
 *
 * Static mocks are thread-scoped, which is why the queue is drained on the test thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class DictionaryLoaderTest {

    /** An executor that only records; [drain] runs the recorded tasks in order on the caller. */
    private class QueueExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        fun runOne() = tasks.removeFirst().run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private lateinit var threadStatic: MockedStatic<ThreadUtils>
    private lateinit var factoryStatic: MockedStatic<DictionaryFactory>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var subtypes: SubtypeManager
    private lateinit var executor: QueueExecutor
    private lateinit var ctx: Context
    private lateinit var loader: DictionaryLoader
    private lateinit var callback: DictionaryLoader.DictionaryInitCallback

    private val en = Locale.ENGLISH
    private val fr = Locale.FRENCH

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        executor = QueueExecutor()
        threadStatic = mockStatic(ThreadUtils::class.java)
        threadStatic.`when`<Any> { ThreadUtils.getBackgroundExecutor(anyString()) }.thenReturn(executor)
        factoryStatic = mockStatic(DictionaryFactory::class.java)
        subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(en)
        // Explicit: a bare mock would answer an EMPTY set, which is not null and so always reloads.
        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(null)
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)
        loader = DictionaryLoader()
        callback = mock(DictionaryLoader.DictionaryInitCallback::class.java)
    }

    @After
    fun tearDown() {
        subtypeStatic.close()
        factoryStatic.close()
        threadStatic.close()
    }

    private fun dict(initialized: Boolean = true): Dictionary =
        mock(Dictionary::class.java).also { `when`(it.isInitialized).thenReturn(initialized) }

    private fun primaryWillCreate(locale: Locale?, d: Dictionary) =
        factoryStatic.`when`<Dictionary> { DictionaryFactory.createDictionary(ctx, locale) }.thenReturn(d)

    private fun secondaryWillCreate(locale: Locale?, d: Dictionary) =
        factoryStatic.`when`<Dictionary> { DictionaryFactory.createDictionary(ctx, locale, true) }.thenReturn(d)

    /** Loads [d] for [locale] through the IME path and drains the executor. */
    private fun loaded(locale: Locale, d: Dictionary) {
        primaryWillCreate(locale, d)
        loader.initDictionary(ctx, locale, false, false, callback)
        executor.drain()
    }

    // ---- fresh state ------------------------------------------------------------------------

    @Test
    fun freshLoader_hasNothing() {
        assertNull(loader.locale)
        assertNull(loader.mainDictionary)
        assertFalse(loader.isDictionaryReady)
        assertFalse(loader.isWordValid("the", true))
    }

    // ---- the async window -------------------------------------------------------------------

    @Test
    fun initDictionary_newLocale_isVisibleBeforeTheLoadFinishes() {
        val d = dict()
        primaryWillCreate(en, d)

        assertTrue(loader.initDictionary(ctx, en, false, false, callback))

        // Before the background task runs: locale already switched, no dictionary, not ready.
        assertEquals(en, loader.locale)
        assertNull(loader.mainDictionary)
        assertFalse(loader.isDictionaryReady)
        assertEquals(1, executor.tasks.size)
        threadStatic.verify { ThreadUtils.getBackgroundExecutor("InitializeBinaryDictionary") }
        factoryStatic.verify({ DictionaryFactory.createDictionary(any(), any()) }, never())
        verifyNoInteractions(callback)

        executor.drain()

        assertSame(d, loader.mainDictionary)
        assertTrue(loader.isDictionaryReady)
        verify(callback).onDictionaryInitialized(true)
    }

    @Test
    fun callbackReportsReadiness_notMerelyCompletion() {
        val d = dict(initialized = false)
        loaded(en, d)

        // Installed even though it is not initialised; the callback carries isDictionaryReady().
        assertSame(d, loader.mainDictionary)
        verify(callback).onDictionaryInitialized(false)
    }

    @Test
    fun nullCallback_isTolerated() {
        primaryWillCreate(en, dict())
        loader.initDictionary(ctx, en, false, false, null)
        executor.drain()
        assertTrue(loader.isDictionaryReady)
    }

    @Test
    fun reload_closesTheOldDictionaryOnTheExecutor_beforeCreatingTheNewOne() {
        val old = dict()
        loaded(en, old)
        val fresh = dict()
        var closedBeforeCreate: Boolean? = null
        factoryStatic.`when`<Dictionary> { DictionaryFactory.createDictionary(ctx, fr) }.thenAnswer {
            closedBeforeCreate = org.mockito.Mockito.mockingDetails(old).invocations.any { it.method.name == "close" }
            fresh
        }

        loader.initDictionary(ctx, fr, false, false, callback)

        // Not closed inline: callers may still be holding it until the task runs.
        verify(old, never()).close()
        assertNull(loader.mainDictionary)

        executor.drain()

        verify(old).close()
        assertEquals(true, closedBeforeCreate)
        assertSame(fresh, loader.mainDictionary)
    }

    @Test
    fun supersededLoad_closesItsOwnProduct_andStillFiresItsCallback() {
        val dEn = dict()
        val dFr = dict()
        primaryWillCreate(en, dEn)
        primaryWillCreate(fr, dFr)
        val cbEn = mock(DictionaryLoader.DictionaryInitCallback::class.java)
        val cbFr = mock(DictionaryLoader.DictionaryInitCallback::class.java)

        loader.initDictionary(ctx, en, false, false, cbEn)
        loader.initDictionary(ctx, fr, false, false, cbFr)
        assertEquals(2, executor.tasks.size)

        executor.runOne()
        verify(dEn).close()
        assertNull(loader.mainDictionary)
        verify(cbEn).onDictionaryInitialized(false)

        executor.runOne()
        assertSame(dFr, loader.mainDictionary)
        verify(dFr, never()).close()
        verify(cbFr).onDictionaryInitialized(true)
    }

    // ---- reload predicate -------------------------------------------------------------------

    @Test
    fun sameLocale_loadedAndInitialised_keepsTheDictionary() {
        val d = dict()
        loaded(en, d)

        assertFalse(loader.initDictionary(ctx, en, false, false, callback))

        assertTrue(executor.tasks.isEmpty())
        assertSame(d, loader.mainDictionary)
        verify(d, never()).close()
    }

    @Test
    fun sameLocale_keptDictionaryNotInitialised_reloads() {
        val d = dict()
        loaded(en, d)
        `when`(d.isInitialized).thenReturn(false)

        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
        assertNull(loader.mainDictionary)
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun forceReload_reloadsEvenWhenNothingChanged() {
        loaded(en, dict())
        assertTrue(loader.initDictionary(ctx, en, false, true, callback))
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun additionalLocales_aReloadRecordsThem_soARepeatInitDoesNotReloadAgain() {
        // A reload records the additional locales it was started for, so a repeat init before the
        // async updateAdditionalLocales lands keeps the dictionary instead of reloading again.
        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(setOf(fr))
        val d = dict()
        loaded(en, d)
        assertFalse(loader.initDictionary(ctx, en, false, false, callback))
        assertFalse(loader.initDictionary(ctx, en, false, false, callback))
        assertTrue(executor.tasks.isEmpty())
        assertSame(d, loader.mainDictionary)

        `when`(d.addLocales(setOf(fr))).thenReturn(true)
        assertTrue(loader.updateAdditionalLocales(setOf(fr), false, null))
        assertFalse(loader.initDictionary(ctx, en, false, false, callback))

        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(setOf(Locale.GERMAN))
        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
    }

    @Test
    fun additionalLocalesGoingNull_afterBeingRecorded_reloads() {
        val d = dict()
        loaded(en, d)
        `when`(d.addLocales(setOf(fr))).thenReturn(true)
        loader.updateAdditionalLocales(setOf(fr), false, null)
        // active is null, recorded is {fr}
        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
    }

    @Test
    fun nullLocale_afterALocale_reloads_installsItsDictionary_andNullAfterNullKeepsIt() {
        loaded(en, dict())
        val fallback = dict()
        primaryWillCreate(null, fallback)
        assertTrue(loader.initDictionary(ctx, null, false, false, callback))
        assertNull(loader.locale)
        executor.drain()
        // The null-locale load (FallbackDictionary in production) is installed and serves.
        assertSame(fallback, loader.mainDictionary)
        verify(fallback, never()).close()
        // Once for the en load, once for the null-locale load (the old guard reported false here).
        verify(callback, org.mockito.Mockito.times(2)).onDictionaryInitialized(true)
        assertFalse(loader.initDictionary(ctx, null, false, false, callback))
        assertSame(fallback, loader.mainDictionary)
    }

    @Test
    fun closeAndReset_duringAPendingNullLocaleLoad_stillDiscardsIt() {
        val d = dict()
        primaryWillCreate(null, d)
        loaded(en, dict())
        loader.initDictionary(ctx, null, false, false, callback)

        // The reset group's locale is null too; it must still refuse the pending load.
        loader.closeAndReset()
        executor.drain()

        verify(d).close()
        assertNull(loader.mainDictionary)
    }

    @Test
    fun sameLocaleWhileLoadPending_noAdditionalLocales_doesNotQueueASecondLoad() {
        val d = dict()
        primaryWillCreate(en, d)
        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
        assertFalse(loader.initDictionary(ctx, en, false, false, callback))
        assertEquals(1, executor.tasks.size)

        executor.drain()

        // The one pending load installs into whatever group is current when it finishes.
        assertSame(d, loader.mainDictionary)
    }

    @Test
    fun sameLocaleWhileLoadPending_withAdditionalLocales_doesNotQueueASecondLoad() {
        // The pending load's group records the additional locales it was started for, so a repeat
        // init for the same locale and set does not queue a second load (which used to leak).
        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(setOf(fr))
        val d = dict()
        primaryWillCreate(en, d)
        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
        assertFalse(loader.initDictionary(ctx, en, false, false, callback))
        assertEquals(1, executor.tasks.size)

        executor.drain()

        assertSame(d, loader.mainDictionary)
        verify(d, never()).close()
    }

    @Test
    fun additionalLocales_engineRefusesThem_nextInitStillReloads() {
        // Recording the set at reload time must not cost the retry: a refusal forgets it.
        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(setOf(fr))
        val d = dict()
        loaded(en, d)
        `when`(d.addLocales(setOf(fr))).thenReturn(false)

        assertFalse(loader.updateAdditionalLocales(setOf(fr), false, callback))

        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun forceReloadWhileLoadPending_closesTheDictionaryItReplaces() {
        // reinitDictionary() forces a reload; two in quick succession queue two loads for one locale.
        val first = dict()
        val second = dict()
        loaded(en, dict())
        factoryStatic.`when`<Dictionary> { DictionaryFactory.createDictionary(ctx, en) }.thenReturn(first, second)
        assertTrue(loader.initDictionary(ctx, en, false, true, callback))
        assertTrue(loader.initDictionary(ctx, en, false, true, callback))
        assertEquals(2, executor.tasks.size)

        executor.runOne()
        assertSame(first, loader.mainDictionary)
        executor.runOne()

        assertSame(second, loader.mainDictionary)
        verify(first).close()
        verify(second, never()).close()
    }

    // ---- spell-checker prefix ---------------------------------------------------------------

    @Test
    fun spellcheckPrefix_loadsSynchronouslyOnTheSecondaryEngine() {
        val d = dict()
        secondaryWillCreate(en, d)

        assertTrue(loader.initDictionaryWithPrefix(ctx, en, false, false, callback, "spellcheck_"))

        assertSame(d, loader.mainDictionary)
        assertTrue(loader.isDictionaryReady)
        assertTrue(executor.tasks.isEmpty())
        threadStatic.verify({ ThreadUtils.getBackgroundExecutor(anyString()) }, never())
        factoryStatic.verify({ DictionaryFactory.createDictionary(any(), any()) }, never())
        // The synchronous path never calls back.
        verifyNoInteractions(callback)
    }

    @Test
    fun spellcheckPrefix_reload_doesNotCloseTheOldDictionary() {
        // CHARACTERISED QUIRK: only the async path closes the previous dictionary. Unreachable
        // today — AndroidSpellCheckerService builds a fresh DictionaryLoader per init and calls
        // closeAndReset() on the old one itself.
        val old = dict()
        secondaryWillCreate(en, old)
        loader.initDictionaryWithPrefix(ctx, en, false, false, null, "spellcheck_")
        val fresh = dict()
        secondaryWillCreate(fr, fresh)

        loader.initDictionaryWithPrefix(ctx, fr, false, false, null, "spellcheck_")

        assertSame(fresh, loader.mainDictionary)
        verify(old, never()).close()
    }

    @Test
    fun anyOtherPrefix_takesTheAsyncPrimaryPath() {
        primaryWillCreate(en, dict())
        assertTrue(loader.initDictionaryWithPrefix(ctx, en, false, false, callback, "something_else"))
        assertNull(loader.mainDictionary)
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun useContacts_doesNotAffectLoading() {
        primaryWillCreate(en, dict())
        assertTrue(loader.initDictionary(ctx, en, true, false, callback))
        executor.drain()
        factoryStatic.verify { DictionaryFactory.createDictionary(ctx, en) }
    }

    // ---- closeAndReset ----------------------------------------------------------------------

    @Test
    fun closeAndReset_closesSynchronously_andEmptiesTheLoader() {
        val d = dict()
        loaded(en, d)

        loader.closeAndReset()

        verify(d).close()
        assertNull(loader.locale)
        assertNull(loader.mainDictionary)
        assertFalse(loader.isDictionaryReady)
    }

    @Test
    fun closeAndReset_duringAPendingLoad_discardsTheLoadedDictionary() {
        val d = dict()
        primaryWillCreate(en, d)
        loader.initDictionary(ctx, en, false, false, callback)

        loader.closeAndReset()
        executor.drain()

        verify(d).close()
        assertNull(loader.mainDictionary)
        verify(callback).onDictionaryInitialized(false)
    }

    @Test
    fun closeAndReset_thenSameLocale_reloads() {
        loaded(en, dict())
        loader.closeAndReset()
        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
    }

    // ---- generateSuggestions ----------------------------------------------------------------

    private val tracker: ComposingTextTracker = mock(ComposingTextTracker::class.java)
    private val strip: SuggestionStripSettings = mock(SuggestionStripSettings::class.java)

    private fun info(w: String) =
        SuggestedWords.SuggestedWordInfo(w, 1, 1, Dictionary.DICTIONARY_HARDCODED, -1, -1, null)

    @Test
    fun generateSuggestions_passesThroughToTheMainDictionary() {
        val d = dict()
        loaded(en, d)
        val prev = PrevWordsInfo(PrevWordsInfo.WordInfo("hello"))
        val words = arrayListOf(info("a"), info("b"))
        `when`(d.generateSuggestions(tracker, prev, strip, 7)).thenReturn(words)

        val result = loader.generateSuggestions(tracker, prev, null, strip, 7)

        assertEquals(listOf("a", "b"), result.suggestions.map { it.word })
        assertEquals(en, result.locale)
        assertFalse(result.hasNextWordSuggestions)
    }

    @Test
    fun generateSuggestions_capsAtTheMaxSuggestionCount_andCarriesBeginningOfSentence() {
        val d = dict()
        loaded(en, d)
        val prev = PrevWordsInfo(PrevWordsInfo.WordInfo())
        val words = ArrayList((1..40).map { info("w$it") })
        `when`(d.generateSuggestions(any(), any(), any(), anyInt())).thenReturn(words)

        val result = loader.generateSuggestions(tracker, prev, null, strip, 0)

        assertEquals(SuggestedWords.getMaxSuggestionCount(), result.suggestions.size)
        assertTrue(result.hasNextWordSuggestions)
    }

    @Test
    fun generateSuggestions_noLanguageLocale_neverConsultsTheDictionary() {
        val zz = Locale("zz")
        val d = dict()
        loaded(zz, d)
        val result = loader.generateSuggestions(
            tracker, PrevWordsInfo(PrevWordsInfo.WordInfo("x")), null, strip, 0)
        assertTrue(result.isEmpty)
        verify(d, never()).generateSuggestions(any(), any(), any(), anyInt())
    }

    @Test
    fun generateSuggestions_nullDictionaryOrNullResult_isEmpty() {
        primaryWillCreate(en, dict())
        loader.initDictionary(ctx, en, false, false, callback) // pending: no dictionary yet
        val prev = PrevWordsInfo(PrevWordsInfo.WordInfo("x"))
        assertTrue(loader.generateSuggestions(tracker, prev, null, strip, 0).isEmpty)

        executor.drain() // dictionary mock returns null from generateSuggestions
        val r = loader.generateSuggestions(tracker, prev, null, strip, 0)
        assertTrue(r.isEmpty)
        assertEquals(en, r.locale)
    }

    // ---- isWordValid ------------------------------------------------------------------------

    @Test
    fun isWordValid_emptyOrNull_isFalseWithoutConsulting() {
        val d = dict()
        loaded(en, d)
        assertFalse(loader.isWordValid("", true))
        assertFalse(loader.isWordValid(null, true))
        verify(d, never()).isValidWord(anyString())
    }

    @Test
    fun isWordValid_exactThenLowercasedInTheLoaderLocale() {
        val tr = Locale("tr")
        val d = dict()
        loaded(tr, d)
        // Turkish lower-cases 'I' to dotless 'ı' — proves the loader's locale is used.
        `when`(d.isValidWord("ıs")).thenReturn(true)

        assertFalse(loader.isWordValid("Is", false))
        verify(d, never()).isValidWord("ıs")
        assertTrue(loader.isWordValid("Is", true))

        `when`(d.isValidWord("Exact")).thenReturn(true)
        assertTrue(loader.isWordValid("Exact", false))
    }

    @Test
    fun isWordValid_noDictionaryYet_isFalse() {
        primaryWillCreate(en, dict())
        loader.initDictionary(ctx, en, false, false, callback)
        assertFalse(loader.isWordValid("the", true))
    }

    // ---- updateAdditionalLocales ------------------------------------------------------------

    @Test
    fun updateAdditionalLocales_nullSet_isFalse() {
        assertFalse(loader.updateAdditionalLocales(null, false, callback))
        assertFalse(loader.updateAdditionalLocales(null, true, callback))
        assertTrue(executor.tasks.isEmpty())
    }

    @Test
    fun updateAdditionalLocales_sync_needsAnInitialisedDictionary() {
        assertFalse(loader.updateAdditionalLocales(setOf(fr), false, callback))
        val d = dict(initialized = false)
        loaded(en, d)
        assertFalse(loader.updateAdditionalLocales(setOf(fr), false, callback))
        verify(d, never()).addLocales(any())
    }

    @Test
    fun updateAdditionalLocales_sync_success_recordsTheLocalesAndCallsBack() {
        val d = dict()
        loaded(en, d)
        `when`(d.addLocales(setOf(fr))).thenReturn(true)

        assertTrue(loader.updateAdditionalLocales(setOf(fr), false, callback))
        verify(callback).onLocalesUpdated()

        // Recorded: an init with the same active set now keeps the dictionary.
        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(setOf(fr))
        assertFalse(loader.initDictionary(ctx, en, false, false, callback))
        assertSame(d, loader.mainDictionary)
    }

    @Test
    fun updateAdditionalLocales_sync_successWithNullCallback_isTrue() {
        val d = dict()
        loaded(en, d)
        `when`(d.addLocales(setOf(fr))).thenReturn(true)
        assertTrue(loader.updateAdditionalLocales(setOf(fr), false, null))
    }

    @Test
    fun updateAdditionalLocales_sync_engineRefuses_isFalse_andNotRecorded() {
        val d = dict()
        loaded(en, d)
        `when`(d.addLocales(setOf(fr))).thenReturn(false)

        assertFalse(loader.updateAdditionalLocales(setOf(fr), false, callback))
        verify(callback, never()).onLocalesUpdated()

        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(setOf(fr))
        assertTrue(loader.initDictionary(ctx, en, false, false, callback))
    }

    @Test
    fun updateAdditionalLocales_async_returnsFalse_andDoesTheWorkOnTheExecutor() {
        val d = dict()
        loaded(en, d)
        `when`(d.addLocales(setOf(fr))).thenReturn(true)

        assertFalse(loader.updateAdditionalLocales(setOf(fr), true, callback))
        verify(d, never()).addLocales(any())
        assertEquals(1, executor.tasks.size)

        executor.drain()

        verify(d).addLocales(setOf(fr))
        verify(callback).onLocalesUpdated()
    }
}
