package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.KeyboardLayoutCallback;
import dev.bbkb.ime.core.layout.PhysicalKeyResolver;

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
            // Null when the keyboard is gone or the base character is unmapped.
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
     * Drops multitap state and the pending timeout without committing: at session teardown a commit would
     * flush the character into the next editor, and leftover state would swallow the next field's keystroke.
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

    /**
     * @param baseChar the multitap base character
     * @param index index into its more-keys, or -1 for the base itself
     */
    private String getMultitapCandidate(String baseChar, int index) {
        String[] moreKeys = this.keyboardLayoutCallback.getMoreKeysForKeyByStyle(baseChar);
        if (moreKeys == null || index >= moreKeys.length) {
            index = -1;
        }
        return index == -1 ? baseChar : moreKeys[index];
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

    /** @param metaState the meta state to interpret the key under */
    @Override
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int metaState) {
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
            // Letter layouts first (the multitap base is the script letter), then their Alt character,
            // then the KCM. Strip COMBINING_ACCENT from dead keys to get the real character.
            int rawUnicode = PhysicalKeyResolver.letter(keyEvent, metaState);
            if (rawUnicode == 0) {
                rawUnicode = PhysicalKeyResolver.altFor(keyEvent, metaState);
            }
            if (rawUnicode == 0) {
                rawUnicode = keyEvent.getUnicodeChar(metaState);
            }
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
