package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import dev.bbkb.ime.core.settings.util.SettingsManager;



public final class CurrencyKeyHandler implements KeyCharacterInterpreter {
    public KeyCharacterInterpreter asKeyCharacterInterpreter() {
        return this;
    }

    /**
     * Audit DK-30: written with three magic values — {@code 11} is {@code KEYCODE_4}, {@code 26} is
     * {@link Character#CURRENCY_SYMBOL} and {@code 4} is {@link KeyEvent#META_SYM_ON} — so "the 4
     * key producing a currency symbol" was unreadable. This interpreter sits in the
     * {@code CompositeKeyCharacterInterpreter} chain evaluated on every hardware key event, so the
     * doubled {@code getSettingsValues()} hop was per-keystroke too.
     */
    @Override
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int i) {
        if (keyEvent.getKeyCode() != KeyEvent.KEYCODE_4) {
            return null;
        }
        boolean isCurrencyKey = Character.getType(keyEvent.getUnicodeChar()) == Character.CURRENCY_SYMBOL
                || (KeyEvent.normalizeMetaState(i) & KeyEvent.META_SYM_ON) != 0;
        if (!isCurrencyKey) {
            return null;
        }
        String currencySymbol = SettingsManager.getInstance().getSettingsValues().currencySymbol;
        if (currencySymbol.isEmpty()) {
            return null;
        }
        return new KeyCharacterResult.Interpretation(currencySymbol.charAt(0));
    }
}
