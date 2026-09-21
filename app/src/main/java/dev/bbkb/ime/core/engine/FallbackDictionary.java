package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.util.Log;

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import dev.bbkb.ime.BuildConfig;

/**
 * Fallback dictionary implementation that provides basic word suggestions
 * when the NuanceSDK fails to initialize. This ensures the suggestion strip
 * always has functional word suggestions.
 *
 * <p>{@link DictionaryFactory} returns it for a {@code null} locale and when the engine failed to
 * load (a null engine); its match table is pinned by {@code FallbackDictionaryTest}.
 * {@link #isInitialized()} and {@link #onWordChanged} are the {@link Dictionary} defaults.
 */
public class FallbackDictionary extends Dictionary {

    private static final String TAG = "FallbackDictionary";

    private final Locale locale;

    /**
     * GD-27: {@code COMMON_WORDS} lower-cased once for this instance's locale, plus a set view of
     * it. The array is a compile-time constant and {@link #locale} is fixed per instance, so the
     * per-entry {@code toLowerCase(locale)} that used to run on every suggestion request and every
     * spell-check is hoisted here. {@code commonLower[i]} corresponds to {@code COMMON_WORDS[i]}.
     */
    private final String[] commonLower;
    private final Set<String> commonLowerSet;

    // Basic English word suggestions for common prefixes
    private static final String[] COMMON_WORDS = {
        "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was", "one", "our", "out", "day", "get", "has", "him", "his", "how", "its", "may", "new", "now", "old", "see", "two", "who", "boy", "did", "man", "men", "run", "say", "she", "too", "use",
        "about", "after", "again", "before", "being", "could", "every", "first", "found", "great", "group", "house", "just", "know", "large", "last", "left", "life", "little", "long", "made", "make", "many", "most", "move", "much", "name", "need", "never", "night", "number", "other", "over", "place", "point", "right", "same", "seem", "small", "sound", "still", "such", "take", "than", "that", "their", "there", "these", "they", "thing", "think", "this", "those", "three", "time", "under", "very", "want", "water", "way", "well", "were", "what", "where", "which", "while", "with", "work", "world", "would", "write", "year", "young",
        "because", "between", "change", "children", "different", "example", "follow", "good", "help", "important", "information", "interest", "keep", "learn", "line", "look", "might", "need", "often", "part", "people", "picture", "play", "program", "question", "school", "show", "small", "start", "state", "story", "study", "system", "through", "turn", "until", "want", "without"
    };

    public FallbackDictionary(Context context, Locale locale) {
        super("main");
        this.locale = locale != null ? locale : Locale.ENGLISH;
        this.commonLower = new String[COMMON_WORDS.length];
        for (int i = 0; i < COMMON_WORDS.length; i++) {
            this.commonLower[i] = COMMON_WORDS[i].toLowerCase(this.locale);
        }
        this.commonLowerSet = new HashSet<>(Arrays.asList(this.commonLower));
        if (BuildConfig.DEBUG) Log.i(TAG, "FallbackDictionary initialized for locale: " + this.locale);
    }

    @Override
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker c0670ag, PrevWordsInfo prevWordsInfo, SuggestionStripSettings c0805e, int i) {
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = new ArrayList<>();

        if (c0670ag == null) {
            return suggestions;
        }

        String currentWord = c0670ag.getComposingText();
        if (currentWord == null || currentWord.isEmpty()) {
            // Return some common words for empty input
            addCommonWordSuggestions(suggestions, 3);
            return suggestions;
        }

        String lowerInput = currentWord.toLowerCase(locale);
        int maxSuggestions = Math.min(i > 0 ? i : 8, 8); // Limit to reasonable number

        // GD-27: a LinkedHashSet keeps insertion order and makes a repeat match a no-op (the word
        // list has duplicates, and the second pass revisits every prefix match).
        Set<String> matchedWords = new LinkedHashSet<>();
        // Words that start with the input first; then, if there is room and the input is longer
        // than one character, words that merely contain it.
        addMatches(matchedWords, lowerInput, maxSuggestions, true);
        if (matchedWords.size() < maxSuggestions && lowerInput.length() > 1) {
            addMatches(matchedWords, lowerInput, maxSuggestions, false);
        }

        int j = 0;
        for (String word : matchedWords) {
            String wordLower = word.toLowerCase(locale);
            // Kind: 0 exact, 2 prefix, 1 contains. Score: earlier matches rank higher.
            int suggestionType = wordLower.equals(lowerInput) ? 0 : wordLower.startsWith(lowerInput) ? 2 : 1;
            int score = matchedWords.size() - j;
            j++;

            suggestions.add(new SuggestedWords.SuggestedWordInfo(
                word,
                score,
                suggestionType,
                this,
                -1,
                Integer.MAX_VALUE,
                null
            ));
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Generated " + suggestions.size() + " suggestions for input: '" + currentWord + "'");
        return suggestions;
    }

    /** Adds, in word-list order and up to {@code max} words in total, each word that starts with
     *  ({@code prefix}) or contains {@code lowerInput}. */
    private void addMatches(Set<String> matchedWords, String lowerInput, int max, boolean prefix) {
        for (int k = 0; k < COMMON_WORDS.length && matchedWords.size() < max; k++) {
            if (prefix ? commonLower[k].startsWith(lowerInput) : commonLower[k].contains(lowerInput)) {
                matchedWords.add(COMMON_WORDS[k]);
            }
        }
    }

    private void addCommonWordSuggestions(ArrayList<SuggestedWords.SuggestedWordInfo> suggestions, int count) {
        for (int i = 0; i < Math.min(count, COMMON_WORDS.length); i++) {
            suggestions.add(new SuggestedWords.SuggestedWordInfo(
                COMMON_WORDS[i],
                COMMON_WORDS.length - i,
                8, // Prediction type
                this,
                -1,
                Integer.MAX_VALUE,
                null
            ));
        }
    }

    @Override
    public boolean isWordValid(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }

        // Simple spell check - just check if word exists in our basic dictionary
        return commonLowerSet.contains(str.toLowerCase(locale));
    }

    @Override
    public String getWordSeparator(String str) {
        return " "; // Default word separator
    }
}
