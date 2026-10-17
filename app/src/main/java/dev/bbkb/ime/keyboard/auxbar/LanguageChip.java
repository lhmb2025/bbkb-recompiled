package dev.bbkb.ime.keyboard.auxbar;

import android.view.inputmethod.InputMethodSubtype;

/**
 * One keyboard language on the language-chip bar: the row of this keyboard's enabled languages
 * the strip shows in place of suggestions when the user asks to change language (a long press
 * on the space bar, the globe key, Alt+Sym, the multifunction key's action). The current one is
 * first and bold; a tap on another switches to it.
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
