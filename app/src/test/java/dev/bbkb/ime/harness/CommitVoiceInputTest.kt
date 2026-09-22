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
 * Executable proof for `SS-1` of the Phase 2 audit
 * (docs/2026-07_pipeline-audit-findings_reference.md).
 *
 * `commitVoiceInput` is the entry point for TEXT_INPUT events — the route a single-char BMP
 * emoji from the palette takes (U+2705 and friends, fully-qualified emoji without VS16; the
 * supplementary-plane ones go through `onCodeInput` instead). When a word is composing, the
 * payload was committed twice: once by the separator-append gate inside `commitWordExtended`
 * — `isWordCharacter` reports symbols as separators — and again by `commitVoiceInput`'s own
 * unconditional `commitText`. Typing "k" and tapping ✅ produced "k✅✅".
 *
 * This suite exists at all only because the harness can now build a real `SuggestionUpdater`;
 * `commitVoiceInput` dereferences `mIme.suggestionUpdater` unconditionally and used to NPE here.
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
     * An ordinary message field. `commitVoiceInput` reaches
     * `CommitController.autoCorrectAndCommitExtended`, which now reads
     * `editorCapabilities.shouldShowSuggestions` to decide whether a macro may expand - so these
     * scenarios have to say which editor they are in. [PipelineHarness.settingsWith] leaves any
     * field it is not given at its JVM default, and for an object field that default is `null`,
     * not "off": before this, the suite NPE'd.
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
        // The non-composing branch never reached commitWordExtended, so it was always correct.
        // Asserted so a fix to the composing branch cannot silently break this one.
        h.startSession("hi ", 3)

        commitTextInput(checkMark)

        assertEquals("hi $checkMark", h.editor.getText())
    }

    @Test
    fun ss1_multiCodePointTextInput_isCommittedOnce() {
        // commitWordExtended's gate requires codePointCount == 1, so a longer payload was never
        // double-committed. Pins that, since the fix must not start dropping these.
        h.startSession()
        h.editor.setComposingText("k", 1)
        h.seedComposing("k")

        commitTextInput(":-)")

        assertEquals("k:-)", h.editor.getText())
    }

    @Test
    fun ss1_wordCharacterPayloadWhileComposing_isCommittedOnce() {
        // A letter payload is NOT a separator, so commitWordExtended's gate does fire for it too
        // (isWordCharacter || z). Pinned to catch a fix that keys off the wrong half of the gate.
        h.startSession()
        h.editor.setComposingText("k", 1)
        h.seedComposing("k")

        commitTextInput("a")

        assertEquals("ka", h.editor.getText())
    }
}
