package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.harness.PipelineHarness
import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * CHARACTERISATION scenarios for [InputLogic.handleSeparatorInput] — the branch forest Phase 1c
 * step 4 turns into a staged pipeline.
 *
 * ## What the method decides
 *
 * A separator keystroke does up to four separate things, and the old body interleaved all of
 * them in one 90-line sequence of booleans named `z`, `z2`, `z3` and `zM5182e`:
 *
 *  1. **retire the composing word** — commit it, auto-corrected or verbatim, with this separator
 *     as its trailing payload (or reset instead, if the caret had moved inside the word);
 *  2. **insert an auto-space BEFORE the separator**, when the previous commit asked for one and
 *     this separator is the kind usually preceded by a space;
 *  3. **pick a disposition for the separator itself** — double-space-to-period, punctuation swap,
 *     plain space, or anything else — which decides whether the separator is written at all;
 *  4. **set `mCommitType`**, the 0/1/2/3/4 state the NEXT keystroke reads.
 *
 * Step 4 is the reason these tests assert `mCommitType` as well as the editor: it is invisible in
 * the text but it steers the following keystroke's auto-space and swap decisions. A refactor that
 * gets the text right and the code wrong breaks the *next* character, which is exactly the class
 * of bug that is hard to find by hand.
 *
 * ## Oracle
 *
 * As in [CommitEntryPointsCharacterisationTest], the assertion is the exact `InputConnection`
 * call sequence, because "the same text by a different route" is a real behaviour change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SeparatorInputCharacterisationTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    @Before
    fun setUp() {
        h = PipelineHarness()
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(settings())
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private val NORMAL_FIELD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    private fun settings(
        autoCorrect: Boolean = true,
        doubleSpacePeriod: Boolean = false,
    ): SettingsValues = h.settingsWith(
        "isAutoCorrectionEnabledPerUserSettings" to autoCorrect,
        "useDoubleSpacePeriod" to doubleSpacePeriod,
        "doubleSpacePeriodTimeoutMs" to 1000,
        "locale" to Locale.US,
        "editorCapabilities" to EditorCapabilities(
            EditorInfo().apply { inputType = NORMAL_FIELD; packageName = "com.example.app" },
            /* allowsAppSpecifiedCompletions = */ false,
            /* keyboardPackageName = */ "dev.bbkb.ime.debug",
            Locale.US,
            /* forceSuggestions = */ false,
        ),
    )

    /**
     * Drive one separator keystroke through the real method.
     *
     * `handleSeparatorInput` and its only caller `routeKeyEvent` are both private, so reflection
     * is the way in — the same tactic [dev.bbkb.ime.harness.PipelineHarness] uses for the
     * `@JvmField` back-references and `MacroCommitAutoCorrectOffTest` uses for this same method.
     *
     * @param commitType the `mCommitType` left over from the PREVIOUS keystroke — the 0/1/2/3/4
     *                   state that gates the auto-space and swap decisions. 4 is "a word was just
     *                   committed and a space may be owed".
     */
    private fun separator(
        codePoint: Int,
        sv: SettingsValues = settings(),
        commitType: Int = 0,
        timestamp: Long = 1000L,
        source: InputSource = InputSource.HARDWARE,
    ): InputEventContext {
        val event = InputEvent.createKeyPress(codePoint, -1, -4, -4, timestamp, false)
        val ctx = InputEventContext(sv, event, timestamp, commitType, 0)
        ctx.setInputSource(source)
        val m = InputLogic::class.java.getDeclaredMethod(
            "handleSeparatorInput",
            InputEvent::class.java,
            InputEventContext::class.java,
            UIUpdateHandler::class.java,
        ).apply { isAccessible = true }
        m.invoke(h.inputLogic, event, ctx, h.uiHandler)
        return ctx
    }

    private fun armComposing(typed: String, at: String = "", cursor: Int = at.length) {
        h.startSession(at, cursor)
        h.inputLogic.mRichInputConnection.setComposingText(typed, 1)
        h.seedComposing(typed)
        h.editor.dropPendingSelectionEvents()
        h.editor.callLog.clear()
    }

    private fun armIdle(at: String = "", cursor: Int = at.length) {
        h.startSession(at, cursor)
        h.editor.dropPendingSelectionEvents()
        h.editor.callLog.clear()
    }

    private fun log(): String = h.editor.callLog.joinToString(" ")

    /**
     * A bare double quote. The call log wraps every payload in quotes of its own, so a quote
     * payload logs as `commitText(""",1)` — three quotes in a row, which a Kotlin raw string
     * cannot spell directly.
     */
    private val QUOTE = "\""

    /** The `mCommitType` the next keystroke will read. */
    private fun commitTypeAfter(): Int = h.inputLogic.mCommitType

    private fun suggestions(vararg words: String): SuggestedWords {
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, w) in words.withIndex()) {
            list.add(
                SuggestedWords.SuggestedWordInfo(
                    w, 100 - i, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null,
                ),
            )
        }
        return SuggestedWords(list, false, false, 0)
    }

    // ── disposition: SPACE ──────────────────────────────────────────────────────

    @Test
    fun space_whileComposing_commitsTheWordAndTheSpaceAsOneCommitPair() {
        // The word commit consumes the separator, so the SPACE disposition must NOT write it
        // again (`!z` gate). Two commitTexts, not three.
        armComposing("hello")

        val ctx = separator(' '.code)

        assertEquals("hello ", h.editor.text)
        assertEquals("""commitText("hello",1) commitText(" ",1)""", log())
        assertEquals("mCommitType 3 = 'a space was just typed'", 3, commitTypeAfter())
        assertTrue(ctx.shouldUpdateSuggestions())
        assertEquals(1, ctx.uiUpdateMode)
    }

    @Test
    fun space_whileIdle_isWrittenByTheDispositionItself() {
        // Nothing was composing, so `z` is false and the SPACE disposition is the only writer.
        armIdle("hi", 2)

        separator(' '.code)

        assertEquals("hi ", h.editor.text)
        assertEquals("""commitText(" ",1)""", log())
        assertEquals(3, commitTypeAfter())
    }

    @Test
    fun space_alwaysClaimsCommitTypeThree() {
        // A space always claims mCommitType 3. The former guard `!isAutoCorrection()` was dead
        // (hard-coded false) and was deleted on the owner's decision 2026-09-22; this pins that a
        // pending correction still does not change the outcome.
        armIdle("hi", 2)
        h.inputLogic.mCurrentSuggestions = SuggestedWords(
            arrayListOf(
                SuggestedWords.SuggestedWordInfo(
                    "hi", 100, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null,
                ),
                SuggestedWords.SuggestedWordInfo(
                    "his", 99, 2, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null,
                ),
            ),
            false, /* willAutoCorrect = */ true, 0,
        )
        assertFalse(
            "the premise: isAutoCorrection() is hard-coded false even with willAutoCorrect set",
            h.inputLogic.mCurrentSuggestions.isAutoCorrection,
        )

        separator(' '.code)

        assertEquals(
            "a space claims mCommitType 3 regardless of any pending correction",
            3, commitTypeAfter(),
        )
    }

    @Test
    fun space_whileComposing_appliesTheAutoCorrectionFirst() {
        armComposing("teh")
        h.inputLogic.mComposingTracker.setAutoCorrection("the", 2)
        h.editor.callLog.clear()

        val ctx = separator(' '.code)

        assertEquals("the ", h.editor.text)
        assertTrue("the context must record that a correction was applied", ctx.wasAutoCorrectApplied())
        assertEquals(
            """commitText("the",1) commitText(" ",1) commitCorrection""", log(),
        )
    }

    // ── disposition: OTHER (any non-space separator) ─────────────────────────────

    @Test
    fun period_whileComposing_commitsTheWordThenTheSeparator() {
        armComposing("hello")

        separator('.'.code)

        assertEquals("hello.", h.editor.text)
        assertEquals("""commitText("hello",1) commitText(".",1)""", log())
    }

    @Test
    fun period_whileIdle_isWrittenByTheDisposition() {
        armIdle("hi", 2)

        separator('.'.code)

        assertEquals("hi.", h.editor.text)
        assertEquals("""commitText(".",1)""", log())
    }

    @Test
    fun period_afterAWordCommit_claimsCommitTypeFour() {
        // `commitType == 4 && isUsuallyFollowedBySpace(cp)` re-arms the auto-space state, which
        // is what makes the NEXT character get a space in front of it.
        armIdle("hi", 2)

        separator('.'.code, commitType = 4)

        assertEquals("mCommitType 4 = 'a space is owed before the next word'", 4, commitTypeAfter())
    }

    @Test
    fun aSeparatorAlwaysNeutralisesTheSuggestionStrip_unlessItIsASpace() {
        // The OTHER disposition ends in setNeutralSuggestionStrip(); the SPACE one does not.
        // That asymmetry is the only externally visible difference between the two tails.
        armIdle("hi", 2)
        separator('.'.code)
        org.mockito.Mockito.verify(h.suggestionStripListener).setNeutralSuggestionStrip()

        armIdle("hi", 2)
        h.editor.callLog.clear()
        org.mockito.Mockito.clearInvocations(h.suggestionStripListener)
        separator(' '.code)
        org.mockito.Mockito.verify(h.suggestionStripListener, org.mockito.Mockito.never())
            .setNeutralSuggestionStrip()
    }

    // ── stage 2: the auto-space inserted BEFORE the separator ───────────────────

    @Test
    fun anOpeningParenAfterAWordCommit_getsAnAutoSpaceBeforeIt() {
        // `isUsuallyPrecededBySpace('(')` is true, and mCommitType 4 means the previous commit
        // deferred its space — so the separator stage writes the space first, then the paren.
        armIdle("hi", 2)

        separator('('.code, commitType = 4)

        assertEquals("hi (", h.editor.text)
        assertEquals(
            "the auto-space must precede the separator, as two separate commits",
            """commitText(" ",1) commitText("(",1)""", log(),
        )
    }

    @Test
    fun aPeriodAfterAWordCommit_getsNoAutoSpaceBeforeIt() {
        // The other side of the same gate: a period is NOT usually preceded by a space.
        armIdle("hi", 2)

        separator('.'.code, commitType = 4)

        assertEquals("hi.", h.editor.text)
        assertEquals("""commitText(".",1)""", log())
    }

    @Test
    fun withNoPendingCommit_noAutoSpaceIsEverInserted() {
        // The whole auto-space stage is gated on mCommitType == 4. At 0 it must not fire even
        // for a code point that would otherwise qualify.
        armIdle("hi", 2)

        separator('('.code, commitType = 0)

        assertEquals("hi(", h.editor.text)
        assertEquals("""commitText("(",1)""", log())
    }

    // ── the quote-after-digit special case ──────────────────────────────────────

    @Test
    fun aQuoteAfterADigit_isTreatedAsAnInchMarkAndArmsAnAutoSpace() {
        // `34 == cp && endsWithQuoteAfterDigit()` — a quote following a digit is a unit mark
        // (`6"`), not the close of a quotation, so it suppresses the auto-space BEFORE it and
        // arms one AFTER it instead.
        armIdle("6", 1)

        separator('"'.code, commitType = 4)

        assertEquals("""commitText("$QUOTE",1)""", log())
        assertEquals(4, commitTypeAfter())
    }

    @Test
    fun aQuoteAfterALetter_getsTheOrdinaryAutoSpaceTreatment() {
        // Same code point, different preceding character: not an inch mark, so the quote takes
        // the `!z3` path and the auto-space before it is allowed.
        armIdle("hi", 2)

        separator('"'.code, commitType = 4)

        assertEquals("hi \"", h.editor.text)
        assertEquals("""commitText(" ",1) commitText("$QUOTE",1)""", log())
    }

    // ── disposition: DOUBLE_SPACE_PERIOD ────────────────────────────────────────

    @Test
    fun aSecondSpaceInQuickSuccession_becomesAPeriodAndASpace() {
        // The highest-priority disposition, and the only one that DELETES. It is reached only
        // when nothing was composing (`!z`), which is why the word is committed first here.
        armIdle("hi ", 3)
        h.inputLogic.recordSpaceTimestamp(
            InputEventContext(settings(doubleSpacePeriod = true), InputEvent.createEmptyEvent(), 900L, 0, 0),
        )
        h.editor.callLog.clear()

        val ctx = separator(' '.code, sv = settings(doubleSpacePeriod = true), timestamp = 1000L)

        assertEquals("hi. ", h.editor.text)
        assertEquals(
            """deleteSurroundingText(1,0) commitText(". ",1)""", log(),
        )
        assertEquals("mCommitType 1 = 'a double-space period was just made'", 1, commitTypeAfter())
        assertTrue(ctx.shouldUpdateSuggestions())
    }

    @Test
    fun aSpaceThatCommittedAWord_isNeverTurnedIntoADoubleSpacePeriod() {
        // The `!wordCommitted` guard on the highest-priority disposition. Without it, a space
        // that terminates a word would find the word's own trailing space in the buffer, decide
        // the user had double-tapped, and rewrite "hi ok " as "hi ok. ".
        //
        // Everything needed to make the wrong branch attractive is armed: the preference is on,
        // the timestamp is inside the timeout, and the text before the caret ends in a space
        // (the one the word commit just wrote).
        armIdle("hi ", 3)
        h.inputLogic.mRichInputConnection.setComposingText("ok", 1)
        h.seedComposing("ok")
        h.inputLogic.recordSpaceTimestamp(
            InputEventContext(settings(doubleSpacePeriod = true), InputEvent.createEmptyEvent(), 900L, 0, 0),
        )
        h.editor.dropPendingSelectionEvents()
        h.editor.callLog.clear()

        separator(' '.code, sv = settings(doubleSpacePeriod = true), timestamp = 1000L)

        assertEquals("hi ok ", h.editor.text)
        assertEquals(
            "no delete may happen: the double-space disposition must not be reached",
            """commitText("ok",1) commitText(" ",1)""", log(),
        )
        assertEquals(3, commitTypeAfter())
    }

    @Test
    fun aSecondSpaceWithThePreferenceOff_staysAPlainSpace() {
        armIdle("hi ", 3)
        h.inputLogic.recordSpaceTimestamp(
            InputEventContext(settings(), InputEvent.createEmptyEvent(), 900L, 0, 0),
        )
        h.editor.callLog.clear()

        separator(' '.code, sv = settings(doubleSpacePeriod = false), timestamp = 1000L)

        assertEquals("hi  ", h.editor.text)
        assertEquals("""commitText(" ",1)""", log())
        assertEquals(3, commitTypeAfter())
    }

    @Test
    fun aSecondSpaceAfterTheTimeout_staysAPlainSpace() {
        armIdle("hi ", 3)
        h.inputLogic.recordSpaceTimestamp(
            InputEventContext(settings(doubleSpacePeriod = true), InputEvent.createEmptyEvent(), 0L, 0, 0),
        )
        h.editor.callLog.clear()

        separator(' '.code, sv = settings(doubleSpacePeriod = true), timestamp = 5000L)

        assertEquals("hi  ", h.editor.text)
        assertEquals(3, commitTypeAfter())
    }

    // ── the cursor-moved reset that runs before everything else ──────────────────

    @Test
    fun aSeparatorWithTheCaretInsideTheWord_resetsInsteadOfCommittingIt() {
        // The first statement of the method: a moved in-composing caret means the word is
        // abandoned, not committed. The separator itself is still written by the disposition,
        // because after the reset nothing is composing.
        armComposing("hello")
        h.inputLogic.mComposingTracker.setComposingCursorPosition(2)
        h.editor.callLog.clear()

        separator('.'.code)

        assertFalse(
            "the tracker must have been reset, not committed",
            h.inputLogic.mComposingTracker.isComposing,
        )
        assertEquals("hello.", h.editor.text)
        h.fourCopies().assertConsistent()
    }

    // ── the invariant after every disposition ───────────────────────────────────

    @Test
    fun everyDisposition_leavesTheCopiesConsistent() {
        armComposing("hello"); separator(' '.code); h.fourCopies().assertConsistent()
        armComposing("hello"); separator('.'.code); h.fourCopies().assertConsistent()
        armIdle("hi", 2); separator(' '.code); h.fourCopies().assertConsistent()
        armIdle("hi", 2); separator('('.code, commitType = 4); h.fourCopies().assertConsistent()
        armIdle("6", 1); separator('"'.code, commitType = 4); h.fourCopies().assertConsistent()
    }
}
