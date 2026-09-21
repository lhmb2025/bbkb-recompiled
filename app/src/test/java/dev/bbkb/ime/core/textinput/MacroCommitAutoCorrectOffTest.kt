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
 * FIX-MACRO / divergence D-3 — the kind-7 escape hatch at commit time.
 *
 * A **substitution** (a user macro or a shipped one from `assets/substitution_macros`, e.g.
 * `i → I`, `bb → BlackBerry`, `alot → a lot`, the `%D`/`%T` date macros) reaches the commit
 * path as an auto-correction candidate whose *kind* is 7. The original app committed it
 * regardless of the auto-correct setting:
 *
 * ```
 * // sources/dev/bbkb/ime/core/c/a.java:461  (take-the-typed-word branch)
 * if (this.f.t() == null || (iU != 7 && (!dVar.ab || iU == 0))) { ...commit typed word... }
 * ```
 *
 * i.e. the correction commits when `acWord != null && (kind == 7 || (acEnabled && kind != 0))`.
 * We had dropped the `kind == 7` disjunct at both commit sites, so switching auto-correct off
 * silently switched every macro off with it.
 *
 * These scenarios pin the four corners of that truth table at BOTH commit sites — the
 * decision in [InputLogic.handleSeparatorInput] and the word-selection in
 * [CommitController.autoCorrectAndCommitExtended], which re-derives it independently.
 *
 * Kind 2 (`CORRECTION`, e.g. `teh → the`) stands in for ordinary spelling auto-correction:
 * it must keep obeying the setting exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class MacroCommitAutoCorrectOffTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    /** `SuggestedWordInfo` kind for a substitution/macro — the escape hatch this file is about. */
    private val KIND_SUBSTITUTION = 7

    /** `SuggestedWordInfo` kind for an ordinary spelling correction. */
    private val KIND_CORRECTION = 2

    @Before
    fun setUp() {
        h = PipelineHarness()
        // Same two seams BackspaceScenariosTest needs: the commit path reaches
        // SubtypeManager.getInstance() (locale) and the SettingsManager singleton.
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

    // ── driving the two commit sites ────────────────────────────────────────────

    /**
     * A real [EditorCapabilities] for a field of [inputType], not a stub: the whole point of the
     * editor gate is the predicate this class computes from the input type, so a scenario has to
     * go through it. [forceSuggestions] is the user's "Force suggestions" override.
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
     * Arm a live composing word plus the auto-correction candidate the suggestion round-trip
     * would have stamped on the tracker. `setAutoCorrection`'s second argument is the candidate's
     * KIND, not a score — see the F11 note at `InputLogic.onSuggestionsReceived`.
     */
    private fun armComposing(typed: String, candidate: String, kind: Int) {
        h.startSession("", 0)
        h.inputLogic.mRichInputConnection.setComposingText(typed, 1)
        h.seedComposing(typed)
        h.editor.dropPendingSelectionEvents()
        h.inputLogic.mComposingTracker.setAutoCorrection(candidate, kind)
    }

    /**
     * Send a space through `InputLogic.handleSeparatorInput`, the gate that decides whether the
     * candidate or the typed word is committed. The method is private and its only caller,
     * `routeKeyEvent`, is private too, so reflection is the only way in — the same tactic
     * `PipelineHarness` already uses for the `@JvmField` back-references.
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

    /**
     * `InputLogic.mCommitController` is private; the second commit site has to be reached
     * through it because `autoCorrectAndCommitExtended` re-derives the word to commit.
     */
    private fun commitController(): CommitController =
        InputLogic::class.java.getDeclaredField("mCommitController")
            .apply { isAccessible = true }.get(h.inputLogic) as CommitController

    /** Text the editor ended up with, trailing separator included. */
    private fun committed(): String = h.editor.text

    // ── D-3, site 1: InputLogic.handleSeparatorInput ────────────────────────────

    @Test
    fun separator_macroKind7_commitsExpansion_withAutoCorrectOff() {
        // The bug: with auto-correct off the gate was `acEnabled && kind != 0`, so `bb` was
        // committed verbatim and every shipped macro looked broken.
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
        // The other half of the contract: the kind-7 escape must not leak into ordinary
        // spelling correction. With the setting off, `teh` stays `teh`.
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
        // Kind 0 is "the word the user typed"; `iU == 0` is a reject in the original too.
        // The candidate deliberately differs from the typed word, or committing either one
        // would produce the same text and the assertion would prove nothing.
        armComposing("hello", "hellos", 0)
        separator(settings(autoCorrect = true))
        assertEquals("hello ", committed())
    }

    // ── D-3, site 2: CommitController.autoCorrectAndCommitExtended ──────────────
    //
    // InputLogic having decided to auto-correct is not enough: the commit controller picks the
    // word again from the tracker, and it consulted the setting a second time. Reached here
    // directly, so a fix applied only at site 1 still fails.

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
        // `%D` is one of the shipped dynamic macros; it reaches this path exactly like `bb`,
        // already expanded to its text by the personal-dictionary lookup.
        armComposing("%D", "2026-09-15", KIND_SUBSTITUTION)
        commitController().autoCorrectAndCommitExtended(
            settings(autoCorrect = false), " ", h.uiHandler, InputSource.HARDWARE, false,
        )
        assertEquals("2026-09-15 ", committed())
    }

    // ── the editor gate: a macro must not rewrite what was typed in a field that asked ──
    // ── for no suggestions. A DELIBERATE DIVERGENCE from the original, which expands   ──
    // ── a macro in a password box as readily as in a message.                          ──

    @Test
    fun separator_macro_doesNotExpand_inPasswordField() {
        // The reason this gate exists: `bb` is a plausible fragment of a password, and silently
        // committing `BlackBerry` instead corrupts a credential the user cannot see.
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
        // Discriminating against the obvious wrong fix. Folding the editor term into the
        // original's single expression as `(kind == 7 && allowed) || (acEnabled && kind != 0)`
        // lets a blocked kind-7 candidate fall straight through the second disjunct - kind 7 is
        // non-zero - so the macro expands anyway whenever auto-correct happens to be on.
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
        // "Force suggestions" is the user's own override of an app's no-suggestions declaration.
        // EditorCapabilities already honours it, so the gate inherits it: anyone who wants macros
        // in those fields still has a switch.
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
        // The override deliberately stops at passwords - EditorCapabilities ANDs `!isPassword`
        // into it - and the gate must not undo that.
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
        // Site 2 re-derives the word independently, so it needs the gate independently: a fix
        // applied only in handleSeparatorInput still leaks here, via the text-input/voice paths.
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
