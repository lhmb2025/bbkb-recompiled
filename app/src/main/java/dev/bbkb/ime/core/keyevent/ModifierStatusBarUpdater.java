package dev.bbkb.ime.core.keyevent;

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
     * The icon last handed to the system: a drawable id, {@code 0} for hidden, or
     * {@link #ICON_UNKNOWN} before the first post. Each show or hide is a binder transaction
     * ({@code updateStatusIconAsync}), and a keyboard show or hide used to make about five of
     * them for an icon that had not moved.
     *
     * <p>The invariant: a post goes out only when the icon differs from this record, with one
     * exception — the first re-assert of a visible icon after an input session ended (see
     * {@link #mReassertedThisSession}). That exception exists because the record says what we
     * SENT, not what the slot shows: the system clears the IME slot on unbind and drops a post
     * made before the privileged operations are attached. A hidden icon needs no such
     * exception: a cleared or dropped slot is hidden too, so re-sending "hidden" changes nothing.
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

    /**
     * Audit CT-22: the four masks were bare integers, so a reader could not tell that {@code &512}
     * is Alt-lock and {@code &256} is caps-lock — and those two decide which status icon
     * the PKB user sees.
     */
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
     * Re-assert the icon for {@code metaState} whether or not it differs from the last one we
     * posted. Used from the IME lifecycle (see {@code BlackBerryIME.onWindowShown} /
     * {@code onStartInputView}): the status-bar slot belongs to the system, not to us, and the
     * system drops or clears it behind our back — {@code InputMethodService.showStatusIcon}
     * silently returns whenever {@code InputMethodPrivilegedOperations} is not attached yet, and
     * {@code InputMethodManagerService} clears the IME slot on every unbind. {@link
     * #mLastMetaState} is only a record of what we last *sent*, so after any of those the cache
     * says "already showing" for an icon that is not there.
     *
     * <p>"Whether or not" is bounded by {@link #postIcon}: a visible icon is re-posted once per
     * input session, and a hidden one is not re-posted at all (a cleared slot is hidden already).
     */
    public void refreshModifierStatus(int metaState) {
        updateModifierStatus(metaState, true);
    }

    /**
     * The single feed: {@code PhysicalKeyboardStateTracker} publishes every modifier transition
     * here and nowhere else. The icon is chosen from
     * {@link ModifierState#getInterpretedMetaState()} — the state a character key is interpreted
     * against, which is what the icon has always shown and what the Shift/Alt drawables encode
     * (including the span tracker's own alt-lock 0x200 and caps-lock 0x100 bits, which are not
     * {@link KeyEvent} values).
     */
    @Override
    public void onModifierStateChanged(ModifierState state, boolean force) {
        updateModifierStatus(state == null ? 0
                : withCtrl(state.getInterpretedMetaState(), state.isCtrlActive(), state.isCtrlLocked()), force);
    }

    /**
     * The input session ended (window hidden, input finished, editor switched, screen off): the
     * system may clear the status-bar slot before the next show, so the next re-assert of a
     * visible icon has to post it even though {@link #mSentIconResId} already holds it.
     */
    @Override
    public void onInputSessionEnded() {
        this.mReassertedThisSession = false;
    }

    public void updateModifierStatus(int metaState, boolean forceUpdate) {
        // Check if status bar icon display is enabled
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings != null && !settings.showPkbModifierStatusIcon) {
            // Setting is disabled, hide icon if it's currently showing
            if (this.mLastMetaState != 0 || forceUpdate) {
                postIcon(0, forceUpdate);
                this.mLastMetaState = 0;
            }
            return;
        }

        // The cheap test first: this runs on every physical modifier event, and the
        // device check below reaches through to Resources.getConfiguration().
        if (!forceUpdate && this.mLastMetaState == metaState) {
            return;
        }
        // forceUpdate means "post it anyway": it skips both this device check (the caller
        // already knows it is winding the state down) and the unchanged-state short circuit,
        // so the IME has a way to put the icon back after the system has dropped it.
        if (!forceUpdate && !DeviceProfile.isPhysicalKeyboardAvailable(this.mBlackberryIme)) {
            return;
        }
        postIcon(getStatusIconDrawable(metaState), forceUpdate);
        this.mLastMetaState = metaState;
    }

    /** Hands {@code iconResId} (0 = hidden) to the system unless it is already there; see {@link #mSentIconResId}. */
    private void postIcon(int iconResId, boolean reassert) {
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

    /**
     * Audit CT-13: this used {@code InputMethodManager.showStatusIcon(IBinder, String, int)} /
     * {@code hideStatusIcon(IBinder)} — the token-based variants deprecated in API 28 — behind a
     * {@code getWindow().getWindow().getAttributes().token} dance that silently no-ops (logging an
     * error) whenever the window token is unavailable. The owner is itself an
     * {@link android.inputmethodservice.InputMethodService}, which offers the token-free
     * replacements, so the whole token plumbing is gone.
     */
    private void setStatusBarIconVisibility(boolean show, int iconResId) {
        if (show) {
            this.mBlackberryIme.showStatusIcon(iconResId);
        } else {
            this.mBlackberryIme.hideStatusIcon();
        }
    }
}
