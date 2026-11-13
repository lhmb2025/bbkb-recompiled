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
 * Characterises every commit entry point of the typing path ([CommitController] plus the
 * [InputLogic] methods that commit directly; test names carry the epN number). Each scenario pins
 * [dev.bbkb.ime.harness.FakeEditor.callLog] as well as text, cursor and region: the same final
 * text reached by a different sequence of editor calls is a different result to a real editor.
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

        // commitGestureSuggestion ends by clearing the manual shift, which NPEs on the bare IME
        // mock's null tracker.
        `when`(h.ime.getPhysicalKeyboardStateTracker())
            .thenReturn(mock(PhysicalKeyboardStateTracker::class.java))
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

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

    /** Alphabet page, no manual shift. */
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

    @Test
    fun ep2_commitOrResetComposing_whileComposing_commitsWord() {
        armComposing("world")

        h.inputLogic.commitOrResetComposing(settings(), InputSource.HARDWARE)

        assertEquals("world", h.editor.text)
        assertEquals("""commitText("world",1)""", log())
    }

    @Test
    fun ep2_commitOrResetComposing_cursorMovedMidWord_resetsInsteadOfCommitting() {
        // The tracker believes the caret is inside the word, so this abandons the commit and
        // re-selects instead: no commitText is made.
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

    @Test
    fun ep3_commitTypedWord_appendsSeparatorAsASecondCommit() {
        // The word and the separator are two commitText calls; fusing them changes what the editor sees.
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
        // The append gate in commitWordExtended requires a separator-class payload; a letter is dropped.
        armComposing("hi")

        h.inputLogic.commitTypedWord(settings(), "x", InputSource.HARDWARE)

        assertEquals("hi", h.editor.text)
        assertEquals("""commitText("hi",1)""", log())
    }

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

    @Test
    fun ep5_commitWordExtended_forceSeparator_appendsAWordCharacterPayload() {
        // The `z` flag is the only way a non-separator payload gets appended (CommitVoiceInputTest relies on it).
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
        // mShouldAppendSpace means a separator already follows the cursor, so the commit steps over
        // it (newCursorPosition=2) and must not write one of its own.
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
        // With nothing composing there is no correction: returns false and the caller keeps the
        // separator payload.
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

    @Test
    fun ep9_commitTouchEventText_withNoActiveHighlight_isANoOp() {
        armIdle("hi", 2)

        h.inputLogic.commitTouchEventText()

        assertEquals("hi", h.editor.text)
        assertEquals("", log())
    }

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
        // A space already follows the caret, so the pick commits with newCursorPosition=2 to land
        // past it instead of adding a second one.
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

    @Test
    fun ep11_processClipboardMarker_stripsBoundaryMarkers() {
        assertEquals("hello", h.inputLogic.processClipboardMarker("hello"))
        assertEquals("hello", h.inputLogic.processClipboardMarker("hello%B."))
        assertEquals("hello", h.inputLogic.processClipboardMarker("hello%b"))
    }

    @Test
    fun ep11_processClipboardMarker_leadingLowerMarker_retiresTheRegionBeforeDeleting() {
        // The delete means "the char before the composing region" to a real editor, so the region
        // has to be retired first or the cache and the editor disagree by one.
        armComposing("x")

        val cleaned = h.inputLogic.processClipboardMarker("%bhello")

        assertEquals("hello", cleaned)
        assertEquals(
            "finishComposingText must precede the delete",
            """finishComposingText() deleteSurroundingText(1,0)""", log(),
        )
    }

    /**
     * Drives the prediction-word protocol with the `LocaleUtils` statics mocked (the only seam).
     * `updateSuggestionsSync` is safe to run: with `shouldShowLxxButton` false it only calls the
     * mock strip listener, so no worker thread is involved.
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
        // The pick is written with no suggestion span, then the separator. The re-compose step
        // (`setComposingTextInternal`) does not fire: the pick consumed the whole buffer, and
        // `setComposingTextWithHighlight` returns early on a zero-length sequence.
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
        // The only scenario where `setComposingTextInternal` reaches the editor: a pick whose
        // `spell` ("ni") is shorter than the buffer ("nihao") splits it, so the editor sees the pick,
        // then "hao" re-sent as a fresh composing region, then the separator.
        // The split also needs `getPointerSize() > splitIndex`, hence `seedComposing` (one
        // coordinate pair per code point) rather than writing to the editor directly.
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
        // A kind-7 Japanese pick clears the engine buffer and the tracker, so the re-compose writes
        // nothing back.
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
        // Deliberate: under a CJK locale `isWordSeparator` negates
        // `isNotWordSeparatorForLocale(cp, subtypeLocale)`, so an ASCII letter reads as a separator
        // and is appended (the Latin path drops it, see
        // `ep3_commitTypedWord_wordCharacterSeparator_isNotAppended`). The prediction gate has no
        // forceSeparator term, so the locale alone decides.
        armComposing("ni")

        predictionPick("你", separator = "x")

        assertEquals("""commitText("你",1) commitText("x",1)""", log())
    }

    @Test
    fun everyCommitEntryPoint_leavesTheCopiesConsistent() {
        // Run after each protocol; FourCopies R2/R3 cover the cursor rules too.
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
        // The manual-pick path commits with newCursorPosition=2 to step over a following separator,
        // and the RIC cache has to follow (FourCopies R2).
        armComposing("hel", at = " ", cursor = 0)
        h.inputLogic.mCurrentSuggestions = suggestions("hello")

        h.inputLogic.handleManualPick(
            settings(), h.inputLogic.mCurrentSuggestions.getWordInfo(0),
            symbolPages(), 0, h.uiHandler, InputSource.SOFTWARE,
        )

        h.fourCopies().assertConsistent()
    }
}
