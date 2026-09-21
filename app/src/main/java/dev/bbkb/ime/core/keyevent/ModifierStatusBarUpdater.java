package dev.bbkb.ime.core.keyevent;

import android.text.method.MetaKeyKeyListener;
import android.view.KeyEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.BlackBerryIME;


public class ModifierStatusBarUpdater {

    private static final String TAG = "ModifierStatusBarUpdater";

    private final BlackBerryIME mBlackberryIme;

    private int mLastMetaState = 0;

    /**
     * Audit CT-22: the four masks were bare integers, so a reader could not tell that {@code &512}
     * is Alt-lock and {@code &256} is caps-lock — and those two decide which of eight status icons
     * the PKB user sees.
     */
    private int getStatusIconDrawable(int metaState) {
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

    public void updateModifierStatus(int metaState, boolean forceUpdate) {
        // Check if status bar icon display is enabled
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings != null && !settings.showPkbModifierStatusIcon) {
            // Setting is disabled, hide icon if it's currently showing
            if (this.mLastMetaState != 0) {
                setStatusBarIconVisibility(false, 0);
                this.mLastMetaState = 0;
            }
            return;
        }
        
        if ((forceUpdate || DeviceProfile.isPhysicalKeyboardAvailable(this.mBlackberryIme)) && this.mLastMetaState != metaState) {
            int iconResId = getStatusIconDrawable(metaState);
            if (iconResId != 0) {
                setStatusBarIconVisibility(true, iconResId);
            } else {
                setStatusBarIconVisibility(false, 0);
            }
            this.mLastMetaState = metaState;
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
