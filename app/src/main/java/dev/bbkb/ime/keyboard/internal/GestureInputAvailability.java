package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.core.shared.InputPathDebug;



public final class GestureInputAvailability {

    private boolean isVkbGestureAvailable;
    private boolean isCkbGestureAvailable;
    private boolean isMainDictionaryAvailable;
    private boolean isValidKeyboardLayout;
    private boolean isVkbGestureEnabledByUser;
    private boolean isCkbGestureEnabledByUser;
    private synchronized boolean areBaseGestureConditionsMet() {
        if (this.isMainDictionaryAvailable && this.isValidKeyboardLayout && !LocaleUtils.isCurrentSubtypeChinese()) {
            return true;
        }
        return false;
    }

    private void updateVkbGestureAvailability(boolean z) {
        this.isVkbGestureAvailable = z && this.isVkbGestureEnabledByUser;
    }

    private void updateCkbGestureAvailability(boolean z) {
        this.isCkbGestureAvailable = z && this.isCkbGestureEnabledByUser;
    }

    /** @param z true if the main dictionary is loaded */
    public synchronized void setMainDictionaryAvailable(boolean z) {
        if (this.isMainDictionaryAvailable != z) {
            this.isMainDictionaryAvailable = z;
            boolean zM7357c = areBaseGestureConditionsMet();
            updateVkbGestureAvailability(zM7357c);
            updateCkbGestureAvailability(zM7357c);
        }
    }

    /** @param z the user's on-screen keyboard swipe setting */
    public void setVkbGestureEnabledByUser(boolean z) {
        if (this.isVkbGestureEnabledByUser != z) {
            this.isVkbGestureEnabledByUser = z;
            updateVkbGestureAvailability(areBaseGestureConditionsMet());
        }
    }

    /** @param z the user's capacitive keypad swipe setting */
    public void setCkbGestureEnabledByUser(boolean z) {
        if (this.isCkbGestureEnabledByUser != z) {
            this.isCkbGestureEnabledByUser = z;
            updateCkbGestureAvailability(areBaseGestureConditionsMet());
        }
    }

    /** Inverted argument: a password-field layout is not valid for gesture typing. */
    public void setValidKeyboardLayout(boolean isPasswordInput) {
        final boolean isValid = !isPasswordInput;
        if (this.isValidKeyboardLayout != isValid) {
            this.isValidKeyboardLayout = isValid;
            boolean zM7357c = areBaseGestureConditionsMet();
            updateVkbGestureAvailability(zM7357c);
            updateCkbGestureAvailability(zM7357c);
        }
    }

    public boolean isVkbGestureInputEnabled() {
        Keyboard c0965eM6834l = KeyboardSwitcher.getInstance().getCurrentKeyboard();
        boolean kbSupportsGesture = c0965eM6834l != null && c0965eM6834l.supportsGestureInput();
        if (!kbSupportsGesture) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG",
                "isVkbGestureInputEnabled returned FALSE" +
                " | isVkbGestureAvailable=" + this.isVkbGestureAvailable +
                " | keyboard=" + c0965eM6834l +
                " | supportsGestureInput=" + kbSupportsGesture);
        }
        return this.isVkbGestureAvailable && kbSupportsGesture;
    }

    public boolean isCkbGestureInputEnabled() {
        Keyboard c0965eM6834l = KeyboardSwitcher.getInstance().getCurrentKeyboard();
        boolean kbNonNull = c0965eM6834l != null;
        int elementId = kbNonNull ? c0965eM6834l.mId.mElementId : -1;
        // supportsGestureInput() reads an XML flag, not real gesture support: allow every
        // keyboard except the input menu ids 38 and 138, or one whose XML enables it.
        boolean kbSupportsGesture = kbNonNull && (c0965eM6834l.supportsGestureInput() || !(elementId == 38 || elementId == 138));
        boolean result = this.isCkbGestureAvailable && kbNonNull && kbSupportsGesture;
        if (!result) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG",
                "isCkbGestureInputEnabled returned FALSE" +
                " | isCkbGestureAvailable=" + this.isCkbGestureAvailable +
                " isMainDictionaryAvailable=" + this.isMainDictionaryAvailable +
                " isValidKeyboardLayout=" + this.isValidKeyboardLayout +
                " isCkbGestureEnabledByUser=" + this.isCkbGestureEnabledByUser +
                " | currentKeyboard!=null=" + kbNonNull +
                " keyboardElementId=" + elementId +
                " supportsGestureInput=" + (kbNonNull && c0965eM6834l.supportsGestureInput()) +
                " idAllowsGesture=" + (kbNonNull && !(elementId == 38 || elementId == 138)));
        }
        return result;
    }
}
