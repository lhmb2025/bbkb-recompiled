package dev.bbkb.ime.keyboard.inputboard

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewPropertyAnimator
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for board open/close state reconciliation between [UnifiedBoardCoordinator] — which owns
 * the only copy of "which board is open" — and the view state each component reports.
 *
 * `UnifiedBoardCoordinatorTest` already pins the coordinator's toggle core against a trivial host.
 * This file pins the part that file cannot reach: **the real host**. A board-key press runs
 * `handleKeyEvent → coordinator.requestBoard → host.openBoard → openBoardFromExternalKey →
 * dispatchBoardAction`.
 *
 * `dispatchBoardAction` used to re-decide open-versus-close from `component.isShowing()` — the
 * exact "derive it from clobberable view state" pattern the coordinator was introduced to remove —
 * which cost the user a press whenever the two halves disagreed (defect 8, the board "dead press").
 * It is now the OPEN path only: the coordinator makes the decision, and where the view disagrees it
 * is repaired to match rather than allowed to overrule. The tests below construct both directions
 * of coordinator/view disagreement and pin the corrected outcome.
 *
 * ### How the subject is instantiated
 *
 * `UnifiedInputBoardManager`'s constructor needs a live `AuxBarView`, a themed `Context` and a real
 * `BlackBerryIME`, none of which exist on the JVM. The instance here is created without running
 * that constructor (`Mockito.CALLS_REAL_METHODS`, which under the inline mock maker yields the real
 * class) and the handful of collaborators the board-state paths actually touch are injected. Every
 * method under test then runs **real production code** — no dispatch logic is reimplemented here.
 * The collaborators are stubbed so that:
 *  - `isShowing()` answers true (no `AuxBarManager`, `keyboardView` VISIBLE — the legacy fallback),
 *    so `openBoardFromExternalKey` skips `show()` and goes straight to the dispatcher;
 *  - `keyboardView.getKeyboard()` is null, so `refresh()` / `updateKeyHighlightedStates` /
 *    `updateAlphabetKeyForSlideboard` short-circuit and no key rendering is involved — except in
 *    section 7, which installs a bar keyboard precisely so that painting pass runs.
 *
 * The boards are real [AbstractBoardController] subclasses, so their `show`/`hide` carry the
 * production "am I already in that state" guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedInputBoardManagerBoardStateTest {

    /** A board with a settable "is my view up" answer, over the real controller skeleton. */
    private class FakeBoard(keyCode: Int) : AbstractBoardController<Any>(keyCode, null, Any()) {
        var viewIsUp = false
        var enabled = true

        /**
         * The board refuses to raise its view when asked, the way `FccController.showFcc()` does
         * while a physical key is still counted as held (and the way any board whose `ViewStub`
         * has not been inflated does silently).
         */
        var refuseToOpen = false
        var onShowCalls = 0
        var onHideCalls = 0
        var refreshCalls = 0

        /**
         * Times `hide()` was *asked for*, as opposed to `onHideCalls` (times the guard let it
         * through). The two differ exactly when the view is already down — which is the case
         * `VoiceInputController` cares about, since it overrides `hide()` to run unconditionally
         * so a recognizer session that outlived its view still releases the microphone.
         */
        var hideRequests = 0

        override fun isShowing() = viewIsUp
        override fun isEnabled() = enabled
        override fun peekBoardView(): View? = null
        override fun onShow() { onShowCalls++; if (!refuseToOpen) viewIsUp = true }
        override fun onHide() { onHideCalls++; viewIsUp = false }
        override fun onRefresh() { refreshCalls++; super.onRefresh() }
        override fun hide() { hideRequests++; super.hide() }

        /** A side effect hid the view behind the controller's back (the on-device regression). */
        fun clobberViewClosed() { viewIsUp = false }

        /** The view is up although nothing told the coordinator. */
        fun clobberViewOpen() { viewIsUp = true }
    }

    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var ime: BlackBerryIME
    private lateinit var keyboardView: SimplifiedKeyboardView
    private lateinit var components: ConcurrentHashMap<Int, UnifiedInputBoardComponent>
    private lateinit var coordinator: UnifiedBoardCoordinator

    private lateinit var clipboard: FakeBoard
    private lateinit var fcc: FakeBoard
    private lateinit var numberPad: FakeBoard
    private lateinit var emoji: FakeBoard

    @Before
    fun setUp() {
        uim = Mockito.mock(UnifiedInputBoardManager::class.java, Mockito.CALLS_REAL_METHODS)

        ime = Mockito.mock(BlackBerryIME::class.java)
        val viewCoordinator = Mockito.mock(InputViewCoordinator::class.java)
        Mockito.`when`(ime.getUiCoordinator()).thenReturn(viewCoordinator)
        // getAuxBarManager() is left unstubbed (null), which selects the legacy view fallback.

        keyboardView = Mockito.mock(SimplifiedKeyboardView::class.java)
        Mockito.`when`(keyboardView.visibility).thenReturn(View.VISIBLE)
        Mockito.`when`(keyboardView.animate())
            .thenReturn(Mockito.mock(ViewPropertyAnimator::class.java))

        components = ConcurrentHashMap()
        coordinator = UnifiedBoardCoordinator(uim)

        inject("componentMap", components)
        inject("boardCoordinator", coordinator)
        inject("componentsRegistered", true)
        inject("imeService", ime)
        inject("keyboardView", keyboardView)
        inject("invalidationHandler", Handler(Looper.getMainLooper()))
        inject("uimHandler", Mockito.mock(UnifiedInputBoardHandler::class.java))
        inject("keyboardSwwitcher", KeyboardSwitcher.getInstance())
        inject("animationSafetyReset", Runnable { })

        clipboard = register(CLIPBOARD)
        fcc = register(FCC)
        numberPad = register(NUMBER_PAD)
        emoji = register(EMOJI)
    }

    private fun inject(name: String, value: Any?) =
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, name, value)

    private fun register(keyCode: Int): FakeBoard {
        val board = FakeBoard(keyCode)
        components[keyCode] = board
        return board
    }

    private fun barKey(code: Int, longPressCode: Int? = null): Key {
        val key = Mockito.mock(Key::class.java)
        Mockito.`when`(key.code).thenReturn(code)
        if (longPressCode != null) {
            Mockito.`when`(key.hasLongPressKey()).thenReturn(true)
            Mockito.`when`(key.longPressCode).thenReturn(longPressCode)
        }
        return key
    }

    // ── assertions (every test routes through one of these) ───────────────────

    private fun assertEq(message: String, expected: Any?, actual: Any?) =
        assertEquals(message, expected, actual)

    private fun assertYes(message: String, actual: Boolean) = assertTrue(message, actual)

    private fun assertNo(message: String, actual: Boolean) = assertFalse(message, actual)

    private fun assertActiveBoard(expected: Int) =
        assertEq("coordinator activeBoard", expected, coordinator.activeBoard())

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Coordinator versus view — both directions of disagreement
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun bothAgreeClosed_pressOpensTheBoard() {
        uim.requestBoard(CLIPBOARD)

        assertEq("onShow calls", 1, clipboard.onShowCalls)
        assertEq("onHide calls", 0, clipboard.onHideCalls)
        assertActiveBoard(CLIPBOARD)
    }

    @Test
    fun bothAgreeOpen_pressClosesTheBoard() {
        uim.requestBoard(CLIPBOARD)

        uim.requestBoard(CLIPBOARD)

        assertEq("onShow calls", 1, clipboard.onShowCalls)
        assertEq("onHide calls", 1, clipboard.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /**
     * The desync the coordinator exists to survive: a key-down side effect hid the view between
     * the board key's down and its up, so the component reports closed while the coordinator still
     * says open.
     *
     * Today the press CLOSES — the coordinator's own state decides, `closeBoard` runs, and the
     * component's guarded `hide()` is a no-op because the view is already down. Wave 3's proposed
     * reconcile would instead notice the disagreement and leave the board open, i.e. re-show it.
     * This test is what will make that flip visible.
     */
    @Test
    fun coordinatorOpenViewClosed_pressClosesAndDoesNotReopen() {
        uim.requestBoard(CLIPBOARD)
        clipboard.clobberViewClosed()

        uim.requestBoard(CLIPBOARD)

        assertNo("the board must not have been re-shown", clipboard.viewIsUp)
        assertEq("no second onShow", 1, clipboard.onShowCalls)
        assertEq("the guarded hide() was a no-op", 0, clipboard.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /**
     * CORRECT (defect 8, the board "dead press"): the reverse desync no longer costs a press.
     *
     * The coordinator says nothing is open, so `requestBoard` takes the OPEN path, and
     * `dispatchBoardAction` now honours that instead of re-deriving from `component.isShowing()`.
     * It used to see the view up and CLOSE the board — a press whose whole purpose was to open one
     * closed it, and only the second press opened it.
     *
     * The disagreement is repaired rather than ignored: the guarded `show()` leaves an already-up
     * view up (so no second `onShow`), and the report that follows brings the coordinator into
     * line. Both halves end the press agreeing the board is open.
     */
    @Test
    fun coordinatorClosedViewOpen_pressLeavesTheBoardOpenAndRepairsTheCoordinator() {
        clipboard.clobberViewOpen()

        uim.requestBoard(CLIPBOARD)

        assertEq("the open request must not have been turned into a close", 0, clipboard.onHideCalls)
        assertEq("the already-up view was left up, not re-shown", 0, clipboard.onShowCalls)
        assertYes("the board is up", clipboard.viewIsUp)
        assertActiveBoard(CLIPBOARD)
    }

    /**
     * CORRECT (defect 8): with the first press no longer wasted, the next press is an ordinary
     * second press — it closes. Previously the first press was the dead one and this was where the
     * board finally opened.
     */
    @Test
    fun coordinatorClosedViewOpen_theNextPressClosesLikeAnyOtherSecondPress() {
        clipboard.clobberViewOpen()

        uim.requestBoard(CLIPBOARD) // reconciles: stays open
        uim.requestBoard(CLIPBOARD) // ordinary toggle: closes

        assertEq("nothing needed re-showing", 0, clipboard.onShowCalls)
        assertEq("the second press closed it", 1, clipboard.onHideCalls)
        assertNo("the board is down", clipboard.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Exclusive open
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun switchingBoardsClosesThePreviousActiveFirst() {
        uim.requestBoard(CLIPBOARD)

        uim.requestBoard(FCC)

        assertEq("clipboard was closed", 1, clipboard.onHideCalls)
        assertEq("fcc was opened", 1, fcc.onShowCalls)
        assertActiveBoard(FCC)
    }

    /**
     * CORRECT (defect 8): switching to a board whose view is already up leaves it up and makes it
     * the active board.
     *
     * The exclusive-open guard used to be gated on `previousActive.isShowing()` as well, so when
     * the previous active board's view had been clobbered down the guard did nothing — and then
     * the target board's own `isShowing()` re-check closed it. The user asked to switch to a board
     * and ended up with nothing open. The guard now fires on the coordinator's answer alone, and
     * the target is opened unconditionally.
     *
     * The previous board's `hide()` is still *requested* even though its view is already down —
     * that is the point of dropping the gate, not an accident: for boards whose `hide()` runs
     * unconditionally (voice, releasing the microphone) the view being down does not mean the
     * board is finished.
     */
    @Test
    fun switchingToABoardWhoseViewIsAlreadyUpKeepsItUpAndMakesItActive() {
        uim.requestBoard(CLIPBOARD)
        clipboard.clobberViewClosed()
        fcc.clobberViewOpen()

        uim.requestBoard(FCC)

        assertEq("the already-up view was left up, not re-shown", 0, fcc.onShowCalls)
        assertEq("fcc must not have been hidden", 0, fcc.onHideCalls)
        assertYes("fcc is up", fcc.viewIsUp)
        assertNo("the previous board stays down", clipboard.viewIsUp)
        assertEq("the exclusive-open guard still ran the previous board's close path",
            1, clipboard.hideRequests)
        assertActiveBoard(FCC)
    }

    @Test
    fun openingABoardHidesStrayShowingBoardsWithoutTellingTheCoordinator() {
        // A board is up but was never reported: hideOtherComponents sweeps it away as
        // defence-in-depth, and because it was not the active board nothing is reported for it.
        emoji.clobberViewOpen()

        uim.requestBoard(CLIPBOARD)

        assertEq("the stray board was swept", 1, emoji.onHideCalls)
        assertEq("clipboard opened", 1, clipboard.onShowCalls)
        assertActiveBoard(CLIPBOARD)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. The hides that bypass the components' normal close paths
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * CORRECT (defect 8, third finding): `hideOtherComponents` reaches straight into each
     * component's `hide()`, bypassing the normal close paths. It used to report nothing, so the
     * coordinator kept claiming the board was open and `getActiveComponent()` handed back a board
     * whose view was down — the UIM manufacturing the very desync the toggle has to survive. The
     * sweep now reports the close when it takes down the active board, which is what the explicit
     * reconcile in `hideKeyboardOnKeyboardStateChange` used to have to do after the fact.
     */
    @Test
    fun hideOtherComponentsHidesEveryOtherBoardAndReportsTheActiveOneClosed() {
        uim.requestBoard(CLIPBOARD)

        uim.hideOtherComponents(EXEMPT)

        assertEq("clipboard was hidden", 1, clipboard.onHideCalls)
        assertNo("no board is up", uim.isAnyBoardShowing)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
        assertEq(
            "getActiveComponent no longer hands back the hidden board",
            null, uim.activeComponent,
        )
    }

    @Test
    fun hideOtherComponentsSparesTheNamedKeycode() {
        uim.requestBoard(CLIPBOARD)
        emoji.clobberViewOpen()

        uim.hideOtherComponents(CLIPBOARD)

        assertYes("clipboard survives", clipboard.viewIsUp)
        assertEq("emoji was swept", 1, emoji.onHideCalls)
        assertActiveBoard(CLIPBOARD)
    }

    @Test
    fun hideKeyboardOnKeyboardStateChangeReconcilesTheStaleCoordinator() {
        uim.requestBoard(CLIPBOARD)

        uim.hideKeyboardOnKeyboardStateChange()

        assertEq("clipboard was hidden by the sweep", 1, clipboard.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun hideKeyboardOnKeyboardStateChangeExemptsFcc() {
        uim.requestBoard(FCC)

        uim.hideKeyboardOnKeyboardStateChange()

        assertEq("fcc must survive a keyboard state change", 0, fcc.onHideCalls)
        assertActiveBoard(FCC)
    }

    @Test
    fun hideKeyboardOnKeyboardStateChangeExemptsTheNumberPad() {
        // Every committed digit runs the keyboard-state chain, so the number pad has to survive it.
        uim.requestBoard(NUMBER_PAD)

        uim.hideKeyboardOnKeyboardStateChange()

        assertEq("the number pad must survive", 0, numberPad.onHideCalls)
        assertActiveBoard(NUMBER_PAD)
    }

    /**
     * REGRESSION (owner report, KEY2, 2026-09-21): the voice panel closed itself as soon as a
     * dictation result was committed.
     *
     * `VoiceRecognitionManager.onResults` commits the recognised text — `postCommitText` →
     * `BlackBerryIME.onTextInput` → `InputLogic.commitVoiceInput`, which asks for UI update mode 1
     * — and mode 1 is `resetKeyboardState()`, i.e. `KeyboardSwitcher.setAlphabetKeyboard(caps,
     * recap)` → `KeyboardState.requestShiftMode` → `requestShiftOff`/`requestAutomaticShift` →
     * the private `setAlphabetKeyboard(int)`, whose first act is to call this method. Voice was
     * not on the exemption list, so the sweep reached `VoiceInputController.hide()` — which is
     * unconditional and cancels the recognizer — and the user's own dictated words closed the
     * panel. Only the listening session should end; the panel returns to "tap to speak" and waits
     * for the user to close it.
     */
    @Test
    fun hideKeyboardOnKeyboardStateChangeExemptsVoiceSoADictationCommitLeavesThePanelUp() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(VOICE)

        uim.hideKeyboardOnKeyboardStateChange() // the keyboard rebuild a commit runs

        assertEq("the sweep must not have reached the voice board", 0, voice.hideRequests)
        assertEq("the recognizer must not have been cancelled", 0, voice.cancelCalls)
        assertYes("the voice panel is still up", voice.viewIsUp)
        assertActiveBoard(VOICE)
    }

    /** Voice's exemption is the same early return over the whole sweep that FCC's is. */
    @Test
    fun hideKeyboardOnKeyboardStateChangeExemptsVoiceEvenWhenAnotherBoardIsAlsoUp() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(VOICE)
        clipboard.clobberViewOpen()

        uim.hideKeyboardOnKeyboardStateChange()

        assertEq("the sweep never ran", 0, clipboard.onHideCalls)
        assertYes("the voice panel is still up", voice.viewIsUp)
        assertActiveBoard(VOICE)
    }

    @Test
    fun hideKeyboardOnKeyboardStateChangeExemptsFccEvenWhenAnotherBoardIsAlsoUp() {
        // The FCC guard is an early return over the whole sweep, not a per-component skip.
        uim.requestBoard(CLIPBOARD)
        fcc.clobberViewOpen()

        uim.hideKeyboardOnKeyboardStateChange()

        assertEq("the sweep never ran", 0, clipboard.onHideCalls)
        assertActiveBoard(CLIPBOARD)
    }

    @Test
    fun hideKeyboardOnKeyboardStateChangeDoesNothingWhenNoBoardIsShowing() {
        uim.hideKeyboardOnKeyboardStateChange()

        assertEq("nothing hidden", 0, clipboard.onHideCalls + emoji.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    // ── the exemption's predicate, now read by the cross-axis table ────────────

    /**
     * Phase 1a: the exempt list moved to `CrossAxisRules`, which probes it through this predicate
     * instead of the manager keeping a second copy of the list. Every board answers for its own
     * view, exempt or not, and an unregistered keycode answers false.
     */
    @Test
    fun isBoardViewShowingAnswersForEachBoardsOwnView() {
        assertNo("nothing is up yet", uim.isBoardViewShowing(CLIPBOARD))

        uim.requestBoard(CLIPBOARD)

        assertYes("the clipboard is up", uim.isBoardViewShowing(CLIPBOARD))
        assertNo("the number pad is not", uim.isBoardViewShowing(NUMBER_PAD))
        assertNo("an unregistered keycode is never up", uim.isBoardViewShowing(-9999))
        assertNo("NO_BOARD is never up", uim.isBoardViewShowing(UnifiedBoardCoordinator.NO_BOARD))
    }

    /** The predicate follows the view, not the coordinator — that is what the exemption needs. */
    @Test
    fun isBoardViewShowingFollowsTheViewNotTheCoordinator() {
        uim.requestBoard(CLIPBOARD)
        clipboard.clobberViewClosed()

        assertNo("the view is down, so the board is not up", uim.isBoardViewShowing(CLIPBOARD))
        assertActiveBoard(CLIPBOARD)
    }

    @Test
    fun hideAllComponentsHidesEverythingAndClearsTheCoordinator() {
        uim.requestBoard(CLIPBOARD)
        emoji.clobberViewOpen()

        uim.hideAllComponents()

        assertNo("nothing is up", uim.isAnyBoardShowing)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun hideClearsTheCoordinatorAsWellAsTheBar() {
        uim.requestBoard(CLIPBOARD)

        uim.hide()

        assertEq("clipboard was hidden", 1, clipboard.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun destroyClearsTheCoordinator() {
        uim.requestBoard(CLIPBOARD)

        uim.destroy()

        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. notifyBoardOpened / notifyBoardClosed — the bookkeeping funnel
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun setActiveComponentReportsAnOpenAndANullReportsAClose() {
        uim.setActiveComponent(clipboard)
        assertActiveBoard(CLIPBOARD)
        assertYes("isBoardShowing agrees", coordinator.isBoardShowing(CLIPBOARD))

        uim.setActiveComponent(null)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun setActiveComponentByKeyCodeReportsAnOpenDrivenFromOutsideTheUim() {
        // The KeyboardState emoji machine drives the board from the other side and reports by code.
        uim.setActiveComponentByKeyCode(EMOJI)

        assertActiveBoard(EMOJI)
    }

    @Test
    fun setActiveComponentByKeyCodeIsASilentNoOpForAnUnregisteredKeycode() {
        // CHARACTERISED BUG: an outside-driven open of a board that is not in the component map
        // leaves the coordinator claiming nothing is open, with no diagnostic.
        uim.setActiveComponentByKeyCode(UNREGISTERED)

        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun getActiveComponentDerivesFromTheCoordinatorNotFromViewState() {
        // Reported open while the view is down: the coordinator's answer wins.
        coordinator.notifyBoardOpened(CLIPBOARD)
        assertEq(
            "a board whose view is down is still the active one",
            clipboard as UnifiedInputBoardComponent, uim.activeComponent,
        )

        // Reported closed while the view is up: still the coordinator's answer.
        coordinator.notifyBoardClosed()
        clipboard.clobberViewOpen()
        assertEq("a board whose view is up is not active", null, uim.activeComponent)
    }

    @Test
    fun getActiveComponentIsNullWhenTheActiveKeycodeIsNotRegistered() {
        coordinator.notifyBoardOpened(UNREGISTERED)

        assertEq("no component for that keycode", null, uim.activeComponent)
        assertEq("but the coordinator still holds it", UNREGISTERED, coordinator.activeBoard())
    }

    @Test
    fun closingAnAlreadyClosedBoardIsIdempotent() {
        uim.closeBoard(CLIPBOARD)
        uim.closeBoard(CLIPBOARD)

        assertEq("the guarded hide() never fired", 0, clipboard.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. The bar-tap chain: onKeyUp -> handleKeyEvent -> coordinator
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aBarTapOnABoardKeyGoesThroughTheCoordinator() {
        uim.onKeyUp(barKey(CLIPBOARD), true)

        assertEq("opened", 1, clipboard.onShowCalls)
        assertActiveBoard(CLIPBOARD)

        uim.onKeyUp(barKey(CLIPBOARD), true)

        assertEq("the second tap closes rather than reopening", 1, clipboard.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun aBarTapOnANonBoardKeycodeBypassesTheCoordinator() {
        uim.onKeyUp(barKey(SLIDEBOARD_SETTINGS), true)

        Mockito.verify(ime).openSlideboardSettings()
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun aKeyUpThatIsNotAKeyPressIsIgnored() {
        uim.onKeyUp(barKey(CLIPBOARD), false)

        assertEq("nothing opened", 0, clipboard.onShowCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun aBarTapDuringAnimationIsQueuedAndReplayedWhenTheAnimationEnds() {
        uim.setAnimating(true)

        uim.onKeyUp(barKey(CLIPBOARD), true)
        assertEq("deferred while animating", 0, clipboard.onShowCalls)

        uim.setAnimating(false)
        assertEq("replayed on animation end", 1, clipboard.onShowCalls)
        assertActiveBoard(CLIPBOARD)
    }

    @Test
    fun onlyTheLastBarTapDuringAnAnimationIsReplayed() {
        // CHARACTERISED BUG: the pending-key slot holds one key, so a second tap during the same
        // animation silently discards the first.
        uim.setAnimating(true)
        uim.onKeyUp(barKey(CLIPBOARD), true)
        uim.onKeyUp(barKey(EMOJI), true)

        uim.setAnimating(false)

        assertEq("clipboard tap was dropped", 0, clipboard.onShowCalls)
        assertEq("emoji tap replayed", 1, emoji.onShowCalls)
    }

    @Test
    fun aLongPressOnTheBarUsesTheLongPressKeycode() {
        uim.onKeyLongPress(barKey(CLIPBOARD, longPressCode = EMOJI))

        assertEq("the long-press code decided the board", 1, emoji.onShowCalls)
        assertEq("the primary code did nothing", 0, clipboard.onShowCalls)
        assertActiveBoard(EMOJI)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. Per-board asymmetries in the dispatcher
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun openingVoiceWithANonVoiceControllerRegisteredIsASilentNoOp() {
        // CHARACTERISED BUG: dispatchBoardAction's voice branch is gated on
        // `instanceof VoiceInputController`, so a component registered under -27 that is not one
        // is never shown and never reported — while closeBoard(-27) falls back to a plain hide().
        // The two halves of the toggle disagree about what -27 means.
        val voice = register(VOICE)

        uim.requestBoard(VOICE)

        assertEq("nothing happened", 0, voice.onShowCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun closingVoiceWithANonVoiceControllerRegisteredFallsBackToAPlainHide() {
        val voice = register(VOICE)
        voice.clobberViewOpen()

        uim.closeBoard(VOICE)

        assertEq("the fallback hide ran", 1, voice.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun openingTheAutofillBoardAlsoHidesTheBarView() {
        val autofill = register(EXEMPT)

        uim.requestBoard(EXEMPT)

        assertEq("opened", 1, autofill.onShowCalls)
        Mockito.verify(keyboardView).visibility = View.GONE
        assertActiveBoard(EXEMPT)
    }

    @Test
    fun everyDispatchRefreshesEveryRegisteredBoard() {
        uim.requestBoard(CLIPBOARD)

        assertYes("every board was refreshed", clipboard.refreshCalls > 0 && emoji.refreshCalls > 0)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. Toggles the user has moved off the bar (pref_uim_menu_order)
    // ═══════════════════════════════════════════════════════════════════════
    //
    // Customize Menu lets the user replace any of the four bar toggles with another — the number
    // pad in voice's slot, say. The board itself stays reachable: the physical mic key and the
    // multifunction key's board actions open it through the coordinator exactly like a bar tap,
    // and every open path reports through `setActiveComponent → updateKeyHighlightedStates`.
    //
    // That pass used to decide "is a board open" from whether the board's KEY was on the bar. A
    // board whose toggle had been moved off was skipped, the pass concluded nothing was open, and
    // its `hideOtherComponents(-37)` sweep closed the board the press had just opened — voice lost
    // its recognizer and panel on every mic press once the number pad took its slot. These tests
    // install a real bar keyboard (the other sections leave it null) so that pass runs.

    /**
     * The dispatcher's `-27` branch is typed on [VoiceInputController], so the voice board here is
     * a Mockito mock of the real class carrying just the state that branch and the close path read.
     * `toggleVoiceInput` / `hide` / `cancelVoiceInput` move the state the way the production
     * controller does (`hide()` is unconditional there — it always cancels, which is why a sweep
     * that reaches it costs the user the recognizer).
     */
    private inner class FakeVoice {
        var inVoiceMode = false
        var viewIsUp = false
        var toggleCalls = 0
        var hideRequests = 0
        var cancelCalls = 0
        val controller: VoiceInputController = Mockito.mock(VoiceInputController::class.java).also { v ->
            Mockito.`when`(v.keyCode).thenReturn(VOICE)
            Mockito.`when`(v.isInVoiceMode).thenAnswer { inVoiceMode }
            Mockito.`when`(v.isShowing).thenAnswer { viewIsUp }
            Mockito.`when`(v.isEnabled).thenReturn(true)
            Mockito.doAnswer { toggleCalls++; inVoiceMode = !inVoiceMode; viewIsUp = inVoiceMode; null }
                .`when`(v).toggleVoiceInput()
            Mockito.doAnswer { hideRequests++; inVoiceMode = false; viewIsUp = false; null }
                .`when`(v).hide()
            Mockito.doAnswer { cancelCalls++; inVoiceMode = false; viewIsUp = false; null }
                .`when`(v).cancelVoiceInput()
        }
    }

    private fun registerVoice(): FakeVoice {
        val voice = FakeVoice()
        components[VOICE] = voice.controller
        return voice
    }

    /**
     * Give the harness a bar keyboard holding the centre alphabet key plus exactly [toggles] — the
     * four slots as `applyMenuOrder` would have filled them. Anything else is "not on the bar".
     */
    private fun installBar(vararg toggles: Int): Map<Int, Key> {
        val keys = (toggles.toList() + ALPHABET).associateWith { barKey(it) }
        val keyboard = Mockito.mock(Keyboard::class.java)
        Mockito.`when`(keyboard.getKeyByCode(Mockito.anyInt()))
            .thenAnswer { inv -> keys[inv.getArgument<Int>(0)] }
        Mockito.`when`(keyboardView.keyboard).thenReturn(keyboard)
        // No slideboard on the JVM: KeyboardSwitcher has no input view, so its manager is null.
        Mockito.doReturn(false).`when`(uim).isSlideboardShowing()
        return keys
    }

    @Test
    fun micKey_voiceToggleOffTheBar_opensVoiceAndLeavesItOpen() {
        val voice = registerVoice()
        installBar(NUMBER_PAD, EMOJI, FCC, CLIPBOARD) // the number pad took voice's slot

        uim.requestBoard(VOICE) // what the physical mic key does while the UIM is enabled

        assertEq("voice was started once", 1, voice.toggleCalls)
        assertYes("voice mode is on", voice.inVoiceMode)
        assertYes("the voice panel is up", voice.viewIsUp)
        assertEq("nothing swept the panel closed behind the press", 0, voice.hideRequests)
        assertEq("the recognizer was not cancelled", 0, voice.cancelCalls)
        assertActiveBoard(VOICE)
    }

    @Test
    fun micKey_voiceToggleOffTheBar_secondPressClosesVoice() {
        val voice = registerVoice()
        installBar(NUMBER_PAD, EMOJI, FCC, CLIPBOARD)

        uim.requestBoard(VOICE)
        uim.requestBoard(VOICE)

        assertEq("voice was started once", 1, voice.toggleCalls)
        assertEq("closed through the voice close path", 1, voice.cancelCalls)
        assertNo("voice mode is off", voice.inVoiceMode)
        assertNo("the voice panel is down", voice.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    @Test
    fun voiceToggleOffTheBar_theCentreKeyStillShowsABoardIsOpen() {
        registerVoice()
        val keys = installBar(NUMBER_PAD, EMOJI, FCC, CLIPBOARD)

        uim.requestBoard(VOICE)

        Mockito.verify(keys.getValue(ALPHABET), Mockito.atLeastOnce()).setHighlighted(false)
        Mockito.verify(keys.getValue(ALPHABET), Mockito.never()).setHighlighted(true)
    }

    /** Control: with the toggle ON the bar the same press paints its key and the centre key. */
    @Test
    fun voiceToggleOnTheBar_itsKeyIsPaintedHighlightedAndTheCentreKeyIsNot() {
        val voice = registerVoice()
        val keys = installBar(VOICE, EMOJI, FCC, CLIPBOARD)

        uim.requestBoard(VOICE)

        Mockito.verify(keys.getValue(VOICE), Mockito.atLeastOnce()).setHighlighted(true)
        Mockito.verify(keys.getValue(ALPHABET), Mockito.atLeastOnce()).setHighlighted(false)
        assertEq("not swept", 0, voice.hideRequests)
        assertActiveBoard(VOICE)
    }

    /** The same invariant for every other board: here the multifunction key's clipboard action. */
    @Test
    fun multifunctionKey_clipboardToggleOffTheBar_opensClipboardAndLeavesItOpen() {
        installBar(VOICE, EMOJI, FCC, NUMBER_PAD) // the number pad took clipboard's slot

        uim.requestBoard(CLIPBOARD)

        assertEq("opened", 1, clipboard.onShowCalls)
        assertEq("not swept closed", 0, clipboard.hideRequests)
        assertYes("the board is up", clipboard.viewIsUp)
        assertActiveBoard(CLIPBOARD)
    }

    /** Off-bar boards get the on-bar treatment in full: showing while disabled is force-closed. */
    @Test
    fun boardOffTheBar_showingWhileDisabled_isStillForceClosed() {
        installBar(VOICE, EMOJI, FCC, NUMBER_PAD)
        clipboard.enabled = false
        clipboard.clobberViewOpen()

        uim.refresh()

        assertEq("force-closed by the painting pass", 1, clipboard.onHideCalls)
        assertNo("the board is down", clipboard.viewIsUp)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. A close report names the board it closed (the Key2 mic-key regression)
    // ═══════════════════════════════════════════════════════════════════════
    //
    // `setActiveComponent(null)` means "NO board is open", and the coordinator's activeBoard is the
    // only copy of that state — so a close path that calls it while a DIFFERENT board is open
    // makes the coordinator forget a board that is still on screen, and that board's key then
    // finds NO_BOARD and takes the OPEN path. That is what the physical mic key hit on the Key2:
    // `KeyboardSwitcher.setKeyboard` calls `hideEmojiKeyboard()` on EVERY keyboard rebuild, and it
    // ended in an unscoped `setActiveComponent(null)`. Voice is the board the user keeps open
    // while text is committed (a dictation result runs `onTextInput` → `onInputCodeChanged` → the
    // shift chain → `setAlphabetKeyboard` → `setKeyboard`), so the first dictated word left the
    // panel up and the coordinator empty, and the next mic press re-opened instead of closing.
    //
    // `reportBoardClosed(keyCode)` is the scoped form those paths use now: it clears the state
    // only when the board it names is the one the coordinator is holding, and repaints either way.

    /** What a keyboard rebuild does today: emoji's close report, while voice is the open board. */
    private fun keyboardRebuild() = uim.reportBoardClosed(EMOJI)

    @Test
    fun keyboardRebuildWhileVoiceIsOpen_doesNotForgetTheVoiceBoard() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(VOICE)

        keyboardRebuild()

        assertYes("the voice panel is still up", voice.viewIsUp)
        assertEq("the recognizer was not touched", 0, voice.cancelCalls)
        assertActiveBoard(VOICE)
    }

    @Test
    fun micKey_afterAKeyboardRebuild_secondPressStillClosesVoice() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(VOICE)
        keyboardRebuild() // the dictated word that used to eat the next press

        uim.requestBoard(VOICE)

        assertEq("voice was started once", 1, voice.toggleCalls)
        assertEq("closed through the voice close path", 1, voice.cancelCalls)
        assertNo("voice mode is off", voice.inVoiceMode)
        assertNo("the voice panel is down", voice.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /**
     * The regression itself, kept as a characterisation of the unscoped report: with the
     * coordinator told "nothing is open" the press reaches the OPEN half, which finds the
     * recognizer already running and leaves it running — the press is eaten and the panel stays up.
     */
    @Test
    fun theUnscopedCloseReport_isWhatAteTheSecondMicPress() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(VOICE)

        uim.setActiveComponent(null) // what hideEmojiKeyboard used to do on every rebuild
        uim.requestBoard(VOICE)

        assertEq("the press never reached the close path", 0, voice.cancelCalls)
        assertYes("the panel is still up", voice.viewIsUp)
        assertActiveBoard(VOICE)
    }

    /** The number pad is the other board typed from, so every digit used to cost it its state. */
    @Test
    fun keyboardRebuildWhileTheNumberPadIsOpen_doesNotForgetIt() {
        installBar(VOICE, EMOJI, FCC, NUMBER_PAD)
        uim.requestBoard(NUMBER_PAD)

        keyboardRebuild()
        uim.requestBoard(NUMBER_PAD)

        assertEq("the second press closed it", 1, numberPad.onHideCalls)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /** Emoji's own close still reports: the scoping only silences reports for OTHER boards. */
    @Test
    fun theEmojiCloseReport_stillClearsTheEmojiBoard() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(EMOJI)
        assertActiveBoard(EMOJI)

        uim.reportBoardClosed(EMOJI)

        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /**
     * The emoji key never had voice's defect — a rebuild takes the palettes down WITH the state,
     * so the two stay in step and a second press legitimately re-opens. Pinned so the scoping
     * cannot silently turn a real emoji close into a stale "still open".
     */
    @Test
    fun emojiKey_secondPressClosesTheBoard() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)

        uim.requestBoard(EMOJI)
        uim.requestBoard(EMOJI)

        assertEq("opened once", 1, emoji.onShowCalls)
        assertEq("closed once", 1, emoji.onHideCalls)
        assertNo("the board is down", emoji.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /** A report for a board nobody is holding is a paint-only no-op, not a state clear. */
    @Test
    fun aCloseReportForAnUnopenedBoard_leavesTheOpenBoardAlone() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(CLIPBOARD)

        uim.reportBoardClosed(FCC)

        assertEq("the clipboard was not hidden", 0, clipboard.onHideCalls)
        assertActiveBoard(CLIPBOARD)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. A board that REFUSES to open (the FCC "icon sometimes does nothing")
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * `dispatchBoardAction` used to call `setActiveComponent(component)` unconditionally after
     * `show()`, so a board that refused was recorded as open. The coordinator then held `-42`
     * with nothing on screen, and the user's next tap took `requestBoard`'s CLOSE branch: one
     * refusal cost two taps. The `BoardHost` contract already said the opposite in as many words
     * ("a failed open leaves the coordinator truthful instead of assuming success").
     */
    @Test
    fun aRefusedOpenIsNotReportedAsOpen() {
        fcc.refuseToOpen = true

        uim.requestBoard(FCC)

        assertEq("the open was attempted", 1, fcc.onShowCalls)
        assertNo("…and refused", fcc.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /** The tap after a refusal must OPEN, not spend itself closing a board that never opened. */
    @Test
    fun theTapAfterARefusedOpenOpensTheBoard() {
        fcc.refuseToOpen = true
        uim.requestBoard(FCC)

        fcc.refuseToOpen = false
        uim.requestBoard(FCC)

        assertYes("the second tap must open the board", fcc.viewIsUp)
        assertEq("and must not have closed anything", 0, fcc.onHideCalls)
        assertActiveBoard(FCC)
    }

    /** Negative control: a board that opens normally is still reported open by the same line. */
    @Test
    fun anAcceptedOpenIsStillReportedAsOpen() {
        uim.requestBoard(FCC)

        assertYes("the view is up", fcc.viewIsUp)
        assertActiveBoard(FCC)
    }

    /** A refusal must not disturb a board that some other press legitimately left open. */
    @Test
    fun aRefusedOpenDoesNotResurrectThePreviousBoard() {
        uim.requestBoard(CLIPBOARD)
        fcc.refuseToOpen = true

        uim.requestBoard(FCC)

        assertNo("the clipboard was closed by the exclusive-open guard", clipboard.viewIsUp)
        assertNo("and FCC never came up", fcc.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. A close that did not come through the coordinator
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * FCC closes itself from a dozen places that never touch the UIM (`hideIfDisabled` on a
     * settings reload, `onSelectionUpdate`, the configuration-change path…). Each left the
     * coordinator claiming `-42` with the board gone, so the next icon tap was a dead close.
     * `FccController.hideFcc()` now reports through `reportBoardClosed`; this pins what that
     * report has to achieve at the manager level.
     */
    @Test
    fun aSelfClosedBoardThatReportsIsOpenedAgainByTheNextTap() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(FCC)

        // The board takes itself down and reports it, exactly as hideFcc() now does.
        fcc.clobberViewClosed()
        uim.reportBoardClosed(FCC)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)

        uim.requestBoard(FCC)

        assertYes("the next tap opens the board", fcc.viewIsUp)
        assertActiveBoard(FCC)
    }

    /** Negative control — without the report, the same tap is the dead press we are fixing. */
    @Test
    fun aSelfClosedBoardThatDoesNotReportCostsATap() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(FCC)

        fcc.clobberViewClosed()

        uim.requestBoard(FCC)

        assertNo("this is the dead press the report exists to prevent", fcc.viewIsUp)
    }

    private companion object {
        /** The fixed centre keyboard/settings key. */
        const val ALPHABET = -3
        const val EMOJI = -11
        const val CLIPBOARD = -25
        const val SLIDEBOARD_SETTINGS = -26
        const val VOICE = -27
        /** The keycode `hideOtherComponents` is called with as "hide everything". */
        const val EXEMPT = -37
        const val FCC = -42
        const val NUMBER_PAD = -46
        const val UNREGISTERED = -99
    }
}
