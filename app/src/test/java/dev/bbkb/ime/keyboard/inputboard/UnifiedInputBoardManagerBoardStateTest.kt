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
 * Pins board open/close reconciliation between [UnifiedBoardCoordinator], the only owner of "which
 * board is open", and component view state, through the real host path (`handleKeyEvent →
 * requestBoard → dispatchBoardAction`). The manager is built with `CALLS_REAL_METHODS` (no
 * constructor); `isShowing()` answers true via the legacy fallback and a null
 * `keyboardView.getKeyboard()` skips key painting, except where a test installs a bar keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedInputBoardManagerBoardStateTest {

    /** A board with a settable "is my view up" answer, over the real controller skeleton. */
    private class FakeBoard(keyCode: Int) : AbstractBoardController<Any>(keyCode, null, Any()) {
        var viewIsUp = false
        var enabled = true

        /** Refuses to raise its view, like `FccController.showFcc()` while a key is held, or an uninflated `ViewStub`. */
        var refuseToOpen = false
        var onShowCalls = 0
        var onHideCalls = 0
        var refreshCalls = 0

        /**
         * Times `hide()` was requested, versus `onHideCalls` (times the guard let it through). They differ
         * when the view is already down, which matters for `VoiceInputController`'s unconditional `hide()`.
         */
        var hideRequests = 0

        override fun isShowing() = viewIsUp
        override fun isEnabled() = enabled
        override fun peekBoardView(): View? = null
        override fun onShow() { onShowCalls++; if (!refuseToOpen) viewIsUp = true }
        override fun onHide() { onHideCalls++; viewIsUp = false }
        override fun onRefresh() { refreshCalls++; super.onRefresh() }
        override fun hide() { hideRequests++; super.hide() }

        /** A side effect hid the view behind the controller's back. */
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

    private fun assertEq(message: String, expected: Any?, actual: Any?) =
        assertEquals(message, expected, actual)

    private fun assertYes(message: String, actual: Boolean) = assertTrue(message, actual)

    private fun assertNo(message: String, actual: Boolean) = assertFalse(message, actual)

    private fun assertActiveBoard(expected: Int) =
        assertEq("coordinator activeBoard", expected, coordinator.activeBoard())

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
     * A key-down side effect hid the view between the board key's down and up, so the component says
     * closed while the coordinator says open. The coordinator decides: the press closes.
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
     * Reverse desync (coordinator says nothing open, view is up): the press opens instead of closing,
     * the guarded `show()` adds no second `onShow`, and the report brings the coordinator into line.
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

    /** With the first press not wasted, the next press is an ordinary toggle and closes. */
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

    @Test
    fun switchingBoardsClosesThePreviousActiveFirst() {
        uim.requestBoard(CLIPBOARD)

        uim.requestBoard(FCC)

        assertEq("clipboard was closed", 1, clipboard.onHideCalls)
        assertEq("fcc was opened", 1, fcc.onShowCalls)
        assertActiveBoard(FCC)
    }

    /**
     * Switching to a board whose view is already up leaves it up and active. The exclusive-open guard
     * uses the coordinator's answer alone, so the previous board's `hide()` is still requested with its
     * view down: boards with an unconditional `hide()` (voice releasing the mic) need that.
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

    /**
     * `hideOtherComponents` bypasses the components' close paths, so it must report the close when it
     * takes down the active board; otherwise `getActiveComponent()` returns a board whose view is down.
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
     * Committing a dictation result runs the keyboard-state chain (`resetKeyboardState` →
     * `setAlphabetKeyboard`), which calls this sweep. Voice is exempt so its unconditional `hide()`
     * does not close the panel; only the listening session ends.
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

    /**
     * `CrossAxisRules` probes the exempt list through this predicate. Every board answers for its own
     * view; an unregistered keycode answers false.
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

    /** The predicate follows the view, not the coordinator: that is what the exemption needs. */
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
        // Known bug: an outside-driven open of a board not in the component map leaves the coordinator
        // claiming nothing is open, with no diagnostic.
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
        // Known bug: the pending-key slot holds one key, so a second tap during the same animation
        // silently discards the first.
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

    @Test
    fun openingVoiceWithANonVoiceControllerRegisteredIsASilentNoOp() {
        // Known bug: the voice branch is gated on `instanceof VoiceInputController`, so a non-voice
        // component under -27 is never shown or reported, while closeBoard(-27) falls back to hide().
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

    // Boards whose bar toggle the user moved off (pref_uim_menu_order) stay reachable through the mic
    // and multifunction keys. The highlight pass must not treat an off-bar board as closed and sweep it
    // with `hideOtherComponents(-37)`. These tests install a real bar keyboard so that pass runs.

    /**
     * The `-27` branch is typed on [VoiceInputController], so voice here is a Mockito mock of the real
     * class; `hide()` is unconditional and cancels, as in production.
     */
    private inner class FakeVoice {
        var inVoiceMode = false
        var viewIsUp = false
        var toggleCalls = 0
        var hideRequests = 0
        var cancelCalls = 0
        /** The open is refused, as with no recognition service: the toggle leaves voice mode off. */
        var refuseOpen = false
        val controller: VoiceInputController = Mockito.mock(VoiceInputController::class.java).also { v ->
            Mockito.`when`(v.keyCode).thenReturn(VOICE)
            Mockito.`when`(v.isInVoiceMode).thenAnswer { inVoiceMode }
            Mockito.`when`(v.isShowing).thenAnswer { viewIsUp }
            Mockito.`when`(v.isEnabled).thenReturn(true)
            Mockito.doAnswer {
                toggleCalls++
                if (!(refuseOpen && !inVoiceMode)) { inVoiceMode = !inVoiceMode; viewIsUp = inVoiceMode }
                null
            }.`when`(v).toggleVoiceInput()
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
     * Give the harness a bar keyboard holding the centre alphabet key plus exactly [toggles], the
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

    /** No recognition service: the toggle refuses to open, so the open must not be reported. */
    @Test
    fun micKey_refusedVoiceOpen_isReportedClosedSoTheNextPressTriesToOpenAgain() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        voice.refuseOpen = true

        uim.requestBoard(VOICE)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)

        uim.requestBoard(VOICE)

        assertEq("both presses tried to open", 2, voice.toggleCalls)
        assertEq("neither was spent closing a board that never opened", 0, voice.cancelCalls)
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

    // `setActiveComponent(null)` means no board is open, so close paths use the scoped
    // `reportBoardClosed(keyCode)`: it clears state only when it names the board the coordinator holds,
    // and repaints either way. Every keyboard rebuild (each dictated word) reports emoji closed.

    /** A keyboard rebuild: emoji's close report while voice is the open board. */
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
        keyboardRebuild() // a dictated word

        uim.requestBoard(VOICE)

        assertEq("voice was started once", 1, voice.toggleCalls)
        assertEq("closed through the voice close path", 1, voice.cancelCalls)
        assertNo("voice mode is off", voice.inVoiceMode)
        assertNo("the voice panel is down", voice.viewIsUp)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)
    }

    /**
     * Characterises the unscoped report: told nothing is open, the press reaches the OPEN half, finds
     * the recognizer running and leaves it, so the press is eaten and the panel stays up.
     */
    @Test
    fun theUnscopedCloseReport_isWhatAteTheSecondMicPress() {
        val voice = registerVoice()
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(VOICE)

        uim.setActiveComponent(null) // the unscoped report
        uim.requestBoard(VOICE)

        assertEq("the press never reached the close path", 0, voice.cancelCalls)
        assertYes("the panel is still up", voice.viewIsUp)
        assertActiveBoard(VOICE)
    }

    /** The number pad is also typed from, so a digit must not cost it its state. */
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
     * A rebuild takes the emoji palettes down with the state, so a second press legitimately re-opens.
     * Pinned so the scoping cannot turn a real emoji close into a stale open.
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

    /**
     * A board that refuses to open must not be recorded open, or the next tap takes the CLOSE branch
     * and one refusal costs two taps (see the `BoardHost` contract).
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

    /**
     * FCC closes itself from paths that bypass the UIM (`hideIfDisabled`, `onSelectionUpdate`,
     * configuration change), so `FccController.hideFcc()` reports through `reportBoardClosed`.
     */
    @Test
    fun aSelfClosedBoardThatReportsIsOpenedAgainByTheNextTap() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(FCC)

        // The board takes itself down and reports it, as hideFcc() does.
        fcc.clobberViewClosed()
        uim.reportBoardClosed(FCC)
        assertActiveBoard(UnifiedBoardCoordinator.NO_BOARD)

        uim.requestBoard(FCC)

        assertYes("the next tap opens the board", fcc.viewIsUp)
        assertActiveBoard(FCC)
    }

    /** Negative control: without the report the next tap is a dead press. */
    @Test
    fun aSelfClosedBoardThatDoesNotReportCostsATap() {
        installBar(VOICE, EMOJI, FCC, CLIPBOARD)
        uim.requestBoard(FCC)

        fcc.clobberViewClosed()

        uim.requestBoard(FCC)

        assertNo("this is the dead press the report exists to prevent", fcc.viewIsUp)
    }

    /** Runs the main looper past the next frame, where the uncover is scheduled. */
    private fun idle() = org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(100))

    private fun ui(): InputViewCoordinator = ime.getUiCoordinator()

    /** Closing the last board asks the bar to go back to the user's choice, at the next frame. */
    @Test
    fun closingTheLastBoardUncoversTheBarAfterTheClose() {
        uim.requestBoard(CLIPBOARD)
        uim.requestBoard(CLIPBOARD)
        Mockito.verify(ui(), Mockito.never()).uncoverAfterBoardsClosed()

        idle()

        Mockito.verify(ui(), Mockito.times(1)).uncoverAfterBoardsClosed()
    }

    /**
     * Switching straight from one board to another closes the first and only then opens the
     * second: the uncover runs after both, so it finds a board open and leaves the menu up.
     */
    @Test
    fun aSwitchBetweenBoardsUncoversOnlyAfterTheNextBoardIsOpen() {
        var nextBoardWasOpen: Boolean? = null
        val vc = ui()
        Mockito.doAnswer { nextBoardWasOpen = numberPad.viewIsUp; null }
            .`when`(vc).uncoverAfterBoardsClosed()
        uim.requestBoard(CLIPBOARD)

        uim.requestBoard(NUMBER_PAD)
        idle()

        assertEq("the number pad was open when the uncover ran", true, nextBoardWasOpen)
        assertActiveBoard(NUMBER_PAD)
    }

    /** Opening a board with none open before asks for nothing. */
    @Test
    fun openingABoardDoesNotUncover() {
        uim.requestBoard(CLIPBOARD)
        idle()

        Mockito.verify(ui(), Mockito.never()).uncoverAfterBoardsClosed()
    }

    /** Taking the menu down (any hide()) ends a menu the user chose. */
    @Test
    fun hidingTheMenuEndsTheUsersChoiceOfIt() {
        uim.hide()

        Mockito.verify(ui()).onMenuTakenDown()
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
