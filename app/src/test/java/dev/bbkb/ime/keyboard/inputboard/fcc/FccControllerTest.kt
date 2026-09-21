package dev.bbkb.ime.keyboard.inputboard.fcc

import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.textinput.CursorTracker
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.InputMethodHelper
import dev.bbkb.ime.core.textinput.connection.RichInputConnection
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
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
 * Characterisation tests for [FccController] — the cursor-mode ("fine cursor control") board's
 * controller.
 *
 * The FCC board had **no test coverage at all** (1,046 code lines across three files). These cases
 * pin **what the controller does today**, not what it ought to do, so a rewrite has to reproduce it
 * or deliberately change it rather than drift silently. Assertions recording behaviour that is
 * plainly wrong are marked `CHARACTERISED BUG:`.
 *
 * What is covered: the direction→cursor-move mapping, the synthetic held-shift that *is* select
 * mode (and which of the two shift keycodes `controlMode` chooses), the RTL-aware
 * collapse-the-selection arithmetic on copy/cut/paste, the open gate that refuses to open the board
 * while a physical key is held, the close sequence and its device-specific closing-flag quirk, and
 * the flag the suggestion strip consumes.
 *
 * What is not covered, and why: [FccView] itself is a 621-line `RelativeLayout` with two complete
 * alternative UIs selected at inflate time from the theme, driven by raw `MotionEvent`s against
 * laid-out arrow/nub geometry. It is mocked here — so the *view's* own arithmetic
 * ([FccCursorTouchListener]'s nub travel, thresholds and accelerating repeat delay) is a reported
 * gap, not something these tests pin.
 *
 * The controller reaches four singletons ([SettingsManager], [SubtypeManager], [InputMethodHelper],
 * [DeviceProfile]) which are static-mocked, and the view it drives is injected into `mFccView`
 * exactly as `bindView` would leave it (one case drives `bindView` itself).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class FccControllerTest {

    private lateinit var controller: FccController
    private lateinit var ime: BlackBerryIME
    private lateinit var inputLogic: InputLogic
    private lateinit var connection: RichInputConnection
    private lateinit var keysDown: PhysicalKeyboardStateTracker
    private lateinit var cursorTracker: CursorTracker
    private lateinit var keyboardSwitcher: KeyboardSwitcher
    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var view: FccView
    private lateinit var listener: FccController.Listener

    private lateinit var settingsStatic: MockedStatic<SettingsManager>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var helperStatic: MockedStatic<InputMethodHelper>
    private lateinit var profileStatic: MockedStatic<DeviceProfile>

    private lateinit var settings: SettingsValues
    private lateinit var helper: InputMethodHelper
    private lateinit var profile: DeviceProfile

    @Before
    fun setUp() {
        ime = mock(BlackBerryIME::class.java)
        inputLogic = mock(InputLogic::class.java)
        connection = mock(RichInputConnection::class.java)
        keysDown = mock(PhysicalKeyboardStateTracker::class.java)
        keyboardSwitcher = mock(KeyboardSwitcher::class.java)
        view = mock(FccView::class.java)
        listener = mock(FccController.Listener::class.java)

        // mRichInputConnection is a public FINAL field, so it is injected rather than assigned.
        ReflectionHelpers.setField(InputLogic::class.java, inputLogic, "mRichInputConnection", connection)
        `when`(ime.getInputLogic()).thenReturn(inputLogic)
        `when`(ime.getPhysicalKeyboardStateTracker()).thenReturn(keysDown)
        `when`(ime.getKeyboardSwitcher()).thenReturn(keyboardSwitcher)
        uim = mock(UnifiedInputBoardManager::class.java)
        `when`(keyboardSwitcher.unifiedInputBoardManager).thenReturn(uim)
        cursorTracker = mock(CursorTracker::class.java)
        `when`(ime.getCursorTracker()).thenReturn(cursorTracker)

        settings = mock(SettingsValues::class.java)
        controlMode(1) // the default: right shift
        val settingsManager = mock(SettingsManager::class.java)
        `when`(settingsManager.settingsValues).thenReturn(settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(settingsManager)

        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)

        helper = mock(InputMethodHelper::class.java)
        helperStatic = mockStatic(InputMethodHelper::class.java)
        helperStatic.`when`<InputMethodHelper> { InputMethodHelper.getInstance() }.thenReturn(helper)

        profile = mock(DeviceProfile::class.java)
        profileStatic = mockStatic(DeviceProfile::class.java)
        profileStatic.`when`<DeviceProfile> { DeviceProfile.current() }.thenReturn(profile)

        controller = FccController(ime, listener)
        attachView()
    }

    @After
    fun tearDown() {
        profileStatic.close()
        helperStatic.close()
        subtypeStatic.close()
        settingsStatic.close()
    }

    /**
     * `ArgumentMatchers.any()` and `ArgumentCaptor.capture()` both hand back `null`, which Kotlin
     * refuses to pass to a non-nullable parameter. The matcher is still registered by the call, so
     * casting the null through an unbounded type parameter is the standard way to use them here.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = Mockito.any<T>() as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> ArgumentCaptor<T>.captured(): T = capture() as T

    /** `controlMode` is a public FINAL field, so it is injected rather than assigned. */
    private fun controlMode(mode: Int) =
        ReflectionHelpers.setField(SettingsValues::class.java, settings, "controlMode", mode)

    /** Leave the controller holding [view], exactly as `bindView` would. */
    private fun attachView() =
        ReflectionHelpers.setField(FccController::class.java, controller, "mFccView", view)

    private fun boardIsUp() {
        `when`(view.isShowing).thenReturn(true)
    }

    private fun selectionIs(start: Int, end: Int) {
        `when`(connection.cursorStart).thenReturn(start)
        `when`(connection.cursorEnd).thenReturn(end)
    }

    private fun rtl() {
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale("ar", "EG"))
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)
    }

    /** Assert the selection was collapsed to exactly [offset] — both ends, once. */
    private fun assertCollapsedTo(offset: Int) = verify(connection).setSelection(offset, offset)

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Cursor movement
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun eachDirectionMovesTheCaretOneStepThatWay() {
        controller.onMove(FccView.Direction.LEFT)
        controller.onMove(FccView.Direction.RIGHT)
        controller.onMove(FccView.Direction.UP)
        controller.onMove(FccView.Direction.DOWN)

        verify(ime).moveLeft(1)
        verify(ime).moveRight(1)
        verify(ime).moveUp(1)
        verify(ime).moveDown(1)
    }

    @Test
    fun aMoveIsAlwaysExactlyOneStepHoweverFarTheNubTravelled() {
        // The repeat pump calls onMove once per tick; distance changes the tick RATE, never the
        // step size. Nothing in the controller can emit a multi-character jump.
        repeat(5) { controller.onMove(FccView.Direction.RIGHT) }

        verify(ime, Mockito.times(5)).moveRight(1)
        verify(ime, never()).moveRight(5)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. The action keys — every one carries the shift-held meta state
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun everyActionKeyIsSentWithTheShiftHeldMetaState() {
        controller.onKeyDown(31) // copy
        controller.onKeyUp(31)

        verify(inputLogic).sendKeyDownWithMeta(31, META_SHIFT_HELD)
        verify(inputLogic).sendKeyUpWithMeta(31, META_SHIFT_HELD)
    }

    @Test
    fun selectAllHidesTheCursorTrackerFirstButOtherKeysDoNot() {
        controller.onKeyDown(29) // select all
        verify(cursorTracker).hide()

        Mockito.clearInvocations(cursorTracker)
        controller.onKeyDown(52) // cut
        verify(cursorTracker, never()).hide()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Select mode is a synthetic held shift
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun engagingSelectModeHoldsTheShiftKeyDown() {
        // The view flips its own mSelectMode BEFORE calling back, so "the view is in select mode"
        // is the controller's signal that the mode was just entered.
        `when`(view.isSelectMode).thenReturn(true)

        controller.onToggleSelect(1)

        assertTrue("the controller tracks the mode", controller.isSelectModeActive)
        verify(ime).onKeyDown(Mockito.eq(KEYCODE_SHIFT_RIGHT), anyArg())
        verify(ime, never()).onKeyUp(Mockito.anyInt(), anyArg())
    }

    @Test
    fun leavingSelectModeReleasesTheShiftKey() {
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(3, 7)

        controller.onToggleSelect(1)

        assertFalse(controller.isSelectModeActive)
        verify(ime).onKeyUp(Mockito.eq(KEYCODE_SHIFT_RIGHT), anyArg())
        verify(ime, never()).onKeyDown(Mockito.anyInt(), anyArg())
    }

    @Test
    fun controlModeZeroUsesTheLeftShiftKeyInstead() {
        controlMode(0)
        `when`(view.isSelectMode).thenReturn(true)

        controller.onToggleSelect(1)

        verify(ime).onKeyDown(Mockito.eq(KEYCODE_SHIFT_LEFT), anyArg())
    }

    @Test
    fun selectModeIsNotActiveBeforeAnythingHappens() {
        assertFalse(controller.isSelectModeActive)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Collapsing the selection — the RTL-aware arithmetic
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun collapsingRightInAnLtrLocaleGoesToTheFarEnd() {
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(3, 9)

        controller.onToggleSelect(1) // copy collapses right

        assertCollapsedTo(9)
    }

    @Test
    fun collapsingLeftGoesToTheNearEnd() {
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(3, 9)

        controller.onToggleSelect(0) // cut collapses left

        assertCollapsedTo(3)
    }

    @Test
    fun collapsingRightInAnRtlLocaleGoesToTheNearEndInstead() {
        rtl()
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(3, 9)

        controller.onToggleSelect(1)

        assertCollapsedTo(3) // RTL mirrors the direction
    }

    @Test
    fun aBackwardsSelectionIsNormalisedByMinAndMax() {
        // The connection can report the anchor after the head; the collapse is defined on the
        // ORDER of the two offsets, not on which one is the anchor.
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(9, 3)

        controller.onToggleSelect(1)

        assertCollapsedTo(9)
    }

    @Test
    fun collapseDirectionMinusOneLeavesTheSelectionAlone() {
        // -1 is what show() and hide() pass when they toggle the mode themselves: leave the
        // selection exactly where it is, just release the shift.
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(3, 9)

        controller.onToggleSelect(-1)

        verify(connection, never()).setSelection(Mockito.anyInt(), Mockito.anyInt())
        verify(ime).onKeyUp(Mockito.eq(KEYCODE_SHIFT_RIGHT), anyArg())
    }

    @Test
    fun collapsingWithNoSelectionIsStillAWriteOfTheSameOffset() {
        // CHARACTERISED: there is no "is there a selection" test — a collapse with the caret
        // already collapsed re-writes the same offset, which is a real setSelection call on the
        // editor rather than a no-op.
        `when`(view.isSelectMode).thenReturn(false)
        selectionIs(5, 5)

        controller.onToggleSelect(1)

        assertCollapsedTo(5)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Opening the board
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun openingRaisesTheViewAndAnnouncesItInOrder() {
        `when`(keysDown.numberOfKeysDown).thenReturn(0)

        controller.showFcc()

        val order = Mockito.inOrder(view, keyboardSwitcher, listener)
        order.verify(view).setOpening(true)
        order.verify(keyboardSwitcher).requestShiftOff()
        order.verify(view).show()
        order.verify(listener).onFccPanelShown()
        order.verify(view).setOpening(false)
    }

    @Test
    fun theBoardRefusesToOpenWhileAPhysicalKeyIsHeld() {
        // Opening mid-chord used to strand the modifier state; this gate is the fix and it is the
        // one precondition FCC has that no other board has.
        `when`(keysDown.numberOfKeysDown).thenReturn(1)

        controller.showFcc()

        verify(view, never()).show()
        verify(listener, never()).onFccPanelShown()
    }

    @Test
    fun openingAnAlreadyOpenBoardDoesNothing() {
        boardIsUp()
        `when`(keysDown.numberOfKeysDown).thenReturn(0)

        controller.showFcc()

        verify(view, never()).show()
    }

    @Test
    fun openingWithNoViewDoesNothingAndDoesNotThrow() {
        ReflectionHelpers.setField(FccController::class.java, controller, "mFccView", null)

        controller.showFcc()

        verify(listener, never()).onFccPanelShown()
    }

    @Test
    fun theSharedShowGuardRoutesThroughShowFcc() {
        `when`(keysDown.numberOfKeysDown).thenReturn(0)

        controller.show() // AbstractBoardController's guarded template method

        verify(view).show()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Closing the board
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun closingLowersTheViewAnnouncesItAndLeavesCursorModeOff() {
        boardIsUp()

        controller.hideFcc()

        val order = Mockito.inOrder(view, listener, ime)
        order.verify(view).setClosing(true)
        order.verify(view).hide()
        order.verify(listener).onFccPanelHidden()
        order.verify(ime).enableCursorMode(false)
    }

    @Test
    fun theClosingFlagSurvivesTheCloseOnAnOrdinaryDevice() {
        boardIsUp()
        `when`(profile.isPkbWithoutAlphabeticKeyboard).thenReturn(false)

        controller.hideFcc()

        verify(view, never()).setClosing(false)
    }

    @Test
    fun aPkbWithoutAnAlphabeticKeyboardClearsTheClosingFlagImmediately() {
        // On those devices nothing consumes the flag, so it is cleared in the same breath.
        boardIsUp()
        `when`(profile.isPkbWithoutAlphabeticKeyboard).thenReturn(true)

        controller.hideFcc()

        verify(view).setClosing(false)
    }

    @Test
    fun closingAnAlreadyClosedBoardDoesNothing() {
        controller.hideFcc()

        verify(view, never()).hide()
        verify(listener, never()).onFccPanelHidden()
    }

    /**
     * The close report that keeps the board coordinator truthful.
     *
     * `UnifiedBoardCoordinator.activeBoard()` is the ONLY copy of "which board is open", and it
     * moves only on a report. `UnifiedInputBoardManager.closeBoard(-42)` reports — but FCC closes
     * itself from a dozen places that never touch the UIM (`hideIfDisabled` on a settings reload,
     * `hideUnlessToggling`, `onSelectionUpdate`, the configuration-change and dismiss paths, the
     * UIM's own not-enabled force-close), and `onFccPanelHidden` does not report either
     * (`closeActiveComponent` only runs the keyboard-state chain, as its own javadoc says). Each
     * of those left the coordinator claiming `-42` with the board gone, so the user's next tap on
     * the FCC icon took `requestBoard`'s CLOSE branch and did nothing — the owner-reported "FCC
     * sometimes does not open when tapping on its icon". Reporting from `hideFcc()` closes the
     * hole for every one of those paths at once.
     */
    @Test
    fun closingReportsTheCloseToTheBoardCoordinator() {
        boardIsUp()

        controller.hideFcc()

        verify(uim).reportBoardClosed(-42)
    }

    @Test
    fun aCloseThatDidNotHappenReportsNothing() {
        // Negative control: the "is the board up" guard is what decides, and a no-op close must
        // not clear coordinator state for a board some other press legitimately owns.
        controller.hideFcc()

        verify(uim, never()).reportBoardClosed(Mockito.anyInt())
    }

    @Test
    fun theCloseReportSurvivesTheUimNotExistingYet() {
        // getUnifiedInputBoardManager() genuinely returns null before KeyboardSwitcher builds it.
        `when`(keyboardSwitcher.unifiedInputBoardManager).thenReturn(null)
        boardIsUp()

        controller.hideFcc()

        verify(view).hide()
    }

    @Test
    fun dismissIsAnAliasForClosing() {
        boardIsUp()

        controller.dismiss()

        verify(view).hide()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. The closing flag the suggestion strip consumes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theClosingFlagIsConsumedExactlyOnce() {
        `when`(view.isClosing).thenReturn(true)

        assertTrue("first read sees it", controller.consumeClosingFlag())
        verify(view).setClosing(false)

        `when`(view.isClosing).thenReturn(false)
        assertFalse("second read does not", controller.consumeClosingFlag())
    }

    @Test
    fun thereIsNoClosingFlagWithoutAView() {
        ReflectionHelpers.setField(FccController::class.java, controller, "mFccView", null)

        assertFalse(controller.consumeClosingFlag())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. The conditional closes
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun hideUnlessTogglingSpareTheBoardWhileTheSelectChipIsMidToggle() {
        boardIsUp()
        `when`(view.isToggling).thenReturn(true)

        controller.hideUnlessToggling()

        verify(view, never()).hide()
    }

    @Test
    fun hideUnlessTogglingClosesTheBoardOtherwise() {
        boardIsUp()
        `when`(view.isToggling).thenReturn(false)

        controller.hideUnlessToggling()

        verify(view).hide()
    }

    @Test
    fun hideIfDisabledClosesTheBoardOnlyWhenTheBoardIsNoLongerAllowed() {
        boardIsUp()
        `when`(helper.isPortraitNonPasswordField).thenReturn(true)

        controller.hideIfDisabled()
        verify(view, never()).hide()

        `when`(helper.isPortraitNonPasswordField).thenReturn(false)
        controller.hideIfDisabled()
        verify(view).hide()
    }

    @Test
    fun theBoardIsAllowedOnlyInAPortraitNonPasswordField() {
        `when`(helper.isPortraitNonPasswordField).thenReturn(true)
        assertTrue(controller.isEnabled)

        `when`(helper.isPortraitNonPasswordField).thenReturn(false)
        assertFalse(controller.isEnabled)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. Selection updates from the editor
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun anEditorSelectionCommitsAndClosesTheBoardWhenNoMetaKeyIsHeld() {
        boardIsUp()
        `when`(ime.isMetaKeyActive()).thenReturn(false)

        controller.onSelectionUpdate(3, 9)

        verify(view).hide()
    }

    @Test
    fun anEditorSelectionWhileAMetaKeyIsHeldLeavesTheBoardOpenAndRepaintsIt() {
        boardIsUp()
        `when`(ime.isMetaKeyActive()).thenReturn(true)

        controller.onSelectionUpdate(3, 9)

        verify(view, never()).hide()
        verify(view).updateButtons()
    }

    @Test
    fun aCollapsedCaretUpdateOnlyRepaintsTheButtons() {
        boardIsUp()
        `when`(ime.isMetaKeyActive()).thenReturn(false)

        controller.onSelectionUpdate(5, 5)

        verify(view, never()).hide()
        verify(view).updateButtons()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. Identity, view lifetime and teardown
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theBoardAnswersToKeycodeMinusFortyTwo() {
        assertEquals(-42, controller.keyCode)
        assertEquals(-42, FccController.KEY_CODE)
    }

    @Test
    fun theBoardCountsAsShowingWhileItIsStillOpening() {
        `when`(view.isShowing).thenReturn(false)
        `when`(view.isOpening).thenReturn(true)

        assertTrue("opening counts as showing", controller.isShowing)
        assertTrue(controller.isViewActive)
    }

    @Test
    fun bindViewTakesTheViewFromTheKeyboardSwitcherAndInstallsTheSelectionProvider() {
        mockStatic(KeyboardSwitcher::class.java).use { switcherStatic ->
            switcherStatic.`when`<KeyboardSwitcher> { KeyboardSwitcher.getInstance() }
                .thenReturn(keyboardSwitcher)
            `when`(keyboardSwitcher.fccView).thenReturn(view)

            controller.bindView(mock(android.view.View::class.java))

            val provider = ArgumentCaptor.forClass(FccView.SelectionProvider::class.java)
            verify(view).setListeners(Mockito.eq(controller), provider.captured())

            `when`(connection.hasSelection()).thenReturn(true)
            assertTrue("the provider reads the live connection", provider.value.hasSelection())
        }
    }

    @Test
    fun destroyDropsTheViewAndItsListeners() {
        controller.destroy()

        verify(view).setListeners(null, null)
        assertFalse("no view left", controller.hasView())
    }

    private companion object {
        /** `KeyEvent.META_SHIFT_ON | META_SHIFT_LEFT_ON | META_SHIFT_RIGHT_ON`, held. */
        const val META_SHIFT_HELD = 12288
        const val KEYCODE_SHIFT_LEFT = 59
        const val KEYCODE_SHIFT_RIGHT = 60
    }
}
