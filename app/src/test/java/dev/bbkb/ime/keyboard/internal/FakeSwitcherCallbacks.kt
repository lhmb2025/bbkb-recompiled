package dev.bbkb.ime.keyboard.internal

import org.junit.Assert.assertEquals

/**
 * Test double for [KeyboardState.SwitcherCallbacks] — the one seam through which `KeyboardState`
 * touches the world.
 *
 * `KeyboardState` is a pure state machine: it consumes events (key press / release, code input,
 * hardware key, start-input, save/restore) and its *only* output is the sequence of calls it makes
 * on this interface. So the interface splits cleanly in two, and this fake treats the halves
 * differently:
 *
 *  * **Queries** (the methods that return something — `isPkbDevice()`,
 *    `isVkbSymbolCustomizationEnabled()`, `shouldCapitalizeAfterSpace()`, …) are *inputs* to the
 *    machine. They are plain settable properties here, so a test reads as a configuration block
 *    followed by a script of events. They are deliberately **not** recorded: how many times
 *    `KeyboardState` asks a question is an implementation detail a rewrite is free to change.
 *  * **Commands** (the `void` methods) are the *outputs*. Every one is recorded in [calls], in
 *    order, as a [Call] value object.
 *
 * Assert with [assertCalls], which compares the rendered sequence so a failure prints both scripts
 * line by line.
 *
 * ### The re-entrant callback
 *
 * One production callback calls straight back into `KeyboardState`:
 * `KeyboardSwitcher.setAlphabetKeyboard(autoCapsFlags, recapitalizeMode)` is implemented as
 * `keyboardState.requestShiftMode(autoCapsFlags, recapitalizeMode)`. That loop is how automatic
 * capitalisation is actually applied — `KeyboardState` never applies auto-caps to itself directly,
 * it asks for an alphabet keyboard and the switcher hands the request back. None of the other
 * `request*` callbacks re-enter (`KeyboardSwitcher.requestShiftOff()` and friends go to a *private*
 * one-argument `setAlphabetKeyboard(int)` that does not).
 *
 * By default this fake is a pure recorder and does not re-enter, which pins `KeyboardState` alone.
 * A test that needs the production loop opts in with [wireProductionAlphabetReentry].
 */
class FakeSwitcherCallbacks : KeyboardState.SwitcherCallbacks {

    // ---------------------------------------------------------------- recorded commands

    /** One recorded call on the switcher seam. Every subtype renders itself as its call syntax. */
    sealed class Call {
        // -- keyboard selection ------------------------------------------------------------
        /** `setAlphabetKeyboard(autoCapsFlags, recapitalizeMode)` — the re-entrant one. */
        data class SetAlphabetKeyboard(val autoCapsFlags: Int, val recapitalizeMode: Int) : Call() {
            override fun toString() = "setAlphabetKeyboard(autoCaps=$autoCapsFlags, recap=$recapitalizeMode)"
        }

        /** `setVkbSymbolsKeyboard(page, forceLayout, isCustomPage, symbolShiftAction)`. */
        data class SetVkbSymbolsKeyboard(
            val page: Int,
            val flag: Boolean,
            val customPage: Boolean,
            val symbolShiftAction: Int,
        ) : Call() {
            override fun toString() =
                "setVkbSymbolsKeyboard(page=$page, flag=$flag, custom=$customPage, shiftAction=$symbolShiftAction)"
        }

        /** `setPkbSymbolsKeyboard(page, forceLayout, isCustomPage, symbolShiftAction)`. */
        data class SetPkbSymbolsKeyboard(
            val page: Int,
            val flag: Boolean,
            val customPage: Boolean,
            val symbolShiftAction: Int,
        ) : Call() {
            override fun toString() =
                "setPkbSymbolsKeyboard(page=$page, flag=$flag, custom=$customPage, shiftAction=$symbolShiftAction)"
        }

        // -- shift element requests --------------------------------------------------------
        /** Select the UNSHIFTED alphabet element (switcher: `setAlphabetKeyboard(0)`). */
        data object RequestShiftOff : Call() {
            override fun toString() = "requestShiftOff()"
        }

        /** Leave a manually shifted alphabet; on PKB this also forces VKB mode. */
        data object RequestManualShiftOff : Call() {
            override fun toString() = "requestManualShiftOff()"
        }

        /** Select the MANUAL-shifted element (switcher: `setAlphabetKeyboard(1)`). */
        data object RequestShiftOnce : Call() {
            override fun toString() = "requestShiftOnce()"
        }

