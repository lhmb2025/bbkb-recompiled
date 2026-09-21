package dev.bbkb.ime.core.locale;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputMethodSubtype;

// Extracted from BlackBerryIME.java
// TODO: Review for further modularization and dependency reduction

/**
 * Package-private until the 2026-09 move to {@code core.locale}; it is public now only because
 * its sole user, {@code core.BlackBerryIME}, no longer shares a package with it.
 */
public final class SubtypeState {
    private InputMethodSubtype lastActiveSubtype;
    private boolean currentSubtypeHasBeenUsed;

    public SubtypeState() {}

    public void setCurrentSubtypeHasBeenUsed() {
        this.currentSubtypeHasBeenUsed = true;
    }

    public boolean switchSubtype(InputMethodService ims, RichInputMethodManager richImm) {
        InputMethodSubtype currentInputMethodSubtype = richImm.getInputMethodManager().getCurrentInputMethodSubtype();
        InputMethodSubtype inputMethodSubtype = this.lastActiveSubtype;
        boolean z = this.currentSubtypeHasBeenUsed;
        if (z) {
            this.lastActiveSubtype = currentInputMethodSubtype;
            this.currentSubtypeHasBeenUsed = false;
        }
        if (z && richImm.checkIfSubtypeBelongsToThisImeAndEnabled(inputMethodSubtype) && !currentInputMethodSubtype.equals(inputMethodSubtype)) {
            richImm.setInputMethodAndSubtype(ims, inputMethodSubtype);
            return true;
        }
        return richImm.switchToNextInputMethod(ims, true);
    }
}