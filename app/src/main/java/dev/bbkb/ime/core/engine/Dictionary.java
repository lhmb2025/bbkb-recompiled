package dev.bbkb.ime.core.engine;

import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;


public abstract class Dictionary {

    public static final Dictionary DICTIONARY_USER_TYPED = new PhonyDictionary("user_typed");

    public static final Dictionary DICTIONARY_APPLICATION_DEFINED = new PhonyDictionary("application_defined");

    public static final Dictionary DICTIONARY_HARDCODED = new PhonyDictionary("hardcoded");

    public static final Dictionary DICTIONARY_RESUMED = new PhonyDictionary("resumed");

    public final String dictType;

    public abstract ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker c0670ag, PrevWordsInfo prevWordsInfo, SuggestionStripSettings c0805e, int i);

    /**
     * The suggestions for a composing state taken on the main thread (Wave 3 Phase A: the IME's
     * requests run on the engine thread and never read its tracker). Named apart from
     * {@link #generateSuggestions} so a mock's {@code any()} or a {@code null} argument still
     * picks one method. Dictionaries that build no suggestions answer null.
     */
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestionsForSnapshot(ComposingSnapshot composing, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int i) {
        return null;
    }

    public void close() {
    }

    public boolean onWordChanged(String str, int i, String str2, String str3) {
        return false;
    }

    public boolean addLocales(Set<Locale> set) {
        return false;
    }

    public boolean isInitialized() {
        return true;
    }

    public abstract boolean isWordValid(String str);

    /**
     * Forget a learned word, after the user reverted an auto-correction to it. Dictionaries
     * without a learned model ignore the call. True when an entry was removed.
     */
    public boolean unlearnWord(String str) {
        return false;
    }

    public String getWordSeparator(String str) {
        return "";
    }

    public Dictionary(String str) {
        this.dictType = str;
    }

    public boolean isValidWord(String str) {
        return isWordValid(str);
    }

    /**
     * Whether the language dictionaries alone know the word, leaving out the learned model.
     * Dictionaries without a learned model answer as {@link #isValidWord}.
     */
    public boolean isDictionaryWord(String str) {
        return isWordValid(str);
    }

    
    private static final class PhonyDictionary extends Dictionary {
        @Override // dev.bbkb.ime.core.engine.Dictionary
        public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker c0670ag, PrevWordsInfo prevWordsInfo, SuggestionStripSettings c0805e, int i) {
            return null;
        }

        @Override // dev.bbkb.ime.core.engine.Dictionary
        public boolean isWordValid(String str) {
            return false;
        }

        private PhonyDictionary(String str) {
            super(str);
        }
    }
}
