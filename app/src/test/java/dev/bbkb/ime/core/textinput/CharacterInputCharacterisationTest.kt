package dev.bbkb.ime.core.textinput

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
 * CHARACTERISATION scenarios for the IN-WORD INSERTION stage of
 * [InputLogic.handleCharacterInput] — the branch that runs when the caret sits inside the
 * composing word and a new character arrives.
 *
 * ## Why this stage specifically
 *
 * It is the part of the character path with the longest bug history and the least coverage. The
 * tracker's own `insertCodePointAtCursor` is well tested in `ComposingTextTrackerTest`, but the
 * stage around it — which computes where the editor's composing region starts, re-sends the whole
 * word, and then places the editor caret inside that region — was tested nowhere. Two separate
 * defects lived in exactly that arithmetic:
 *
 *  * the branch used to truncate the buffer at the caret and fall through to the append path,
 *    which silently dropped every character after the caret (2026-05 composing spec #6);
 *  * it then used a plain `setSelection`, which left the RIC's text model holding the composing
 *    prefix in BOTH `mTextBeforeCursor` and `mComposingText` — corrupting caps mode, punctuation
 *    and backspace decisions until the next `resetConnection` (2026-06 audit F5). The fix is
 *    `setSelectionWithinComposing`, and the assertion that proves it is the four-copies invariant
 *    plus a `getCodePointBeforeCursor()` read.
 *
 * Both fixes are invisible in the final text of a single keystroke, which is why these scenarios
 * assert the RIC's view as well as the editor's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CharacterInputCharacterisationTest {

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

    private fun settings(): SettingsValues = h.settingsWith(
        "isAutoCorrectionEnabledPerUserSettings" to true,
        "shouldShowLxxButton" to true,
        "editorCapabilities" to EditorCapabilities(
            EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                packageName = "com.example.app"
            },
            /* allowsAppSpecifiedCompletions = */ false,
            /* keyboardPackageName = */ "dev.bbkb.ime.debug",
            Locale.US,
            /* forceSuggestions = */ false,
        ),
    )

    /**
     * Send one character through the real `handleCharacterInput`. It and its callers are private,
     * so reflection is the way in — the same tactic the separator scenarios use.
     */
    private fun character(
        codePoint: Int,
        sv: SettingsValues = settings(),
        commitType: Int = 0,
        forceSeparator: Boolean = false,
    ): InputEventContext {
        val event = InputEvent.createKeyPress(codePoint, -1, -4, -4, 1000L, false)
        val ctx = InputEventContext(sv, event, 1000L, commitType, 0)
        ctx.setInputSource(InputSource.HARDWARE)
        val m = InputLogic::class.java.getDeclaredMethod(
            "handleCharacterInput",
            InputEvent::class.java,
            SettingsValues::class.java,
            InputEventContext::class.java,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        m.invoke(h.inputLogic, event, sv, ctx, forceSeparator)
        return ctx
    }

    /**
     * Arm a composing word with the caret parked [atCodePoint] code points into it.
     *
     * BOTH halves have to move, and that is the stage's unstated precondition: it derives the
     * editor's composing-region start as `getCursorEnd() - charsBeforeCaretWithinComposing`, so
     * moving only the tracker leaves it computing a region start past the end of the word. In
     * production the two are moved together by `onUpdateSelection` → `moveCursorByCharCount`
     * (the editor reports the tap, the tracker follows); here
     * `setSelectionWithinComposing` is the equivalent, and it is the same call the stage itself
     * makes — which also keeps the RIC text model from double-counting the prefix while arming.
     *
     * The word is composed at offset 0, so its region start is 0 and the editor caret is simply
     * the char offset of [atCodePoint].
     */
    private fun armComposingWithCaretInside(typed: String, atCodePoint: Int) {
        h.startSession("", 0)
        h.inputLogic.mRichInputConnection.setComposingText(typed, 1)
        h.seedComposing(typed)
        h.inputLogic.mComposingTracker.setComposingCursorPosition(atCodePoint)
        val caretCharOffset = Character.offsetByCodePoints(typed, 0, atCodePoint)
        h.inputLogic.mRichInputConnection.setSelectionWithinComposing(caretCharOffset, 0)
        h.editor.dropPendingSelectionEvents()
        h.editor.callLog.clear()
        assertTrue(
            "precondition: the tracker must report the caret inside the word",
            h.inputLogic.mComposingTracker.isCursorMoved,
        )
        assertEquals(
            "precondition: the editor caret must mirror the tracker's in-word position",
            caretCharOffset, h.editor.selStart,
        )
    }

    private fun log(): String = h.editor.callLog.joinToString(" ")

    // ── the in-word insertion stage ──────────────────────────────────────────────

    @Test
    fun aCharacterTypedInsideTheWord_isInsertedThereAndKeepsTheTail() {
        // The 2026-05 spec #6 regression: the old code truncated at the caret, so "helXo" came
        // out instead of "heXllo" — every character after the caret was dropped.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        character('X'.code)

        assertEquals("heXllo", h.inputLogic.mComposingTracker.composingText)
        assertEquals("heXllo", h.editor.text)
    }

    @Test
    fun theEditorCaretLandsRightAfterTheInsertedCharacter() {
        armComposingWithCaretInside("hello", atCodePoint = 2)

        character('X'.code)

        assertEquals("caret must sit between the X and the first l", 3, h.editor.selStart)
        assertEquals(3, h.inputLogic.mComposingTracker.getComposingCursorPos())
    }

    @Test
    fun theWholeWordIsReSentAsOneComposingUpdate_notAnInsertAtTheCaret() {
        // The editor call sequence is the load-bearing part: ONE setComposingText carrying the
        // full new word (which replaces the existing region), then the selection write that puts
        // the caret back inside it. Anything that inserted only the new character would leave the
        // editor's region and the tracker disagreeing.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        character('X'.code)

        assertEquals(
            """setComposingText("heXllo",1) setSelection(3,3)""", log(),
        )
    }

    @Test
    fun theRicTextModelDoesNotDoubleCountTheComposingPrefix() {
        // Audit F5, asserted where it is actually observable.
        //
        // The RIC models the field as (textBeforeRegion | composingRegion | textAfter), and
        // `getTextBeforeCursor` returns `textBeforeRegion + composingRegion`. A plain
        // `setSelection(3,3)` mid-region makes the editor report "heX" as its text-before-cursor,
        // which lands in `mTextBeforeCursor` while `mComposingText` still holds the whole
        // "heXllo" — so the prefix appears TWICE and every downstream reader (caps mode,
        // punctuation, backspace) sees "heXheXllo". `setSelectionWithinComposing` trims the
        // prefix back off, which is what this asserts.
        //
        // `getCodePointBeforeCursor()` is -1 here and that is correct, not a bug: with the word
        // composed at offset 0 there genuinely is nothing before the REGION, and "he" is inside
        // it rather than before it.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        character('X'.code)

        assertEquals(
            "the composing prefix must appear once, not twice",
            "heXllo", h.inputLogic.mRichInputConnection.getTextBeforeCursor(40, 0).toString(),
        )
        h.fourCopies().assertConsistent()
    }

    @Test
    fun insertingAtTheVeryStartOfTheWord_stillKeepsTheWholeWord() {
        armComposingWithCaretInside("hello", atCodePoint = 0)

        character('X'.code)

        assertEquals("Xhello", h.inputLogic.mComposingTracker.composingText)
        assertEquals("Xhello", h.editor.text)
        assertEquals(1, h.editor.selStart)
        h.fourCopies().assertConsistent()
    }

    @Test
    fun aSupplementaryCodePointInsertedMidWord_advancesTheCaretByTwoCharUnits() {
        // The code-point vs char-unit distinction the SS-4 audit finding was about: the tracker
        // counts code points, the editor counts UTF-16 units, and the caret arithmetic has to
        // convert. An emoji is one code point and two char units.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        character(0x1F600)

        assertEquals("he😀llo", h.editor.text)
        assertEquals(
            "two char units past 'he', not one",
            4, h.editor.selStart,
        )
        h.fourCopies().assertConsistent()
    }

    @Test
    fun theStageRequestsASuggestionRefreshAndReturnsWithoutCommitting() {
        // The branch ends in `setShouldUpdateSuggestions(); return;` — it must not fall through
        // into the append / multi-tap / commitCharacter paths below it.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        val ctx = character('X'.code)

        assertTrue(ctx.shouldUpdateSuggestions())
        assertEquals(
            "no commitText may happen: the word is still composing",
            0, h.editor.callLog.count { it.startsWith("commitText") },
        )
    }
}
