package dev.bbkb.ime.core

/**
 * Supplies the current symbol-page order (0 alphabet, 1/3 symbol pages) and the manual-shift
 * state to [dev.bbkb.ime.core.textinput.InputLogic]. Implemented by
 * [dev.bbkb.ime.keyboard.KeyboardSwitcher] for the on-screen keyboard and by
 * [dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker] for a physical
 * keyboard with a shifted symbol layer; [BlackBerryIME.getSymbolPageProvider] picks one.
 */
interface SymbolPageProvider {
    fun getSymbolPageOrder(): Int
    fun isManualShiftAndShiftPressing(): Boolean
}
