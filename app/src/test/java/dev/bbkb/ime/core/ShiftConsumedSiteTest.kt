package dev.bbkb.ime.core

import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.keyevent.KeyCharacterInterpreter
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.harness.PipelineHarness
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Phase 1h: the three reads Phase 1f had to leave on the tracker, now asked through
 * `ModifierState`, checked in the one state that kept them there: a physical Shift that is still
 * held after Alt was pressed with it (CONSUMED). Each site is driven by a real
 * [PhysicalKeyboardStateTracker] fed real key presses, so the state is the one the KEY2 reaches.
 *
 * - `InputLogic.handleEnterKey`: Shift+Enter in a single-line field must NOT insert a newline
 *   while the Shift is consumed (it is not a pending manual shift), and must still insert one for
 *   a tapped (sticky) Shift.
 * - `BlackBerryIME.isShiftChording()`: a consumed Shift is not released, so no chord.
 * - `BlackBerryIME.isMetaKeyActive()`: its "Shift not released" term is true while consumed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class ShiftConsumedSiteTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>
    private lateinit var tracker: PhysicalKeyboardStateTracker

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

        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.PKB,
            /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ false,
            /* isBlackBerryDevice */ false, "qwerty", "4row"))
        ScancodeMappingResolver.getInstance().reset()
        KeyEventDeviceClassifier.getInstance().clearCache()

        tracker = Mockito.spy(PhysicalKeyboardStateTracker(null))
        `when`(h.ime.getPhysicalKeyboardStateTracker()).thenReturn(tracker)
    }

    @After
    fun tearDown() {
        ScancodeMappingResolver.getInstance().reset()
        KeyEventDeviceClassifier.getInstance().clearCache()
        DeviceProfile.initialize(null)
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private val SINGLE_LINE = InputType.TYPE_CLASS_TEXT

    private fun editorInfo(inputType: Int) = EditorInfo().apply {
        this.inputType = inputType
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        packageName = "com.example.app"
    }

    private fun settings(): SettingsValues = h.settingsWith(
        "locale" to Locale.US,
        "editorCapabilities" to EditorCapabilities(
            editorInfo(SINGLE_LINE), false, "dev.bbkb.ime.debug", Locale.US, false),
    )

    private fun event(action: Int, keyCode: Int) = KeyEvent(
        0L, 0L, action, keyCode, /* repeat */ 0, /* metaState */ 0, /* deviceId */ 0,
        /* scanCode */ keyCode, /* flags */ 0, InputDevice.SOURCE_KEYBOARD)

    private fun down(keyCode: Int) = tracker.handleKeyDown(keyCode, event(KeyEvent.ACTION_DOWN, keyCode))
    private fun up(keyCode: Int) = tracker.handleKeyUp(keyCode, event(KeyEvent.ACTION_UP, keyCode))
    private fun tap(keyCode: Int) { down(keyCode); up(keyCode) }

    /** Shift held, then Alt pressed with it: both keys are CONSUMED, Shift is still down. */
    private fun consumeShiftWithAlt() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT)
        down(KeyEvent.KEYCODE_ALT_LEFT)
    }

    /**
     * One hardware Enter through the real `handleEnterKey` (private, so reflection, as the other
     * InputLogic characterisation tests do). `shiftPressed` is what `processInputEvent` would have
     * copied from the symbol-page provider; false here so the tracker read is what decides.
     */
    private fun enter(inputType: Int): InputEventContext {
        `when`(h.ime.getCurrentInputEditorInfo()).thenReturn(editorInfo(inputType))
        h.startSession("abc")
        val ev = InputEvent.createKeyPress(10, KeyEvent.KEYCODE_ENTER, -4, -4, 1000L, false)
        val ctx = InputEventContext(settings(), ev, 1000L, 0, 0)
        ctx.setInputSource(InputSource.HARDWARE)
        ctx.setShiftPressed(false)
        InputLogic::class.java.getDeclaredMethod("handleEnterKey", InputEventContext::class.java)
            .apply { isAccessible = true }
            .invoke(h.inputLogic, ctx)
        return ctx
    }

    private fun insertedNewline(): Boolean =
        h.editor.getText().contains('\n') ||
            h.editor.sentKeyEvents.any { it.keyCode == KeyEvent.KEYCODE_ENTER }

    // ── InputLogic: Shift+Enter ─────────────────────────────────────────────────

    @Test
    fun enterInASingleLineFieldWithAConsumedShiftDoesNotInsertANewline() {
        consumeShiftWithAlt()

        val ctx = enter(SINGLE_LINE)

        assertFalse("a Shift consumed by Alt is not a manual shift: no newline", insertedNewline())
        assertTrue("Enter is left to the editor action", ctx.hasPendingEditorAction())
    }

    @Test
    fun enterInASingleLineFieldWithAConsumedShiftThenAltReleasedDoesNotInsertANewline() {
        consumeShiftWithAlt()
        up(KeyEvent.KEYCODE_ALT_LEFT)

        val ctx = enter(SINGLE_LINE)

        assertFalse("still consumed while Shift is held", insertedNewline())
        assertTrue(ctx.hasPendingEditorAction())
    }

    /** Control: the manual shift the check exists for still turns Enter into a newline. */
    @Test
    fun enterInASingleLineFieldWithATappedShiftInsertsANewline() {
        tap(KeyEvent.KEYCODE_SHIFT_LEFT)

        val ctx = enter(SINGLE_LINE)

        assertTrue("Shift+Enter inserts a newline", insertedNewline())
        assertFalse(ctx.hasPendingEditorAction())
    }

    /** Control: letting go of the consumed Shift leaves it sticky, which IS a manual shift. */
    @Test
    fun releasingTheConsumedShiftMakesItAManualShiftAgain() {
        consumeShiftWithAlt()
        up(KeyEvent.KEYCODE_SHIFT_LEFT)

        enter(SINGLE_LINE)

        assertTrue(insertedNewline())
    }

    /** Control: no Shift at all, no newline. */
    @Test
    fun enterInASingleLineFieldWithNoShiftDoesNotInsertANewline() {
        val ctx = enter(SINGLE_LINE)

        assertFalse(insertedNewline())
        assertTrue(ctx.hasPendingEditorAction())
    }

    /** The first half of the `||` short-circuits: no snapshot when the context already has a manual shift. */
    @Test
    fun enterDoesNotBuildASnapshotWhenTheEventAlreadyCarriedShift() {
        `when`(h.ime.getCurrentInputEditorInfo()).thenReturn(editorInfo(SINGLE_LINE))
        h.startSession("abc")
        val ev = InputEvent.createKeyPress(10, KeyEvent.KEYCODE_ENTER, -4, -4, 1000L, false)
        val ctx = InputEventContext(settings(), ev, 1000L, 0, 0)
        ctx.setInputSource(InputSource.HARDWARE)
        ctx.setShiftPressed(true)
        InputLogic::class.java.getDeclaredMethod("handleEnterKey", InputEventContext::class.java)
            .apply { isAccessible = true }
            .invoke(h.inputLogic, ctx)

        assertTrue(insertedNewline())
        verify(tracker, never()).getModifierState()
    }

    // ── BlackBerryIME: isShiftChording / isMetaKeyActive ─────────────────────────

    private fun ime(shiftKeyReleasing: Boolean): BlackBerryIME {
        val ime = Mockito.mock(BlackBerryIME::class.java, Mockito.CALLS_REAL_METHODS)
        val switcher = Mockito.mock(KeyboardSwitcher::class.java)
        `when`(switcher.isShiftKeyReleasing()).thenReturn(shiftKeyReleasing)
        ReflectionHelpers.setField(ime, "keyboardSwitcher", switcher)
        ReflectionHelpers.setField(ime, "physicalKeyboardStateTracker", tracker)
        return ime
    }

    @Test
    fun aConsumedShiftIsNotAShiftChord() {
        consumeShiftWithAlt()

        assertFalse("consumed is not released", ime(shiftKeyReleasing = true).isShiftChording())
    }

    @Test
    fun aReleasedShiftIsAShiftChordWhenTheScreenShiftIsReleasing() {
        assertTrue(ime(shiftKeyReleasing = true).isShiftChording())
        consumeShiftWithAlt()
        up(KeyEvent.KEYCODE_ALT_LEFT)
        up(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertTrue("released again", ime(shiftKeyReleasing = true).isShiftChording())
    }

    /** The tracker is only asked when the on-screen Shift is releasing. */
    @Test
    fun shiftChordingBuildsNoSnapshotUnlessTheScreenShiftIsReleasing() {
        assertFalse(ime(shiftKeyReleasing = false).isShiftChording())
        verify(tracker, never()).getModifierState()
    }

    /**
     * `isMetaKeyActive()` = Shift not released AND shift-locked for the layout AND Alt not used
     * with a key. The layout lock is driven by a keyboard meta mask that sets the 0x100 bit (the
     * only way to have it while a Shift key is down), so the Shift-released term is what decides.
     */
    @Test
    fun aConsumedShiftCountsAsNotReleasedForMetaKeyActive() {
        consumeShiftWithAlt()
        tracker.updateFilterAndAltGr(0, KeyCharacterInterpreter.MetaMask(0x100, 0))

        assertTrue("consumed Shift + layout lock: meta key active",
            ime(shiftKeyReleasing = false).isMetaKeyActive())
    }

    @Test
    fun metaKeyActiveIsFalseOnceTheConsumedShiftIsReleased() {
        consumeShiftWithAlt()
        tracker.updateFilterAndAltGr(0, KeyCharacterInterpreter.MetaMask(0x100, 0))
        up(KeyEvent.KEYCODE_ALT_LEFT)
        up(KeyEvent.KEYCODE_SHIFT_LEFT)

        assertFalse(ime(shiftKeyReleasing = false).isMetaKeyActive())
    }

    @Test
    fun metaKeyActiveIsFalseWhenTheConsumedShiftWasSpentOnALetter() {
        down(KeyEvent.KEYCODE_SHIFT_LEFT)
        down(KeyEvent.KEYCODE_A)
        tracker.consumeModifiersAfterKey(KeyEvent.KEYCODE_A, true)
        tracker.updateFilterAndAltGr(0, KeyCharacterInterpreter.MetaMask(0x100, 0))

        assertFalse("Alt-used-with-key vetoes it", ime(shiftKeyReleasing = false).isMetaKeyActive())
    }
}
