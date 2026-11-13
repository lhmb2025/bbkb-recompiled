package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import dev.bbkb.ime.core.layout.PhysicalKeyResolver;
import dev.bbkb.ime.core.settings.util.SettingsManager;



public final class CurrencyKeyHandler implements KeyCharacterInterpreter {
    public KeyCharacterInterpreter asKeyCharacterInterpreter() {
        return this;
    }

    /** @param metaState the meta state; Sym on the 4 key also gives the currency symbol */
    @Override
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int metaState) {
        if (keyEvent.getKeyCode() != KeyEvent.KEYCODE_4) {
            return null;
        }
        // On Arabic, Hebrew and Cyrillic keycaps this key carries a letter, which wins over currency.
        if (PhysicalKeyResolver.letter(keyEvent, metaState) != 0) {
            return null;
        }
        boolean isCurrencyKey = Character.getType(keyEvent.getUnicodeChar()) == Character.CURRENCY_SYMBOL
                || (KeyEvent.normalizeMetaState(metaState) & KeyEvent.META_SYM_ON) != 0;
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
