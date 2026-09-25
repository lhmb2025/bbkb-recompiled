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
 * The suggestion strip after **cursor mode** (the CKB double-tap keyboard cursor control) closes.
 *
 * Owner report, KEY2: "the double-tap cursor control mode opens, but after ending it by double
 * tapping again, the suggestion strip is blanked out, rather than showing suggestions from the
 * current cursor position."
 *
 * Entering the mode cancels the composing word and hides the aux bar, and
 * `RecorrectionController.performRecorrection` returns immediately while `isCursorModeEnabled` is
 * set — so every `onUpdateSelection` the arrow keys generate is swallowed. The only re-evaluation
 * on the way out was `restoreSuggestionStrip`'s `postUpdateShiftState(false, false)`, whose
 * `false` tells `performRecorrection` not to offer the word under the cursor: the list it builds
 * comes back empty and the strip goes neutral, i.e. blank on Latin.
 *
 * What is pinned here is that every exit makes the SAME request the editor-tap path makes at the
 * end of `InputLogic.onUpdateSelection` — `postUpdateShiftState(true, true)` — so the word the
 * cursor landed on gets its recorrection suggestions.
 *
 * The subject is a real [BlackBerryIME] whose constructor never ran (`CALLS_REAL_METHODS` under
 * the inline mock maker), with the handful of collaborators the cursor-mode exit touches injected.
 * `toggleCursorMode` / `enableCursorMode` / `applyCursorModeState` / `hideArrowBar` /
 * `restoreSuggestionStrip` are all real production code here.
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

        // hasAuxBarView() == true: the KEY2 always has the aux bar inflated, so the exit really
        // does go through restoreSuggestionStrip().
        auxBarManager = Mockito.mock(AuxBarManager::class.java)
        Mockito.`when`(auxBarManager.getAuxBarView()).thenReturn(Mockito.mock(AuxBarView::class.java))

        richInputConnection = Mockito.mock(RichInputConnection::class.java)
        Mockito.`when`(richInputConnection.hasSelection()).thenReturn(false)
        val inputLogic = Mockito.mock(InputLogic::class.java)
        ReflectionHelpers.setField(inputLogic, "mRichInputConnection", richInputConnection)

        disableCursorModeRunnable = Runnable { ime.enableCursorMode(false) }

        // The cursor-mode exit is a KeyboardStateCoordinator transition now, and the funnel lives
        // on the switcher. The switcher is real (CALLS_REAL_METHODS) so the production Axes runs;
        // it only needs to know which IME to call back into.
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

    /** The reported case: the second double tap on the CKB. */
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

    /** Every other exit site goes through enableCursorMode(false) — onViewClicked, key input. */
    @Test
    fun enableCursorModeFalseReEvaluatesSuggestionsAtTheCursor() {
        ime.enableCursorMode(false)

        verify(uiUpdateHandler).postUpdateShiftState(true, true)
    }

    /**
     * The strip is still put back on screen, and the request that fills it is the editor-tap one:
     * `restoreSuggestionStrip`'s own `postUpdateShiftState(false, false)` — the blank-strip
     * request — must not be the last word.
     */
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
     * Teardown exits (onDestroy, resetUiState) must not queue work for an editor that is gone.
     * `restoreSuggestionStrip`'s own `(false, false)` is untouched — it is gated downstream by
     * `isSuggestionStripActive()`; what must not be added is the recorrection request.
     */
    @Test
    fun exitWithNoActiveInputRequestsNothing() {
        ime.isInputActive = false

        ime.enableCursorMode(false)

        verify(uiUpdateHandler, never()).postUpdateShiftState(true, true)
    }
}
