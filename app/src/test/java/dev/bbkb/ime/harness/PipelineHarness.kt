package dev.bbkb.ime.harness

import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.EngineFacts
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.suggestion.SuggestionUpdater
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener
import com.blackberry.nuanceshim.NuanceSDK
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RuntimeEnvironment

/**
 * Wires the real text pipeline (`InputLogic`, `RichInputConnection`, `ComposingTextTracker`) to a
 * [FakeEditor] as deterministic JVM tests. The engine is static-mocked; the IME is a Mockito mock.
 * Construct in `@Before`, call [close] in `@After` (releases the static mocks).
 */
class PipelineHarness {

    val editor = FakeEditor()
    val scheduler = FakeScheduler()

    private val sdkStatic: MockedStatic<NuanceSDKManager> =
        mockStatic(NuanceSDKManager::class.java)

    val ime: BlackBerryIME = mock(BlackBerryIME::class.java, ContextDelegatingAnswer())
    val inputLogic: InputLogic
    val settings: SettingsValues

    /**
     * The strip listener [InputLogic] was built with. `setNeutralSuggestionStrip()` is the only
     * visible trace of `performRecorrection` bailing out.
     */
    val suggestionStripListener: SuggestionStripListener = mock(SuggestionStripListener::class.java)

    /**
     * The real [UIUpdateHandler]. Robolectric's paused looper never runs its messages (handling one
     * would NPE on the mock IME), so tests prove the queue and cancel set, not the handlers.
     * `initializeDelays()` is not called (needs DeviceProfile); a 0ms delay queues the same way.
     */
    val uiHandler: UIUpdateHandler

    /**
     * The real [SuggestionUpdater]; its effects show up as queued messages on [uiHandler].
     * `commitVoiceInput` dereferences it unconditionally. `requestDelayedLocaleAware` reads a
     * static locale and is not exercised here.
     */
    val suggestionUpdater: SuggestionUpdater

    /** Mock loader; `unlearnWord` is the only evidence the revert/backspace "reject" branch ran. */
    val dictionaryLoader: DictionaryLoader

