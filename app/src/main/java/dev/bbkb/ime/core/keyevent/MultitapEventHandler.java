package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.KeyboardLayoutCallback;

public final class MultitapEventHandler extends WeakOwnerHandler<BlackBerryIME> implements KeyCharacterInterpreter {

    private int multitapKeyCode;

    private String multitapBaseChar;

    private int multitapIndex;

    private boolean multitapInProgress;

    private boolean altMultitapSupported;

    private final KeyboardLayoutCallback keyboardLayoutCallback;

    public KeyCharacterInterpreter asKeyCharacterInterpreter() {
        return this;
    }

    public MultitapEventHandler(BlackBerryIME blackBerryIME, KeyboardLayoutCallback keyboardLayoutCallback) {
        super(blackBerryIME);
        this.multitapKeyCode = 0;
        this.multitapBaseChar = null;
        this.multitapIndex = -1;
        this.multitapInProgress = false;
        this.keyboardLayoutCallback = keyboardLayoutCallback;
    }

    public void onInputEvent(InputEvent event) {
        if (!event.isModifierKey()) {
            commitMultitapIfActive();
            return;
        }
        this.multitapInProgress = true;
        int i = event.mKeyCode;
        if (this.multitapKeyCode == i) {
            this.multitapIndex++;
            // Audit CT-19: getMoreKeysForKeyByStyle returns null when the keyboard is gone
            // (KeyboardSwitcher.getCurrentKeyboard() == null) or the base character is unmapped.
            // Dereferencing it unguarded threw out of onKeyDown — i.e. crashed the IME — when a
            // PKB Alt-multitap key was held across a keyboard teardown. The sibling
            // SoftwareMultitapHandler already guards; this copy did not.
            String[] moreKeys = this.keyboardLayoutCallback.getMoreKeysForKeyByStyle(this.multitapBaseChar);
            if (moreKeys == null || this.multitapIndex >= moreKeys.length) {
                this.multitapIndex = -1;
            }
        } else {
            this.multitapIndex = -1;
            this.multitapBaseChar = String.valueOf(Character.toChars(event.mCodePoint));
            this.multitapKeyCode = i;
        }
        getOwner().uiUpdateHandler.postMultitapTimeout();
    }

    public void commitMultitapIfActive() {
        if (isMultitapInProgress()) {
            commitMultitap();
        }
    }

    public void commitMultitap() {
        BlackBerryIME ime = getOwner();
        ime.uiUpdateHandler.removeMultitapTimeout();
        ime.commitTouchEventText();
        this.multitapKeyCode = 0;
        this.multitapBaseChar = null;
        this.multitapIndex = -1;
        this.multitapInProgress = false;
    }

    /**
     * Audit DW-4: drop multitap state without committing anything.
     *
     * {@link #commitMultitap()} cannot be used at session teardown because it calls
     * {@code commitTouchEventText()}, which would flush the in-progress character into whatever
     * editor is current by then — the very shape of bug this reset exists to prevent. Without
     * some reset, the state and the queued MSG_MULTITAP_TIMEOUT both outlived the session, and
     * pressing the same key in the next field within the leftover 750 ms window hit the stale
     * continuation branch at {@link #onInputEvent} — where the resulting shift-locked event is
     * then dropped by TouchHighlightTracker, so the keystroke vanishes entirely. Each such press
     * re-armed the timer, so the loss repeated until the user paused or changed keys.
     */
    public void resetMultitapState() {
        BlackBerryIME ime = getOwner();
        if (ime != null) {
            ime.uiUpdateHandler.removeMultitapTimeout();
        }
        this.multitapKeyCode = 0;
        this.multitapBaseChar = null;
        this.multitapIndex = -1;
        this.multitapInProgress = false;
    }

    private String getMultitapCandidate(String str, int i) {
        // Audit CT-19: same null path as onInputEvent. interpretKeyCharacter's repeat branch
        // reaches here without having null-checked, so this is the NPE site.
        String[] moreKeys = this.keyboardLayoutCallback.getMoreKeysForKeyByStyle(str);
        if (moreKeys == null || i >= moreKeys.length) {
            i = -1;
        }
        return i == -1 ? str : moreKeys[i];
    }

    public void refreshAltMultitapSupport() {
        this.altMultitapSupported = DeviceProfile.current().hasAltSymKey();
    }

    public boolean isMultitapInProgress() {
        return this.multitapInProgress;
    }

    public boolean isAltMultitapSupported() {
        return this.altMultitapSupported;
    }

    @Override
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int i) {
        int i2;
        String strValueOf;
        boolean z;
        boolean z2;
        if (!isAltMultitapSupported() || !dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent)) {
            return null;
        }
        int keyCode = keyEvent.getKeyCode();
        if (keyCode != 0 && this.multitapKeyCode == keyCode) {
            int i3 = this.multitapIndex + 1;
            strValueOf = this.multitapBaseChar;
            i2 = i3;
            z = true;
            z2 = true;
        } else {
            i2 = -1;
            // ALT_DOT FIX: getUnicodeChar() can return COMBINING_ACCENT (0x80000000) ORed
            // with an accent code point (e.g. 0x800000B4 = dead acute accent). Strip the
            // flag to get the real character; return null if nothing usable remains.
            int rawUnicode = keyEvent.getUnicodeChar(i);
            if ((rawUnicode & android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
                rawUnicode = rawUnicode & android.view.KeyCharacterMap.COMBINING_ACCENT_MASK;
            }
            if (rawUnicode <= 0 || !Character.isValidCodePoint(rawUnicode)) {
                return null;
            }
            strValueOf = String.valueOf(Character.toChars(rawUnicode));
            if (this.keyboardLayoutCallback.getMoreKeysForKeyByStyle(strValueOf) != null) {
                z = true;
                z2 = false;
            } else {
                z = false;
                z2 = false;
            }
        }
        if (z) {
            return new KeyCharacterResult.Interpretation(getMultitapCandidate(strValueOf, i2).codePointAt(0), z2, true);
        }
        return null;
    }
}
