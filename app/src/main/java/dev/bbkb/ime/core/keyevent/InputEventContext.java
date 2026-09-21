package dev.bbkb.ime.core.keyevent;

import dev.bbkb.ime.core.settings.util.SettingsValues;


public class InputEventContext {

    public final SettingsValues settingsValues;

    public final InputEvent event;

    public final long timestamp;

    public final int commitType;

    public final int symbolPageOrder;

    private int uiUpdateMode = 0;

    private boolean updateSuggestionsNeeded = false;

    private boolean keyHandled = false;

    private boolean autoCorrectApplied = false;

    private boolean shiftPressed = false;

    private InputSource inputSource = InputSource.UNKNOWN;

    private boolean pendingEditorAction = false;

    public InputEventContext(SettingsValues settingsValues, InputEvent event, long timestamp, int commitType, int symbolPageOrder) {
        this.settingsValues = settingsValues;
        this.event = event;
        this.timestamp = timestamp;
        this.commitType = commitType;
        this.symbolPageOrder = symbolPageOrder;
    }

    public void setInputSource(InputSource enumC0690f) {
        this.inputSource = enumC0690f;
    }

    public void setPendingEditorAction(boolean z) {
        this.pendingEditorAction = z;
    }

    public boolean hasPendingEditorAction() {
        return this.pendingEditorAction;
    }

    public InputSource getInputSource() {
        return this.inputSource;
    }

    public void setShiftPressed(boolean z) {
        this.shiftPressed = z;
    }

    public boolean isShiftPressed() {
        return this.shiftPressed;
    }

    public void setUiUpdateMode(int i) {
        this.uiUpdateMode = Math.max(this.uiUpdateMode, i);
    }

    public int getUiUpdateMode() {
        return this.uiUpdateMode;
    }

    public void setShouldUpdateSuggestions() {
        this.updateSuggestionsNeeded = true;
    }

    public boolean shouldUpdateSuggestions() {
        return this.updateSuggestionsNeeded;
    }

    public void markKeyHandled() {
        this.keyHandled = true;
    }

    public boolean isKeyHandled() {
        return this.keyHandled;
    }

    public void setAutoCorrectApplied() {
        this.autoCorrectApplied = true;
    }

    public boolean wasAutoCorrectApplied() {
        return this.autoCorrectApplied;
    }
}
