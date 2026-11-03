package dev.bbkb.ime.core.spellcheck;

import android.view.textservice.SentenceSuggestionsInfo;
import android.view.textservice.SuggestionsInfo;
import android.view.textservice.TextInfo;

import dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer;
import dev.bbkb.ime.personaldictionary.tokenizer.SentenceTextInfoParams;
import dev.bbkb.ime.personaldictionary.tokenizer.SentenceWordItem;
import dev.bbkb.ime.core.engine.NuanceSDKManager;

import java.util.Locale;

/**
 * Splits a sentence into words with the secondary engine's tokenizer, and maps per-word results
 * back onto the sentence.
 */
public class SentenceTokenizer {

    private static final SuggestionsInfo EMPTY_SUGGESTIONS_INFO = new SuggestionsInfo(0, null);

    /** The session's language: words are split by its rules. */
    private final Locale mLocale;

    private static class EmptyResultHolder {

        public static final SentenceSuggestionsInfo[] EMPTY_RESULT = new SentenceSuggestionsInfo[0];
    }

    public static SentenceSuggestionsInfo[] getEmptyResult() {
        return EmptyResultHolder.EMPTY_RESULT;
    }

    public SentenceTokenizer(Locale locale) {
        this.mLocale = locale;
    }

    /**
     * Splits by the session's language. The engine's first language used to decide, and the
     * engine can hold several sessions' languages, with another session's first.
     */
    public SentenceTextInfoParams tokenize(TextInfo textInfo) {
        Locale locale = this.mLocale != null ? this.mLocale : NuanceSDKManager.getSecondary().getPrimaryLanguage();
        return new BlackBerryTokenizer(locale).split(textInfo);
    }

    /**
     * One span per tokenized word: the first per-word result whose sequence matches the word's
     * TextInfo (restamped with the sentence's cookie/sequence), else a shared empty result.
     */
    public static SentenceSuggestionsInfo reconstructSentenceSuggestions(SentenceTextInfoParams params, SuggestionsInfo[] wordResults) {
        if (wordResults == null || wordResults.length == 0 || params == null) {
            return null;
        }
        int cookie = params.originalTextInfo.getCookie();
        int sequence = params.originalTextInfo.getSequence();
        int size = params.size;
        int[] offsets = new int[size];
        int[] lengths = new int[size];
        SuggestionsInfo[] infos = new SuggestionsInfo[size];
        for (int i = 0; i < size; i++) {
            SentenceWordItem item = params.items.get(i);
            SuggestionsInfo match = EMPTY_SUGGESTIONS_INFO;
            for (SuggestionsInfo candidate : wordResults) {
                if (candidate != null && candidate.getSequence() == item.textInfo.getSequence()) {
                    candidate.setCookieAndSequence(cookie, sequence);
                    match = candidate;
                    break;
                }
            }
            offsets[i] = item.start;
            lengths[i] = item.length;
            infos[i] = match;
        }
        return new SentenceSuggestionsInfo(infos, offsets, lengths);
    }
}
