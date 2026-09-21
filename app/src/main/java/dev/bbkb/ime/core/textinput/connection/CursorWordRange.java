package dev.bbkb.ime.core.textinput.connection;

import android.text.Spanned;
import android.text.style.SuggestionSpan;

import java.util.Arrays;



public final class CursorWordRange {

    public final CharSequence word;

    public final boolean hasUrlSpans;

    private final CharSequence textAtCursor;

    private final int wordAtCursorStartIndex;

    private final int wordAtCursorEndIndex;

    private final int cursorIndex;

    public int getNumberOfCharsInWordBeforeCursor() {
        return this.cursorIndex - this.wordAtCursorStartIndex;
    }

    public int getNumberOfCharsInWordAfterCursor() {
        return this.wordAtCursorEndIndex - this.cursorIndex;
    }

    public int length() {
        return this.word.length();
    }

    public SuggestionSpan[] getSuggestionSpansAtWord() {
        CharSequence charSequence = this.textAtCursor;
        if (!(charSequence instanceof Spanned) || !(this.word instanceof Spanned)) {
            return new SuggestionSpan[0];
        }
        Spanned spanned = (Spanned) charSequence;
        SuggestionSpan[] suggestionSpanArr = (SuggestionSpan[]) spanned.getSpans(this.wordAtCursorStartIndex - 1, this.wordAtCursorEndIndex + 1, SuggestionSpan.class);
        int i = 0;
        int i2 = 0;
        while (i < suggestionSpanArr.length) {
            SuggestionSpan suggestionSpan = suggestionSpanArr[i];
            if (suggestionSpan != null) {
                int spanStart = spanned.getSpanStart(suggestionSpan);
                int spanEnd = spanned.getSpanEnd(suggestionSpan);
                for (int i3 = i + 1; i3 < suggestionSpanArr.length; i3++) {
                    if (suggestionSpan.equals(suggestionSpanArr[i3])) {
                        spanStart = Math.min(spanStart, spanned.getSpanStart(suggestionSpanArr[i3]));
                        spanEnd = Math.max(spanEnd, spanned.getSpanEnd(suggestionSpanArr[i3]));
                        suggestionSpanArr[i3] = null;
                    }
                }
                if (spanStart == this.wordAtCursorStartIndex && spanEnd == this.wordAtCursorEndIndex) {
                    suggestionSpanArr[i2] = suggestionSpanArr[i];
                    i2++;
                }
            }
            i++;
        }
        return i2 == i ? suggestionSpanArr : (SuggestionSpan[]) Arrays.copyOfRange(suggestionSpanArr, 0, i2);
    }

    public CursorWordRange(CharSequence charSequence, int i, int i2, int i3, boolean z) {
        if (i < 0 || i3 < i || i3 > i2 || i2 > charSequence.length()) {
            throw new IndexOutOfBoundsException();
        }
        this.textAtCursor = charSequence;
        this.wordAtCursorStartIndex = i;
        this.wordAtCursorEndIndex = i2;
        this.cursorIndex = i3;
        this.hasUrlSpans = z;
        this.word = this.textAtCursor.subSequence(this.wordAtCursorStartIndex, this.wordAtCursorEndIndex);
    }
}
