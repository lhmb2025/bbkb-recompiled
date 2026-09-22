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

    public boolean onInputEvent(InputEvent event, int i) {
        if (event.isModifierKey()) {
            if (event.isShiftLocked() && !this.highlightActive) {
                return false;
            }
            this.highlightActive = true;
            this.pendingChar = new String(Character.toChars(event.mCodePoint));
            this.highlightColor = i;
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

    public CharSequence applyHighlight(String str) {
        if (!this.highlightActive) {
            return str;
        }
        if (TextUtils.isEmpty(str)) {
            str = this.pendingChar;
        }
        SpannableString spannableString = new SpannableString(str);
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
