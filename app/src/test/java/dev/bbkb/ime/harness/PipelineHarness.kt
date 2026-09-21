package dev.bbkb.ime.harness

import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.engine.DictionaryLoader
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
 * Wires the REAL text pipeline (`InputLogic` + `RichInputConnection` + `ComposingTextTracker`)
 * to a [FakeEditor], so historical typing/newline/stale-text bugs can be replayed as
 * deterministic JVM tests. The engine is static-mocked (no native); the host IME is a
 * Mockito mock returning the fake editor.
 *
 * Lifecycle: construct inside a `@Before`, call [close] in `@After` (releases the static mocks).
 * See [TextPipelineScenariosTest] for usage; the four-copies checker is [FourCopies].
 */
class PipelineHarness {

    val editor = FakeEditor()
    val scheduler = FakeScheduler()

    private val sdkStatic: MockedStatic<NuanceSDKManager> =
        mockStatic(NuanceSDKManager::class.java).apply {
            // getGestureLock() is used in a synchronized() block by the bridge; a mockStatic
            // default of null would NPE there. Hand back a real monitor object.
            val lock = Any()
            `when`<Any> { NuanceSDKManager.getGestureLock() }.thenReturn(lock)
        }

    val ime: BlackBerryIME = mock(BlackBerryIME::class.java, ContextDelegatingAnswer())
    val inputLogic: InputLogic
    val settings: SettingsValues

    /**
     * The same mock [SuggestionStripListener] the real [InputLogic] was constructed with.
     * Exposed so a scenario can assert what the pipeline told the strip to do — the
     * `setNeutralSuggestionStrip()` call is the only externally visible trace of
     * `performRecorrection` bailing out, which the backspace path leans on.
     */
    val suggestionStripListener: SuggestionStripListener = mock(SuggestionStripListener::class.java)

    /**
     * The REAL [UIUpdateHandler], not a mock — so the deferred-work findings
     * (which are all "is this message in the cancel set?") can be asserted against an actual
     * message queue. Robolectric's default paused looper means nothing here ever runs:
     * messages queue, [android.os.Handler.hasMessages] observes them, and `removeMessages`
     * is proved to have taken effect. That is deliberate — *handling* a message calls back
     * into the mock IME and would NPE. This harness proves the cancel set, not the handlers.
     *
     * `initializeDelays()` is intentionally not called (it needs DeviceProfile + real
     * resources); with a paused looper a 0ms delay queues exactly like a real one.
     */
    val uiHandler: UIUpdateHandler

    /**
     * The REAL [SuggestionUpdater]. It is a plain final class over the IME whose request methods
     * only reach `mIme.uiUpdateHandler` and `mIme.getInputLogic()`, both of which this harness
     * already wires for real — so a genuine instance behaves correctly and its effects are
     * observable as queued messages on [uiHandler], which beats verifying calls on a mock.
     *
     * It exists because `commitVoiceInput` dereferences `mIme.suggestionUpdater` unconditionally;
     * without this the whole commitVoiceInput path NPE'd and `SS-1` could not be tested at all.
     * Note `requestDelayedLocaleAware` additionally reads a static locale and is not exercised
     * here; `requestDelayed` (the commitVoiceInput path) is clean.
     */
    val suggestionUpdater: SuggestionUpdater

    /**
     * The mock [DictionaryLoader] the pipeline learns and unlearns through. The revert and
     * prediction-mode backspace paths both call `unlearnWord`, and that call is the only
     * observable evidence that they took the "reject this word" branch.
     */
    val dictionaryLoader: DictionaryLoader

