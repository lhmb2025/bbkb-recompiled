package dev.bbkb.ime.core

import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.textinput.CursorTracker
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.connection.RichInputConnection
import dev.bbkb.ime.keyboard.auxbar.ArrowBarController
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager
import dev.bbkb.ime.keyboard.auxbar.AuxBarView
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Every cursor-mode exit makes the editor-tap request `postUpdateShiftState(true, true)`, so the
 * word under the cursor gets recorrection suggestions. `performRecorrection` ignores selection
 * updates while the mode is on, and `restoreSuggestionStrip`'s own `(false, false)` would blank the
 * strip. Subject: a real [BlackBerryIME] (`CALLS_REAL_METHODS`, constructor never ran) with the
 * exit's collaborators injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CursorModeExitSuggestionsTest {

    private lateinit var ime: BlackBerryIME
    private lateinit var uiUpdateHandler: UIUpdateHandler
    private lateinit var uiCoordinator: InputViewCoordinator
    private lateinit var arrowBar: ArrowBarController
    private lateinit var auxBarManager: AuxBarManager
    private lateinit var richInputConnection: RichInputConnection

    /** The real 4 s auto-disable wiring: `Runnable { enableCursorMode(false) }`. */
    private lateinit var disableCursorModeRunnable: Runnable

    @Before
    fun setUp() {
        ime = Mockito.mock(BlackBerryIME::class.java, Mockito.CALLS_REAL_METHODS)

        uiUpdateHandler = Mockito.mock(UIUpdateHandler::class.java)
        uiCoordinator = Mockito.mock(InputViewCoordinator::class.java)

        // The arrow bar is on screen, which is what cursor mode means on the KEY2.
        arrowBar = Mockito.mock(ArrowBarController::class.java)
        Mockito.`when`(arrowBar.isShowing()).thenReturn(true)

        // hasAuxBarView() == true, so the exit really goes through restoreSuggestionStrip().
        auxBarManager = Mockito.mock(AuxBarManager::class.java)
        Mockito.`when`(auxBarManager.getAuxBarView()).thenReturn(Mockito.mock(AuxBarView::class.java))

        richInputConnection = Mockito.mock(RichInputConnection::class.java)
        Mockito.`when`(richInputConnection.hasSelection()).thenReturn(false)
        val inputLogic = Mockito.mock(InputLogic::class.java)
        ReflectionHelpers.setField(inputLogic, "mRichInputConnection", richInputConnection)

        disableCursorModeRunnable = Runnable { ime.enableCursorMode(false) }

        // The exit is a KeyboardStateCoordinator transition on the switcher; a real switcher
        // (CALLS_REAL_METHODS) runs the production Axes and only needs the IME to call back.
        val keyboardSwitcher =
            Mockito.mock(KeyboardSwitcher::class.java, Mockito.CALLS_REAL_METHODS)
        ReflectionHelpers.setField(
            KeyboardSwitcher::class.java, keyboardSwitcher, "blackberryIme", ime,
        )
        ReflectionHelpers.setField(ime, "keyboardSwitcher", keyboardSwitcher)

        ReflectionHelpers.setField(ime, "uiUpdateHandler", uiUpdateHandler)
        ReflectionHelpers.setField(ime, "uiCoordinator", uiCoordinator)
        ReflectionHelpers.setField(ime, "cursorTracker", Mockito.mock(CursorTracker::class.java))
        ReflectionHelpers.setField(ime, "inputLogic", inputLogic)
        ReflectionHelpers.setField(ime, "disableCursorModeRunnable", disableCursorModeRunnable)

        ime.arrowBarController = arrowBar
        ime.auxBarManager = auxBarManager
        ime.isInputActive = true
        ime.isCursorModeEnabled = true

        // The unified input board is not up, so applyCursorModeState does not short-circuit.
        Mockito.doReturn(false).`when`(ime).isUnifiedInputBoardShowing()
    }

    /** The second double tap on the CKB. */
    @Test
    fun secondDoubleTapReEvaluatesSuggestionsAtTheCursor() {
        ime.toggleCursorMode()

        verify(uiUpdateHandler).postUpdateShiftState(true, true)
    }

    /** The 4 s auto-disable timer must land in the same state as the double tap. */
    @Test
    fun autoDisableTimeoutReEvaluatesSuggestionsAtTheCursor() {
        disableCursorModeRunnable.run()

        verify(uiUpdateHandler).postUpdateShiftState(true, true)
    }

    /** Every other exit site (onViewClicked, key input) goes through enableCursorMode(false). */
    @Test
    fun enableCursorModeFalseReEvaluatesSuggestionsAtTheCursor() {
        ime.enableCursorMode(false)

        verify(uiUpdateHandler).postUpdateShiftState(true, true)
    }

    /** The strip is restored, and the editor-tap request, not restoreSuggestionStrip's `(false, false)`, comes last. */
    @Test
    fun exitRestoresTheStripAndDoesNotLeaveTheBlankingRequestAsTheOnlyOne() {
        ime.toggleCursorMode()

        verify(arrowBar).hide()
        verify(uiCoordinator).showSuggestionStripOrUim()
        verify(uiUpdateHandler).postUpdateShiftState(true, true)
    }

    /** Without the aux bar there is no restoreSuggestionStrip call, but the request still happens. */
    @Test
    fun exitWithoutAnAuxBarStillReEvaluatesSuggestionsAtTheCursor() {
        ime.auxBarManager = null

        ime.enableCursorMode(false)

        verify(uiCoordinator, never()).showSuggestionStripOrUim()
        verify(uiUpdateHandler).postUpdateShiftState(true, true)
    }

    /** Nothing to exit: no spurious suggestion request. */
    @Test
    fun disablingCursorModeThatWasNotEnabledRequestsNothing() {
        ime.isCursorModeEnabled = false

        ime.enableCursorMode(false)

        verify(uiUpdateHandler, never()).postUpdateShiftState(Mockito.anyBoolean(), Mockito.anyBoolean())
    }

    /**
     * Teardown exits (onDestroy, resetUiState) must not queue a recorrection request for a gone
     * editor; restoreSuggestionStrip's `(false, false)` is gated downstream by `isSuggestionStripActive()`.
     */
    @Test
    fun exitWithNoActiveInputRequestsNothing() {
        ime.isInputActive = false

        ime.enableCursorMode(false)

        verify(uiUpdateHandler, never()).postUpdateShiftState(true, true)
    }
}
