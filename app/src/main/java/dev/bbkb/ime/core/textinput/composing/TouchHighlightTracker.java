package dev.bbkb.ime.core.textinput.composing;

import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;

import dev.bbkb.ime.core.keyevent.InputEvent;



public class TouchHighlightTracker {

    private boolean highlightActive = false;

    private String pendingChar;

    private int highlightColor;

    public boolean isHighlightActive() {
        return this.highlightActive;
    }

    public boolean onInputEvent(InputEvent event, int highlightColor) {
        if (event.isModifierKey()) {
            if (event.isShiftLocked() && !this.highlightActive) {
                return false;
            }
            this.highlightActive = true;
            this.pendingChar = new String(Character.toChars(event.mCodePoint));
            this.highlightColor = highlightColor;
        }
        return true;
    }

    public String consumePendingChar() {
        this.highlightActive = false;
        return this.pendingChar;
    }

    public void clear() {
        this.highlightActive = false;
    }

    public CharSequence applyHighlight(String composingText) {
        if (!this.highlightActive) {
            return composingText;
        }
        if (TextUtils.isEmpty(composingText)) {
            composingText = this.pendingChar;
        }
        SpannableString spannableString = new SpannableString(composingText);
        spannableString.setSpan(new BackgroundColorSpan(this.highlightColor), spannableString.length() - 1, spannableString.length(), 289);
        return spannableString;
    }

    public int getPendingCharLength() {
        String str = this.pendingChar;
        if (str == null) {
            return 0;
        }
        return str.length();
    }
}
