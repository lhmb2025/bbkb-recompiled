package dev.bbkb.ime.keyboard.auxbar;

import android.view.inputmethod.InputMethodSubtype;

/**
 * One language on the language-chip bar, which replaces suggestions when the user asks to change
 * language (space bar long press, globe key, Alt+Sym, multifunction key). The current one is
 * first and bold.
 */
public final class LanguageChip {
    public final CharSequence displayName;
    public final InputMethodSubtype subtype;
    public final boolean current;

    public LanguageChip(CharSequence displayName, InputMethodSubtype subtype, boolean current) {
        this.displayName = displayName;
        this.subtype = subtype;
        this.current = current;
    }
}