        /** Select the AUTOMATIC-shifted element (switcher: `setAlphabetKeyboard(2)`). */
        data object RequestAutomaticShift : Call() {
            override fun toString() = "requestAutomaticShift()"
        }

        /** Select the SHIFT-LOCKED element (switcher: `setAlphabetKeyboard(3)`). */
        data object RequestShiftLocked : Call() {
            override fun toString() = "requestShiftLocked()"
        }

        /** Select the MOMENTARY-shifted element (switcher: `setAlphabetKeyboard(4)`). */
        data object RequestShiftMomentary : Call() {
            override fun toString() = "requestShiftMomentary()"
        }

        // -- indicators --------------------------------------------------------------------
        data class UpdateShiftIndicator(val shifted: Boolean) : Call() {
            override fun toString() = "updateShiftIndicator($shifted)"
        }

        data class UpdateShiftLockedIndicator(val locked: Boolean) : Call() {
            override fun toString() = "updateShiftLockedIndicator($locked)"
        }

        // -- other boards ------------------------------------------------------------------
        data object ShowEmojiKeyboard : Call() {
            override fun toString() = "showEmojiKeyboard()"
        }

        data object HideEmojiKeyboard : Call() {
            override fun toString() = "hideEmojiKeyboard()"
        }

        data object ShowMenu : Call() {
            override fun toString() = "showMenu()"
        }

        // -- notifications -----------------------------------------------------------------
        data object OnReturnToAlphabetFromSymbol : Call() {
            override fun toString() = "onReturnToAlphabetFromSymbol()"
        }

        data object OnCharacterKey : Call() {
            override fun toString() = "onCharacterKey()"
        }

        /** Despite the name, the switcher uses this to arm the double-tap-shift timer. */
        data object OnStartBatchInput : Call() {
            override fun toString() = "onStartBatchInput()"
        }

        data object OnKeyRelease : Call() {
            override fun toString() = "onKeyRelease()"
        }

        data object OnStartShiftLongPress : Call() {
            override fun toString() = "onStartShiftLongPress()"
        }

        data object OnFinishShiftLongPress : Call() {
            override fun toString() = "onFinishShiftLongPress()"
        }

        data object TogglePkbSymbolShift : Call() {
            override fun toString() = "togglePkbSymbolShift()"
        }
    }

    private val recorded = mutableListOf<Call>()

    /** Everything recorded since construction or the last [clear] / [drain], in call order. */
    val calls: List<Call> get() = recorded.toList()

    // ---------------------------------------------------------------- configured queries

    /** Physical-keyboard device? Splits nearly every symbol-mode branch. */
    var pkbDevice: Boolean = false

    /** "Custom symbol page" feature flags — these change `maxSymbolPages` / `maxPkbSymbolPages`. */
    var vkbSymbolCustomizationEnabled: Boolean = false
    var vkbCustomPageFirst: Boolean = false
    var pkbSymbolCustomizationEnabled: Boolean = false
    var pkbCustomPageFirst: Boolean = false

    /** Whether a PKB SYM-key entry auto-closes back to the alphabet after one character. */
    var pkbSymbolAutoCloseEnabled: Boolean = false

    /** Feeds `getSymbolShiftStateFromOrder()`; see the order table in `KeyboardStateTest`. */
    var symbolPageOrderSetting: Int = 0

    /**
     * Production name is misleading: `KeyboardSwitcher` implements this as
     * `MainKeyboardView.isInDoubleTapShiftKeyTimeout()`. Set it true to simulate the second tap of
     * a shift double-tap.
     */
    var shouldCapitalizeAfterSpace: Boolean = false

    /** `DeviceProfile.current().hasShiftedSymbolKeyboard()` in production. */
    var manualTemporaryUppercase: Boolean = false

    /** `KeyboardState` tolerates a null handler; the default keeps the fake framework-free. */
    var repeatHandler: KeyRepeatHandler? = null

    /**
     * Installed on `setAlphabetKeyboard(autoCaps, recap)` when a test opts into the production
     * re-entrant loop. Invoked *after* the call is recorded, so the recorded sequence reads in the
     * same order the production callbacks fire.
     */
    var alphabetKeyboardReentry: ((Int, Int) -> Unit)? = null

    /**
     * Reproduce `KeyboardSwitcher.setAlphabetKeyboard(int, int)`, which forwards straight to
     * `keyboardState.requestShiftMode(...)`. Needed by any test about automatic capitalisation.
     */
    fun wireProductionAlphabetReentry(state: KeyboardState) {
        alphabetKeyboardReentry = { autoCapsFlags, recapitalizeMode ->
            state.requestShiftMode(autoCapsFlags, recapitalizeMode)
        }
    }

