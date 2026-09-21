package dev.bbkb.ime.core.engine

import android.view.inputmethod.InputConnection
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.contacts.ContactsDataProvider.ContactsType
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.shared.ThreadUtils
import dev.bbkb.ime.core.suggestion.PrevWordsInfo
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.suggestion.SuggestionEngine
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.MainKeyboardView
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener
import dev.bbkb.ime.personaldictionary.DictionaryManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/**
 * FIX-D2: a failed engine load must not crash the keyboard.
 *
 * NuanceSDKManager answers null from getInstance()/getSecondary() when the engine failed to load
 * (it swallows the load error). FIX-D made DictionaryFactory fall back; these drive the rest of
 * the startup + learning path the IME runs — construction, onCreate's learning manager, the
 * dictionary load, a suggestion request, commits, layout sync and teardown — and require each to
 * degrade (no learning, fallback suggestions) instead of throwing. Each test isolates one guard so
 * reverting that guard turns exactly that test red.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class EngineLoadFailureTest {

    private class QueueExecutor : AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var threadStatic: MockedStatic<ThreadUtils>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private val executor = QueueExecutor()
    private lateinit var ime: BlackBerryIME
    private lateinit var subtypes: SubtypeManager
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        // The engine failed to load: both accessors answer null (NuanceSDKManager's documented contract).
        sdkStatic.`when`<Any> { NuanceSDKManager.getInstance() }.thenReturn(null)
        sdkStatic.`when`<Any> { NuanceSDKManager.getSecondary() }.thenReturn(null)
        sdkStatic.`when`<Any> { NuanceSDKManager.getGestureLock() }.thenReturn(Any())
        threadStatic = mockStatic(ThreadUtils::class.java)
        threadStatic.`when`<Any> { ThreadUtils.getBackgroundExecutor(anyString()) }.thenReturn(executor)
        subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        `when`(subtypes.currentSubtypeAdditionalLocales).thenReturn(null)
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        ime = mock(BlackBerryIME::class.java)
        val ic = mock(InputConnection::class.java)
        `when`(ime.currentInputConnection).thenReturn(ic)
        `when`(ic.getTextBeforeCursor(anyInt(), anyInt())).thenReturn("")
        `when`(ic.getTextAfterCursor(anyInt(), anyInt())).thenReturn("")
    }

    @After
    fun tearDown() {
        subtypeStatic.close()
        threadStatic.close()
        sdkStatic.close()
    }

    private fun inputLogic(loader: DictionaryLoader = DictionaryLoader()) =
        InputLogic(ime, mock(SuggestionStripListener::class.java), loader)

    private fun fallbackSuggestion() = SuggestedWords.SuggestedWordInfo(
        "the", 10, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null,
    )

    /** BlackBerryIME's field initialiser builds InputLogic before onCreate; it must survive. */
    @Test
    fun imeConstruction_inputLogicBuildsWithoutAnEngine() {
        assertNotNull(inputLogic().mComposingTracker)
    }

    /** onCreate → initializeLearningManagers constructs the manager; it must survive. */
    @Test
    fun onCreate_learningManagerBuildsWithoutAnEngine() {
        assertNotNull(DynamicLearningManager(ctx))
    }

    /** loadSettings / updateDynamicLearningState flip the learning switch. */
    @Test
    fun learningSwitch_flipsWithoutAnEngine() {
        val dlm = DynamicLearningManager(ctx)
        dlm.setDynamicLearningEnabled(false)
        dlm.setDynamicLearningEnabled(true)
    }

    /** CommitController's commit ends in learn(); with no learner active it only clears. */
    @Test
    fun commit_learnWithoutActiveLearner_isANoOp() {
        val dlm = DynamicLearningManager(ctx)
        dlm.learn(fallbackSuggestion())
        dlm.learn(null)
        dlm.setDynamicLearningEnabled(false)
        dlm.learn(fallbackSuggestion(), false)
    }

    /** The learner the engine would have activated must not dereference the missing engine. */
    @Test
    fun commit_learnWithActiveLearner_learnsNothing() {
        val dlm = DynamicLearningManager(ctx)
        dlm.onManagedModeChanged(false)
        dlm.learn(fallbackSuggestion())
        dlm.learn(fallbackSuggestion(), true)
    }

    @Test
    fun contacts_areDroppedWithoutAnEngine() {
        val dlm = DynamicLearningManager(ctx)
        dlm.addContactsWords(arrayOf("alice"), ContactsType.ALL)
        // Over 500 defers to the background executor — the path the contacts sync actually takes.
        dlm.addContactsWords(Array(600) { "w$it" }, ContactsType.WORK)
        assertEquals(1, executor.tasks.size)
        executor.drain()
    }

    /** onDestroy still unregisters (nothing to unregister) and queues the personal-dictionary flush. */
    @Test
    fun destroy_withoutAnEngine_stillQueuesTheFlush() {
        val dlm = DynamicLearningManager(ctx)
        mockStatic(DictionaryManager::class.java).use { dict ->
            dict.`when`<DictionaryManager> { DictionaryManager.getInstance() }.thenReturn(mock(DictionaryManager::class.java))
            dlm.destroy()
            assertEquals(1, executor.tasks.size)
            executor.drain()
        }
    }

    /**
     * reloadDictionaryForSubtype → initDictionary installs FIX-D's fallback; a suggestion request
     * (requestSuggestionsWithContext: shift sync, then the engine query) then serves fallback words.
     */
    @Test
    fun suggestionRequest_servesFallbackWordsWithoutAnEngine() {
        val loader = DictionaryLoader()
        val logic = inputLogic(loader)
        loader.initDictionary(ctx, Locale.US, false, false, null)
        executor.drain()
        assertTrue(loader.mainDictionary is FallbackDictionary)

        // Auto-caps at the start of a field: requestSuggestionsWithContext pushes shift state 5.
        logic.mComposingTracker.setShiftStateIfNotComposing(5)
        var result: SuggestedWords? = null
        logic.mSuggestionEngine.getSuggestedWords(
            ctx, logic.mComposingTracker, PrevWordsInfo.EMPTY_PREV_WORDS_INFO, null, null, false, 0,
            SuggestionEngine.SuggestionCallback { result = it },
        )
        assertTrue("fallback words expected", (result?.size() ?: 0) > 0)
    }

    /** The first keyboard load syncs the layout into the engine; without one it must just skip. */
    @Test
    fun layoutSync_skipsTheEngineWithoutOne() {
        val switcher = KeyboardSwitcher.getInstance()
        val mkvField = KeyboardSwitcher::class.java.getDeclaredField("mainKeyboardView").apply { isAccessible = true }
        val subtypeField = KeyboardSwitcher::class.java.getDeclaredField("subtypeManager").apply { isAccessible = true }
        val oldMkv = mkvField.get(switcher)
        val oldSubtypes = subtypeField.get(switcher)
        try {
            val keyboard = mock(Keyboard::class.java)
            `when`(keyboard.isTouchKeyboard).thenReturn(false)
            val mkv = mock(MainKeyboardView::class.java)
            `when`(mkv.keyboard).thenReturn(keyboard)
            mkvField.set(switcher, mkv)
            subtypeField.set(switcher, subtypes)
            switcher.syncKeyboardLayout()
        } finally {
            mkvField.set(switcher, oldMkv)
            subtypeField.set(switcher, oldSubtypes)
        }
    }
}
