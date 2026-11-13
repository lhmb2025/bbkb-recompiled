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
import org.junit.Assert.assertFalse
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
 * Typing over a selection while the editor's selection reports lag behind the keys.
 *
 * The keyboard moves its cursor cache the moment it types; the editor's report of that edit
 * ("[start,end] collapsed to start + typed length") arrives later. When another key has landed
 * first, the late report must be read as the keyboard's own edit, not as the user moving the
 * cursor, or the word being typed is ended after its first letter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class TypingOverSelectionTest {

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
            false, "dev.bbkb.ime.debug", Locale.US, false,
        ),
    )

    /** One character through the real (private) handleCharacterInput, as the other scenarios do. */
    private fun type(text: String) {
        for (ch in text) {
            val sv = settings()
            val event = InputEvent.createKeyPress(ch.code, -1, -4, -4, 1000L, false)
            val ctx = InputEventContext(sv, event, 1000L, 0, 0)
            ctx.setInputSource(InputSource.HARDWARE)
            InputLogic::class.java.getDeclaredMethod(
                "handleCharacterInput", InputEvent::class.java, SettingsValues::class.java,
                InputEventContext::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(h.inputLogic, event, sv, ctx, false)
        }
    }

    private fun deliverAll() {
        while (h.editor.hasPendingSelectionEvents()) h.deliverSelectionEvent(settings())
    }

    /** The keyboard learns of the selection the normal way: the editor reports it. */
    private fun selectKnown(text: String, start: Int, end: Int) {
        h.startSession(text, text.length)
        h.editor.appSetSelection(start, end)
        deliverAll()
    }

    private fun assertState(text: String, composing: String, cursor: Int) {
        val ric = h.inputLogic.mRichInputConnection
        val state = "text='${h.editor.text}' editor=[${h.editor.selStart},${h.editor.selEnd}] " +
            "cache=[${ric.cursorStart},${ric.cursorEnd}] " +
            "composing='${h.inputLogic.mComposingTracker.composingText}'"
        assertEquals(state, text, h.editor.text)
        assertEquals(state, cursor, h.editor.selStart)
        assertEquals(state, cursor, ric.cursorStart)
        assertEquals(state, cursor, ric.cursorEnd)
        assertEquals(state, composing, h.inputLogic.mComposingTracker.composingText)
    }

    @Test
    fun aWordSelection_twoQuickKeys_keepTheWordBeingTyped() {
        selectKnown("cat dog", 4, 7)
        type("xy")
        deliverAll()
        assertState("cat xy", "xy", 6)
    }

    @Test
    fun aWordSelection_threeQuickKeys_keepTheWordBeingTyped() {
        selectKnown("cat dog house", 4, 7)
        type("xyz")
        deliverAll()
        assertState("cat xyz house", "xyz", 7)
    }

    @Test
    fun aOneLetterSelection_quickKeys_keepTheWordBeingTyped() {
        selectKnown("cat dog", 4, 5)
        type("xyz")
        deliverAll()
        assertState("cat xyzog", "xyz", 7)
    }

    @Test
    fun aWordSelection_reportsBetweenKeys_keepTheWordBeingTyped() {
        selectKnown("cat dog", 4, 7)
        type("x"); deliverAll()
        type("y"); deliverAll()
        assertState("cat xy", "xy", 6)
    }

    @Test
    fun aRealCursorMoveAfterTheKeys_stillEndsTheWord() {
        // The late report of the keyboard's own edit is swallowed; the user's tap after it is not.
        selectKnown("cat dog", 4, 7)
        type("xy")
        h.editor.appSetSelection(1, 1)
        deliverAll()
        assertFalse(h.inputLogic.mComposingTracker.isComposing)
        assertEquals(1, h.inputLogic.mRichInputConnection.cursorStart)
        assertEquals(1, h.inputLogic.mRichInputConnection.cursorEnd)
        assertEquals("cat xy", h.editor.text)
    }

    @Test
    fun theAppCollapsingTheSelection_isStillAMove() {
        // No key was typed, so nothing was recorded: the app's own collapse is a real change.
        selectKnown("cat dog", 4, 7)
        h.editor.appSetSelection(6, 6)
        deliverAll()
        assertEquals(6, h.inputLogic.mRichInputConnection.cursorStart)
        assertEquals(6, h.inputLogic.mRichInputConnection.cursorEnd)
    }
}