    // ---------------------------------------------------------------- assertions

    /** Forget everything recorded so far (typically after arranging a state). */
    fun clear() = recorded.clear()

    /** Return everything recorded so far and forget it. */
    fun drain(): List<Call> = calls.also { recorded.clear() }

    /** Assert the exact recorded sequence, then clear it so the next act/assert starts fresh. */
    fun assertCalls(vararg expected: Call) {
        assertEquals(render(expected.toList()), render(calls))
        clear()
    }

    /** Assert nothing at all was called, then clear. */
    fun assertNoCalls() = assertCalls()

    private fun render(seq: List<Call>): String =
        if (seq.isEmpty()) "(no calls)" else seq.joinToString("\n") { "  $it" }

    // ---------------------------------------------------------------- SwitcherCallbacks

    override fun showEmojiKeyboard() {
        recorded += Call.ShowEmojiKeyboard
    }

    override fun hideEmojiKeyboard() {
        recorded += Call.HideEmojiKeyboard
    }

    override fun isVkbSymbolCustomizationEnabled(): Boolean = vkbSymbolCustomizationEnabled

    override fun isVkbCustomPageFirst(): Boolean = vkbCustomPageFirst

    override fun isPkbSymbolCustomizationEnabled(): Boolean = pkbSymbolCustomizationEnabled

    override fun isPkbCustomPageFirst(): Boolean = pkbCustomPageFirst

    override fun isPkbSymbolAutoCloseEnabled(): Boolean = pkbSymbolAutoCloseEnabled

    override fun getSymbolPageOrder(): Int = symbolPageOrderSetting

    override fun togglePkbSymbolShift() {
        recorded += Call.TogglePkbSymbolShift
    }

    override fun getKeyRepeatHandler(): KeyRepeatHandler? = repeatHandler

    override fun onReturnToAlphabetFromSymbol() {
        recorded += Call.OnReturnToAlphabetFromSymbol
    }

    override fun onCharacterKey() {
        recorded += Call.OnCharacterKey
    }

    override fun onStartBatchInput() {
        recorded += Call.OnStartBatchInput
    }

    override fun onKeyRelease() {
        recorded += Call.OnKeyRelease
    }

    override fun shouldCapitalizeAfterSpace(): Boolean = shouldCapitalizeAfterSpace

    override fun isManualTemporaryUppercase(): Boolean = manualTemporaryUppercase

    override fun setVkbSymbolsKeyboard(page: Int, flag: Boolean, customPage: Boolean, shiftAction: Int) {
        recorded += Call.SetVkbSymbolsKeyboard(page, flag, customPage, shiftAction)
    }

    override fun updateShiftIndicator(shifted: Boolean) {
        recorded += Call.UpdateShiftIndicator(shifted)
    }

    override fun updateShiftLockedIndicator(locked: Boolean) {
        recorded += Call.UpdateShiftLockedIndicator(locked)
    }

    override fun setPkbSymbolsKeyboard(page: Int, flag: Boolean, customPage: Boolean, shiftAction: Int) {
        recorded += Call.SetPkbSymbolsKeyboard(page, flag, customPage, shiftAction)
    }

    override fun setAlphabetKeyboard(autoCapsFlags: Int, recapitalizeMode: Int) {
        recorded += Call.SetAlphabetKeyboard(autoCapsFlags, recapitalizeMode)
        alphabetKeyboardReentry?.invoke(autoCapsFlags, recapitalizeMode)
    }

    override fun isPkbDevice(): Boolean = pkbDevice

    override fun showMenu() {
        recorded += Call.ShowMenu
    }

    override fun requestShiftOff() {
        recorded += Call.RequestShiftOff
    }

    override fun requestManualShiftOff() {
        recorded += Call.RequestManualShiftOff
    }

    override fun requestShiftOnce() {
        recorded += Call.RequestShiftOnce
    }

    override fun requestAutomaticShift() {
        recorded += Call.RequestAutomaticShift
    }

    override fun requestShiftLocked() {
        recorded += Call.RequestShiftLocked
    }

    override fun requestShiftMomentary() {
        recorded += Call.RequestShiftMomentary
    }

    override fun onFinishShiftLongPress() {
        recorded += Call.OnFinishShiftLongPress
    }

    override fun onStartShiftLongPress() {
        recorded += Call.OnStartShiftLongPress
    }
}
