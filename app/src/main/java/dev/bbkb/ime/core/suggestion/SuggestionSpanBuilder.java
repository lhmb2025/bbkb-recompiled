package dev.bbkb.ime.core.suggestion;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.Log;

import dev.bbkb.ime.core.contacts.ContactsQueryUtils;

import java.util.ArrayList;

import dev.bbkb.ime.BuildConfig;



public final class SuggestionSpanBuilder {

    public static CharSequence getTextWithAutoCorrectionIndicator(Context context, String word) {
        if (TextUtils.isEmpty(word)) {
            return word;
        }
        if (BuildConfig.DEBUG) Log.d("SuggestionSpanBuilder", "spanAttach:autoCorrection word=\"" + word + "\" flag=" + SuggestionSpan.FLAG_AUTO_CORRECTION);
        SpannableString spannableString = new SpannableString(word);
        spannableString.setSpan(new SuggestionSpan(context, null, new String[0], SuggestionSpan.FLAG_AUTO_CORRECTION, SuggestionSpanReceiver.class), 0, word.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
        return spannableString;
    }

    public static CharSequence getTextWithSuggestionSpan(Context context, String committedWord, SuggestedWords suggestedWords, boolean isBatchInput) {
        if (TextUtils.isEmpty(committedWord) || suggestedWords.isEmpty() || suggestedWords.isValidInputStyle() || suggestedWords.isAutoCorrection()) {
            if (BuildConfig.DEBUG) Log.d("SuggestionSpanBuilder", "spanAttach:SKIPPED word=\"" + committedWord + "\" empty=" + suggestedWords.isEmpty() + " isPrediction=" + suggestedWords.isValidInputStyle());
            return committedWord;
        }
        ArrayList<String> alternatives = new ArrayList<>();
        for (int i = 0; i < suggestedWords.size() && alternatives.size() < 5; i++) {
            SuggestedWords.SuggestedWordInfo wordInfo = suggestedWords.getWordInfo(i);
            if ((!wordInfo.isKind(8) || isBatchInput) && !wordInfo.isObsolete() && (!wordInfo.isFromContacts() || ContactsQueryUtils.isContactsPermissionGranted(context))) {
                String suggestion = suggestedWords.getWord(i);
                if (!TextUtils.equals(committedWord, suggestion)) {
                    alternatives.add(suggestion.toString());
                }
            }
        }
        if (BuildConfig.DEBUG) Log.d("SuggestionSpanBuilder", "spanAttach:" + (alternatives.isEmpty() ? "NO_ALTERNATIVES" : "attached") + " committedWord=\"" + committedWord + "\" alternatives=" + alternatives);
        SuggestionPickLearner.remember(committedWord, alternatives);
        SuggestionSpan suggestionSpan = new SuggestionSpan(context, null, (String[]) alternatives.toArray(new String[alternatives.size()]), 0, SuggestionSpanReceiver.class);
        SpannableString spannableString = new SpannableString(committedWord);
        spannableString.setSpan(suggestionSpan, 0, committedWord.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannableString;
    }
}
