package dev.bbkb.ime.core

import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.ime.UIUpdateHandler
import dev.bbkb.ime.core.textinput.CursorTracker
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.core.textinput.connection.RichInputConnection
import dev.bbkb.ime.keyboard.auxbar.ArrowBarController
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager
import dev.bbkb.ime.keyboard.auxbar.AuxBarView
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.keyevent.ModifierState
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * Characterisation of the **cursor axis' cross-axis edge on the way IN**.
 *
 * `CursorModeExitSuggestionsTest` pins the way out. This file pins what entering cursor mode does
 * to the other three axes, which before Phase 1a was written as an inline sweep inside
 * `showArrowBar()`: close the boards, hide the suggestion views, close the boards again, raise the
 * arrow bar. The order is load-bearing (the mode flag goes on first, because `FccController` reads
 * it from inside that teardown) and the forced entry deliberately skips the whole sweep.
 *
 * The subject is a real [BlackBerryIME] whose constructor never ran, with the collaborators the
 * entry path touches injected — the same harness `CursorModeExitSuggestionsTest` uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CursorModeEntryTransitionTest {

    private lateinit var ime: BlackBerryIME
    private lateinit var uiCoordinator: InputViewCoordinator
    private lateinit var arrowBar: ArrowBarController
    private lateinit var inputLogic: InputLogic
    private lateinit var fcc: FccController
    private lateinit var uim: UnifiedInputBoardManager

    @Before
    fun setUp() {
        ime = Mockito.mock(BlackBerryIME::class.java, Mockito.CALLS_REAL_METHODS)

        uiCoordinator = Mockito.mock(InputViewCoordinator::class.java)
        arrowBar = Mockito.mock(ArrowBarController::class.java)
        Mockito.`when`(arrowBar.isShowing()).thenReturn(false)

        val auxBarManager = Mockito.mock(AuxBarManager::class.java)
        Mockito.`when`(auxBarManager.getAuxBarView()).thenReturn(Mockito.mock(AuxBarView::class.java))

        inputLogic = Mockito.mock(InputLogic::class.java)
        ReflectionHelpers.setField(
            inputLogic, "mRichInputConnection", Mockito.mock(RichInputConnection::class.java),
        )

        fcc = Mockito.mock(FccController::class.java)

        // The board axis, as BlackBerryIME reaches it: keyboardSwitcher -> the UIM. The switcher
        // is real (CALLS_REAL_METHODS) so the cursor transitions really do run through
        // KeyboardStateCoordinator and its production Axes, not a stub of them.
        uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        val keyboardSwitcher =
            Mockito.mock(KeyboardSwitcher::class.java, Mockito.CALLS_REAL_METHODS)
        ReflectionHelpers.setField(
            KeyboardSwitcher::class.java, keyboardSwitcher, "unifiedInputBoardManager", uim,
        )
        ReflectionHelpers.setField(
            KeyboardSwitcher::class.java, keyboardSwitcher, "blackberryIme", ime,
        )
        ReflectionHelpers.setField(ime, "keyboardSwitcher", keyboardSwitcher)
        // getModifierState() is stubbed because isMetaKeyActive() reads the snapshot, and an
        // unstubbed mock hands back null rather than "nothing is active".
        val tracker = Mockito.mock(PhysicalKeyboardStateTracker::class.java)
        Mockito.`when`(tracker.getModifierState()).thenReturn(ModifierState.none())
        ReflectionHelpers.setField(ime, "physicalKeyboardStateTracker", tracker)

        ReflectionHelpers.setField(ime, "uiUpdateHandler", Mockito.mock(UIUpdateHandler::class.java))
        ReflectionHelpers.setField(ime, "uiCoordinator", uiCoordinator)
        ReflectionHelpers.setField(ime, "cursorTracker", Mockito.mock(CursorTracker::class.java))
        ReflectionHelpers.setField(ime, "inputLogic", inputLogic)
        ReflectionHelpers.setField(ime, "disableCursorModeRunnable", Runnable { })

        ime.arrowBarController = arrowBar
        ime.auxBarManager = auxBarManager
        ime.isInputActive = true
        ime.isCursorModeEnabled = false

        Mockito.doReturn(false).`when`(ime).isUnifiedInputBoardShowing()
        Mockito.doReturn(false).`when`(ime).isGestureInputReady()
        Mockito.doReturn(null).`when`(ime).getCurrentInputConnection()
        ime.fccController = fcc
    }

    // ═══════════════════════════════════════════════ the unforced entry

    @Test
    fun enteringTurnsTheModeOn() {
        ime.enableCursorMode(true)

        assertTrue(ime.isCursorModeEnabled)
    }

    /** FCC is told BEFORE the flag goes on, and it is told this is not a forced entry. */
    @Test
    fun enteringTellsFccFirstAndUnforced() {
        ime.enableCursorMode(true)

        verify(fcc).onFccEnabled(false)
    }

    /**
     * The board axis: entering closes every board. Unlike a layout change, there is no exemption —
     * the arrow bar takes the space the board was using.
     */
    @Test
    fun enteringClosesTheBoards() {
        ime.enableCursorMode(true)

        verify(uiCoordinator).hideUnifiedInputBoard()
    }

    /** The bar axis: the suggestion views come down and the arrow bar goes up. */
    @Test
    fun enteringHidesTheSuggestionViewsAndRaisesTheArrowBar() {
        ime.enableCursorMode(true)

        val inOrder = Mockito.inOrder(uiCoordinator, arrowBar)
        inOrder.verify(uiCoordinator).hideSuggestionViews()
        inOrder.verify(uiCoordinator).hideUnifiedInputBoard()
        inOrder.verify(arrowBar).show()
    }

    /**
     * The text pipeline, the fifth thing the cross-axis table deliberately does not carry: the
     * composing word is cancelled on the way in, which is why the way out has to re-request
     * suggestions.
     */
    @Test
    fun enteringCancelsTheComposingWord() {
        ime.enableCursorMode(true)

        verify(inputLogic).cancelComposingAndTouchEvent()
    }

    /** Already in cursor mode: the whole entry is a no-op, sweep included. */
    @Test
    fun enteringWhenAlreadyInCursorModeDoesNothing() {
        ime.isCursorModeEnabled = true

        ime.enableCursorMode(true)

        verify(fcc, never()).onFccEnabled(Mockito.anyBoolean())
        verify(arrowBar, never()).show()
        verify(uiCoordinator, never()).hideSuggestionViews()
    }

    /** The arrow bar is already up: `showArrowBar` returns before any of the sweep runs. */
    @Test
    fun enteringWithTheArrowBarAlreadyUpSkipsTheWholeSweep() {
        Mockito.`when`(arrowBar.isShowing()).thenReturn(true)

        ime.enableCursorMode(true)

        assertTrue("the mode still goes on", ime.isCursorModeEnabled)
        verify(uiCoordinator, never()).hideSuggestionViews()
        verify(uiCoordinator, never()).hideUnifiedInputBoard()
    }

    // ═══════════════════════════════════════════════════ the board gate

    /**
     * A board being open wins over an unforced cursor-mode request: `applyCursorModeState`
     * short-circuits entirely. This is the board→cursor precedence rule.
     */
    @Test
    fun aBoardBeingOpenBlocksAnUnforcedEntry() {
        Mockito.doReturn(true).`when`(ime).isUnifiedInputBoardShowing()

        ime.enableCursorMode(true)

        assertFalse(ime.isCursorModeEnabled)
        verify(fcc, never()).onFccEnabled(Mockito.anyBoolean())
        verify(arrowBar, never()).show()
    }

    // ══════════════════════════════════════════════════ the forced entry

    /**
     * The forced entry is FCC reconciling a state it has already arranged on screen: the mode goes
     * on and NOTHING else moves — no board sweep, no suggestion-view teardown, no arrow bar.
     * Sweeping there would take down the board the caller just put up.
     */
    @Test
    fun aForcedEntryTurnsTheModeOnAndTouchesNoOtherAxis() {
        ime.applyCursorModeState(true, true, true)

        assertTrue(ime.isCursorModeEnabled)
        verify(fcc).onFccEnabled(true)
        verify(arrowBar, never()).show()
        verify(uiCoordinator, never()).hideSuggestionViews()
        verify(uiCoordinator, never()).hideUnifiedInputBoard()
        verify(inputLogic, never()).cancelComposingAndTouchEvent()
    }

    /** And a forced entry works even with a board open — that is what "forced" means. */
    @Test
    fun aForcedEntryIsNotBlockedByAnOpenBoard() {
        Mockito.doReturn(true).`when`(ime).isUnifiedInputBoardShowing()

        ime.applyCursorModeState(true, true, true)

        assertTrue(ime.isCursorModeEnabled)
    }
}
