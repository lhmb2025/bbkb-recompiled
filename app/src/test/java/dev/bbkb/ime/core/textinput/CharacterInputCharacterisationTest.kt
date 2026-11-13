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
 * Characterises the in-word insertion stage of [InputLogic.handleCharacterInput] (caret inside the
 * composing word): the region start, the full-word re-send and the caret placed back inside it via
 * `setSelectionWithinComposing`. Asserts the RIC's view as well as the editor's, since a wrong text
 * model does not show in the final text.
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

    /** Sends one character through the private `handleCharacterInput` by reflection. */
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
     * Arm a composing word with the caret [atCodePoint] code points in. Both the editor and the
     * tracker must move (the stage derives the region start as `getCursorEnd() - charsBeforeCaret`);
     * `setSelectionWithinComposing` does that here as `onUpdateSelection` does in production.
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

    @Test
    fun aCharacterTypedInsideTheWord_isInsertedThereAndKeepsTheTail() {
        // Every character after the caret must survive: "heXllo", not "helXo".
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
        // One setComposingText with the full new word (replacing the region), then the selection write
        // that puts the caret back inside it.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        character('X'.code)

        assertEquals(
            """setComposingText("heXllo",1) setSelection(3,3)""", log(),
        )
    }

    @Test
    fun theRicTextModelDoesNotDoubleCountTheComposingPrefix() {
        // The RIC models (textBeforeRegion | composingRegion | textAfter). A plain setSelection(3,3)
        // mid-region would put "heX" in `mTextBeforeCursor` too, so readers (caps mode, punctuation,
        // backspace) see "heXheXllo"; `setSelectionWithinComposing` trims it back off.
        // `getCodePointBeforeCursor()` is -1 because the word sits at offset 0: "he" is inside the region.
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
        // The tracker counts code points, the editor UTF-16 units: an emoji is one code point, two chars.
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
        // The branch ends in `setShouldUpdateSuggestions(); return;` and must not fall through into
        // the append / multi-tap / commitCharacter paths.
        armComposingWithCaretInside("hello", atCodePoint = 2)

        val ctx = character('X'.code)

        assertTrue(ctx.shouldUpdateSuggestions())
        assertEquals(
            "no commitText may happen: the word is still composing",
            0, h.editor.callLog.count { it.startsWith("commitText") },
        )
    }

    /**
     * An app can change the text without moving the caret, so no selection update comes (Messages:
     * type "hi", cut, undo, redo leaves the field empty with the caret at 0). Typing A must not
     * rewrite the tracker's stale word back as "AHi".
     */
    @Test
    fun aWordTheAppEmptiedWithoutMovingTheCaret_isNotWrittenBack() {
        armComposingWithCaretInside("Hi", atCodePoint = 0)
        h.editor.appSetText("", 0, 0)
        h.editor.dropPendingSelectionEvents()   // the caret did not move, so none was sent

        character('a'.code)

        assertEquals("a", h.editor.text)
        assertEquals("a", h.inputLogic.mComposingTracker.composingText)
        assertEquals(1, h.editor.selStart)
    }

    @Test
    fun aWordTheAppReplacedWithoutMovingTheCaret_isNotWrittenBackEither() {
        armComposingWithCaretInside("hello", atCodePoint = 0)
        h.editor.appSetText("bye", 0, 0)
        h.editor.dropPendingSelectionEvents()

        character('X'.code)

        assertEquals("Xbye", h.editor.text)
        assertTrue("the old word must be gone", !h.editor.text.contains("hello"))
    }
}
