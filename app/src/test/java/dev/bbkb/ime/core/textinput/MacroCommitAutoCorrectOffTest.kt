package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.harness.PipelineHarness
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins the substitution (macro, kind 7) escape at commit time: a correction commits when
 * `acWord != null && (kind == 7 || (acEnabled && kind != 0))`, so macros work with auto-correct
 * off. Covers both sites ([InputLogic.handleSeparatorInput] and
 * [CommitController.autoCorrectAndCommitExtended], which re-derives the word), the editor gate,
 * and kind 2 (`teh → the`) still obeying the setting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class MacroCommitAutoCorrectOffTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    /** `SuggestedWordInfo` kind for a substitution/macro. */
    private val KIND_SUBSTITUTION = 7

    /** `SuggestedWordInfo` kind for an ordinary spelling correction. */
    private val KIND_CORRECTION = 2

    @Before
    fun setUp() {
        h = PipelineHarness()
        // The commit path reaches SubtypeManager.getInstance() (locale) and the SettingsManager singleton.
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    /**
     * A real [EditorCapabilities] for [inputType], since the editor gate is the predicate it computes.
     * [forceSuggestions] is the user's "Force suggestions" override.
     */
    private fun editorCaps(inputType: Int, forceSuggestions: Boolean = false): EditorCapabilities =
        EditorCapabilities(
            EditorInfo().apply { this.inputType = inputType; packageName = "com.example.app" },
            /* allowsAppSpecifiedCompletions = */ false,
            /* keyboardPackageName = */ "dev.bbkb.ime.debug",
            Locale.US,
            forceSuggestions,
        )

    /** An ordinary message field: multi-line text, suggestions allowed. */
    private val NORMAL_FIELD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    private fun settings(
        autoCorrect: Boolean,
        caps: EditorCapabilities = editorCaps(NORMAL_FIELD),
    ): SettingsValues =
        h.settingsWith(
            "isAutoCorrectionEnabledPerUserSettings" to autoCorrect,
            "editorCapabilities" to caps,
        )

    /**
     * Arm a composing word plus the auto-correction candidate on the tracker.
     * `setAutoCorrection`'s second argument is the candidate's kind, not a score.
     */
    private fun armComposing(typed: String, candidate: String, kind: Int) {
        h.startSession("", 0)
        h.inputLogic.mRichInputConnection.setComposingText(typed, 1)
        h.seedComposing(typed)
        h.editor.dropPendingSelectionEvents()
        h.inputLogic.mComposingTracker.setAutoCorrection(candidate, kind)
    }

    /**
     * Sends a space through the private `InputLogic.handleSeparatorInput` (by reflection), the gate
     * that picks the candidate or the typed word.
     */
    private fun separator(settings: SettingsValues) {
        val event = InputEvent.createKeyPress(' '.code, -1, -4, -4, 1000L, false)
        val ctx = InputEventContext(settings, event, 1000L, 0, 0)
        ctx.setInputSource(InputSource.HARDWARE)
        val m = InputLogic::class.java.getDeclaredMethod(
            "handleSeparatorInput",
            InputEvent::class.java,
            InputEventContext::class.java,
            UIUpdateHandler::class.java,
        ).apply { isAccessible = true }
        m.invoke(h.inputLogic, event, ctx, h.uiHandler)
    }

    /** The second commit site, reached through the private `mCommitController`. */
    private fun commitController(): CommitController =
        InputLogic::class.java.getDeclaredField("mCommitController")
            .apply { isAccessible = true }.get(h.inputLogic) as CommitController

    /** Text the editor ended up with, trailing separator included. */
    private fun committed(): String = h.editor.text

    @Test
    fun separator_macroKind7_commitsExpansion_withAutoCorrectOff() {
        // With auto-correct off, `bb` must still expand.
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(settings(autoCorrect = false))
        assertEquals("BlackBerry ", committed())
    }

    @Test
    fun separator_macroKind7_commitsExpansion_withAutoCorrectOn() {
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(settings(autoCorrect = true))
        assertEquals("BlackBerry ", committed())
    }

    @Test
    fun separator_ordinaryCorrection_stillObeysTheSetting_whenOff() {
        // The kind-7 escape must not leak into spelling correction: with the setting off, `teh` stays.
        armComposing("teh", "the", KIND_CORRECTION)
        separator(settings(autoCorrect = false))
        assertEquals("teh ", committed())
    }

    @Test
    fun separator_ordinaryCorrection_stillCorrects_whenOn() {
        armComposing("teh", "the", KIND_CORRECTION)
        separator(settings(autoCorrect = true))
        assertEquals("the ", committed())
    }

    @Test
    fun separator_kindZeroCandidate_neverCommits_evenWithAutoCorrectOn() {
        // Kind 0 is the typed word and is rejected. The candidate differs from the typed word so the
        // assertion can tell them apart.
        armComposing("hello", "hellos", 0)
        separator(settings(autoCorrect = true))
        assertEquals("hello ", committed())
    }

    // Site 2: the commit controller picks the word again from the tracker, so it is reached
    // directly; a fix applied only at site 1 still fails here.

    @Test
    fun commitController_macroKind7_commitsExpansion_withAutoCorrectOff() {
        armComposing("alot", "a lot", KIND_SUBSTITUTION)
        commitController().autoCorrectAndCommitExtended(
            settings(autoCorrect = false), " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("a lot ", committed())
    }

    @Test
    fun commitController_ordinaryCorrection_stillObeysTheSetting_whenOff() {
        armComposing("teh", "the", KIND_CORRECTION)
        commitController().autoCorrectAndCommitExtended(
            settings(autoCorrect = false), " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("teh ", committed())
    }

    @Test
    fun commitController_ordinaryCorrection_stillCorrects_whenOn() {
        armComposing("teh", "the", KIND_CORRECTION)
        commitController().autoCorrectAndCommitExtended(
            settings(autoCorrect = true), " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("the ", committed())
    }

    @Test
    fun commitController_dateMacro_commitsExpansion_withAutoCorrectOff() {
        // `%D` is a shipped dynamic macro; it arrives already expanded by the personal-dictionary lookup.
        armComposing("%D", "2026-09-15", KIND_SUBSTITUTION)
        commitController().autoCorrectAndCommitExtended(
            settings(autoCorrect = false), " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("2026-09-15 ", committed())
    }

    // The editor gate: a macro must not rewrite text in a field that asked for no suggestions.

    @Test
    fun separator_macro_doesNotExpand_inPasswordField() {
        // `bb` is a plausible password fragment; committing `BlackBerry` would corrupt a hidden credential.
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = false,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            ),
        )
        assertEquals("bb ", committed())
    }

    @Test
    fun separator_macro_doesNotExpand_inPasswordField_evenWithAutoCorrectOn() {
        // Guards the wrong fix `(kind == 7 && allowed) || (acEnabled && kind != 0)`: a blocked kind-7
        // candidate falls through the second disjunct and expands whenever auto-correct is on.
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = true,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            ),
        )
        assertEquals("bb ", committed())
    }

    @Test
    fun separator_macro_doesNotExpand_whenEditorAsksForNoSuggestions() {
        armComposing("alot", "a lot", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = false,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
            ),
        )
        assertEquals("alot ", committed())
    }

    @Test
    fun separator_macro_doesNotExpand_inEmailAddressField() {
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = false,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
            ),
        )
        assertEquals("bb ", committed())
    }

    @Test
    fun separator_macro_doesNotExpand_inUriField() {
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = false,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            ),
        )
        assertEquals("bb ", committed())
    }

    @Test
    fun separator_macro_expandsAgain_whenTheUserForcesSuggestions() {
        // "Force suggestions" overrides an app's no-suggestions declaration; EditorCapabilities honours
        // it, so the gate does too.
        armComposing("alot", "a lot", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = false,
                caps = editorCaps(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    forceSuggestions = true,
                ),
            ),
        )
        assertEquals("a lot ", committed())
    }

    @Test
    fun separator_macro_stillBlockedInPasswordField_evenWhenTheUserForcesSuggestions() {
        // The override stops at passwords (EditorCapabilities ANDs `!isPassword` into it).
        armComposing("bb", "BlackBerry", KIND_SUBSTITUTION)
        separator(
            settings(
                autoCorrect = false,
                caps = editorCaps(
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    forceSuggestions = true,
                ),
            ),
        )
        assertEquals("bb ", committed())
    }

    @Test
    fun commitController_macro_doesNotExpand_inPasswordField() {
        // Site 2 needs the gate independently: a fix only in handleSeparatorInput still leaks via the
        // text-input/voice paths.
        armComposing("alot", "a lot", KIND_SUBSTITUTION)
        commitController().autoCorrectAndCommitExtended(
            settings(
                autoCorrect = false,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            ),
            " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("alot ", committed())
    }

    @Test
    fun commitController_macro_doesNotExpand_inPasswordField_evenWithAutoCorrectOn() {
        armComposing("alot", "a lot", KIND_SUBSTITUTION)
        commitController().autoCorrectAndCommitExtended(
            settings(
                autoCorrect = true,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            ),
            " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("alot ", committed())
    }

    @Test
    fun commitController_macro_doesNotExpand_whenEditorAsksForNoSuggestions() {
        armComposing("%D", "2026-09-15", KIND_SUBSTITUTION)
        commitController().autoCorrectAndCommitExtended(
            settings(
                autoCorrect = false,
                caps = editorCaps(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
            ),
            " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("%D ", committed())
    }
}
