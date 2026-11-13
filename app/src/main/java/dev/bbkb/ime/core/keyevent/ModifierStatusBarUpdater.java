package dev.bbkb.ime.core.keyevent;

import android.os.Handler;
import android.os.Looper;
import android.text.method.MetaKeyKeyListener;
import android.view.KeyEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.BlackBerryIME;


public class ModifierStatusBarUpdater implements ModifierStateListener {

    private static final String TAG = "ModifierStatusBarUpdater";

    private final BlackBerryIME mBlackberryIme;

    /**
     * What was last posted: the meta state, plus {@link #CTRL_ACTIVE_BIT} / {@link #CTRL_LOCKED_BIT}
     * for Ctrl, which is not span state and so has no meta bit of its own.
     */
    private int mLastMetaState = 0;

    /**
     * The icon last sent (drawable id, 0 hidden, {@link #ICON_UNKNOWN} before the first). Each post is a
     * binder call, so posts go out only when the icon changes, except the first re-assert of a visible icon
     * per input session: the system clears the slot on unbind and drops posts made before it is attached.
     */
    private int mSentIconResId = ICON_UNKNOWN;

    /**
     * Whether a visible icon has been re-asserted since the last input-session boundary, so the
     * window-shown and input-view-started re-asserts of one show post it once, not twice.
     */
    private boolean mReassertedThisSession = false;

    private static final int ICON_UNKNOWN = -1;

    /** Unused by both meta encodings (KeyEvent's and the span tracker's); see {@link #withCtrl}. */
    private static final int CTRL_ACTIVE_BIT = 1 << 30;
    private static final int CTRL_LOCKED_BIT = 1 << 29;

    /** {@code metaState} with Ctrl folded in, so a Ctrl change is a change of the posted state. */
    static int withCtrl(int metaState, boolean ctrlActive, boolean ctrlLocked) {
        int state = metaState & ~(CTRL_ACTIVE_BIT | CTRL_LOCKED_BIT);
        if (ctrlActive || ctrlLocked) {
            state |= CTRL_ACTIVE_BIT;
        }
        if (ctrlLocked) {
            state |= CTRL_LOCKED_BIT;
        }
        return state;
    }

    static int getStatusIconDrawable(int metaState) {
        int altMask = metaState & MetaKeyKeyListener.META_ALT_LOCKED;
        boolean isAltLocked = altMask != 0;
        boolean isAltPressed = altMask == 0 && (metaState & KeyEvent.META_ALT_ON) != 0;
        int shiftMask = metaState & MetaKeyKeyListener.META_CAP_LOCKED;
        boolean isShiftLocked = shiftMask != 0;
        boolean isShiftPressed = shiftMask == 0 && (metaState & KeyEvent.META_SHIFT_ON) != 0;
        if (isAltLocked && isShiftLocked) {
            return R.drawable.ic_status_alt_locked_shift_locked;
        }
        if (isAltLocked && isShiftPressed) {
            return R.drawable.ic_status_alt_locked_shift;
        }
        if (isAltPressed && isShiftLocked) {
            return R.drawable.ic_status_alt_shift_locked;
        }
        if (isAltPressed && isShiftPressed) {
            return R.drawable.ic_status_alt_shift;
        }
        // Ctrl, alone or with Shift. Alt outranks it (above): a Ctrl that Alt is on top of is
        // turned off by the next key, which types its Alt character.
        if ((metaState & CTRL_ACTIVE_BIT) != 0 && !isAltLocked && !isAltPressed) {
            boolean isCtrlLocked = (metaState & CTRL_LOCKED_BIT) != 0;
            if (isShiftLocked) {
                return isCtrlLocked ? R.drawable.ic_status_ctrl_locked_shift_locked : R.drawable.ic_status_ctrl_shift_locked;
            }
            if (isShiftPressed) {
                return isCtrlLocked ? R.drawable.ic_status_ctrl_locked_shift : R.drawable.ic_status_ctrl_shift;
            }
            return isCtrlLocked ? R.drawable.ic_status_ctrl_locked : R.drawable.ic_status_ctrl;
        }
        if (isAltLocked) {
            return R.drawable.ic_status_alt_locked;
        }
        if (isShiftLocked) {
            return R.drawable.ic_status_shift_locked;
        }
        if (isAltPressed) {
            return R.drawable.ic_status_alt;
        }
        if (isShiftPressed) {
            return R.drawable.ic_status_shift;
        }
        return 0;
    }

    public ModifierStatusBarUpdater(BlackBerryIME blackBerryIME) {
        this.mBlackberryIme = blackBerryIME;
    }

