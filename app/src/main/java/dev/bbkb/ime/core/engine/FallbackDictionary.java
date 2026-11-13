package dev.bbkb.ime.core.engine;

import android.content.Context;
import android.util.Log;

import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot;
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

/** A small English word list used for a null locale or when the engine failed to load. */
public class FallbackDictionary extends Dictionary {

    private static final String TAG = "FallbackDictionary";

    private final Locale locale;

    /** {@code COMMON_WORDS} lower-cased once for the locale; {@code commonLower[i]} matches {@code COMMON_WORDS[i]}. */
    private final String[] commonLower;
    private final Set<String> commonLowerSet;

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
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
        if (composingTracker == null) {
            return new ArrayList<>();
        }
        return suggestionsFor(composingTracker.getComposingText());
    }

    @Override
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestionsForSnapshot(ComposingSnapshot composing, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
        if (composing == null) {
            return new ArrayList<>();
        }
        return suggestionsFor(composing.text);
    }

    private static final int MAX_SUGGESTIONS = 8;

    private ArrayList<SuggestedWords.SuggestedWordInfo> suggestionsFor(String currentWord) {
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = new ArrayList<>();

        if (currentWord == null || currentWord.isEmpty()) {
            addCommonWordSuggestions(suggestions, 3);
            return suggestions;
        }

        String lowerInput = currentWord.toLowerCase(locale);
        int maxSuggestions = MAX_SUGGESTIONS;

        // Ordered and deduplicated: the list has duplicates and the second pass revisits prefix matches.
        Set<String> matchedWords = new LinkedHashSet<>();
        // Prefix matches first, then (input longer than one char) words that contain it.
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

    /** Adds words that start with ({@code prefix}) or contain {@code lowerInput}, up to {@code max} in total. */
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
    public boolean isWordValid(String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }

        return commonLowerSet.contains(word.toLowerCase(locale));
    }

    @Override
    public String getWordSeparator(String precedingText) {
        return " ";
    }
}
