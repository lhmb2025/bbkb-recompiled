package dev.bbkb.ime.core.device.state;

import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Spannable;
import android.text.Spanned;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.keyboard.internal.KeyRepeatHandler;



public abstract class MetaKeyStateTracker implements KeyRepeatHandler.Callback {

    /*
     * Locked-modifier bits emitted by {@link #getMetaState}.
     *
     * These are this class's own span-state encoding, not KeyEvent.META_* values: 0x100, 0x200,
     * 0x400 and 0x800 are unassigned in KeyEvent, and the *pressed* bits deliberately reuse
     * KeyEvent.META_SHIFT_ON / META_ALT_ON / META_SYM_ON so a span state can be OR-ed into a
     * real event meta state. The CTRL-locked bit used to be spelled
     * NuanceSDK.MAX_CONTEXT_LENGTH -- a de-obfuscation rename that substituted a named engine
     * constant for the literal 1024 because the values happened to match, so changing the
     * engine's buffer size would have silently changed what "Ctrl locked" means.
     */
    public static final int META_SHIFT_LOCKED = 0x100;
    public static final int META_ALT_LOCKED = 0x200;
    public static final int META_CTRL_LOCKED = 0x400;
    public static final int META_CAPS_LOCK = 0x800;

    private static final int DOUBLE_TAP_TIMEOUT = ViewConfiguration.getDoubleTapTimeout() * 2;

    private static final Object SHIFT_SPAN = new NoCopySpan.Concrete();

    private static final Object ALT_SPAN = new NoCopySpan.Concrete();

    private static final Object CTRL_SPAN = new NoCopySpan.Concrete();

    private static final Object CAPS_LOCK_SPAN = new NoCopySpan.Concrete();


    private int mDoubleTapKeyCode = 0;

    private boolean mIsDoubleTapPending = false;

    private boolean mIsAltGrPressed = false;

    private KeyRepeatHandler mKeyRepeatHandler = new KeyRepeatHandler(this);

    public static boolean isShiftKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

