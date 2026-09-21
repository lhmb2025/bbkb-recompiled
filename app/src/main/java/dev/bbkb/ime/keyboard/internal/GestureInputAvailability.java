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
    private synchronized boolean areBaseGestureConditionsMet() {        // Fixed decompilation: All conditions must be true (AND logic) to return true
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

    public synchronized void setMainDictionaryAvailable(boolean z) {
        if (this.isMainDictionaryAvailable != z) {
            this.isMainDictionaryAvailable = z;
            boolean zM7357c = areBaseGestureConditionsMet();
            updateVkbGestureAvailability(zM7357c);
            updateCkbGestureAvailability(zM7357c);
        }
    }

    public void setVkbGestureEnabledByUser(boolean z) {
        if (this.isVkbGestureEnabledByUser != z) {
            this.isVkbGestureEnabledByUser = z;
            updateVkbGestureAvailability(areBaseGestureConditionsMet());
        }
    }

    public void setCkbGestureEnabledByUser(boolean z) {
        if (this.isCkbGestureEnabledByUser != z) {
            this.isCkbGestureEnabledByUser = z;
            updateCkbGestureAvailability(areBaseGestureConditionsMet());
        }
    }

    /**
     * NOTE the inverted argument. The only caller is
     * {@code MainKeyboardView.setKeyboard}, which passes {@code keyboard.mId.passwordInput()} —
     * i.e. {@code true} means "this is a password field", which is precisely a layout gesture
     * typing must NOT run on. The stored flag is therefore {@code !isPasswordInput}.
     *
     * <p>The decompiled body expressed the same thing as {@code if (field == z) field = !z;},
     * which reads like a broken setter (it is not — for booleans {@code field != !z} is exactly
     * {@code field == z}). Kept behaviour-identical, with the inversion made explicit.
     */
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
        // supportsGestureInput() reads the XML proximity-correction flag (mAllowRedundantMoreKeys), which is
        // NOT the same as gesture-typing support. On the original app CKB gesture input was
        // gated by KeyboardId.isPkbKeyboard() — it returned true for every keyboard except the
        // unified-input-menu IDs 38 and 138. We keep the dynamic flag as an OR so explicit
        // XML declarations still work, but we must also allow all normal text-input keyboards.
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
