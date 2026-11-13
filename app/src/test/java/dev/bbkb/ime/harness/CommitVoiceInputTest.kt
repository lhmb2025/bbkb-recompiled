package dev.bbkb.ime.harness

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins that `commitVoiceInput` (TEXT_INPUT events, e.g. a single-BMP palette emoji like U+2705)
 * commits its payload once while a word is composing, despite the separator-append gate in
 * `commitWordExtended` treating symbols as separators.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CommitVoiceInputTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    /** A single-BMP-code-point emoji: `Character.getType` is OTHER_SYMBOL, so it is a separator. */
    private val checkMark = "✅"

    /**
     * An ordinary message field. `autoCorrectAndCommitExtended` reads
     * `editorCapabilities.shouldShowSuggestions`, and an unset object field is `null` (NPE).
     */
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

    private fun commitTextInput(payload: String) =
        h.inputLogic.commitVoiceInput(
            settings(),
            InputEvent.createTextInputEvent(payload, 0),
            /* symbolPageOrder = */ 0,
            /* fromVoice = */ false,
            h.uiHandler,
        )

    @Test
    fun ss1_emojiWhileComposing_isCommittedExactlyOnce() {
        h.startSession()
        h.editor.setComposingText("k", 1)
        h.seedComposing("k")

        commitTextInput(checkMark)

        assertEquals(
            "single-code-point symbol was committed twice while composing (SS-1)",
            "k$checkMark",
            h.editor.getText(),
        )
    }

    @Test
    fun ss1_emojiWithNoComposingWord_isStillCommittedOnce() {
        // The non-composing branch never reaches commitWordExtended.
        h.startSession("hi ", 3)

        commitTextInput(checkMark)

        assertEquals("hi $checkMark", h.editor.getText())
    }

    @Test
    fun ss1_multiCodePointTextInput_isCommittedOnce() {
        // commitWordExtended's gate requires codePointCount == 1, so longer payloads bypass it.
        h.startSession()
        h.editor.setComposingText("k", 1)
        h.seedComposing("k")

        commitTextInput(":-)")

        assertEquals("k:-)", h.editor.getText())
    }

    @Test
    fun ss1_wordCharacterPayloadWhileComposing_isCommittedOnce() {
        // A letter payload also fires the gate (isWordCharacter || z); catches a fix keyed
        // off the wrong half of it.
        h.startSession()
        h.editor.setComposingText("k", 1)
        h.seedComposing("k")

        commitTextInput("a")

        assertEquals("ka", h.editor.getText())
    }
}
