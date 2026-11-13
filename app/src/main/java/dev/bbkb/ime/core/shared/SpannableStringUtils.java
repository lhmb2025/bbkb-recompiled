package dev.bbkb.ime.core.shared;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.SuggestionSpan;
import android.text.style.URLSpan;



public final class SpannableStringUtils {
    public static void copyNonParagraphSuggestionSpansTo(Spanned spanned, int start, int end, Spannable spannable, int destoff) {
        Object[] spans = spanned.getSpans(start, end, SuggestionSpan.class);
        for (int i4 = 0; i4 < spans.length; i4++) {
            int spanFlags = spanned.getSpanFlags(spans[i4]) & (-52);
            int spanStart = spanned.getSpanStart(spans[i4]);
            int spanEnd = spanned.getSpanEnd(spans[i4]);
            if (spanStart < start) {
                spanStart = start;
            }
            if (spanEnd > end) {
                spanEnd = end;
            }
            spannable.setSpan(spans[i4], (spanStart - start) + destoff, (spanEnd - start) + destoff, spanFlags);
        }
    }

    public static CharSequence concatWithNonParagraphSuggestionSpansOnly(CharSequence... charSequences) {
        if (charSequences.length == 0) {
            return "";
        }
        boolean z = true;
        if (charSequences.length == 1) {
            return charSequences[0];
        }
        int i = 0;
        while (true) {
            if (i >= charSequences.length) {
                z = false;
                break;
            }
            if (charSequences[i] instanceof Spanned) {
                break;
            }
            i++;
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence charSequence : charSequences) {
            sb.append(charSequence);
        }
        if (!z) {
            return sb.toString();
        }
        SpannableString spannableString = new SpannableString(sb);
        int i2 = 0;
        for (int i3 = 0; i3 < charSequences.length; i3++) {
            int length = charSequences[i3].length();
            if (charSequences[i3] instanceof Spanned) {
                copyNonParagraphSuggestionSpansTo((Spanned) charSequences[i3], 0, length, spannableString, i2);
            }
            i2 += length;
        }
        return new SpannedString(spannableString);
    }

    public static boolean hasUrlSpans(CharSequence charSequence, int startIndex, int endIndex) {
        URLSpan[] uRLSpanArr;
        return (charSequence instanceof Spanned) && (uRLSpanArr = (URLSpan[]) ((Spanned) charSequence).getSpans(startIndex - 1, endIndex + 1, URLSpan.class)) != null && uRLSpanArr.length > 0;
    }
}