    init {
        // If anything below throws, the static mock must still be released or every later
        // test fails with "static mocking is already registered".
        try {
            val nuance = mock(NuanceSDK::class.java)
            // The composing tracker resolves script from the engine's primary language while
            // building the buffer; a bare mock returns null and NPEs in ScriptUtils.
            `when`(nuance.primaryLanguage).thenReturn(java.util.Locale.US)
            sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(nuance)

            `when`(ime.currentInputConnection).thenReturn(editor)

            uiHandler = UIUpdateHandler(ime)
            // The handler is reachable two ways and BOTH must resolve, or a fix is "verified"
            // against a null the production path never sees. `uiUpdateHandler` is @JvmField, so
            // Kotlin call sites compile to a direct field read that Mockito cannot intercept —
            // that one has to be set reflectively. Java call sites go through the real
            // getUiUpdateHandler() method, which does need a stub.
            BlackBerryIME::class.java.getDeclaredField("uiUpdateHandler")
                .apply { isAccessible = true }.set(ime, uiHandler)
            `when`(ime.getUiUpdateHandler()).thenReturn(uiHandler)

            // Same @JvmField story as uiUpdateHandler — no getter exists at all for this one,
            // so the reflective set is the only way production code can see it.
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
            // `inputLogic` is @JvmField too, so Kotlin call sites (the UIUpdateHandler's own
            // message branches among them) read the field directly and never see the getter stub.
            BlackBerryIME::class.java.getDeclaredField("inputLogic")
                .apply { isAccessible = true }.set(ime, inputLogic)
            // Every word commit ends in a learn() call. A no-op mock is the right stand-in:
            // these scenarios assert what reaches the EDITOR, and the real manager would drag
            // the DLM and its native learner into a JVM test.
            `when`(ime.dynamicLearningManager).thenReturn(mock(DynamicLearningManager::class.java))
            settings = buildSettings()
        } catch (t: Throwable) {
            sdkStatic.close()
            throw t
        }
    }

    /**
     * A [SettingsValues] carrying only the field the scoped entry points read
     * (`spacingAndPunctuation`, used by `hasWordAfterCursor`). Its real constructor reaches
     * SubtypeManager/RichInputMethodManager — far outside the text-core scope — so we allocate
     * the shell without running it and inject the one real collaborator.
     */
    private fun buildSettings(): SettingsValues = settingsWith()

    /**
     * A [SettingsValues] shell like [settings], with the named fields overridden.
     *
     * Everything not named keeps the JVM default the raw allocation gives it — `false` / `0` /
     * `null` — which is deliberate: a scenario should switch on exactly the settings it is
     * about, and a field it forgot reads as "off" rather than as whatever a real device had.
     *
     * Example: `settingsWith("isPredictionsEnabled" to true, "shouldShowLxxButton" to true)`.
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

    /**
     * Arm the pipeline against an editor pre-loaded with [text] and a collapsed cursor at
     * [cursor], with NO composing region — the normal starting point for a scenario.
     */
    fun startSession(text: String = "", cursor: Int = text.length) {
        editor.appSetText(text, cursor, cursor)
        editor.dropPendingSelectionEvents() // the setup itself is not an IME-visible event
        check(inputLogic.mRichInputConnection.resetConnection(cursor, cursor, false)) {
            "resetConnection failed during startSession"
        }
    }

    /**
     * Put the tracker into the composing state for [word] (as if the user had typed it),
     * without going through the full key path — enough to exercise the suggestion/commit
     * entry points. Mirrors InputLogic's own tracker seeding.
     */
    fun seedComposing(word: String) {
        val codePoints = word.codePoints().toArray()
        // Flattened (x,y) pairs — 2 ints per code point; no real geometry needed for the
        // composing buffer (coords drive prediction only), so use the no-coordinate sentinel.
        val coords = IntArray(codePoints.size * 2) { -1 }
        inputLogic.mComposingTracker.setComposingFromCodePoints(codePoints, coords)
    }

    /**
     * Deliver the next queued [FakeEditor.SelectionEvent] to `InputLogic.onUpdateSelection`,
     * exactly as the framework would after the editor applied a change — the asynchronous half
     * of every cursor-movement scenario. Returns what `onUpdateSelection` returned, or `null`
     * if nothing was queued.
     */
    fun deliverSelectionEvent(settingsValues: SettingsValues = settings): Boolean? {
        val e = editor.pollSelectionEvent() ?: return null
        return selectionChanged(e.oldSelStart, e.oldSelEnd, e.newSelStart, e.newSelEnd, settingsValues)
    }

