package dev.bbkb.ime.core.keyevent;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.core.BlackBerryIME;



public final class SoftwareMultitapHandler extends WeakOwnerHandler<BlackBerryIME> {

    private int multitapCodePoint;

    private int multitapBaseCode;

    private int multitapIndex;

    private boolean multitapInProgress;

    private final KeyboardSwitcher keyboardSwitcher;

    public SoftwareMultitapHandler(BlackBerryIME blackBerryIME, KeyboardSwitcher keyboardSwitcher) {
        super(blackBerryIME);
        this.multitapCodePoint = 0;
        this.multitapBaseCode = 0;
        this.multitapIndex = -1;
        this.multitapInProgress = false;
        this.keyboardSwitcher = keyboardSwitcher;
    }

    public void onInputEvent(InputEvent event) {
        if (!event.isModifierKey()) {
            commitMultitapIfActive();
            return;
        }
        this.multitapInProgress = true;
        int i = event.mCodePoint;
        if (this.multitapCodePoint == i) {
            this.multitapIndex++;
            String[] moreKeys = this.keyboardSwitcher.getMoreKeysForCode(this.multitapBaseCode);
            if (moreKeys == null || this.multitapIndex >= moreKeys.length) {
                this.multitapIndex = -1;
            }
        } else {
            this.multitapIndex = -1;
            this.multitapBaseCode = event.mCodePoint;
            this.multitapCodePoint = i;
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
        this.multitapCodePoint = 0;
        this.multitapBaseCode = 0;
        this.multitapIndex = -1;
        this.multitapInProgress = false;
    }

    /** Drops multitap state without committing; see {@link MultitapEventHandler#resetMultitapState}. */
    public void resetMultitapState() {
        BlackBerryIME ime = getOwner();
        if (ime != null) {
            ime.uiUpdateHandler.removeMultitapTimeout();
        }
        this.multitapCodePoint = 0;
        this.multitapBaseCode = 0;
        this.multitapIndex = -1;
        this.multitapInProgress = false;
    }

    /**
     * @param baseCode the multitap base code point
     * @param index index into its more-keys, or -1 for the base itself
     */
    private String getMultitapCandidate(int baseCode, int index) {
        String[] moreKeys = this.keyboardSwitcher.getMoreKeysForCode(baseCode);
        if (moreKeys == null || index >= moreKeys.length) {
            index = -1;
        }
        return index == -1 ? Character.toString((char) baseCode) : moreKeys[index];
    }

    public boolean isMultitapInProgress() {
        return this.multitapInProgress;
    }

    /** @param code the code point of the tapped key */
    public KeyCharacterResult.Interpretation interpretCodePoint(int code) {
        int i2;
        boolean z;
        int i3;
        boolean z2;
        if (this.multitapCodePoint == code) {
            int i4 = this.multitapIndex + 1;
            i2 = this.multitapBaseCode;
            i3 = i4;
            z = true;
            z2 = true;
        } else if (this.keyboardSwitcher.getMoreKeysForCode(code) != null) {
            i2 = code;
            z = true;
            i3 = -1;
            z2 = false;
        } else {
            i2 = code;
            z = false;
            i3 = -1;
            z2 = false;
        }
        if (z) {
            return new KeyCharacterResult.Interpretation(getMultitapCandidate(i2, i3).codePointAt(0), z2, true);
        }
        return null;
    }
}
