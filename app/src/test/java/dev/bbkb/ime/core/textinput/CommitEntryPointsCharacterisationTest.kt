package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.SymbolPageProvider
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord
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
 * CHARACTERISATION scenarios for every commit ENTRY POINT of the typing path — written before
 * the Phase-1c "ten entry points become one `CommitRequest` spec" consolidation, so the move can
 * be proved behaviour-preserving rather than asserted to be.
 *
 * ## Why the call log, and not just the resulting text
 *
 * Two commit paths can leave the editor holding the same string by very different routes — one
 * `commitText`, or a `setComposingText` followed by a `commitText`, or a commit plus a separate
 * separator commit. The difference is invisible in the final text but extremely visible to a
 * real editor (extra `onUpdateSelection` round trips, a composing region that briefly exists,
 * an undo stack with two entries instead of one). So each scenario pins
 * [dev.bbkb.ime.harness.FakeEditor.callLog] — the exact `InputConnection` call sequence — as
 * well as the resulting text, cursor and composing region.
 *
 * That makes these tests deliberately brittle in the one direction that matters: a refactor
 * that routes an entry point through a different sequence of editor calls fails here even when
 * the text comes out identical.
 *
 * ## The entry points pinned
 *
 * Committing is reached through eleven distinct methods on [CommitController] plus three more on
 * [InputLogic] that commit without going through it. Each `@Test` below names the one it covers;
 * the map lives in PHASE1C-SUMMARY.md at the worktree root.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CommitEntryPointsCharacterisationTest {

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

        // commitGestureSuggestion ends by clearing the manual shift; the bare IME mock hands
        // back null for the tracker and the path NPEs before it can be observed.
        `when`(h.ime.getPhysicalKeyboardStateTracker())
            .thenReturn(mock(PhysicalKeyboardStateTracker::class.java))
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private val NORMAL_FIELD = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    private fun editorCaps(inputType: Int = NORMAL_FIELD) = EditorCapabilities(
        EditorInfo().apply { this.inputType = inputType; packageName = "com.example.app" },
        /* allowsAppSpecifiedCompletions = */ false,
        /* keyboardPackageName = */ "dev.bbkb.ime.debug",
        Locale.US,
        /* forceSuggestions = */ false,
    )

    private fun settings(autoCorrect: Boolean = true): SettingsValues = h.settingsWith(
        "isAutoCorrectionEnabledPerUserSettings" to autoCorrect,
        "editorCapabilities" to editorCaps(),
    )

    /** `InputLogic.mCommitController` is private; the package-private methods need it directly. */
    private fun commitController(): CommitController =
        InputLogic::class.java.getDeclaredField("mCommitController")
            .apply { isAccessible = true }.get(h.inputLogic) as CommitController

    /** Arm a live composing word exactly as typing it would have. */
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

    /** The exact `InputConnection` call sequence since the last arm/clear. */
    private fun log(): String = h.editor.callLog.joinToString(" ")

    /** Alphabet page, no manual shift — the plain state every pick scenario here starts from. */
    private fun symbolPages(): SymbolPageProvider = mock(SymbolPageProvider::class.java)

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

    // ── EP1: commitComposingOrReset(SettingsValues) ──────────────────────────────

    @Test
    fun ep1_commitComposingOrReset_whileComposing_commitsWordWithNoSeparator() {
        armComposing("hello")

        h.inputLogic.commitComposingOrReset(settings())

        assertEquals("hello", h.editor.text)
        assertFalse("the composing region must be retired by the commit", h.editor.hasComposingRegion())
        assertEquals("""commitText("hello",1)""", log())
    }

    @Test
    fun ep1_commitComposingOrReset_whenIdle_touchesNothing() {
        // Not composing and no pending multi-tap char: commitTouchEventText is a no-op, so the
        // entry point must not reach the editor at all.
        armIdle("hi ", 3)

        h.inputLogic.commitComposingOrReset(settings())

        assertEquals("hi ", h.editor.text)
        assertEquals("", log())
    }

    // ── EP2: commitOrResetComposing(SettingsValues, InputSource) ─────────────────

    @Test
    fun ep2_commitOrResetComposing_whileComposing_commitsWord() {
        armComposing("world")

        h.inputLogic.commitOrResetComposing(settings(), InputSource.HARDWARE)

        assertEquals("world", h.editor.text)
        assertEquals("""commitText("world",1)""", log())
    }

    @Test
    fun ep2_commitOrResetComposing_cursorMovedMidWord_resetsInsteadOfCommitting() {
        // The tracker believes the caret sits inside the word, so this entry point abandons the
        // commit and re-selects instead. The distinguishing trace is that no commitText is made.
        armComposing("world")
        h.inputLogic.mComposingTracker.setComposingCursorPosition(2)
        h.editor.callLog.clear()
        assertTrue("precondition: tracker must report a moved cursor", h.inputLogic.mComposingTracker.isCursorMoved)

        h.inputLogic.commitOrResetComposing(settings(), InputSource.HARDWARE)

        assertFalse(
            "the reset path must not commit the word",
            h.editor.callLog.any { it.startsWith("commitText") },
        )
        assertEquals("world", h.editor.text)
    }

    // ── EP3: commitTypedWord(SettingsValues, String, InputSource) ────────────────

    @Test
    fun ep3_commitTypedWord_appendsSeparatorAsASecondCommit() {
        // Load-bearing: the word and the separator are TWO commitText calls, not one. A
        // consolidation that fuses them changes what the editor sees.
        armComposing("hi")

        h.inputLogic.commitTypedWord(settings(), " ", InputSource.HARDWARE)

        assertEquals("hi ", h.editor.text)
        assertEquals("""commitText("hi",1) commitText(" ",1)""", log())
    }

    @Test
    fun ep3_commitTypedWord_whenNotComposing_isANoOp() {
        armIdle("hi ", 3)

        h.inputLogic.commitTypedWord(settings(), " ", InputSource.HARDWARE)

        assertEquals("hi ", h.editor.text)
        assertEquals("", log())
    }

    @Test
    fun ep3_commitTypedWord_wordCharacterSeparator_isNotAppended() {
        // The append gate in commitWordExtended requires the payload to be separator-class.
        // A letter is not, so it is dropped — pinned so the spec move cannot start emitting it.
        armComposing("hi")

        h.inputLogic.commitTypedWord(settings(), "x", InputSource.HARDWARE)

        assertEquals("hi", h.editor.text)
        assertEquals("""commitText("hi",1)""", log())
    }

    // ── EP4: commitWord(...) ────────────────────────────────────────────────────

    @Test
    fun ep4_commitWord_commitsGivenTextIgnoringTheTracker() {
        // commitWord takes the text as an argument; the tracker's own word is irrelevant to it.
        armComposing("teh")

        h.inputLogic.commitWord(
            settings(), "the", CommitEventRecord.CommitType.DECIDED_WORD, " ", InputSource.HARDWARE,
        )

        assertEquals("the ", h.editor.text)
        assertEquals("""commitText("the",1) commitText(" ",1)""", log())
    }

    @Test
    fun ep4_commitWord_returnsWordPlusSeparatorLength() {
        armComposing("hi")
        val written = h.inputLogic.commitWord(
            settings(), "hi", CommitEventRecord.CommitType.USER_TYPED_WORD, " ", InputSource.HARDWARE,
        )
        assertEquals(3, written)
    }

    // ── EP5: commitWordExtended(..., forceSeparator) ─────────────────────────────

    @Test
    fun ep5_commitWordExtended_forceSeparator_appendsAWordCharacterPayload() {
        // The `z` flag is the only way a non-separator payload gets appended. This is the gate
        // CommitVoiceInputTest's letter-payload case rides on.
        armComposing("hi")

        commitController().commitWordExtended(
            settings(), "hi", CommitEventRecord.CommitType.DECIDED_WORD, "x",
            InputSource.SOFTWARE, /* forceSeparator = */ true,
        )

        assertEquals("hix", h.editor.text)
        assertEquals("""commitText("hi",1) commitText("x",1)""", log())
    }

    @Test
    fun ep5_commitWordExtended_pendingAppendSpace_skipsTheSeparatorAndStepsTheCursor() {
        // F9 / SS-3: mShouldAppendSpace means a separator already sits after the cursor, so the
        // commit steps over it (newCursorPosition=2) and must NOT write one of its own.
        armComposing("hi", at = " ", cursor = 0)
        h.inputLogic.mShouldAppendSpace = true
        h.editor.callLog.clear()

        commitController().commitWordExtended(
            settings(), "hi", CommitEventRecord.CommitType.MANUAL_PICK, " ",
            InputSource.SOFTWARE, /* forceSeparator = */ false,
        )

        assertEquals(
            "the separator must be stepped over, not duplicated",
            """commitText("hi",2)""", log(),
        )
        assertFalse("the consume-once flag must be cleared", h.inputLogic.mShouldAppendSpace)
    }

    // ── EP6: autoCorrectAndCommit / autoCorrectAndCommitExtended ────────────────

    @Test
    fun ep6_autoCorrectAndCommit_appliesTheCandidateAndReportsSeparatorConsumed() {
        armComposing("teh")
        h.inputLogic.mComposingTracker.setAutoCorrection("the", 2)
        h.editor.callLog.clear()

        val separatorConsumed =
            commitController().autoCorrectAndCommit(settings(), " ", h.uiHandler, InputSource.HARDWARE)

        assertEquals("the ", h.editor.text)
        assertTrue("SS-1: the pipeline must report that it wrote the separator", separatorConsumed)
        assertEquals(
            """commitText("the",1) commitText(" ",1) commitCorrection""", log(),
        )
    }

    @Test
    fun ep6_autoCorrectAndCommit_noCandidate_commitsTypedWordAndIssuesNoCorrection() {
        armComposing("hello")

        val separatorConsumed =
            commitController().autoCorrectAndCommit(settings(), " ", h.uiHandler, InputSource.HARDWARE)

        assertEquals("hello ", h.editor.text)
        assertTrue(separatorConsumed)
        assertEquals(
            "no correction was applied, so no commitCorrection round trip",
            """commitText("hello",1) commitText(" ",1)""", log(),
        )
    }

    @Test
    fun ep6_autoCorrectAndCommit_withNothingComposing_commitsNothingAndLeavesTheSeparatorToTheCaller() {
        // With nothing composing, `getAutoCorrection()` is null and `getComposingText()` is "".
        // This used to hit an "Impossible!" RuntimeException (unreachable only because every
        // production caller checks isComposing() first). Owner decision 2026-09-22: it returns
        // false — nothing was committed, the caller still owns the separator payload.
        armIdle("hi ", 3)
        val before = h.editor.text

        val consumed = commitController().autoCorrectAndCommit(settings(), "!", h.uiHandler, InputSource.INTERNAL)

        assertFalse("nothing was committed, so the separator is still the caller's", consumed)
        assertEquals("the editor is untouched", before, h.editor.text)
    }

    @Test
    fun ep7_commitGestureSuggestion_commitsTopWordWithASpace() {
        armIdle()

        h.inputLogic.commitGestureSuggestion(settings(), suggestions("hello", "help"), InputSource.SOFTWARE)

        assertEquals("hello ", h.editor.text)
        assertEquals("""commitText("hello",1) commitText(" ",1)""", log())
    }

    @Test
    fun ep7_commitGestureSuggestion_phraseCandidate_prefersTheSpaceFreeAlternative() {
        // One swipe is one word: a ranked bigram must not deposit two.
        armIdle()

        h.inputLogic.commitGestureSuggestion(
            settings(), suggestions("same time", "sane"), InputSource.SOFTWARE,
        )

        assertEquals("sane ", h.editor.text)
    }

    @Test
    fun ep7_commitGestureSuggestion_allPhrases_fallsBackToTheFirstToken() {
        armIdle()

        h.inputLogic.commitGestureSuggestion(
            settings(), suggestions("same time", "end of"), InputSource.SOFTWARE,
        )

        assertEquals("same ", h.editor.text)
    }

    @Test
    fun ep7_commitGestureSuggestion_emptyList_commitsNothing() {
        armIdle("hi ", 3)

        h.inputLogic.commitGestureSuggestion(settings(), SuggestedWords.EMPTY, InputSource.SOFTWARE)

        assertEquals("hi ", h.editor.text)
        assertEquals("", log())
    }

    // ── EP8: commitCharacter ────────────────────────────────────────────────────

    @Test
    fun ep8_commitCharacter_writesOneCodePointAsASingleCommit() {
        armIdle("hi", 2)

        commitController().commitCharacter(settings(), '!'.code, InputSource.HARDWARE)

        assertEquals("hi!", h.editor.text)
        assertEquals("""commitText("!",1)""", log())
    }

    @Test
    fun ep8_commitCharacter_supplementaryCodePoint_isWrittenAsASurrogatePair() {
        armIdle()

        commitController().commitCharacter(settings(), 0x1F600, InputSource.SOFTWARE)

        assertEquals(2, h.editor.text.length)
        assertEquals(0x1F600, h.editor.text.codePointAt(0))
    }

    // ── EP9: commitTouchEventText ───────────────────────────────────────────────

    @Test
    fun ep9_commitTouchEventText_withNoActiveHighlight_isANoOp() {
        armIdle("hi", 2)

        h.inputLogic.commitTouchEventText()

        assertEquals("hi", h.editor.text)
        assertEquals("", log())
    }

    // ── EP10: handleManualPick ──────────────────────────────────────────────────

    @Test
    fun ep10_handleManualPick_commitsThePickAndItsSeparator() {
        armComposing("hel")
        h.inputLogic.mCurrentSuggestions = suggestions("hello", "help")
        h.editor.callLog.clear()

        h.inputLogic.handleManualPick(
            settings(), h.inputLogic.mCurrentSuggestions.getWordInfo(0),
            symbolPages(), 0, h.uiHandler, InputSource.SOFTWARE,
        )

        assertEquals("hello ", h.editor.text)
        assertEquals("""commitText("hello",1) commitText(" ",1)""", log())
    }

    @Test
    fun ep10_handleManualPick_separatorAlreadyAfterCursor_stepsOverItInstead() {
        // The SS-3 cursor-skip commit: a space already follows the caret, so the pick must not
        // add a second one — it commits with newCursorPosition=2 to land past the existing one.
        armComposing("hel", at = " ", cursor = 0)
        h.inputLogic.mCurrentSuggestions = suggestions("hello")
        h.editor.callLog.clear()

        h.inputLogic.handleManualPick(
            settings(), h.inputLogic.mCurrentSuggestions.getWordInfo(0),
            symbolPages(), 0, h.uiHandler, InputSource.SOFTWARE,
        )

        assertEquals("hello ", h.editor.text)
        assertEquals("""commitText("hello",2)""", log())
        assertEquals(
            "the caret must end up after the pre-existing separator",
            6, h.editor.selStart,
        )
    }

    // ── EP11: processClipboardMarker (the pre-commit text transform) ─────────────

    @Test
    fun ep11_processClipboardMarker_stripsBoundaryMarkers() {
        assertEquals("hello", h.inputLogic.processClipboardMarker("hello"))
        assertEquals("hello", h.inputLogic.processClipboardMarker("hello%B."))
        assertEquals("hello", h.inputLogic.processClipboardMarker("hello%b"))
    }

    @Test
    fun ep11_processClipboardMarker_leadingLowerMarker_retiresTheRegionBeforeDeleting() {
        // SS-5: the delete means "the char before the composing region" to a real editor, so the
        // region has to be retired first or the cache and the editor disagree by one.
        armComposing("x")

        val cleaned = h.inputLogic.processClipboardMarker("%bhello")

        assertEquals("hello", cleaned)
        assertEquals(
            "finishComposingText must precede the delete",
            """finishComposingText() deleteSurroundingText(1,0)""", log(),
        )
    }

    // ── EP12: commitPredictionWord — the CJK / Japanese protocol ─────────────────

    /**
     * Drive the prediction-word protocol with the locale predicates forced.
     *
     * The suite could not reach this path before: `handleManualPick` only routes here for a
     * Chinese/Japanese subtype, and every locale predicate is a `LocaleUtils` static. Mocking
     * that class is the only seam. `updateSuggestionsSync` is safe to let run — with
     * `shouldShowLxxButton` at its default `false` it takes the "suggestions were not requested"
     * branch and only calls the mock strip listener, so no worker thread is involved.
     */
    private fun predictionPick(
        word: String,
        kind: Int = 0,
        chinese: Boolean = true,
        japanese: Boolean = false,
        separator: String = " ",
    ): Boolean {
        val info = SuggestedWords.SuggestedWordInfo(
            word, 100, kind, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null,
        )
        val event = dev.bbkb.ime.core.keyevent.InputEvent.createForSuggestion(info)
        // Built BEFORE the static mock opens: SpacingAndPunctuation's constructor reads a
        // LocaleUtils static that returns a Locale, and a mocked static hands back null there.
        val sv = settings()
        val ctx = dev.bbkb.ime.core.keyevent.InputEventContext(sv, event, 1000L, 0, 0)
        mockStatic(dev.bbkb.ime.core.locale.LocaleUtils::class.java).use { locales ->
            locales.`when`<Boolean> {
                dev.bbkb.ime.core.locale.LocaleUtils.isCurrentSubtypeChinese()
            }.thenReturn(chinese)
            locales.`when`<Boolean> {
                dev.bbkb.ime.core.locale.LocaleUtils.isCurrentSubtypeJapanese()
            }.thenReturn(japanese)
            return commitController().commitPredictionWord(
                sv, info, CommitEventRecord.CommitType.MANUAL_PICK,
                InputSource.SOFTWARE, ctx, h.uiHandler, separator,
            )
        }
    }

    @Test
    fun ep12_commitPredictionWord_chinese_commitsRawTextThenTheSeparator() {
        // The pick is written with NO suggestion span, then the separator.
        //
        // The re-compose step in between (`setComposingTextInternal`) is CONDITIONAL and does
        // not fire here: `createEventDispatcherForPickedSuggestion` consumed the whole composing
        // buffer, so the tracker's text is empty and `setComposingTextWithHighlight` returns
        // early on a zero-length sequence. It only reaches the editor when the pick covered
        // part of the buffer and a remainder is left to keep composing — the phrase-split case
        // at ComposingTextTracker's picked-suggestion branch.
        armComposing("ni")

        predictionPick("你好")

        assertEquals("""commitText("你好",1) commitText(" ",1)""", log())
        assertFalse(
            "the buffer was fully consumed by the pick, which is why no region was re-sent",
            h.inputLogic.mComposingTracker.isComposing,
        )
    }

    @Test
    fun ep12_commitPredictionWord_partialPick_reComposesTheRemainder() {
        // The one scenario in which the prediction protocol's `setComposingTextInternal` step
        // actually reaches the editor, and therefore the only test that can prove it survived
        // the move into `executePredictionWord`.
        //
        // The tracker splits the composing buffer when the picked entry carries a Nuance
        // `spell` SHORTER than what is being composed: "ni" out of "nihao" consumes the first
        // two code points, and the remaining "hao" stays composing. So the editor sees the pick
        // committed, then the leftover re-sent as a fresh composing region, then the separator.
        //
        // `getPointerSize() > splitIndex` is the second half of the split condition, which is
        // why the buffer has to be seeded through `seedComposing` (it fills one coordinate pair
        // per code point) rather than written straight to the editor.
        armComposing("nihao")

        val spelled = com.blackberry.nuanceshim.WordInfo().apply { word = "你"; spell = "ni" }
        val info = SuggestedWords.SuggestedWordInfo(
            "你", 100, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, spelled,
        )
        val event = dev.bbkb.ime.core.keyevent.InputEvent.createForSuggestion(info)
        val sv = settings()
        val ctx = dev.bbkb.ime.core.keyevent.InputEventContext(sv, event, 1000L, 0, 0)
        mockStatic(dev.bbkb.ime.core.locale.LocaleUtils::class.java).use { locales ->
            locales.`when`<Boolean> {
                dev.bbkb.ime.core.locale.LocaleUtils.isCurrentSubtypeChinese()
            }.thenReturn(true)
            commitController().commitPredictionWord(
                sv, info, CommitEventRecord.CommitType.MANUAL_PICK,
                InputSource.SOFTWARE, ctx, h.uiHandler, " ",
            )
        }

        assertEquals(
            "hao",
            h.inputLogic.mComposingTracker.composingText,
        )
        assertEquals(
            """commitText("你",1) setComposingText("hao",1) commitText(" ",1)""", log(),
        )
    }

    @Test
    fun ep12_commitPredictionWord_japaneseKind7_clearsTheEngineAndTheTracker() {
        // The one branch that discards state as part of committing: a kind-7 Japanese pick
        // clears the engine buffer and the tracker, so the re-compose writes nothing back.
        armComposing("ka")

        predictionPick("カ", kind = 7, chinese = false, japanese = true)

        assertFalse(
            "a kind-7 Japanese pick must leave the tracker empty",
            h.inputLogic.mComposingTracker.isComposing,
        )
        assertEquals("カ ", h.editor.text)
    }

    @Test
    fun ep12_commitPredictionWord_underACjkLocaleALatinLetterCountsAsASeparator() {
        // Not a typo in the fixture — this is what the separator test resolves to here, and it
        // is worth pinning because it is the opposite of the Latin-script answer.
        //
        // `InputLogic.isWordSeparator` has two implementations: under a CJK locale it asks
        // `isNotWordSeparatorForLocale(cp, subtypeLocale)` and negates it, everywhere else it
        // asks `isWordCodePoint(cp)`. Under Chinese, an ASCII letter is NOT a word code point of
        // the composing script, so it reads as a separator and the append gate lets it through.
        // The same payload is dropped by the Latin-script word protocol
        // (`ep3_commitTypedWord_wordCharacterSeparator_isNotAppended`).
        //
        // Also pins that the prediction protocol has no force-separator escape at all: unlike
        // `executeWord` there is no `forceSeparator` term in its gate, so the locale's answer is
        // the only thing that decides.
        armComposing("ni")

        predictionPick("你", separator = "x")

        assertEquals("""commitText("你",1) commitText("x",1)""", log())
    }

    // ── the four-copies invariant holds after every entry point ─────────────────

    @Test
    fun everyCommitEntryPoint_leavesTheCopiesConsistent() {
        // The Phase-5 invariant, run after each protocol. It now covers the cursor rules too
        // (see FourCopies R2/R3), which is what makes the cursor-skip cases below meaningful.
        armComposing("hello")
        h.inputLogic.commitComposingOrReset(settings())
        h.fourCopies().assertConsistent()

        armComposing("hi")
        h.inputLogic.commitTypedWord(settings(), " ", InputSource.HARDWARE)
        h.fourCopies().assertConsistent()

        armIdle()
        h.inputLogic.commitGestureSuggestion(settings(), suggestions("hello"), InputSource.SOFTWARE)
        h.fourCopies().assertConsistent()

        armComposing("teh")
        h.inputLogic.mComposingTracker.setAutoCorrection("the", 2)
        commitController().autoCorrectAndCommit(settings(), " ", h.uiHandler, InputSource.HARDWARE)
        h.fourCopies().assertConsistent()

        armIdle("hi", 2)
        commitController().commitCharacter(settings(), '!'.code, InputSource.HARDWARE)
        h.fourCopies().assertConsistent()
    }

    @Test
    fun cursorSkipCommit_keepsTheCachedCursorWithTheEditor() {
        // Audit SS-3 as an INVARIANT rather than a hand-written assertion: the manual-pick path
        // commits with newCursorPosition=2 to step over a separator already after the caret, and
        // the RIC cache has to follow. FourCopies R2 is the rule; this is the scenario that puts
        // the pipeline into the state where it can be broken.
        armComposing("hel", at = " ", cursor = 0)
        h.inputLogic.mCurrentSuggestions = suggestions("hello")

        h.inputLogic.handleManualPick(
            settings(), h.inputLogic.mCurrentSuggestions.getWordInfo(0),
            symbolPages(), 0, h.uiHandler, InputSource.SOFTWARE,
        )

        h.fourCopies().assertConsistent()
    }
}