    init {
        // If anything below throws, the static mock must still be released or every later
        // test fails with "static mocking is already registered".
        try {
            // Engine ops run inline on the test thread so scenarios stay synchronous.
            EngineThreads.setInlineForTests(true)
            val nuance = mock(NuanceSDK::class.java)
            // The composing tracker resolves script from the engine's primary language while
            // building the buffer; a bare mock returns null and NPEs in ScriptUtils.
            `when`(nuance.primaryLanguage).thenReturn(java.util.Locale.US)
            sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(nuance)
            // EngineOps resolve the engine via sdkOrWarn and the main thread reads facts();
            // a static mock answers null for both unless stubbed.
            sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.sdkOrWarn(org.mockito.ArgumentMatchers.anyString()) }
                .thenReturn(nuance)
            sdkStatic.`when`<EngineFacts> { NuanceSDKManager.facts() }.thenReturn(FACTS_EN_US)

            `when`(ime.currentInputConnection).thenReturn(editor)

            uiHandler = UIUpdateHandler(ime)
            // Both access paths must resolve: `uiUpdateHandler` is @JvmField, so Kotlin callers
            // read the field directly (set reflectively); Java callers use the getter (stubbed).
            BlackBerryIME::class.java.getDeclaredField("uiUpdateHandler")
                .apply { isAccessible = true }.set(ime, uiHandler)
            `when`(ime.getUiUpdateHandler()).thenReturn(uiHandler)

            // @JvmField with no getter, so only a reflective set reaches production code.
            suggestionUpdater = SuggestionUpdater(ime)
            BlackBerryIME::class.java.getDeclaredField("suggestionUpdater")
                .apply { isAccessible = true }.set(ime, suggestionUpdater)

            dictionaryLoader = mock(DictionaryLoader::class.java)
            inputLogic = InputLogic(
                ime,
                suggestionStripListener,
                dictionaryLoader,
            )
            `when`(ime.getInputLogic()).thenReturn(inputLogic)
            // @JvmField too: Kotlin callers (UIUpdateHandler among them) bypass the getter stub.
            BlackBerryIME::class.java.getDeclaredField("inputLogic")
                .apply { isAccessible = true }.set(ime, inputLogic)
            // Every commit ends in learn(); a no-op mock keeps the native DLM out of the JVM.
            `when`(ime.dynamicLearningManager).thenReturn(mock(DynamicLearningManager::class.java))
            settings = buildSettings()
        } catch (t: Throwable) {
            EngineThreads.setInlineForTests(false)
            sdkStatic.close()
            throw t
        }
    }

    /**
     * A [SettingsValues] allocated without its constructor (which reaches SubtypeManager), with
     * only `spacingAndPunctuation` injected.
     */
    private fun buildSettings(): SettingsValues = settingsWith()

    /**
     * A [SettingsValues] shell like [settings] with the named fields overridden; every other field
     * reads as off (`false`/`0`/`null`). Example: `settingsWith("isPredictionsEnabled" to true)`.
     */
    fun settingsWith(vararg overrides: Pair<String, Any?>): SettingsValues {
        val ctx = RuntimeEnvironment.getApplication()
        val sp = dev.bbkb.ime.core.settings.util
            .SpacingAndPunctuation(ctx.resources)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        val sv = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, SettingsValues::class.java) as SettingsValues
        val f = SettingsValues::class.java.getDeclaredField("spacingAndPunctuation")
        f.isAccessible = true
        f.set(sv, sp)
        for ((name, value) in overrides) {
            SettingsValues::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(sv, value)
        }
        return sv
    }

    /** Arm the pipeline on an editor holding [text], cursor at [cursor], no composing region. */
    fun startSession(text: String = "", cursor: Int = text.length) {
        editor.appSetText(text, cursor, cursor)
        editor.dropPendingSelectionEvents() // the setup itself is not an IME-visible event
        check(inputLogic.mRichInputConnection.resetConnection(cursor, cursor, false)) {
            "resetConnection failed during startSession"
        }
    }

    /** Put the tracker into the composing state for [word] without going through the key path. */
    fun seedComposing(word: String) {
        val codePoints = word.codePoints().toArray()
        // Flattened (x,y) pairs per code point; -1 is the no-coordinate sentinel.
        val coords = IntArray(codePoints.size * 2) { -1 }
        inputLogic.mComposingTracker.setComposingFromCodePoints(codePoints, coords)
    }

    /**
     * Deliver the next queued [FakeEditor.SelectionEvent] to `InputLogic.onUpdateSelection`.
     * Returns its result, or `null` if nothing was queued.
     */
    fun deliverSelectionEvent(settingsValues: SettingsValues = settings): Boolean? {
        val e = editor.pollSelectionEvent() ?: return null
        return selectionChanged(e.oldSelStart, e.oldSelEnd, e.newSelStart, e.newSelEnd, settingsValues)
    }

    /**
     * Deliver an external `onUpdateSelection` (user tap, app moving the caret), in the framework's
     * (oldStart, oldEnd, newStart, newEnd) order. `InputLogic.onUpdateSelection`'s javadoc lists a
     * different order; the javadoc is wrong.
     */
    fun selectionChanged(
        oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int,
        settingsValues: SettingsValues = settings,
    ): Boolean = inputLogic.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, settingsValues)

    /** Deliver a suggestion round-trip reply into InputLogic (the worker-thread callback). */
    fun deliverSuggestions(words: List<String>, willAutoCorrect: Boolean = false) {
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, w) in words.withIndex()) {
            list.add(
                SuggestedWords.SuggestedWordInfo(
                    w, 100 - i, /* kindAndFlags */ 0,
                    Dictionary.DICTIONARY_USER_TYPED, -1, -1, null,
                ),
            )
        }
        val sw = SuggestedWords(list, false, willAutoCorrect, 0)
        inputLogic.onSuggestionsReceived(sw, settings, ime.uiUpdateHandler)
    }

    /** Make the static-mocked gesture-pending flag stateful (otherwise it always reads `false`). */
    fun enableStatefulGesturePending() {
        val pending = booleanArrayOf(false)
        sdkStatic.`when`<Unit> { NuanceSDKManager.noteGestureDeposit() }
            .thenAnswer { pending[0] = true; Unit }
        sdkStatic.`when`<Boolean> { NuanceSDKManager.isGesturePending() }
            .thenAnswer { pending[0] }
        sdkStatic.`when`<Boolean> { NuanceSDKManager.consumeGesturePending() }
            .thenAnswer { val was = pending[0]; pending[0] = false; was }
        // A payload with seq <= 0 (e.g. SuggestedWords.EMPTY) consumes all, which this stub models.
        sdkStatic.`when`<Boolean> { NuanceSDKManager.consumeGestureSeq(org.mockito.ArgumentMatchers.anyInt()) }
            .thenAnswer { val was = pending[0]; pending[0] = false; was }
    }

    /** Snapshot of the current word as the four owners see it. */
    fun fourCopies(): FourCopies = FourCopies(
        editorText = editor.getText(),
        editorComposing = editor.getComposingText(),
        editorHasRegion = editor.hasComposingRegion(),
        editorSelStart = editor.getSelStart(),
        ricCursorStart = inputLogic.mRichInputConnection.cursorStart,
        ricRegionSynced = inputLogic.mRichInputConnection.isComposingRegionSynced,
        trackerComposing = inputLogic.mComposingTracker.composingText,
        trackerIsComposing = inputLogic.mComposingTracker.isComposing,
    )

    /** Mirror of [UIUpdateHandler]'s private `MSG_*` values; a renumbering scrambles test results. */
    object Msg {
        const val UPDATE_SUGGESTIONS = 2
        const val BATCH_INPUT_SUGGESTIONS = 6
        const val UPDATE_GESTURE_SUGGESTIONS = 14
        const val UPDATE_JAPANESE_SUGGESTIONS = 15
        const val COMMIT_TEXT = 17
        const val SHOW_SUGGESTIONS = 3

        /** What `cancelPendingSuggestionUpdates()` clears: the update requests, nothing else. */
        val SUGGESTION_UPDATE_SET = mapOf(
            UPDATE_SUGGESTIONS to "MSG_UPDATE_SUGGESTIONS",
            UPDATE_GESTURE_SUGGESTIONS to "MSG_UPDATE_GESTURE_SUGGESTIONS",
            UPDATE_JAPANESE_SUGGESTIONS to "MSG_UPDATE_JAPANESE_SUGGESTIONS",
        )

        /** What `cancelPendingTextCommits()` clears at teardown: the messages that type text. */
        val TEXT_COMMIT_SET = mapOf(
            BATCH_INPUT_SUGGESTIONS to "MSG_BATCH_INPUT_SUGGESTIONS",
            COMMIT_TEXT to "MSG_COMMIT_TEXT",
        )

        /** Everything a session teardown is meant to clear. */
        val CANCEL_SET = SUGGESTION_UPDATE_SET + TEXT_COMMIT_SET
    }

    /** arg2 of the queued MSG_SHOW_SUGGESTIONS: the session generation the reply was dispatched under. */
    fun pendingShowSuggestionsGeneration(): Int {
        val q = android.os.Looper.myLooper()!!.queue
        val f = android.os.MessageQueue::class.java.getDeclaredField("mMessages")
            .apply { isAccessible = true }
        var m = f.get(q) as android.os.Message?
        while (m != null) {
            if (m.what == Msg.SHOW_SUGGESTIONS) return m.arg2
            m = android.os.Message::class.java.getDeclaredField("next")
                .apply { isAccessible = true }.get(m) as android.os.Message?
        }
        error("no MSG_SHOW_SUGGESTIONS queued")
    }

    /** Names of the cancel-set messages currently queued on [uiHandler]. */
    fun pendingMessages(): Set<String> =
        Msg.CANCEL_SET.filterKeys { uiHandler.hasMessages(it) }.values.toSet()

    /** Queue every cancel-set message, so a teardown can be asserted to clear all of them. */
    fun queueAllCancelSetMessages() {
        for (what in Msg.CANCEL_SET.keys) uiHandler.sendMessage(uiHandler.obtainMessage(what))
    }

    private var closed = false
    companion object {
        /** What a loaded US English engine publishes; the harness's engine mock speaks en_US. */
        @JvmField
        val FACTS_EN_US = EngineFacts(
            primaryLanguage = java.util.Locale.US,
            languages = listOf(java.util.Locale.US),
            isCurrLocaleChinese = false,
            isChineseStrokeMode = false,
            isChineseCangjieMode = false,
            isChineseBPMFMode = false,
            defaultWordSeparator = " ",
            isDLMReady = true,
            isDLMAttached = true,
        )
    }

    fun close() {
        if (!closed) {
            closed = true
            // Robolectric shares one main looper across tests in a class; a message left queued
            // here would be observed by the next test's handler.
            uiHandler.removeCallbacksAndMessages(null)
            EngineThreads.setInlineForTests(false)
            sdkStatic.close()
        }
    }
}
