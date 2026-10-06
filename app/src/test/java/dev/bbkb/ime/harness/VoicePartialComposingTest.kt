package dev.bbkb.ime.harness

import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceRecognitionManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

/**
 * Dictation partial results in the editor: shown as composing text while the user speaks,
 * replaced by the final result's commit, and left as plain text when the session ends without one.
 * Before this the partials were only logged, and the text appeared all at once at the end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class VoicePartialComposingTest {

    private lateinit var h: PipelineHarness

    /** Set only by the tests that drive a real dictation session; see [dictation]. */
    private var voiceController: VoiceInputController? = null

    @Before fun setUp() { h = PipelineHarness() }

    @After fun tearDown() {
        voiceController?.let {
            it.destroy()
            resetSettingsManager()
            PrefsManager.getPrefs(RuntimeEnvironment.getApplication()).edit().clear().commit()
            DeviceProfile.clearPendingInitForTest()
            ShadowSpeechRecognizer.reset()
        }
        h.close()
    }

    private fun settings() = h.settingsWith(
        "editorCapabilities" to EditorCapabilities(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                packageName = "com.example.app"
            },
            /* allowsAppSpecifiedCompletions = */ false,
            /* keyboardPackageName = */ "dev.bbkb.ime.debug",
            java.util.Locale.US,
            /* forceSuggestions = */ false,
        ),
    )

    /** What the final result's MSG_COMMIT_TEXT reaches: onTextInput -> commitVoiceInput. */
    private fun commitFinal(text: String) =
        h.inputLogic.commitVoiceInput(
            settings(), InputEvent.createTextInputEvent(text, 0), 0, /* fromVoice = */ true, h.uiHandler)

    @Test
    fun partialsComposeAndTheFinalResultReplacesThem() {
        h.startSession("Hi ", 3)

        h.inputLogic.setVoiceComposingText("hello")
        assertEquals("Hi hello", h.editor.getText())
        assertEquals("the partial is composing text", "hello", h.editor.getComposingText())

        h.inputLogic.setVoiceComposingText("hello wor")
        assertEquals("a newer partial replaces the older one", "Hi hello wor", h.editor.getText())

        commitFinal("hello world ")

        assertEquals("the final text replaces the partial, once", "Hi hello world ", h.editor.getText())
        assertFalse("nothing is left composing", h.editor.hasComposingRegion())
    }

    @Test
    fun anAbandonedSessionLeavesThePartialAsPlainText() {
        h.startSession("Hi ", 3)
        h.inputLogic.setVoiceComposingText("hello wor")

        h.inputLogic.finishVoiceComposingText()

        assertEquals("Hi hello wor", h.editor.getText())
        assertFalse(h.editor.hasComposingRegion())
    }

    @Test
    fun finishingWithNoPartialShownTouchesNothing() {
        h.startSession("Hi", 2)
        h.editor.setComposingText("yo", 1)
        h.seedComposing("yo")

        h.inputLogic.finishVoiceComposingText()

        assertTrue("a typed word's composing region is not the dictation's to finish", h.editor.hasComposingRegion())
        assertEquals("yo", h.editor.getComposingText())
    }

    @Test
    fun anEmptyFirstPartialShowsNothing() {
        h.startSession("Hi", 2)

        h.inputLogic.setVoiceComposingText("")

        assertEquals("Hi", h.editor.getText())
        assertFalse(h.editor.hasComposingRegion())
    }

    /**
     * A word being typed owns the composing region; the first partial commits it rather than
     * overwriting it, the way a final result with no partials before it always has.
     */
    @Test
    fun aTypedWordIsCommittedBeforeTheFirstPartial() {
        val s = settings()
        `when`(h.ime.getSettingsValues()).thenReturn(s)
        h.startSession()
        h.editor.setComposingText("ok", 1)
        h.seedComposing("ok")

        h.inputLogic.setVoiceComposingText("hello")

        assertEquals("okhello", h.editor.getText())
        assertEquals("hello", h.editor.getComposingText())
        assertFalse("the typed word is no longer composing", h.inputLogic.mComposingTracker.isComposing())
    }

    // ── "Show words as you speak", through the real dictation path ───────────

    /** Hand SettingsManager back as it was found; see VoiceDictationOffensiveWordsTest. */
    private fun resetSettingsManager() {
        val settings = SettingsManager.getInstance()
        runCatching { settings.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(settings, null)
        }
    }

    /**
     * A dictation session started through a real [VoiceInputController] on the harness IME, so a
     * partial travels the production route: Robolectric's recogniser -> [VoiceRecognitionManager]
     * (where the setting is read) -> the controller -> the real UIUpdateHandler -> InputLogic -> the
     * editor. The final result's MSG_COMMIT_TEXT reaches the mocked onTextInput, which is wired to
     * [commitFinal] the way the real IME's onTextInput reaches commitVoiceInput.
     */
    private fun dictation(showPartialResults: Boolean): ShadowSpeechRecognizer {
        val context = RuntimeEnvironment.getApplication()
        resetSettingsManager()
        PrefsManager.getPrefs(context).edit().clear()
            .putBoolean("voice_input_use_input_language", false)
            .putBoolean("voice_input_show_partial_results", showPartialResults)
            .commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        runCatching { SettingsManager.getInstance().unregisteredListener() }
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
        ShadowSpeechRecognizer.reset()

        doAnswer { commitFinal(it.getArgument(0)); null }.`when`(h.ime).onTextInput(anyString(), anyLong())
        val controller = VoiceInputController(h.ime, mock(VoiceInputController.Listener::class.java))
        voiceController = controller
        val manager = VoiceInputController::class.java.getDeclaredField("mRecognitionManager")
            .apply { isAccessible = true }.get(controller) as VoiceRecognitionManager
        manager.startDictation()
        idle()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Runs only the final result's queued MSG_COMMIT_TEXT. A full idle would go on to run the
     * suggestion update the commit queues, which this harness's mocked IME cannot service.
     */
    private fun deliverFinalCommit() = shadowOf(Looper.getMainLooper()).runOneTask()

    /** The on case through the same route, so the off case below is not passing on a dead path. */
    @Test
    fun withShowWordsAsYouSpeakOnPartialsReachTheEditorAsComposingText() {
        h.startSession("Hi ", 3)
        val recognizer = dictation(showPartialResults = true)

        recognizer.triggerOnPartialResults(results("hello"))
        recognizer.triggerOnPartialResults(results("hello wor"))
        idle()

        assertEquals("Hi hello wor", h.editor.getText())
        assertEquals("hello wor", h.editor.getComposingText())

        recognizer.triggerOnResults(results("hello world"))
        deliverFinalCommit()

        assertEquals("Hi hello world ", h.editor.getText())
        assertFalse(h.editor.hasComposingRegion())
    }

    @Test
    fun withShowWordsAsYouSpeakOffNoPartialReachesTheEditorAndTheFinalResultCommitsOnce() {
        h.startSession("Hi ", 3)
        val recognizer = dictation(showPartialResults = false)

        recognizer.triggerOnPartialResults(results("hello"))
        recognizer.triggerOnPartialResults(results("hello wor"))
        idle()

        assertEquals("no partial text reaches the editor", "Hi ", h.editor.getText())
        assertFalse("nothing is composing", h.editor.hasComposingRegion())

        recognizer.triggerOnResults(results("hello world"))
        deliverFinalCommit()

        assertEquals("the final result is committed once", "Hi hello world ", h.editor.getText())
        assertFalse(h.editor.hasComposingRegion())
        verify(h.ime, times(1)).onTextInput(anyString(), anyLong())
    }
}
