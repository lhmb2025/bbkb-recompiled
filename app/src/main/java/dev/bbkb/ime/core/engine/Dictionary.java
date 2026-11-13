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

    /**
     * @param c0670ag the composing tracker
     * @param c0805e the suggestion strip settings
     * @param i the session id
     */
    public abstract ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker c0670ag, PrevWordsInfo prevWordsInfo, SuggestionStripSettings c0805e, int i);

    /**
     * For a snapshot taken on main (engine-thread requests never read the tracker). Named apart from
     * {@link #generateSuggestions} so a mock's {@code any()} or a null still picks one method.
     *
     * @param i the session id
     */
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestionsForSnapshot(ComposingSnapshot composing, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int i) {
        return null;
    }

    public void close() {
    }

    /**
     * Reports a recorrection to the learned model.
     *
     * @param str the text holding the recorrected word
     * @param i the recorrection cursor position in {@code str}
     * @param str2 the original word
     * @param str3 the current (replacement) word
     */
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

    /** Forgets a learned word the user rejected; ignored without a learned model. */
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

    /** Whether the language dictionaries alone know the word; {@link #isValidWord} without a learned model. */
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