    public static boolean isAltKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT;
    }

    protected MetaKeyStateTracker() {
    }


    public void setTimer(KeyRepeatHandler handler) {
        this.mKeyRepeatHandler = handler;
    }

    public void resetMetaState(Spannable spannable) {
        cancelLongPressTimer();
        cancelDoubleTapTimer();
        this.mIsAltGrPressed = false;
        spannable.removeSpan(SHIFT_SPAN);
        spannable.removeSpan(ALT_SPAN);
        spannable.removeSpan(CTRL_SPAN);
        spannable.removeSpan(CAPS_LOCK_SPAN);
    }

    public void resetAltState(Spannable spannable) {
        cancelLongPressTimer();
        cancelDoubleTapTimer();
        spannable.removeSpan(ALT_SPAN);
    }

    public void resetShiftState(Spannable spannable) {
        cancelLongPressTimer();
        cancelDoubleTapTimer();
        setAltGrPressed(false);
        spannable.removeSpan(SHIFT_SPAN);
    }

    public static int getMetaState(CharSequence charSequence) {
        return getSpanMetaState(charSequence, CAPS_LOCK_SPAN, META_CAPS_LOCK, META_CAPS_LOCK)
                | getSpanMetaState(charSequence, SHIFT_SPAN, KeyEvent.META_SHIFT_ON, META_SHIFT_LOCKED)
                | getSpanMetaState(charSequence, ALT_SPAN, KeyEvent.META_ALT_ON, META_ALT_LOCKED)
                | getSpanMetaState(charSequence, CTRL_SPAN, KeyEvent.META_SYM_ON, META_CTRL_LOCKED);
    }

    public static int getMetaStateKcmOverride(CharSequence charSequence, KeyEvent keyEvent) {
        int metaState = keyEvent.getMetaState();
        boolean shouldUseInternalMetaState = keyEvent.getKeyCharacterMap().getModifierBehavior() == 1;
        if (!shouldUseInternalMetaState && isOverrideDeviceMetaStateEnabled()) {
            shouldUseInternalMetaState = true;
        }
        int spanMeta = shouldUseInternalMetaState ? getMetaState(charSequence) : 0;
        return shouldUseInternalMetaState ? metaState | spanMeta : metaState;
    }

    private static int getSpanMetaState(CharSequence charSequence, Object span, int pressedFlag, int lockedFlag) {
        if (!(charSequence instanceof Spanned)) {
            return 0;
        }
        int spanFlags = ((Spanned) charSequence).getSpanFlags(span);
        if (spanFlags == 67108881) {
            return lockedFlag;
        } else if (spanFlags == 16777233 || spanFlags == 33554449) {
            return pressedFlag;
        }
        return 0;
    }

    public void clearPressedModifiers(Spannable spannable) {
        this.mIsAltGrPressed = false;
        clearPressedSpan(spannable, SHIFT_SPAN);
        clearPressedSpan(spannable, ALT_SPAN);
        clearPressedSpan(spannable, CTRL_SPAN);
    }

    private static void clearPressedSpan(Spannable spannable, Object span) {
        int spanFlags = spannable.getSpanFlags(span);
        if (spanFlags == 16777233) {
            spannable.setSpan(span, 0, 0, 50331665);
        } else if (spanFlags == 33554449 || spanFlags == 50331665) {
            spannable.removeSpan(span);
        }
    }

    public boolean onKeyDown(View view, Editable editable, int keyCode, KeyEvent keyEvent) {
        startLongPressTimer(keyCode);
        if (!this.mKeyRepeatHandler.isInDoubleTapTimeout() || keyCode != this.mDoubleTapKeyCode) {
            cancelDoubleTapTimer();
            if ((isShiftKey(keyCode) && getMetaState((CharSequence) editable) != META_SHIFT_LOCKED) || (isAltKey(keyCode) && getMetaState((CharSequence) editable) != META_ALT_LOCKED)) {
                startDoubleTapTimer(keyCode);
            }
        } else {
            this.mIsDoubleTapPending = true;
        }
        if (isShiftKey(keyCode) && this.mIsAltGrPressed) {
            this.mIsAltGrPressed = false;
            return true;
        }
        if (isShiftKey(keyCode)) {
            toggleModifierSpan(editable, SHIFT_SPAN);
            return true;
        }
        if (isAltKey(keyCode)) {
            toggleModifierSpan(editable, ALT_SPAN);
            return true;
        }
        if (keyCode != 63) {
            return false;
        }
        toggleModifierSpan(editable, CTRL_SPAN);
        return true;
    }

    private void toggleModifierSpan(Editable editable, Object span) {
        int spanFlags = editable.getSpanFlags(span);
        if (spanFlags == 16777233) {
            // PRESSED -> no-op
        } else if (spanFlags == 33554449) {
            editable.removeSpan(span);
        } else if (spanFlags == 50331665) {
            // USED -> no-op
        } else if (spanFlags == 67108881) {
            editable.removeSpan(span);
        } else {
            editable.setSpan(span, 0, 0, 16777233);
        }
    }

    public boolean onKeyUp(View view, Editable editable, int keyCode, KeyEvent keyEvent, boolean trackingPrevious) {
        this.mKeyRepeatHandler.cancelKeyRepeatTimer();
        if (this.mKeyRepeatHandler.isInDoubleTapTimeout() && this.mIsDoubleTapPending) {
            boolean doubleTapLockAllowed = isDoubleTapLockEnabled(keyCode);
            if (doubleTapLockAllowed) {
                lockModifier(editable, keyCode);
            }
            cancelDoubleTapTimer();
            return true;
        }
        if (isShiftKey(keyCode)) {
            adjustMetaAfterKeyUp(editable, SHIFT_SPAN, keyEvent, trackingPrevious);
            return true;
        }
        if (isAltKey(keyCode)) {
            adjustMetaAfterKeyUp(editable, ALT_SPAN, keyEvent, trackingPrevious);
            return true;
        }
        if (keyCode != 63) {
            return false;
        }
        adjustMetaAfterKeyUp(editable, CTRL_SPAN, keyEvent, trackingPrevious);
        return true;
    }

    private void adjustMetaAfterKeyUp(Editable editable, Object span, KeyEvent keyEvent, boolean trackingPrevious) {
        int spanFlags = editable.getSpanFlags(span);
        int modifierBehavior = keyEvent.getKeyCharacterMap().getModifierBehavior();
        boolean overrideEnabled = isOverrideDeviceMetaStateEnabled();
        
        if (modifierBehavior != 1 && !overrideEnabled) {
            editable.removeSpan(span);
            return;
        }
        if (spanFlags == 50331665) {
            editable.removeSpan(span);
        } else if (spanFlags == 16777233) {
            editable.setSpan(span, 0, 0, 33554449);
        } else if (spanFlags == 67108881 && trackingPrevious) {
            editable.removeSpan(span);
        }
    }

    private void startLongPressTimer(int keyCode) {
        int altLongPressTimeout = SettingsManager.getInstance().getSettingsValues().keyLongpressTimeoutMs * 2;
        int shiftLongPressTimeout = SettingsManager.getInstance().getSettingsValues().shiftKeyLongPressTimeout;
        if (isShiftKey(keyCode)) {
            this.mKeyRepeatHandler.startKeyRepeatTimer(this, KeyEvent.KEYCODE_SHIFT_LEFT, shiftLongPressTimeout);
        } else if (isAltKey(keyCode)) {
            this.mKeyRepeatHandler.startKeyRepeatTimer(this, KeyEvent.KEYCODE_ALT_LEFT, altLongPressTimeout);
        }
    }

    protected void lockModifier(Editable editable, int keyCode) {
        if (isShiftKey(keyCode) && getMetaState((CharSequence) editable) != META_SHIFT_LOCKED) {
            editable.setSpan(SHIFT_SPAN, 0, 0, 67108881);
        } else {
            if (!isAltKey(keyCode) || getMetaState((CharSequence) editable) == META_ALT_LOCKED) {
                return;
            }
            editable.setSpan(ALT_SPAN, 0, 0, 67108881);
        }
    }

    public void cancelLongPressTimer() {
        this.mKeyRepeatHandler.cancelKeyRepeatTimer();
    }

    protected void cancelDoubleTapTimer() {
        this.mKeyRepeatHandler.cancelDoubleTapTimer();
        this.mIsDoubleTapPending = false;
        this.mDoubleTapKeyCode = 0;
    }

    protected void startDoubleTapTimer(int keyCode) {
        this.mKeyRepeatHandler.startDoubleTapTimer();
        this.mDoubleTapKeyCode = keyCode;
    }

    /**
     * Checks whether double-tap-to-lock is enabled for the given modifier key.
     * Shift and Alt each have independent settings; defaults to true (enabled).
     */
    private static boolean isDoubleTapLockEnabled(int keyCode) {
        try {
            SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
            if (settings != null) {
                if (isShiftKey(keyCode)) {
                    return settings.shiftDoubleTapLock;
                }
                if (isAltKey(keyCode)) {
                    return settings.altDoubleTapLock;
                }
            }
        } catch (Exception e) {
            // If we can't read the preference, default to enabled
        }
        return true;
    }

    public static boolean isOverrideDeviceMetaStateEnabled() {
        // Check if override device meta state setting is enabled
        try {
            SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
            if (settings != null && settings.overrideDeviceMetaState != null) {
                return settings.overrideDeviceMetaState;
            }
        } catch (Exception e) {
            // If we can't read the preference, default to enabled for backward compatibility
        }
        return true; // Default to true (enabled)
    }

    public static int getDoubleTapTimeout() {
        return DOUBLE_TAP_TIMEOUT;
    }

    public KeyRepeatHandler getKeyRepeatHandler() {
        return this.mKeyRepeatHandler;
    }

    protected void setAltGrPressed(boolean pressed) {
        this.mIsAltGrPressed = pressed;
    }

    protected boolean isAltGrPressed() {
        return this.mIsAltGrPressed;
    }

    /**
     * Returns true if Alt span is in PRESSED state (physically held down).
     * PRESSED = 16777233, STICKY = 33554449, LOCKED = 67108881
     */
    protected static boolean isAltSpanPressed(CharSequence charSequence) {
        if (!(charSequence instanceof Spanned)) {
            return false;
        }
        int spanFlags = ((Spanned) charSequence).getSpanFlags(ALT_SPAN);
        return spanFlags == 16777233; // PRESSED
    }
}
