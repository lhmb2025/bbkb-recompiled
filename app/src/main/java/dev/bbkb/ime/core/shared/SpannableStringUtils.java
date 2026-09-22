package dev.bbkb.ime.core.shared;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.SuggestionSpan;
import android.text.style.URLSpan;



public final class SpannableStringUtils {
    public static void copyNonParagraphSuggestionSpansTo(Spanned spanned, int i, int i2, Spannable spannable, int i3) {
        Object[] spans = spanned.getSpans(i, i2, SuggestionSpan.class);
        for (int i4 = 0; i4 < spans.length; i4++) {
            int spanFlags = spanned.getSpanFlags(spans[i4]) & (-52);
            int spanStart = spanned.getSpanStart(spans[i4]);
            int spanEnd = spanned.getSpanEnd(spans[i4]);
            if (spanStart < i) {
                spanStart = i;
            }
            if (spanEnd > i2) {
                spanEnd = i2;
            }
            spannable.setSpan(spans[i4], (spanStart - i) + i3, (spanEnd - i) + i3, spanFlags);
        }
    }

    public static CharSequence concatWithNonParagraphSuggestionSpansOnly(CharSequence... charSequenceArr) {
        if (charSequenceArr.length == 0) {
            return "";
        }
        boolean z = true;
        if (charSequenceArr.length == 1) {
            return charSequenceArr[0];
        }
        int i = 0;
        while (true) {
            if (i >= charSequenceArr.length) {
                z = false;
                break;
            }
            if (charSequenceArr[i] instanceof Spanned) {
                break;
            }
            i++;
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence charSequence : charSequenceArr) {
            sb.append(charSequence);
        }
        if (!z) {
            return sb.toString();
        }
        SpannableString spannableString = new SpannableString(sb);
        int i2 = 0;
        for (int i3 = 0; i3 < charSequenceArr.length; i3++) {
            int length = charSequenceArr[i3].length();
            if (charSequenceArr[i3] instanceof Spanned) {
                copyNonParagraphSuggestionSpansTo((Spanned) charSequenceArr[i3], 0, length, spannableString, i2);
            }
            i2 += length;
        }
        return new SpannedString(spannableString);
    }

    public static boolean hasUrlSpans(CharSequence charSequence, int i, int i2) {
        URLSpan[] uRLSpanArr;
        return (charSequence instanceof Spanned) && (uRLSpanArr = (URLSpan[]) ((Spanned) charSequence).getSpans(i - 1, i2 + 1, URLSpan.class)) != null && uRLSpanArr.length > 0;
    }
}
