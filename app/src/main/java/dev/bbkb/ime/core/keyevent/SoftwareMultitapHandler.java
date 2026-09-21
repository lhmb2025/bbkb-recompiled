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

    /** Audit DW-4: drop multitap state without committing. See MultitapEventHandler's copy. */
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

    private String getMultitapCandidate(int i, int i2) {
        String[] moreKeys = this.keyboardSwitcher.getMoreKeysForCode(i);
        if (moreKeys == null || i2 >= moreKeys.length) {
            i2 = -1;
        }
        return i2 == -1 ? Character.toString((char) i) : moreKeys[i2];
    }

    public boolean isMultitapInProgress() {
        return this.multitapInProgress;
    }

    public KeyCharacterResult.Interpretation interpretCodePoint(int i) {
        int i2;
        boolean z;
        int i3;
        boolean z2;
        if (this.multitapCodePoint == i) {
            int i4 = this.multitapIndex + 1;
            i2 = this.multitapBaseCode;
            i3 = i4;
            z = true;
            z2 = true;
        } else if (this.keyboardSwitcher.getMoreKeysForCode(i) != null) {
            i2 = i;
            z = true;
            i3 = -1;
            z2 = false;
        } else {
            i2 = i;
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
