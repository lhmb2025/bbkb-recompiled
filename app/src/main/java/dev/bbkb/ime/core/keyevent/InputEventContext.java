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

    public void setInputSource(InputSource inputSource) {
        this.inputSource = inputSource;
    }

    public void setPendingEditorAction(boolean pendingEditorAction) {
        this.pendingEditorAction = pendingEditorAction;
    }

    public boolean hasPendingEditorAction() {
        return this.pendingEditorAction;
    }

    public InputSource getInputSource() {
        return this.inputSource;
    }

    public void setShiftPressed(boolean shiftPressed) {
        this.shiftPressed = shiftPressed;
    }

    public boolean isShiftPressed() {
        return this.shiftPressed;
    }

    public void setUiUpdateMode(int uiUpdateMode) {
        this.uiUpdateMode = Math.max(this.uiUpdateMode, uiUpdateMode);
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
