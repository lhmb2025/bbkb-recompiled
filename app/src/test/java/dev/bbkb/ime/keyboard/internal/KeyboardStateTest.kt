package dev.bbkb.ime.keyboard.internal

import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.HideEmojiKeyboard
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.OnCharacterKey
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.OnFinishShiftLongPress
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.OnKeyRelease
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.OnReturnToAlphabetFromSymbol
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.OnStartBatchInput
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.OnStartShiftLongPress
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.RequestAutomaticShift
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.RequestManualShiftOff
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.RequestShiftLocked
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.RequestShiftMomentary
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.RequestShiftOff
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.RequestShiftOnce
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.SetAlphabetKeyboard
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.SetPkbSymbolsKeyboard
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.SetVkbSymbolsKeyboard
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.ShowEmojiKeyboard
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.ShowMenu
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.TogglePkbSymbolShift
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.UpdateShiftIndicator
import dev.bbkb.ime.keyboard.internal.FakeSwitcherCallbacks.Call.UpdateShiftLockedIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import dev.bbkb.ime.core.device.state.MetaKeyStateTracker
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Characterisation tests for [KeyboardState] — the shift / symbol / caps state machine.
 *
 * These pin **what the class does today**, not what it ought to do. Where the behaviour looks
 * wrong it is still asserted, tagged `CHARACTERISED BUG:` in place. Waves 3/4 rewrite these
 * surfaces and will be diffed against this file plus [FakeSwitcherCallbacks].
 *
 * ## The state space being characterised
 *
 * `KeyboardState` carries several interacting sub-states:
 *
 *  * `currentMode` ∈ ALPHABET | SYMBOL | EMOJI | MENU
 *  * [VkbShiftModeTracker] ∈ UNSHIFTED | MANUAL_SHIFTED | MANUAL_SHIFTED_FROM_AUTO |
 *    AUTOMATIC_SHIFTED | SHIFT_LOCKED | SHIFT_LOCK_SHIFTED
 *  * [ShiftKeyState] ∈ RELEASING | PRESSING | CHORDING | PRESSING_ON_SHIFTED | IGNORING
 *    (plus two plain [ModifierKeyState]s for the SYM key and the symbol-paging key)
 *  * `switchState` ∈ ALPHA(0) | SYMBOL-BEGIN(1) | SYMBOL(2) | SYMBOL-AFTER-SPACE(3) |
 *    MOMENTARY-ALPHA-SYMBOL(4) | MOMENTARY-SYMBOL-MORE(5) | MOMENTARY-ALPHA_SHIFT(6)
 *  * `symbolEntryMethod` ∈ 0 (none) | 1 (start-of-input default) | 2 (PKB SYM) | 3 (VKB SYM)
 *  * page bookkeeping: `currentSymbolPageIndex`, `savedSymbolPageIndex`, `maxSymbolPages`,
 *    `maxPkbSymbolPages`, `isVkb/PkbCustomSymbolPage`, `vkb/pkbSymbolShiftState` (+ saved copies)
 *  * latches: `wasShiftLockedBeforeSymbol`, `isShiftLockReleased`, `isCapitalizationEnabled`,
 *    `requestedShiftMode`, and the `SavedKeyboardState` snapshot.
 *
 * Only two of those are directly readable from outside (the shift-mode tracker, and `currentMode`
 * via `isInSymbolMode()`/`isInEmojiMode()`); `switchState` and the page maxima are readable through
 * the public `toString()`. Everything else is pinned indirectly, through the callback sequence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyboardStateTest {

    private lateinit var fake: FakeSwitcherCallbacks
    private lateinit var state: KeyboardState

    @Before
    fun setUp() {
        fake = FakeSwitcherCallbacks()
        state = KeyboardState(fake)
    }

    // ================================================================= helpers

    /** Run `onStartInput` and forget the resulting calls; most tests only care what follows. */
    private fun startInput(
        autoCaps: Int = 0,
        recapitalize: Int = NO_RECAPITALIZE,
        locale: Locale = Locale.US,
        inputType: Int = TYPE_TEXT,
        packageName: String = "com.example.app",
    ) {
        state.onStartInput(autoCaps, recapitalize, locale, inputType, packageName)
        fake.clear()
    }

    /** Press then release the SYM key, landing in symbol mode. */
    private fun tapSymbolKey(autoCaps: Int = 0, recapitalize: Int = NO_RECAPITALIZE) {
        state.onCodeInput(CODE_SYMBOL, false, autoCaps, recapitalize)
        state.onCodeRelease(CODE_SYMBOL, false, autoCaps, recapitalize)
    }

    /** Press then release the shift key. */
    private fun tapShiftKey(autoCaps: Int = 0, recapitalize: Int = NO_RECAPITALIZE) {
        state.onCodeInput(CODE_SHIFT, false, autoCaps, recapitalize)
        state.onCodeRelease(CODE_SHIFT, false, autoCaps, recapitalize)
    }

    private val tracker get() = state.shiftModeTracker

    // ── PKB Sym after a UIM panel cleared symbol mode ──────────────────────

    @Test
    fun theHardwareSymToggleEntersFromTheAlphabetEvenAfterResetSymbolMode() {
        fake.pkbDevice = true
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)
        assertTrue(state.isInSymbolMode())
        assertTrue(state.wasSymbolEnteredFromAlphabet())

        // A UIM panel opens over the board: KeyboardSwitcher.clearPkbSymbolMode() resets the
        // state without callbacks and leaves the entry method at 0.
        state.resetSymbolMode()
        assertFalse(state.isInSymbolMode())
        assertFalse(state.wasSymbolEnteredFromAlphabet())

        // The next Sym press must not inherit that: the hinted physical keys map only while
        // wasSymbolEnteredFromAlphabet() is true.
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)
        assertTrue(state.isInSymbolMode())
        assertTrue(state.wasSymbolEnteredFromAlphabet())
    }

    @Test
    fun turningTheSymbolPageWithSymKeepsTheHintedKeysMapped() {
        // Sym, Sym: the second press pages the PKB symbol board. Its code (-22) reaches
        // onCodeInput like every processed code; it must not count as "a symbol was typed".
        fake.pkbDevice = true
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)
        assertTrue(state.wasSymbolEnteredFromAlphabet())

        state.onCodeInput(-22, false, 0, NO_RECAPITALIZE)
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)

        assertTrue(state.isInSymbolMode())
        assertTrue("page 2 must still map the hinted physical keys", state.wasSymbolEnteredFromAlphabet())
    }

    @Test
    fun turningTheSymbolPageAfterAKeyEntry_neitherClosesTheBoardNorDropsTheMapping() {
        // The hardware path also records entry method 2 via onHardwareKeyEvent(KEYCODE_SYM);
        // with that method the default branch would have returned to the alphabet outright.
        fake.pkbDevice = true
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)

        state.onCodeInput(-22, false, 0, NO_RECAPITALIZE)
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)

        assertTrue(state.isInSymbolMode())
        assertTrue(state.wasSymbolEnteredFromAlphabet())
    }

    @Test
    fun theHardwareSymTogglePressedAsAKeyStillRecordsTheKeyEntryMethod() {
        fake.pkbDevice = true
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")
        state.resetSymbolMode()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        assertTrue(state.wasSymbolEnteredFromAlphabet())
        // Entry method 2 ("entered by the key") is what onSymbolKeyLongPress keys off: a long
        // press returns to the alphabet. The plain toggle (method 1) leaves a long press inert.
        state.onSymbolKeyLongPress(0, NO_RECAPITALIZE)
        assertFalse(state.isInSymbolMode())

        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, true)
        state.onSymbolKeyLongPress(0, NO_RECAPITALIZE)
        assertTrue(state.isInSymbolMode())
    }

    /** `switch=` field of the public `toString()` — the only window onto `switchState`. */
    private fun switchState(): String =
        Regex("switch=([A-Z\\-_]+)").find(state.toString())!!.groupValues[1]

    private fun field(name: String): String =
        Regex("$name=([^\\s\\]]+)").find(state.toString())!!.groupValues[1]

    // ================================================================= 1. onStartInput / load

    @Test
    fun startInput_freshSession_loadsUnshiftedAlphabet() {
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
        assertFalse(state.isInEmojiMode)
        assertEquals("UNSHIFTED", tracker.toString())
        assertEquals("ALPHA", switchState())
    }

    /**
     * Automatic capitalisation is NOT applied by `KeyboardState` itself: it asks the switcher for
     * an alphabet keyboard and the switcher's `setAlphabetKeyboard(autoCaps, recap)` hands the
     * request straight back as `requestShiftMode`. Wired up, start-input with auto-caps on lands
     * in AUTOMATIC_SHIFTED.
     */
    @Test
    fun startInput_withAutoCaps_selectsAutomaticShiftThroughTheSwitcherLoop() {
        fake.wireProductionAlphabetReentry(state)

        state.onStartInput(AUTO_CAPS_ON, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(
            RequestShiftOff,
            SetAlphabetKeyboard(AUTO_CAPS_ON, NO_RECAPITALIZE),
            RequestAutomaticShift,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )
        assertTrue(tracker.isAutomaticShifted)
    }

    /** The same event without the switcher loop leaves the keyboard unshifted — pins the seam. */
    @Test
    fun startInput_withAutoCaps_doesNothingWithoutTheSwitcherLoop() {
        state.onStartInput(AUTO_CAPS_ON, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(AUTO_CAPS_ON, NO_RECAPITALIZE))
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun startInput_symbolPageCounts_perLocaleAndCustomisationFlags() {
        // locale, vkbCustomisation, pkbCustomisation -> maxSymbolPages, maxPkbSymbolPages.
        // NOTE the asymmetry: maxPkbSymbolPages is derived from the pre-VKB-bump base, so the
        // VKB custom page never widens the PKB range and vice versa.
        data class Row(val locale: Locale, val vkb: Boolean, val pkb: Boolean, val max: Int, val pkbMax: Int)
        val rows = listOf(
            Row(Locale.US, vkb = false, pkb = false, max = 2, pkbMax = 2),
            Row(Locale.US, vkb = true, pkb = false, max = 3, pkbMax = 2),
            Row(Locale.US, vkb = false, pkb = true, max = 2, pkbMax = 3),
            Row(Locale.US, vkb = true, pkb = true, max = 3, pkbMax = 3),
            Row(Locale.CHINESE, vkb = false, pkb = false, max = 3, pkbMax = 3),
            Row(Locale.CHINESE, vkb = true, pkb = true, max = 4, pkbMax = 4),
        )
        for (row in rows) {
            setUp()
            fake.vkbSymbolCustomizationEnabled = row.vkb
            fake.pkbSymbolCustomizationEnabled = row.pkb
            startInput(locale = row.locale)

            assertEquals("$row symbolPageMax", row.max.toString(), field("symbolPageMax"))
            assertEquals("$row pkbSymbolPageMax", row.pkbMax.toString(), field("pkbSymbolPageMax"))
        }
    }

    @Test
    fun startInput_clearsShiftLockAndPendingKeyPresses() {
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE) // shift-lock on
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)      // shift key left held
        assertTrue(state.isShiftKeyPressed)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        assertFalse(state.isShiftKeyPressed)
        assertTrue(state.isShiftKeyReleasing)
        assertEquals("UNSHIFTED", tracker.toString())
        assertEquals("RELEASING", field("shift"))
        assertEquals("RELEASING", field("symbol"))
        assertEquals("RELEASING", field("symbolPagingKey"))
    }

    @Test
    fun hasImeOptionsChanged_comparesAgainstTheLastSavedInputType() {
        assertTrue(state.hasImeOptionsChanged(TYPE_TEXT)) // nothing saved yet -> saved is 0
        assertFalse(state.hasImeOptionsChanged(0))

        state.saveKeyboardState(TYPE_TEXT)

        assertFalse(state.hasImeOptionsChanged(TYPE_TEXT))
        assertTrue(state.hasImeOptionsChanged(TYPE_PASSWORD))
    }

    // ================================================================= 2. save / restore

    @Test
    fun saveRestore_alphabetShiftLocked_comesBackShiftLocked() {
        startInput()
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        assertTrue(tracker.isShiftLocked)
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            RequestShiftLocked,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(true),
        )
        assertTrue(tracker.isShiftLocked)
    }

    @Test
    fun saveRestore_alphabetAutomaticShift_comesBackAutomaticallyShifted() {
        startInput()
        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE) // -> AUTOMATIC_SHIFTED
        assertTrue(tracker.isAutomaticShifted)
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        // setShiftLocked(false) runs unconditionally on the restore path first, hence the leading
        // pair of "off" indicator updates before the saved shift mode is re-applied.
        fake.assertCalls(
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            UpdateShiftIndicator(false),
            UpdateShiftLockedIndicator(false),
            RequestAutomaticShift,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )
        assertTrue(tracker.isAutomaticShifted)
    }

    @Test
    fun saveRestore_symbolMode_comesBackOnTheSameSymbolPage() {
        startInput()
        tapSymbolKey()
        state.onCodeInput(CODE_ACTION_NEXT, false, 0, NO_RECAPITALIZE) // cycle to page 1
        assertTrue(state.isInSymbolMode)
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(SetVkbSymbolsKeyboard(page = 1, flag = true, customPage = false, symbolShiftAction = 0))
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun saveRestore_symbolModeOnPkbDevice_comesBackOnThePkbSymbolKeyboard() {
        fake.pkbDevice = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        assertTrue(state.isInSymbolMode)
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(SetPkbSymbolsKeyboard(page = 0, flag = true, customPage = false, symbolShiftAction = 0))
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun saveRestore_emojiMode_reopensEmoji() {
        startInput()
        state.onEmojiInput()
        assertTrue(state.isInEmojiMode)
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(ShowEmojiKeyboard)
    }

    @Test
    fun saveRestore_menuMode_reopensMenu() {
        startInput()
        state.onInputCodeChanged(CODE_SHOW_INPUT_MENU, 0, NO_RECAPITALIZE)
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(ShowMenu)
    }

    @Test
    fun saveRestore_whenInputTypeChanged_discardsTheSnapshotAndLoadsPlainAlphabet() {
        startInput()
        tapSymbolKey()
        state.saveKeyboardState(TYPE_TEXT)
        fake.clear()

        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_PASSWORD, "com.example.app")

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun saveRestore_snapshotIsConsumedByOneRestore() {
        startInput()
        tapSymbolKey()
        state.saveKeyboardState(TYPE_TEXT)
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")
        assertTrue(state.isInSymbolMode)
        fake.clear()

        // Second restart with no intervening save: the snapshot is spent, so we fall back to
        // the alphabet even though the keyboard is currently showing symbols.
        state.onStartInput(0, NO_RECAPITALIZE, Locale.US, TYPE_TEXT, "com.example.app")

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    // ================================================================= 3. manual shift

    @Test
    fun shiftKeyPress_fromUnshifted_selectsTheManuallyShiftedKeyboard() {
        startInput()

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        // CHARACTERISED BUG: onCodeInput() suppresses onKeyRelease() for the shift code only
        // (`if (i != CODE_SHIFT)`), so the switcher never hears about a shift key-down.
        fake.assertCalls(
            OnStartBatchInput, // switcher uses this to arm the double-tap-shift timer
            RequestShiftOnce,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )
        assertTrue(tracker.isManualShifted)
        assertTrue(state.isShiftKeyPressed)
        assertFalse(state.isShiftKeyMomentary)
    }

    @Test
    fun shiftKeyRelease_afterAPlainTap_leavesTheShiftArmed() {
        startInput()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.clear()

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(tracker.isManualShifted)
        assertTrue(state.isShiftKeyReleasing)
    }

    @Test
    fun manualShift_isConsumedByTheNextLetter() {
        startInput()
        tapShiftKey()
        assertTrue(tracker.isManualShifted)
        fake.clear()

        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)
        // The key-down half already asks for the shift to come off...
        fake.assertCalls(OnKeyRelease, RequestShiftOff)

        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)
        // ...and the post-commit half is what actually clears the tracker.
        fake.assertCalls(
            RequestShiftOff,
            UpdateShiftIndicator(false),
            UpdateShiftLockedIndicator(false),
            OnCharacterKey,
        )
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun manualShift_isNotConsumedWhileTheShiftKeyIsStillHeld() {
        startInput()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE) // press and hold
        fake.clear()

        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(OnKeyRelease) // no requestShiftOff: the key is CHORDING, not RELEASING
        assertTrue(tracker.isManualShifted)
    }

    @Test
    fun chordedShift_endsUnshiftedAndReloadsTheAlphabet() {
        startInput()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE) // makes the shift key CHORDING
        fake.clear()

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(
            RequestShiftOff,
            UpdateShiftIndicator(false),
            UpdateShiftLockedIndicator(false),
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
        )
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun secondShiftTapOutsideTheDoubleTapWindow_turnsShiftBackOff() {
        startInput()
        tapShiftKey()
        fake.clear()

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        // Pressing shift on an already-shifted keyboard only marks the key PRESSING_ON_SHIFTED.
        fake.assertCalls(OnStartBatchInput)
        assertTrue(state.isShiftKeyMomentary)

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false))
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun doubleTapShift_insideTheTimeout_locksShift() {
        startInput()
        tapShiftKey()
        assertTrue(tracker.isManualShifted)
        fake.clear()

        fake.shouldCapitalizeAfterSpace = true // == MainKeyboardView.isInDoubleTapShiftKeyTimeout()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftLocked, UpdateShiftIndicator(true), UpdateShiftLockedIndicator(true))
        assertTrue(tracker.isShiftLocked)
        // CHARACTERISED: this branch returns before ShiftKeyState.onPress(), so the key is still
        // "releasing" while it is physically held down.
        assertTrue(state.isShiftKeyReleasing)

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertNoCalls()
        assertTrue(tracker.isShiftLocked)
    }

    @Test
    fun capsLockKey_togglesShiftLockOnRelease() {
        startInput()

        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftLocked, UpdateShiftIndicator(true), UpdateShiftLockedIndicator(true))
        assertTrue(tracker.isShiftLocked)

        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false))
        assertFalse(tracker.isShiftLocked)
    }

    @Test
    fun capsLockKey_pressDoesNothingButNotifyTheSwitcher() {
        startInput()

        state.onCodeInput(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(OnKeyRelease)
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun shiftLock_survivesLetters() {
        startInput()
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        fake.clear()

        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)
        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)

        fake.assertCalls(OnKeyRelease, OnCharacterKey)
        assertTrue(tracker.isShiftLocked)
    }

    @Test
    fun shiftTapWhileShiftLocked_goesMomentaryThenUnlocks() {
        startInput()
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        fake.clear()

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(
            OnStartBatchInput,
            RequestShiftMomentary,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(true),
        )
        assertEquals("SHIFT_LOCK_SHIFTED", tracker.toString())

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false))
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun shiftTapWhileAutomaticallyShifted_promotesToManualThenTurnsOff() {
        startInput()
        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE)
        assertTrue(tracker.isAutomaticShifted)
        fake.clear()

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(
            OnStartBatchInput,
            RequestShiftOnce,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )
        assertEquals("MANUAL_SHIFTED_FROM_AUTO", tracker.toString())

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false))
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun shiftKey_isInertInSymbolMode() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        // onShiftKeyPress() requires ALPHABET; onShiftKeyRelease()'s symbol branch only reacts to
        // a CHORDING shift key, and nothing here made it chord.
        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun chordedShiftInSymbolMode_isDeadCode() {
        // CHARACTERISED BUG: onShiftKeyRelease() has a symbol-mode arm that cycles the symbol page
        // when the shift key is CHORDING — but the key can never BE chording in symbol mode.
        // onShiftKeyPress() returns early unless currentMode == ALPHABET, so ShiftKeyState.onPress()
        // is never called there, and onOtherKeyPressed() only promotes RELEASING->... from PRESSING.
        startInput()
        tapSymbolKey()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE) // would be "chording" on the alphabet
        assertEquals("RELEASING", field("shift"))
        fake.clear()

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    // ================================================================= 4. automatic capitalisation

    @Test
    fun onInputCodeChanged_letter_appliesAutoCapsAndRecapitalizeMode() {
        // autoCapsFlags, recapitalizeMode -> callbacks + resulting tracker state.
        data class Row(
            val autoCaps: Int,
            val recapitalize: Int,
            val calls: List<FakeSwitcherCallbacks.Call>,
            val tracker: String,
        )
        val rows = listOf(
            // No recapitalize in progress: auto-caps flags alone decide.
            Row(0, NO_RECAPITALIZE, listOf(UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false)), "UNSHIFTED"),
            Row(
                AUTO_CAPS_ON, NO_RECAPITALIZE,
                listOf(RequestAutomaticShift, UpdateShiftIndicator(true), UpdateShiftLockedIndicator(false)),
                "AUTOMATIC_SHIFTED",
            ),
            // A recapitalize mode overrides the auto-caps flags entirely. Only 2 and 3 are mapped;
            // every other value falls through to "shift off".
            Row(AUTO_CAPS_ON, 0, listOf(UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false)), "UNSHIFTED"),
            Row(AUTO_CAPS_ON, 1, listOf(UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false)), "UNSHIFTED"),
            Row(
                0, 2,
                listOf(RequestAutomaticShift, UpdateShiftIndicator(true), UpdateShiftLockedIndicator(false)),
                "AUTOMATIC_SHIFTED",
            ),
            Row(
                0, 3,
                listOf(RequestShiftMomentary, UpdateShiftIndicator(true), UpdateShiftLockedIndicator(false)),
                "MANUAL_SHIFTED",
            ),
            Row(AUTO_CAPS_ON, 4, listOf(UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false)), "UNSHIFTED"),
        )
        for (row in rows) {
            setUp()
            startInput()

            state.onInputCodeChanged('a'.code, row.autoCaps, row.recapitalize)

            fake.assertCalls(*(row.calls + OnCharacterKey).toTypedArray())
            assertEquals(row.toString(), row.tracker, tracker.toString())
        }
    }

    @Test
    fun onInputCodeChanged_space_isTreatedAsALetterCodeForAutoCaps() {
        // CHARACTERISED: Constants.isLetterCode() is `code >= 32`, so SPACE takes the "letter"
        // branch and re-applies auto-caps (which is what makes sentence-start capitalisation work,
        // but it also means punctuation and every other printable code do the same).
        startInput()

        state.onInputCodeChanged(CODE_SPACE, AUTO_CAPS_ON, NO_RECAPITALIZE)

        fake.assertCalls(
            RequestAutomaticShift,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
            OnCharacterKey,
        )
        assertTrue(tracker.isAutomaticShifted)
    }

    @Test
    fun onInputCodeChanged_backspace_doesNotTouchTheShiftState() {
        // CODE_DELETE is below 32, so it is not a "letter code" and never reaches the
        // auto-caps recompute; an armed automatic shift survives a backspace.
        startInput()
        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE)
        fake.clear()

        state.onInputCodeChanged(CODE_DELETE, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(tracker.isAutomaticShifted)
    }

    @Test
    fun autoCaps_isNotAppliedInSymbolMode() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE)

        fake.assertCalls(OnCharacterKey)
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun autoCaps_isSuppressedWhileShiftLocked() {
        startInput()
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        fake.clear()

        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE)

        fake.assertCalls(OnCharacterKey)
        assertTrue(tracker.isShiftLocked)
    }

    @Test
    fun autoCaps_isSuppressedWhileTheShiftKeyIsHeld() {
        startInput()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE) // key is PRESSING, not RELEASING
        fake.clear()

        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE)

        fake.assertCalls(OnCharacterKey)
        assertTrue(tracker.isManualShifted)
    }

    @Test
    fun autoCaps_isSuppressedWhileTheShiftKeyIsIgnoring() {
        startInput()
        tapShiftKey()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE) // -> PRESSING_ON_SHIFTED
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)   // -> IGNORING
        fake.clear()

        state.onInputCodeChanged('a'.code, AUTO_CAPS_ON, NO_RECAPITALIZE)

        fake.assertCalls(OnCharacterKey)
        // DEFECT 6 (investigated, NOT a defect — see the report for wave 2.5). The keyboard stays
        // MANUAL_SHIFTED here *because the shift key is still physically down*: IGNORING is only
        // reachable from PRESSING_ON_SHIFTED, so dropping the shift now would make the second and
        // later letters of a held-shift run come out lowercase. This matches AOSP
        // `KeyboardState.updateAlphabetShiftState`, which also refuses to recompute while the
        // shift key is not RELEASING. The shift is consumed by the key's own release, below.
        assertEquals("MANUAL_SHIFTED", tracker.toString())

        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, UpdateShiftIndicator(false), UpdateShiftLockedIndicator(false))
        assertEquals("UNSHIFTED", tracker.toString())
        assertEquals("RELEASING", field("shift"))
    }

    @Test
    fun ignoringShift_releasedBySlidingOffTheKey_isClearedByTheMomentaryFinish() {
        // The other way out of IGNORING. A release `withSliding` takes the
        // `isManualShifted() && withSliding` arm instead, which clears nothing and parks
        // `switchState` on MOMENTARY-ALPHA_SHIFT; `PointerTracker` then reports
        // `onFinishSlidingInput()`, and *that* is what unshifts the keyboard. Pinned because the
        // two halves live in different classes and only make sense together.
        startInput()
        tapShiftKey()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE) // -> PRESSING_ON_SHIFTED
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)   // -> IGNORING
        fake.clear()

        state.onCodeRelease(CODE_SHIFT, true, 0, NO_RECAPITALIZE) // withSliding

        fake.assertNoCalls()
        assertEquals("MANUAL_SHIFTED", tracker.toString())
        assertEquals("MOMENTARY-ALPHA_SHIFT", switchState())

        state.onMomentaryStateFinish(0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertEquals("UNSHIFTED", tracker.toString())
        assertEquals("ALPHA", switchState())
    }

    @Test
    fun capModeCharacters_suppressesTheKeyDownShiftRelease() {
        // onCodeInput() skips its shift-off branch when autoCapsFlags == CAP_MODE_CHARACTERS,
        // so an all-caps field does not drop the manual shift on key-down.
        startInput()
        tapShiftKey()
        fake.clear()

        state.onCodeInput('a'.code, false, CAP_MODE_CHARACTERS, NO_RECAPITALIZE)

        fake.assertCalls(OnKeyRelease)
        assertTrue(tracker.isManualShifted)
    }

    @Test
    fun requestShiftMode_latchesTheRequestedModeAndDisablesTheShiftKey() {
        // CHARACTERISED BUG: requestShiftMode() stores the recapitalize mode in
        // `requestedShiftMode` and nothing on the alphabet path ever clears it, so after one
        // recapitalize-driven alphabet load the shift key's press handler is inert.
        startInput()

        state.requestShiftMode(0, 2)
        fake.assertCalls(RequestAutomaticShift, UpdateShiftIndicator(true), UpdateShiftLockedIndicator(false))

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertNoCalls() // onShiftKeyPress() bails out: requestedShiftMode != -1

        // The release re-applies the latched mode. setShiftMode() only fires the request callback
        // when the mode actually changes, so this time only the indicators are refreshed.
        state.onCodeRelease(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(UpdateShiftIndicator(true), UpdateShiftLockedIndicator(false))
        assertTrue(tracker.isAutomaticShifted)
    }

    @Test
    fun alphabetLoad_doesNotLeaveTheRecapitalizeModeLatched() {
        // DEFECT 5 (hardening): the switcher's setAlphabetKeyboard() re-enters as
        // requestShiftMode(), which stores the recapitalize mode in `requestedShiftMode`.
        // KeyboardState.loadAlphabetKeyboard() clears it again once the load has been applied, so a
        // mode asked for by one keyboard load cannot outlive it. Without that clear a single wrong
        // argument disables onShiftKeyPress() for the rest of the session, which is what the emoji
        // return path used to do.
        fake.wireProductionAlphabetReentry(state)
        startInput()

        // The alpha key runs switchToAlphabetUnshifted() with a live recapitalize mode (2).
        state.onInputCodeChanged(CODE_ALPHA, 0, 2)
        fake.assertCalls(
            RequestShiftOff,
            SetAlphabetKeyboard(0, 2),
            RequestAutomaticShift,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )

        // The shift key still works afterwards.
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(
            OnStartBatchInput,
            RequestShiftOnce,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )
        assertEquals("MANUAL_SHIFTED_FROM_AUTO", tracker.toString())
    }

    // ================================================================= 5. symbols / alphabet

    @Test
    fun symbolKeyTap_entersSymbolModeOnPageZero() {
        startInput()

        state.onCodeInput(CODE_SYMBOL, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(OnKeyRelease)
        assertFalse(state.isInSymbolMode) // press alone does not switch

        state.onCodeRelease(CODE_SYMBOL, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(SetVkbSymbolsKeyboard(page = 0, flag = false, customPage = false, symbolShiftAction = 0))
        assertTrue(state.isInSymbolMode)
        assertEquals("SYMBOL-BEGIN", switchState())
    }

    @Test
    fun symbolKeyTapWhileInSymbolMode_returnsToTheAlphabet() {
        startInput()
        tapSymbolKey()
        fake.clear()

        tapSymbolKey()

        fake.assertCalls(OnKeyRelease, RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun chordedSymbolKey_staysOnTheAlphabetWithoutFlashingSymbols() {
        // DEFECT 7 (fixed): a chorded SYM key now touches no keyboard at all. onSymbolKeyPress()
        // does not toggle on key-down, so the chord happened on the alphabet and the release
        // leaves it there. Previously onSymbolKeyRelease() called switchToSymbolFromAlphabet()
        // inside the `isChording()` branch AND again unconditionally after it (no else/return);
        // the two toggles cancelled, flashing the symbol keyboard on the way back.
        startInput()
        state.onCodeInput(CODE_SYMBOL, false, 0, NO_RECAPITALIZE)
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE) // SYM key -> CHORDING
        fake.clear()

        state.onCodeRelease(CODE_SYMBOL, false, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertFalse(state.isInSymbolMode)
        // The momentary marker onSymbolKeyPress() set is cleared explicitly now that the
        // round-trip through switchToAlphabetUnshifted() no longer happens.
        assertEquals("ALPHA", switchState())
        assertTrue(state.isShiftKeyReleasing)
        assertEquals("RELEASING", field("symbol"))
    }

    @Test
    fun symbolMode_survivesALetter() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)
        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)

        fake.assertCalls(OnKeyRelease, OnCharacterKey)
        assertTrue(state.isInSymbolMode)
        assertEquals("MOMENTARY-ALPHA-SYMBOL", switchState())
    }

    @Test
    fun symbolThenSpaceThenLetter_returnsToTheAlphabet() {
        // The "symbol, space, letter" sequence is the classic auto-return path: the space moves
        // switchState to SYMBOL-AFTER-SPACE and the next alphabetic code pops back to letters.
        startInput()
        tapSymbolKey()
        state.onCodeInput('!'.code, false, 0, NO_RECAPITALIZE)
        state.onInputCodeChanged('!'.code, 0, NO_RECAPITALIZE)
        state.onInputCodeChanged(CODE_SPACE, 0, NO_RECAPITALIZE)
        assertEquals("SYMBOL-AFTER-SPACE", switchState())
        assertTrue(state.isInSymbolMode)
        fake.clear()

        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)

        // Having landed back on the alphabet, the same call then re-runs the auto-caps recompute.
        fake.assertCalls(
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            UpdateShiftIndicator(false),
            UpdateShiftLockedIndicator(false),
            OnCharacterKey,
        )
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun spaceRelease_inSymbolMode_returnsToTheAlphabetOnAVkbDevice() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onCodeRelease(CODE_SPACE, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(
            OnStartShiftLongPress,
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            OnFinishShiftLongPress,
        )
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun spaceRelease_inSymbolMode_staysInSymbolsOnAPkbDevice() {
        fake.pkbDevice = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        state.onCodeRelease(CODE_SPACE, false, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun spaceRelease_whenRepeating_isIgnored() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onCodeRelease(CODE_SPACE, true, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun symbolPagingKey_cyclesVkbSymbolPages() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onCodeInput(CODE_SYMBOL_PAGING, false, 0, NO_RECAPITALIZE)
        state.onCodeRelease(CODE_SYMBOL_PAGING, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(OnKeyRelease, SetVkbSymbolsKeyboard(1, true, false, 0))

        state.onCodeInput(CODE_SYMBOL_PAGING, false, 0, NO_RECAPITALIZE)
        state.onCodeRelease(CODE_SYMBOL_PAGING, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(OnKeyRelease, SetVkbSymbolsKeyboard(0, true, false, 0)) // wraps
    }

    @Test
    fun symbolPagingKey_cyclesPkbSymbolPagesWithTheirOwnMaximum() {
        fake.pkbDevice = true
        fake.pkbSymbolCustomizationEnabled = true
        startInput() // maxPkbSymbolPages = 3, maxSymbolPages = 2
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        repeat(4) {
            state.onCodeInput(CODE_SYMBOL_PAGING, false, 0, NO_RECAPITALIZE)
            state.onCodeRelease(CODE_SYMBOL_PAGING, false, 0, NO_RECAPITALIZE)
        }

        fake.assertCalls(
            OnKeyRelease, SetPkbSymbolsKeyboard(1, true, false, 0),
            OnKeyRelease, SetPkbSymbolsKeyboard(2, true, true, 0), // page 2 is the custom page
            OnKeyRelease, SetPkbSymbolsKeyboard(0, true, false, 0),
            OnKeyRelease, SetPkbSymbolsKeyboard(1, true, false, 0),
        )
    }

    @Test
    fun actionNextAndPrevious_cycleSymbolPagesUsingTheVkbMaximumEvenOnPkb() {
        // CHARACTERISED: cycleThroughSymbolPages() always uses maxSymbolPages, never
        // maxPkbSymbolPages, and always calls setVkbSymbolsKeyboard — even on a PKB device.
        fake.pkbDevice = true
        fake.pkbSymbolCustomizationEnabled = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        state.onCodeInput(CODE_ACTION_NEXT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(OnKeyRelease, SetVkbSymbolsKeyboard(1, true, false, 0))

        state.onCodeInput(CODE_ACTION_PREVIOUS, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(OnKeyRelease, SetVkbSymbolsKeyboard(0, true, false, 0)) // wraps at 2
    }

    @Test
    fun isVkbCustomSymbolPage_dependsOnTheCustomPagePosition() {
        // customisationEnabled, customPageFirst -> which page indices are "custom" (of 0..2).
        data class Row(val enabled: Boolean, val first: Boolean, val custom: List<Int>)
        for (row in listOf(
            Row(enabled = false, first = false, custom = emptyList()),
            Row(enabled = false, first = true, custom = emptyList()),
            Row(enabled = true, first = true, custom = listOf(0)),
            Row(enabled = true, first = false, custom = listOf(2)), // maxSymbolPages - 1
        )) {
            setUp()
            fake.vkbSymbolCustomizationEnabled = row.enabled
            fake.vkbCustomPageFirst = row.first
            startInput()

            assertEquals(row.toString(), row.custom, (0..2).filter { state.isVkbCustomSymbolPage(it) })
        }
    }

    @Test
    fun isPkbCustomSymbolPage_dependsOnTheCustomPagePosition() {
        data class Row(val enabled: Boolean, val first: Boolean, val custom: List<Int>)
        for (row in listOf(
            Row(enabled = false, first = false, custom = emptyList()),
            Row(enabled = true, first = true, custom = listOf(0)),
            Row(enabled = true, first = false, custom = listOf(2)), // maxPkbSymbolPages - 1
        )) {
            setUp()
            fake.pkbSymbolCustomizationEnabled = row.enabled
            fake.pkbCustomPageFirst = row.first
            startInput()

            assertEquals(row.toString(), row.custom, (0..2).filter { state.isPkbCustomSymbolPage(it) })
        }
    }

    @Test
    fun enteringSymbolsWhileShifted_marksTheCustomPageAsShifted() {
        fake.vkbSymbolCustomizationEnabled = true
        fake.vkbCustomPageFirst = true
        startInput()
        tapShiftKey() // MANUAL_SHIFTED
        fake.clear()

        tapSymbolKey()

        // getVkbSymbolShiftAction(): shift state 1 (from a shifted alphabet), saved state 0 -> 1.
        fake.assertCalls(
            OnKeyRelease,
            SetVkbSymbolsKeyboard(page = 0, flag = false, customPage = true, symbolShiftAction = 1),
        )
        assertTrue(state.isVkbSymbolShifted)
        // Entering symbols always clears the alphabet shift-lock tracker.
        assertEquals("UNSHIFTED", tracker.toString())
    }

    @Test
    fun letterOnAShiftedCustomSymbolPage_dropsBackToTheUnshiftedCustomPage() {
        fake.vkbSymbolCustomizationEnabled = true
        fake.vkbCustomPageFirst = true
        startInput()
        tapShiftKey()
        tapSymbolKey()
        assertTrue(state.isVkbSymbolShifted)
        fake.clear()

        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)

        fake.assertCalls(
            SetVkbSymbolsKeyboard(page = 0, flag = false, customPage = true, symbolShiftAction = 2),
            OnCharacterKey,
        )
        assertFalse(state.isVkbSymbolShifted)
    }

    @Test
    fun symbolShiftToggleFromAlphabet_derivesThePkbShiftStateFromTheConfiguredPageOrder() {
        // getSymbolShiftStateFromOrder(): orders 1, 3, 5, 7 mean "shifted", everything else "not",
        // and that shows up as the shiftAction argument on the custom page.
        for (order in 0..8) {
            setUp()
            fake.pkbDevice = true
            fake.pkbSymbolCustomizationEnabled = true
            fake.pkbCustomPageFirst = true
            fake.symbolPageOrderSetting = order
            startInput()

            state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)

            val expectedAction = if (order in listOf(1, 3, 5, 7)) 1 else 0
            fake.assertCalls(SetPkbSymbolsKeyboard(0, true, true, expectedAction))
        }
    }

    @Test
    fun symbolShiftToggle_advancesThroughPagesThenReturnsToTheAlphabet_vkb() {
        startInput() // maxSymbolPages = 2
        tapSymbolKey()
        fake.clear()

        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, false)
        fake.assertCalls(SetVkbSymbolsKeyboard(1, false, false, 0))

        // Last page -> back to the alphabet. Note the VKB path does NOT fire
        // onReturnToAlphabetFromSymbol(), unlike the PKB path below.
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, false)
        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun symbolShiftToggle_advancesThroughPagesThenReturnsToTheAlphabet_pkb() {
        fake.pkbDevice = true
        startInput() // maxPkbSymbolPages = 2
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, false)
        fake.assertCalls(SetPkbSymbolsKeyboard(1, true, false, 0))

        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, false, false)
        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE), OnReturnToAlphabetFromSymbol)
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun wasSymbolEnteredFromAlphabet_tracksTheEntryMethodNotTheMode() {
        // CHARACTERISED BUG: the accessor is `symbolEntryMethod != 0`, and onStartInput() seeds
        // that field with 1. So it reports "entered from alphabet" while sitting on a plain
        // alphabet keyboard, and reports false once the SYM key has actually opened symbols.
        startInput()
        assertTrue(state.wasSymbolEnteredFromAlphabet())
        assertFalse(state.isInSymbolMode)

        tapSymbolKey()
        assertTrue(state.isInSymbolMode)
        assertFalse(state.wasSymbolEnteredFromAlphabet())

        // The SYM-toggle entry points do set a non-zero method.
        setUp()
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, false)
        assertTrue(state.wasSymbolEnteredFromAlphabet())
    }

    @Test
    fun symbolKeyLongPress_returnsToTheAlphabetOnlyForSymToggleEntries() {
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, false) // entry method 3
        fake.clear()

        state.onSymbolKeyLongPress(0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun symbolKeyLongPress_isANoOpForAPlainSymKeyEntry() {
        startInput()
        tapSymbolKey() // entry method 0
        fake.clear()

        state.onSymbolKeyLongPress(0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun backspaceInSymbolMode_returnsToTheUnshiftedAlphabet() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onBackspaceInSymbolMode(0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun backspaceInAlphabetMode_isANoOp() {
        startInput()

        state.onBackspaceInSymbolMode(0, NO_RECAPITALIZE)

        fake.assertNoCalls()
    }

    @Test
    fun backspaceInSymbolMode_restoresAShiftLockHeldBeforeTheSymbolKeyboard() {
        startInput()
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE) // shift lock on
        tapSymbolKey()
        fake.clear()

        state.onBackspaceInSymbolMode(0, NO_RECAPITALIZE)

        fake.assertCalls(
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            RequestShiftLocked,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(true),
        )
        assertTrue(tracker.isShiftLocked)
    }

    @Test
    fun backspaceInSymbolMode_usesTheManualShiftedPathForTheBanamexApp() {
        // CHARACTERISED: a hard-coded per-app quirk with no recorded rationale (see the javadoc on
        // PKG_BANAMEX). It also silently stops applying if the app changes package name.
        fake.manualTemporaryUppercase = true
        startInput(packageName = "com.citibanamex.banamexmobile")
        tapSymbolKey()
        fake.clear()

        state.onBackspaceInSymbolMode(0, NO_RECAPITALIZE)

        fake.assertCalls(RequestManualShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
    }

    @Test
    fun backspaceInSymbolMode_usesThePlainPathForEveryOtherApp() {
        fake.manualTemporaryUppercase = true
        startInput(packageName = "com.example.app")
        tapSymbolKey()
        fake.clear()

        state.onBackspaceInSymbolMode(0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
    }

    @Test
    fun resetSymbolMode_leavesSymbolModeWithoutTellingTheSwitcher() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.resetSymbolMode()

        fake.assertNoCalls()
        assertFalse(state.isInSymbolMode)
    }

    // ================================================================= 6. emoji and menu

    @Test
    fun emojiKey_opensEmojiAndTogglesBackToTheAlphabet() {
        startInput()

        state.onInputCodeChanged(CODE_EMOJI, 0, NO_RECAPITALIZE)
        fake.assertCalls(ShowEmojiKeyboard)
        assertTrue(state.isInEmojiMode)

        state.onInputCodeChanged(CODE_EMOJI, 0, NO_RECAPITALIZE)
        // DEFECT 5 (fixed): the return path passes the recapitalize "none" sentinel
        // (KeyboardState.RECAPITALIZE_NONE == -1) rather than a literal 0, which is a real
        // recapitalize mode. See emojiClose_leavesTheShiftKeyWorking for the consequence.
        fake.assertCalls(SetAlphabetKeyboard(0, NO_RECAPITALIZE), HideEmojiKeyboard)
        assertFalse(state.isInEmojiMode)
    }

    @Test
    fun emojiClose_leavesTheShiftKeyWorking() {
        // DEFECT 5 (fixed): closing the emoji board used to hand the switcher a recapitalize mode
        // of 0, and through the production re-entrant loop that latched requestedShiftMode = 0
        // for the rest of the session — onShiftKeyPress()'s `RECAPITALIZE_NONE ==
        // requestedShiftMode` guard then failed forever and the shift key was dead. It now
        // responds normally after the emoji board closes.
        fake.wireProductionAlphabetReentry(state)
        startInput()
        state.onEmojiInput()
        fake.clear()

        state.onEmojiInput() // close

        fake.assertCalls(
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            UpdateShiftIndicator(false),
            UpdateShiftLockedIndicator(false),
            HideEmojiKeyboard,
        )

        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(
            OnStartBatchInput, // switcher uses this to arm the double-tap-shift timer
            RequestShiftOnce,
            UpdateShiftIndicator(true),
            UpdateShiftLockedIndicator(false),
        )
        assertEquals("MANUAL_SHIFTED", tracker.toString())
    }

    @Test
    fun enteringEmojiFromSymbols_clearsTheSymbolMode() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onEmojiInput()

        fake.assertCalls(ShowEmojiKeyboard)
        assertTrue(state.isInEmojiMode)
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun resetEmojiMode_onlyLeavesEmojiAndNeverClobbersAnotherMode() {
        // Regression pin: this is called whenever the emoji view is hidden, including as a side
        // effect of refreshing the suggestion strip, so forcing ALPHABET would break symbol mode.
        startInput()
        state.onEmojiInput()
        fake.clear()

        state.resetEmojiMode()
        fake.assertNoCalls()
        assertFalse(state.isInEmojiMode)

        tapSymbolKey()
        fake.clear()
        state.resetEmojiMode()
        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun spaceRelease_inEmojiMode_returnsToTheAlphabetOnAVkbDevice() {
        startInput()
        state.onEmojiInput()
        fake.clear()

        state.onCodeRelease(CODE_SPACE, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(
            OnStartShiftLongPress,
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            OnFinishShiftLongPress,
        )
        assertFalse(state.isInEmojiMode)
    }

    @Test
    fun menuKey_opensTheMenuAndSuspendsShiftLock() {
        startInput()
        state.onCodeRelease(CODE_CAPSLOCK, false, 0, NO_RECAPITALIZE)
        fake.clear()

        state.onInputCodeChanged(CODE_SHOW_INPUT_MENU, 0, NO_RECAPITALIZE)

        fake.assertCalls(ShowMenu)
        assertFalse(tracker.isShiftLocked) // stashed in wasShiftLockedBeforeSymbol
    }

    @Test
    fun onInputCodeChanged_functionCodes_thatReturnToTheAlphabet() {
        // Codes that fall through the tail of onInputCodeChanged to switchToAlphabetUnshifted().
        for (code in listOf(CODE_HIDE_SYMBOL_PKB, CODE_PW_FORCE_BAR, CODE_CANGJIE_REGULAR, CODE_CANGJIE_QUICK, CODE_ALPHA)) {
            setUp()
            startInput()
            tapSymbolKey()
            fake.clear()

            state.onInputCodeChanged(code, 0, NO_RECAPITALIZE)

            val expected = if (code == CODE_HIDE_SYMBOL_PKB) {
                // -43 is the only one that also notifies the switcher.
                listOf(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE), OnReturnToAlphabetFromSymbol)
            } else {
                listOf(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
            }
            fake.assertCalls(*expected.toTypedArray())
            assertFalse("code $code", state.isInSymbolMode)
        }
    }

    @Test
    fun onInputCodeChanged_unhandledFunctionCode_changesNothing() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onInputCodeChanged(CODE_DIACRITICS, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    // ================================================================= 7. switchState machine

    @Test
    fun switchState_transitionsDrivenByOnInputCodeChanged() {
        // Walk the reachable part of the switch-state machine. States are read through toString().
        startInput()
        assertEquals("ALPHA", switchState())

        tapSymbolKey()
        assertEquals("SYMBOL-BEGIN", switchState())

        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)
        assertEquals("SYMBOL", switchState()) // letter from SYMBOL-BEGIN

        state.onInputCodeChanged(CODE_SPACE, 0, NO_RECAPITALIZE)
        assertEquals("SYMBOL-AFTER-SPACE", switchState())

        state.onInputCodeChanged(CODE_DELETE, 0, NO_RECAPITALIZE)
        assertEquals("MOMENTARY-ALPHA-SYMBOL", switchState())

        state.onInputCodeChanged(CODE_SYMBOL, 0, NO_RECAPITALIZE)
        assertEquals("SYMBOL-BEGIN", switchState()) // back round, still in symbol mode

        state.onInputCodeChanged(CODE_SYMBOL, 0, NO_RECAPITALIZE)
        assertEquals("MOMENTARY-ALPHA-SYMBOL", switchState())
    }

    @Test
    fun onMomentaryStateFinish_actsOnlyOnTheMomentaryStates() {
        // ALPHA: nothing happens.
        startInput()
        state.onMomentaryStateFinish(0, NO_RECAPITALIZE)
        fake.assertNoCalls()

        // MOMENTARY-ALPHA-SYMBOL: leave symbols.
        setUp()
        startInput()
        tapSymbolKey()
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE) // SYMBOL-BEGIN + letter -> state 4
        assertEquals("MOMENTARY-ALPHA-SYMBOL", switchState())
        fake.clear()
        state.onMomentaryStateFinish(0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)

        // MOMENTARY-ALPHA_SHIFT: reload the unshifted alphabet.
        setUp()
        startInput()
        state.onCodeInput(CODE_SHIFT, false, 0, NO_RECAPITALIZE)
        state.onCodeRelease(CODE_SHIFT, true, 0, NO_RECAPITALIZE) // repeat release on a manual shift
        assertEquals("MOMENTARY-ALPHA_SHIFT", switchState())
        fake.clear()
        state.onMomentaryStateFinish(0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
    }

    // ================================================================= 8. hardware keys (PKB)

    @Test
    fun hardwareSymKey_armsThePkbSymbolEntryMethod() {
        fake.pkbDevice = true
        fake.pkbSymbolAutoCloseEnabled = true
        startInput()
        tapSymbolKey() // in symbols, but by the on-screen SYM key: entry method 0
        fake.clear()

        state.onSoftwareSymbolCommitted(0, NO_RECAPITALIZE)
        fake.assertNoCalls() // nothing armed yet

        state.onHardwareKeyEvent(KEYCODE_SYM, 0, NO_RECAPITALIZE) // arms entry method 2
        fake.assertNoCalls()

        state.onSoftwareSymbolCommitted(0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE), OnReturnToAlphabetFromSymbol)
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun hardwareSymKey_isInertOnAVkbDevice() {
        startInput()
        tapSymbolKey()
        assertEquals("SYMBOL-BEGIN", switchState())
        fake.clear()

        state.onHardwareKeyEvent(KEYCODE_SYM, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        // A hardware *character* key would advance SYMBOL-BEGIN -> SYMBOL here; SYM does not.
        assertEquals("SYMBOL-BEGIN", switchState())
        state.onHardwareKeyEvent(KEYCODE_A, 0, NO_RECAPITALIZE)
        assertEquals("SYMBOL", switchState())
    }

    @Test
    fun hardwareSymKey_inTheMomentaryState_actsAsBackspaceOutOfSymbols() {
        fake.pkbDevice = true
        startInput()
        tapSymbolKey()
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE) // -> MOMENTARY-ALPHA-SYMBOL
        assertEquals("MOMENTARY-ALPHA-SYMBOL", switchState())
        fake.clear()

        state.onHardwareKeyEvent(KEYCODE_SYM, 0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE))
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun hardwareCharacterKey_closesAPkbSymbolKeyboardWhenAutoCloseIsOn() {
        fake.pkbDevice = true
        fake.pkbSymbolAutoCloseEnabled = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true) // entry method 2
        fake.clear()

        state.onHardwareKeyEvent(KEYCODE_A, 0, NO_RECAPITALIZE)

        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE), OnReturnToAlphabetFromSymbol)
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun hardwareCharacterKey_keepsThePkbSymbolKeyboardWhenAutoCloseIsOff() {
        fake.pkbDevice = true
        fake.pkbSymbolAutoCloseEnabled = false
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        state.onHardwareKeyEvent(KEYCODE_A, 0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun hardwareModifierKeys_areIgnoredEntirely() {
        fake.pkbDevice = true
        fake.pkbSymbolAutoCloseEnabled = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        for (code in listOf(KEYCODE_SHIFT_LEFT, KEYCODE_SHIFT_RIGHT, KEYCODE_SPACE)) {
            state.onHardwareKeyEvent(code, 0, NO_RECAPITALIZE)
        }

        // CHARACTERISED: KEYCODE_SPACE (62) shares the "ignore" arm with the two shift keys, so a
        // hardware space does not close an auto-close PKB symbol keyboard, while ENTER (66) does.
        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun onSoftwareSymbolCommitted_closesThePkbSymbolKeyboardAfterTheCommit() {
        fake.pkbDevice = true
        fake.pkbSymbolAutoCloseEnabled = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        // The letter's key-DOWN deliberately does not leave symbol mode (the tap would be lost).
        state.onCodeInput('a'.code, false, 0, NO_RECAPITALIZE)
        fake.assertCalls(OnKeyRelease)
        assertTrue(state.isInSymbolMode)

        state.onSoftwareSymbolCommitted(0, NO_RECAPITALIZE)
        fake.assertCalls(RequestShiftOff, SetAlphabetKeyboard(0, NO_RECAPITALIZE), OnReturnToAlphabetFromSymbol)
        assertFalse(state.isInSymbolMode)
    }

    @Test
    fun onSoftwareSymbolCommitted_isANoOpOutsideAPkbSymEntry() {
        startInput()
        tapSymbolKey()
        fake.clear()

        state.onSoftwareSymbolCommitted(0, NO_RECAPITALIZE)

        fake.assertNoCalls()
        assertTrue(state.isInSymbolMode)
    }

    @Test
    fun nonLetterKeyInAPkbSymEntry_leavesSymbolModeOnKeyDown() {
        fake.pkbDevice = true
        fake.pkbSymbolAutoCloseEnabled = true
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.clear()

        state.onCodeInput(CODE_DELETE, false, 0, NO_RECAPITALIZE)

        fake.assertCalls(
            OnKeyRelease,
            RequestShiftOff,
            SetAlphabetKeyboard(0, NO_RECAPITALIZE),
            OnReturnToAlphabetFromSymbol,
        )
        assertFalse(state.isInSymbolMode)
    }

    // ============================================ 9. shift-key repeat (PKB symbol shift)

    /**
     * `KeyboardState`'s constructor installs a [KeyRepeatHandler.ModifierListener] on the handler
     * the switcher hands it. Capturing the listener here lets the repeat callbacks be driven
     * directly, without a Looper or a real key-repeat timer.
     */
    private class ListenerCapturingKeyRepeatHandler(owner: MetaKeyStateTracker) : KeyRepeatHandler(owner) {
        var listener: KeyRepeatHandler.ModifierListener? = null

        override fun setModifierListener(listener: KeyRepeatHandler.ModifierListener?) {
            this.listener = listener
        }
    }

    /** Rebuild [state] with a capturing key-repeat handler, configured for a PKB custom page. */
    private fun withKeyRepeatHandler(): KeyRepeatHandler.ModifierListener {
        val handler = ListenerCapturingKeyRepeatHandler(mock(MetaKeyStateTracker::class.java))
        fake = FakeSwitcherCallbacks()
        fake.repeatHandler = handler
        fake.pkbDevice = true
        fake.pkbSymbolCustomizationEnabled = true
        fake.pkbCustomPageFirst = true
        state = KeyboardState(fake)
        return handler.listener!!
    }

    @Test
    fun shiftKeyRepeat_onACustomPkbSymbolPage_togglesTheSymbolShiftAndReloadsThePage() {
        val repeat = withKeyRepeatHandler()
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.assertCalls(SetPkbSymbolsKeyboard(0, true, true, 0))

        // The shiftAction argument is getPkbSymbolShiftAction(): it compares the live symbol-shift
        // state against the copy saved on the previous custom-page load, so it walks 1, 2, 1...
        repeat.onModifierKeyRepeatStart()
        fake.assertCalls(SetPkbSymbolsKeyboard(0, true, true, 1))

        repeat.onModifierKeyRepeatStart()
        fake.assertCalls(SetPkbSymbolsKeyboard(0, true, true, 2))

        repeat.onModifierKeyRepeatEnd()
        fake.assertCalls(SetPkbSymbolsKeyboard(0, true, true, 1))
    }

    @Test
    fun shiftKeyRepeat_outsideSymbolMode_doesNothing() {
        val repeat = withKeyRepeatHandler()
        startInput()

        repeat.onModifierKeyRepeatStart()
        repeat.onModifierKeyRepeatEnd()

        fake.assertNoCalls()
    }

    @Test
    fun shiftKeyRepeat_onANonCustomPkbSymbolPage_changesNoKeyboard() {
        val repeat = withKeyRepeatHandler()
        fake.pkbCustomPageFirst = false // custom page moves to the end, so page 0 is plain
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        fake.assertCalls(SetPkbSymbolsKeyboard(0, true, false, 0))

        repeat.onModifierKeyRepeatStart()
        repeat.onModifierKeyRepeatEnd()

        fake.assertNoCalls()
    }

    @Test
    fun letterOnAShiftedCustomPkbSymbolPage_togglesTheSymbolShiftBackOff() {
        val repeat = withKeyRepeatHandler()
        startInput()
        state.onSymbolShiftToggle(0, NO_RECAPITALIZE, true, true)
        repeat.onModifierKeyRepeatStart() // symbol shift on
        fake.clear()

        state.onInputCodeChanged('a'.code, 0, NO_RECAPITALIZE)

        fake.assertCalls(
            TogglePkbSymbolShift,
            SetPkbSymbolsKeyboard(0, true, true, 2),
            OnCharacterKey,
        )
    }

    private companion object {
        // Key codes (see Constants.printableCode).
        const val CODE_SHIFT = -1
        const val CODE_CAPSLOCK = -2
        const val CODE_SYMBOL = -3
        const val CODE_DELETE = -5
        const val CODE_ACTION_NEXT = -8
        const val CODE_ACTION_PREVIOUS = -9
        const val CODE_EMOJI = -11
        const val CODE_ALPHA = -14
        const val CODE_SYMBOL_PAGING = -15
        const val CODE_DIACRITICS = -16
        const val CODE_SHOW_INPUT_MENU = -23
        const val CODE_PW_FORCE_BAR = -37
        const val CODE_CANGJIE_REGULAR = -39
        const val CODE_CANGJIE_QUICK = -40
        const val CODE_HIDE_SYMBOL_PKB = -43
        const val CODE_SPACE = 32

        // android.view.KeyEvent codes used by onHardwareKeyEvent.
        const val KEYCODE_A = 29
        const val KEYCODE_SHIFT_LEFT = 59
        const val KEYCODE_SHIFT_RIGHT = 60
        const val KEYCODE_SPACE = 62
        const val KEYCODE_SYM = 63

        /** `RecapitalizeStatus.NOT_A_RECAPITALIZE_MODE`. */
        const val NO_RECAPITALIZE = -1

        /** Any non-zero auto-caps flag turns on automatic capitalisation. */
        const val AUTO_CAPS_ON = 1

        /** `android.text.TextUtils.CAP_MODE_CHARACTERS`. */
        const val CAP_MODE_CHARACTERS = 4096

        const val TYPE_TEXT = 1
        const val TYPE_PASSWORD = 129
    }
}
