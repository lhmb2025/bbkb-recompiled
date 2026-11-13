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

    /** Splits by the session's language: the engine may hold another session's language first. */
    public SentenceTextInfoParams tokenize(TextInfo textInfo) {
        Locale locale = this.mLocale != null ? this.mLocale : enginePrimaryLanguage();
        return new BlackBerryTokenizer(locale).split(textInfo);
    }

    /**
     * The engine's first language from its facts (no engine call); with no engine yet, creates one
     * on the loader thread with a bounded wait. English (the engine's own fallback) if that fails.
     */
    private static Locale enginePrimaryLanguage() {
        final com.blackberry.nuanceshim.NuanceSDK engine = NuanceSDKManager.peekSecondary();
        Locale locale = engine != null ? engine.getFacts().getPrimaryLanguage() : null;
        if (locale == null && engine == null) {
            try {
                locale = dev.bbkb.ime.core.engine.EngineOps.loadSecondary("SentenceTokenizer",
                        sdk -> sdk.getFacts().getPrimaryLanguage())
                        .get(AndroidSpellCheckerService.LOAD_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // Fall through to the default below.
            }
        }
        return locale != null ? locale : Locale.ENGLISH;
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