    /**
     * Deliver an `onUpdateSelection` the editor did NOT generate — an external cursor move (the
     * user tapping elsewhere, an app repositioning the caret).
     *
     * Argument order is the framework's `InputMethodService.onUpdateSelection` order,
     * (oldStart, oldEnd, newStart, newEnd), which is what `BlackBerryIME` forwards positionally.
     * Note that `InputLogic.onUpdateSelection`'s own `@param` list documents a DIFFERENT order
     * (old start, new start, old end, new end); the javadoc is wrong, the code is right.
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

    /**
     * Make the static-mocked `NuanceSDKManager` gesture-pending flag actually STATEFUL, so tests
     * can observe it being set and cleared. Without this the static mock answers `false` to
     * `isGesturePending()` no matter what was set, and any assertion about the flag passes or
     * fails for the wrong reason.
     */
    fun enableStatefulGesturePending() {
        val pending = booleanArrayOf(false)
        sdkStatic.`when`<Unit> { NuanceSDKManager.noteGestureDeposit() }
            .thenAnswer { pending[0] = true; Unit }
        sdkStatic.`when`<Boolean> { NuanceSDKManager.isGesturePending() }
            .thenAnswer { pending[0] }
        sdkStatic.`when`<Boolean> { NuanceSDKManager.consumeGesturePending() }
            .thenAnswer { val was = pending[0]; pending[0] = false; was }
        // The commit handler consumes by sequence now (e37de97d); a legacy payload (seq <= 0, e.g.
        // SuggestedWords.EMPTY) falls back to consume-all, which is what this stub models.
        sdkStatic.`when`<Boolean> { NuanceSDKManager.consumeGestureSeq(org.mockito.ArgumentMatchers.anyInt()) }
            .thenAnswer { val was = pending[0]; pending[0] = false; was }
    }

    /** Snapshot of the current word as the four owners see it. */
    fun fourCopies(): FourCopies = FourCopies(
        editorText = editor.getText(),
        editorComposing = editor.getComposingText(),
        editorHasRegion = editor.hasComposingRegion(),
        ricCursorStart = inputLogic.mRichInputConnection.cursorStart,
        trackerComposing = inputLogic.mComposingTracker.composingText,
        trackerIsComposing = inputLogic.mComposingTracker.isComposing,
    )

    /**
     * The `MSG_*` values of [UIUpdateHandler], which are private there. Mirrored
     * rather than exposed because widening real visibility for a test is worse than a mirror
     * that [pendingMessages] keeps honest — a renumbering shows up as a scrambled test result.
     */
    object Msg {
        const val UPDATE_SUGGESTIONS = 2
        const val BATCH_INPUT_SUGGESTIONS = 6
        const val CANCEL_QUICK_SWITCH = 13
        const val UPDATE_GESTURE_SUGGESTIONS = 14
        const val UPDATE_JAPANESE_SUGGESTIONS = 15
        const val COMMIT_TEXT = 17
        const val SHOW_SUGGESTIONS = 3

        /** Everything `cancelPendingSuggestionUpdates()` is meant to clear. */
        val CANCEL_SET = mapOf(
            UPDATE_SUGGESTIONS to "MSG_UPDATE_SUGGESTIONS",
            BATCH_INPUT_SUGGESTIONS to "MSG_BATCH_INPUT_SUGGESTIONS",
            CANCEL_QUICK_SWITCH to "MSG_CANCEL_QUICK_SWITCH",
            UPDATE_GESTURE_SUGGESTIONS to "MSG_UPDATE_GESTURE_SUGGESTIONS",
            UPDATE_JAPANESE_SUGGESTIONS to "MSG_UPDATE_JAPANESE_SUGGESTIONS",
            COMMIT_TEXT to "MSG_COMMIT_TEXT",
        )
    }

    /**
     * arg2 of the queued MSG_SHOW_SUGGESTIONS — the session generation the reply was dispatched
     * under (audit DW-3). Robolectric's paused looper keeps the message inspectable.
     */
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
    fun close() {
        if (!closed) {
            closed = true
            // Robolectric shares one main looper across tests in a class; a message left queued
            // here would be observed by the next test's handler.
            uiHandler.removeCallbacksAndMessages(null)
            sdkStatic.close()
        }
    }
}
