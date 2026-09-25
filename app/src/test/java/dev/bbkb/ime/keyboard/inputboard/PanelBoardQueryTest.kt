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
import dev.bbkb.ime.keyboard.state.CrossAxisRules
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
 * Characterisation of **the two board questions**, pinned across the step that registers the
 * alphabet and symbol boards in the UIM's component map.
 *
 * Phase 1d gave every keyboard a board identity but could not make the two typing boards real
 * `UnifiedInputBoardComponent`s, because four sites iterate the component map while *meaning*
 * "is a PANEL board up" — and the alphabet board is always up. Registering the typing boards
 * without splitting those two questions first would have made:
 *
 *  1. `hideKeyboardOnKeyboardStateChange()`'s gate permanently true, so every layout change sweeps;
 *  2. `dispatchBoardAction`'s exclusive-open invariant `hide()` the alphabet board;
 *  3. `updateKeyHighlightedStates`'s "did I find a showing, enabled component" conclusion always
 *     true, suppressing its `hideOtherComponents(-37)` sweep and un-highlighting the centre key;
 *  4. `isSlideboardActiveAndNoBoardOpen()` — and therefore `updateAlphabetKeyForSlideboard()` —
 *     always take the "a board is open" branch, so the slideboard never gets its settings key.
 *
 * Every assertion below was read off the **unmodified** code and confirmed green against it before
 * the split was made. They answer only in terms of panel boards, so they must read identically
 * before and after registration; [section 5][theTypingBoardsAreRegisteredInTheComponentMap] then
 * adds the same questions again with the typing boards actually in the map.
 *
 * The harness is [UnifiedInputBoardManagerBoardStateTest]'s: a real manager whose constructor never
 * ran, with only the collaborators these paths touch injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PanelBoardQueryTest {

    private companion object {
        const val ALPHABET = CrossAxisRules.ALPHABET_BOARD_KEY_CODE     // -3
        const val SYMBOL = CrossAxisRules.SYMBOL_BOARD_KEY_CODE         // -22
        const val EMOJI = CrossAxisRules.EMOJI_KEY_CODE                 // -11
        const val CLIPBOARD = CrossAxisRules.CLIPBOARD_KEY_CODE         // -25
        const val NUMBER_PAD = CrossAxisRules.NUMBER_PAD_KEY_CODE       // -46
        const val AUTOFILL = CrossAxisRules.AUTOFILL_KEY_CODE           // -37
        const val SLIDEBOARD_SETTINGS = -26
    }

    /** A panel board with a settable "is my view up" answer, over the real controller skeleton. */
    private class FakeBoard(keyCode: Int) : AbstractBoardController<Any>(keyCode, null, Any()) {
        var viewIsUp = false
        var onShowCalls = 0
        var onHideCalls = 0

        override fun isShowing() = viewIsUp
        override fun peekBoardView(): View? = null
        override fun onShow() { onShowCalls++; viewIsUp = true }
        override fun onHide() { onHideCalls++; viewIsUp = false }
    }

    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var ime: BlackBerryIME
    private lateinit var keyboardView: SimplifiedKeyboardView
    private lateinit var components: ConcurrentHashMap<Int, UnifiedInputBoardComponent>
    private lateinit var coordinator: UnifiedBoardCoordinator

    private lateinit var clipboard: FakeBoard
    private lateinit var emoji: FakeBoard

    @Before
    fun setUp() {
        uim = Mockito.mock(UnifiedInputBoardManager::class.java, Mockito.CALLS_REAL_METHODS)

        ime = Mockito.mock(BlackBerryIME::class.java)
        Mockito.`when`(ime.getUiCoordinator())
            .thenReturn(Mockito.mock(InputViewCoordinator::class.java))

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

        // No slideboard on the JVM unless a test asks for one: KeyboardSwitcher has no input view
        // here, so its manager is null and the real isSlideboardShowing() would NPE.
        Mockito.doReturn(false).`when`(uim).isSlideboardShowing()

        clipboard = register(CLIPBOARD)
        emoji = register(EMOJI)
    }

    private fun inject(name: String, value: Any?) =
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, name, value)

    private fun register(keyCode: Int): FakeBoard {
        val board = FakeBoard(keyCode)
        components[keyCode] = board
        return board
    }

    private fun barKey(code: Int): Key {
        val key = Mockito.mock(Key::class.java)
        Mockito.`when`(key.code).thenReturn(code)
        return key
    }

    /**
     * Give the harness a bar keyboard holding the centre alphabet key plus exactly [toggles].
     * [alphabetKeyPresent] false stubs `getKeyByCode(-3)` to null, which is how the slideboard
     * tests below tell the two branches of `updateAlphabetKeyForSlideboard` apart without letting
     * either construct a real [Key] from a mock template.
     */
    private fun installBar(vararg toggles: Int, alphabetKeyPresent: Boolean = true): Keyboard {
        val keys = toggles.associateWith { barKey(it) }.toMutableMap()
        if (alphabetKeyPresent) keys[ALPHABET] = barKey(ALPHABET)
        val keyboard = Mockito.mock(Keyboard::class.java)
        Mockito.`when`(keyboard.getKeyByCode(Mockito.anyInt()))
            .thenAnswer { inv -> keys[inv.getArgument<Int>(0)] }
        Mockito.`when`(keyboardView.keyboard).thenReturn(keyboard)
        return keyboard
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. The query itself
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun noPanelBoardIsUpWhenNothingIsUp() {
        assertFalse("nothing is up", uim.isPanelBoardShowing())
    }

    @Test
    fun aPanelBoardBeingUpIsWhatTheQueryAnswersTo() {
        uim.requestBoard(CLIPBOARD)

        assertTrue("the clipboard is a panel board", uim.isPanelBoardShowing())
    }

    /**
     * The query follows each component's own view, not the coordinator — that is what it has always
     * done and what the sweep gate needs (a board whose view a side effect clobbered down is not up).
     */
    @Test
    fun theQueryFollowsTheViewNotTheCoordinator() {
        uim.requestBoard(CLIPBOARD)
        clipboard.viewIsUp = false

        assertFalse("the view is down, so no panel board is up", uim.isPanelBoardShowing())
        assertEquals("the coordinator still holds it", CLIPBOARD, coordinator.activeBoard())
    }

    /**
     * CHARACTERISED, and deliberately unchanged: the inline autofill strip is in the component map
     * but is not a board — except here, where it has always counted. `updateKeyHighlightedStates`
     * skips it by name, `hideComponentsExcept` spares it by name, and this query does not. The
     * split does not touch that; it only adds the two typing boards to the set the query skips.
     */
    @Test
    fun theAutofillStripStillCountsForThisQuery() {
        val autofill = register(AUTOFILL)
        autofill.viewIsUp = true

        assertTrue("the autofill strip counts, as it always has", uim.isPanelBoardShowing())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Site 1 — hideKeyboardOnKeyboardStateChange's gate
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aLayoutChangeWithAPanelBoardUpSweepsIt() {
        uim.requestBoard(CLIPBOARD)

        uim.hideKeyboardOnKeyboardStateChange()

        assertEquals("the clipboard was swept", 1, clipboard.onHideCalls)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun aLayoutChangeWithNoPanelBoardUpSweepsNothing() {
        uim.hideKeyboardOnKeyboardStateChange()

        assertEquals("nothing was hidden", 0, clipboard.onHideCalls)
        assertEquals("nothing was hidden", 0, emoji.onHideCalls)
    }

    /** An exempt board being up spares the whole sweep — the long-standing FCC semantics. */
    @Test
    fun anExemptPanelBoardBeingUpSparesTheWholeSweep() {
        val numberPad = register(NUMBER_PAD)
        numberPad.viewIsUp = true
        emoji.viewIsUp = true

        uim.hideKeyboardOnKeyboardStateChange()

        assertEquals("the number pad survives", 0, numberPad.onHideCalls)
        assertEquals("and so does the board that is not exempt", 0, emoji.onHideCalls)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Site 2 — the exclusive-open invariant
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun openingAPanelBoardClosesThePanelBoardThatWasOpen() {
        uim.requestBoard(CLIPBOARD)

        uim.requestBoard(EMOJI)

        assertEquals("the clipboard was closed first", 1, clipboard.onHideCalls)
        assertEquals("emoji opened", 1, emoji.onShowCalls)
        assertEquals(EMOJI, coordinator.activeBoard())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. Sites 3 and 4 — the painting pass and the slideboard's centre key
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * With nothing open the centre alphabet key is highlighted and the `hideOtherComponents(-37)`
     * sweep runs. The sweep is the load-bearing half: it is what takes down a component showing
     * outside the coordinator's knowledge, and it is gated on the same boolean as the highlight.
     */
    @Test
    fun withNoPanelBoardOpenTheCentreKeyIsHighlightedAndTheSweepRuns() {
        val keyboard = installBar(CLIPBOARD, EMOJI)

        uim.updateKeyHighlightedStates(keyboard)

        Mockito.verify(keyboard.getKeyByCode(ALPHABET)).setHighlighted(true)
        Mockito.verify(uim, Mockito.atLeastOnce()).hideOtherComponents(AUTOFILL)
    }

    @Test
    fun withAPanelBoardOpenTheCentreKeyIsNotHighlightedAndTheSweepIsSuppressed() {
        val keyboard = installBar(CLIPBOARD, EMOJI)
        clipboard.viewIsUp = true

        uim.updateKeyHighlightedStates(keyboard)

        Mockito.verify(keyboard.getKeyByCode(ALPHABET)).setHighlighted(false)
        Mockito.verify(uim, Mockito.never()).hideOtherComponents(AUTOFILL)
        assertEquals("the open board was not swept", 0, clipboard.onHideCalls)
    }

    /**
     * Site 4. The slideboard's centre key is the settings key only while no board is open; with a
     * board open the key goes back to the alphabet key. The two branches are told apart by which
     * keycode the swap looks up: the settings branch asks for −3, the restore branch for −26.
     */
    @Test
    fun theSlideboardGetsItsSettingsKeyWhenNoPanelBoardIsOpen() {
        val keyboard = installBar(CLIPBOARD, alphabetKeyPresent = false)
        Mockito.doReturn(true).`when`(uim).isSlideboardShowing()

        uim.updateAlphabetKeyForSlideboard()

        Mockito.verify(keyboard, Mockito.never()).getKeyByCode(SLIDEBOARD_SETTINGS)
    }

    @Test
    fun theSlideboardLosesItsSettingsKeyWhileAPanelBoardIsOpen() {
        val keyboard = installBar(CLIPBOARD, alphabetKeyPresent = false)
        Mockito.doReturn(true).`when`(uim).isSlideboardShowing()
        clipboard.viewIsUp = true

        uim.updateAlphabetKeyForSlideboard()

        Mockito.verify(keyboard, Mockito.atLeastOnce()).getKeyByCode(SLIDEBOARD_SETTINGS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. The typing boards, once they are in the map
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The registry is the component map, so the two typing boards are in it — which is the whole
     * point of the step. Everything after this test asserts that putting them there changed none
     * of the four answers above.
     */
    @Test
    fun theTypingBoardsAreRegisteredInTheComponentMap() {
        uim.registerComponent(AlphabetBoardController())
        uim.registerComponent(SymbolBoardController())

        assertEquals("the alphabet board is registered", ALPHABET, components[ALPHABET]?.keyCode)
        assertEquals("the symbol board is registered", SYMBOL, components[SYMBOL]?.keyCode)
    }

    /**
     * The alphabet board is ALWAYS up — that is what made registration unsafe — and the panel
     * query does not notice, which is what makes it safe.
     */
    @Test
    fun theAlwaysUpAlphabetBoardIsNotAPanelBoard() {
        val alphabet = AlphabetBoardController()
        uim.registerComponent(alphabet)

        assertTrue("the alphabet board is up, as it always is", alphabet.isShowing)
        assertFalse("and no PANEL board is up", uim.isPanelBoardShowing())
    }

    @Test
    fun aLayoutChangeWithOnlyTheTypingBoardsRegisteredStillSweepsNothing() {
        uim.registerComponent(AlphabetBoardController())
        uim.registerComponent(SymbolBoardController())

        uim.hideKeyboardOnKeyboardStateChange()

        assertEquals("nothing was hidden", 0, clipboard.onHideCalls)
        assertEquals("nothing was hidden", 0, emoji.onHideCalls)
    }

    /** The exclusive-open invariant cannot reach a typing board: they are never the active one. */
    @Test
    fun openingAPanelBoardDoesNotHideTheTypingBoards()  {
        val alphabet = AlphabetBoardController()
        uim.registerComponent(alphabet)
        uim.registerComponent(SymbolBoardController())

        uim.requestBoard(CLIPBOARD)

        assertTrue("the alphabet board is still up", alphabet.isShowing)
        assertEquals(CLIPBOARD, coordinator.activeBoard())
    }

    /** Nor can either sweep. */
    @Test
    fun neitherSweepTakesDownATypingBoard() {
        val alphabet = AlphabetBoardController()
        uim.registerComponent(alphabet)
        uim.registerComponent(SymbolBoardController())

        uim.hideComponentsExcept(intArrayOf(CLIPBOARD))
        uim.hideAllComponents()

        assertTrue("the alphabet board is still up", alphabet.isShowing)
    }

    @Test
    fun theCentreKeyAndTheSweepAreUnchangedByTheTypingBoardsBeingRegistered() {
        uim.registerComponent(AlphabetBoardController())
        uim.registerComponent(SymbolBoardController())
        val keyboard = installBar(CLIPBOARD, EMOJI)

        uim.updateKeyHighlightedStates(keyboard)

        Mockito.verify(keyboard.getKeyByCode(ALPHABET)).setHighlighted(true)
        Mockito.verify(uim, Mockito.atLeastOnce()).hideOtherComponents(AUTOFILL)
    }

    @Test
    fun theSlideboardStillGetsItsSettingsKeyWithTheTypingBoardsRegistered() {
        uim.registerComponent(AlphabetBoardController())
        uim.registerComponent(SymbolBoardController())
        val keyboard = installBar(CLIPBOARD, alphabetKeyPresent = false)
        Mockito.doReturn(true).`when`(uim).isSlideboardShowing()

        uim.updateAlphabetKeyForSlideboard()

        Mockito.verify(keyboard, Mockito.never()).getKeyByCode(SLIDEBOARD_SETTINGS)
    }

    /**
     * The bar's centre key keeps its legacy dispatch. Registration must not divert −3 into the
     * coordinator's toggle: `handleKeyEvent` routes a keycode through `requestBoard` only when it
     * is a PANEL board, so tapping the centre key still runs the `-3` branch — restore the strip,
     * nothing opened.
     */
    @Test
    fun tappingTheCentreKeyStillTakesTheLegacyDispatchNotTheCoordinator() {
        val alphabet = AlphabetBoardController()
        uim.registerComponent(alphabet)
        // closeActiveComponent() reaches the IME's InputLogic and the live switcher, neither of
        // which exists here; the -3 branch's own effect is the strip restore verified below.
        Mockito.doNothing().`when`(uim).closeActiveComponent()
        uim.requestBoard(CLIPBOARD)

        uim.onKeyUp(barKey(ALPHABET), true)

        assertEquals("the clipboard was closed", 1, clipboard.onHideCalls)
        assertEquals(
            "nothing is the active board — the centre key did not open one",
            UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard(),
        )
        assertTrue("the alphabet board is up again", alphabet.isShowing)
        Mockito.verify(ime).restoreSuggestionStrip(true, true)
    }
}