    /**
     * Re-asserts the icon even if unchanged, from the IME lifecycle: the system silently drops posts made
     * before privileged ops attach and clears the slot on unbind. A visible icon re-posts once per session.
     */
    public void refreshModifierStatus(int metaState) {
        updateModifierStatus(metaState, true);
    }

    /**
     * The only feed of modifier transitions. The icon follows the interpreted meta state, including the
     * span tracker's alt-lock 0x200 and caps-lock 0x100 bits (not {@link KeyEvent} values).
     */
    @Override
    public void onModifierStateChanged(ModifierState state, boolean force) {
        if (state == null) {
            updateModifierStatus(0, force);
            return;
        }
        int metaState = withCtrl(state.getInterpretedMetaState(), state.isCtrlActive(), state.isCtrlLocked());
        updateModifierStatus(holdGlyphsOfKeysStillDown(metaState, state), force);
    }

    private static final int SHIFT_GLYPH_BITS = KeyEvent.META_SHIFT_ON | MetaKeyKeyListener.META_CAP_LOCKED;
    private static final int ALT_GLYPH_BITS = KeyEvent.META_ALT_ON | MetaKeyKeyListener.META_ALT_LOCKED;

    /**
     * Keeps a modifier's glyph while its key is physically down: the state machine turns modifiers off on
     * key-down (double-tap second press, unlock press, held modifier just used) and the key-up decides.
     */
    private int holdGlyphsOfKeysStillDown(int metaState, ModifierState state) {
        if (state.isShiftKeyPhysicallyDown() && (metaState & SHIFT_GLYPH_BITS) == 0) {
            metaState |= this.mLastMetaState & SHIFT_GLYPH_BITS;
        }
        if (state.isAltKeyPhysicallyDown() && (metaState & ALT_GLYPH_BITS) == 0) {
            metaState |= this.mLastMetaState & ALT_GLYPH_BITS;
        }
        return metaState;
    }

    /** The system may clear the slot before the next show, so the next re-assert must post again. */
    @Override
    public void onInputSessionEnded() {
        this.mReassertedThisSession = false;
    }

    public void updateModifierStatus(int metaState, boolean forceUpdate) {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings != null && !settings.showPkbModifierStatusIcon) {
            if (this.mLastMetaState != 0 || forceUpdate) {
                postIcon(0, forceUpdate);
                this.mLastMetaState = 0;
            }
            return;
        }

        // Cheap test first: this runs on every modifier event; the device check reads the Configuration.
        if (!forceUpdate && this.mLastMetaState == metaState) {
            return;
        }
        // forceUpdate skips both checks so the IME can put the icon back after the system dropped it.
        if (!forceUpdate && !DeviceProfile.isPhysicalKeyboardAvailable(this.mBlackberryIme)) {
            return;
        }
        postIcon(getStatusIconDrawable(metaState), forceUpdate);
        this.mLastMetaState = metaState;
    }

    /**
     * Sends the icon (0 hidden) at the end of the current main-thread message: one key event can change
     * modifiers twice (Shift spent, then Ctrl off), and only the final icon should reach the screen.
     */
    private void postIcon(int iconResId, boolean reassert) {
        this.mPendingIconResId = iconResId;
        this.mPendingReassert |= reassert;
        if (!this.mFlushScheduled) {
            this.mFlushScheduled = true;
            this.mHandler.post(this.mFlush);
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private int mPendingIconResId;

    private boolean mPendingReassert;

    private boolean mFlushScheduled;

    private final Runnable mFlush = new Runnable() {
        @Override
        public void run() {
            mFlushScheduled = false;
            boolean reassert = mPendingReassert;
            mPendingReassert = false;
            sendIcon(mPendingIconResId, reassert);
        }
    };

    /** Hands {@code iconResId} (0 = hidden) to the system unless it is already there; see {@link #mSentIconResId}. */
    private void sendIcon(int iconResId, boolean reassert) {
        if (iconResId == this.mSentIconResId) {
            if (iconResId == 0 || !reassert || this.mReassertedThisSession) {
                return;
            }
        }
        setStatusBarIconVisibility(iconResId != 0, iconResId);
        this.mSentIconResId = iconResId;
        if (iconResId != 0 && reassert) {
            this.mReassertedThisSession = true;
        }
    }

    private void setStatusBarIconVisibility(boolean show, int iconResId) {
        if (show) {
            this.mBlackberryIme.showStatusIcon(iconResId);
        } else {
            this.mBlackberryIme.hideStatusIcon();
        }
    }
}
