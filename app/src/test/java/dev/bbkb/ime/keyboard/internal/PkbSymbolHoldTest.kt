package dev.bbkb.ime.keyboard.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * "Close the symbol board after a symbol, unless Sym is held" — owner decision, 2026-09-22.
 *
 * The user-facing "Close symbol keyboard after symbol" toggle (`pkb_symbol_auto_close`) is gone.
 * Closing after one symbol is now the default and only behaviour, and the way to keep the board
 * open is to HOLD the Sym key down while typing symbols:
 *
 *  * Sym tapped, one symbol typed -> back to letters.
 *  * Sym held, symbols typed -> the board stays up and the symbols keep going in.
 *  * Sym released -> the entry ends and the board closes.
 *
 * These drive [KeyboardState] the way the hardware key path drives it on a PKB device:
 *
 *  * the Sym key-DOWN reaches `onSymbolShiftToggle(.., fromSym = true)`
 *    (`InputLogic.toggleKeyboardDirection` -> `BlackBerryIME.updateSymbolShift`),
 *  * a character key-DOWN reaches `onCodeInput`, its key-UP `onHardwareKeyEvent`,
 *  * the Sym key-UP reaches `onHardwareKeyEvent(KEYCODE_SYM)`.
 *
 * The held state itself comes from `PhysicalKeyboardStateTracker.isSymKeyHeld()`, which
 * `KeyboardSwitcher` answers the [KeyboardState.SwitcherCallbacks.isSymKeyHeld] question with;
 * here [FakeSwitcherCallbacks.symKeyHeld] stands in for the physical key. Production order is
 * preserved: the tracker is told about the release *after* `KeyboardState` has seen the Sym
 * key-up, so the flag is still set while that key-up is processed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PkbSymbolHoldTest {

    private lateinit var fake: FakeSwitcherCallbacks
    private lateinit var state: KeyboardState

    @Before
    fun setUp() {
        fake = FakeSwitcherCallbacks()
        fake.pkbDevice = true
        state = KeyboardState(fake)
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")
        fake.clear()
    }

    // ── the hardware key path, one call per half of each physical key press ──

    /** The Sym key-DOWN: opens the PKB symbol board. */
    private fun symDown() {
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)
    }

    /** The Sym key-UP. The tracker learns about the release only afterwards. */
    private fun symUp() {
        state.onHardwareKeyEvent(KEYCODE_SYM, 0, NO_RECAPITALIZE)
        fake.symKeyHeld = false
    }

    /** A hinted character key pressed and released on the symbol board. */
    private fun typeSymbol(code: Int = 'a'.code, keyCode: Int = KEYCODE_A) {
        state.onCodeInput(code, false, 0, NO_RECAPITALIZE)
        state.onHardwareKeyEvent(keyCode, 0, NO_RECAPITALIZE)
    }

    // ── the three behaviours ─────────────────────────────────────────────────

    @Test
    fun aSymbolTypedWithSymNotHeldClosesTheBoard() {
        symDown()
        symUp()
        assertTrue("premise: tapping Sym opens the symbol board", state.isInSymbolMode)

        typeSymbol()

        assertFalse("one symbol ends a tapped-Sym entry", state.isInSymbolMode)
    }

    @Test
    fun symbolsTypedWhileSymIsHeldKeepTheBoardOpen() {
        fake.symKeyHeld = true
        symDown()

        typeSymbol()
        assertTrue("the first symbol must not close a held-Sym entry", state.isInSymbolMode)

        typeSymbol('b'.code, KEYCODE_B)
        typeSymbol('c'.code, KEYCODE_C)

        assertTrue("and neither must the ones after it", state.isInSymbolMode)
    }

    @Test
    fun releasingSymAfterTypingClosesTheBoard() {
        fake.symKeyHeld = true
        symDown()
        typeSymbol()
        typeSymbol('b'.code, KEYCODE_B)
        assertTrue(state.isInSymbolMode)

        symUp()

        assertFalse("releasing Sym ends the entry", state.isInSymbolMode)
    }

    /**
     * The hold is not a way to get stuck in symbols: once Sym is released the board is back to
     * closing after a single symbol, exactly as if it had been tapped.
     */
    @Test
    fun theNextEntryAfterAHoldIsAOneShotAgain() {
        fake.symKeyHeld = true
        symDown()
        typeSymbol()
        symUp()
        assertFalse(state.isInSymbolMode)

        symDown()
        symUp()
        typeSymbol()

        assertFalse(state.isInSymbolMode)
    }

    /**
     * Sym pressed and released with nothing typed leaves the board up — that is the one-shot
     * entry waiting for its symbol, not a hold.
     */
    @Test
    fun tappingSymWithNothingTypedLeavesTheBoardUp() {
        symDown()
        symUp()

        assertTrue(state.isInSymbolMode)
    }

    // ── the on-screen ABC key on the Sym board ───────────────────────────────

    /**
     * Sym tapped, then the board's on-screen ABC key (−3) tapped. The soft-key path fires, in
     * production order: the press, the IME's onCodeInput (which runs `onInputCodeChanged` and the
     * symbol-committed hook), then the release. ABC is a toggle read against the current mode, so
     * the hook must not end the entry first: with the entry still up, the release asks for the
     * on-screen alphabet (`requestManualShiftOff`, which forces the on-screen keyboard), as in the
     * original app. Before the fix the hook treated −3 as a committed symbol, the release found
     * the alphabet already loaded and toggled onto the on-screen SYMBOLS page (KEY2, 2026-10-25).
     */
    @Test
    fun theOnScreenAbcKeyOnTheSymBoardAsksForTheOnScreenAlphabet() {
        fake.manualTemporaryUppercase = true // a KEY2: PKB device with an alphabetic keypad
        symDown()
        symUp()
        fake.clear()

        state.onCodeInput(ABC_KEY, false, 0, NO_RECAPITALIZE)
        state.onInputCodeChanged(ABC_KEY, 0, NO_RECAPITALIZE)
        state.onSoftwareSymbolCommitted(ABC_KEY, 0, NO_RECAPITALIZE)
        assertTrue("a function key is not a committed symbol: the entry is still up", state.isInSymbolMode)

        state.onCodeRelease(ABC_KEY, false, 0, NO_RECAPITALIZE)

        assertFalse(state.isInSymbolMode)
        fake.assertCalls(
            FakeSwitcherCallbacks.Call.OnKeyRelease,
            FakeSwitcherCallbacks.Call.RequestManualShiftOff,
            FakeSwitcherCallbacks.Call.SetAlphabetKeyboard(0, NO_RECAPITALIZE),
        )
    }

    /** A character tapped on the board still ends the one-shot entry through the same hook. */
    @Test
    fun aCharacterTappedOnTheSymBoardStillClosesIt() {
        symDown()
        symUp()
        fake.clear()

        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)
        state.onSoftwareSymbolCommitted('a'.code, 0, NO_RECAPITALIZE)

        assertFalse(state.isInSymbolMode)
    }

    private companion object {
        /** `key_switch_alpha_symbol`: the on-screen ABC / ?123 key. */
        const val ABC_KEY = -3
        const val KEYCODE_A = 29
        const val KEYCODE_B = 30
        const val KEYCODE_C = 31
        const val KEYCODE_SYM = 63

        /** `RecapitalizeStatus.NOT_A_RECAPITALIZE_MODE`. */
        const val NO_RECAPITALIZE = -1
        const val TYPE_TEXT = 1
    }
}
