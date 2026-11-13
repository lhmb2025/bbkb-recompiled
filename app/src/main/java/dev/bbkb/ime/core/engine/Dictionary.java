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
     * @param composingTracker the composing tracker
     * @param stripSettings the suggestion strip settings
     * @param sessionId the session id
     */
    public abstract ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId);

    /**
     * For a snapshot taken on main (engine-thread requests never read the tracker). Named apart from
     * {@link #generateSuggestions} so a mock's {@code any()} or a null still picks one method.
     *
     * @param sessionId the session id
     */
    public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestionsForSnapshot(ComposingSnapshot composing, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
        return null;
    }

    public void close() {
    }

    /**
     * Reports a recorrection to the learned model.
     *
     * @param contextBefore the text holding the recorrected word
     * @param cursorPosition the recorrection cursor position in {@code contextBefore}
     * @param originalWord the original word
     * @param currentWord the current (replacement) word
     */
    public boolean onWordChanged(String contextBefore, int cursorPosition, String originalWord, String currentWord) {
        return false;
    }

    public boolean addLocales(Set<Locale> set) {
        return false;
    }

    public boolean isInitialized() {
        return true;
    }

    public abstract boolean isWordValid(String word);

    /** Forgets a learned word the user rejected; ignored without a learned model. */
    public boolean unlearnWord(String word) {
        return false;
    }

    public String getWordSeparator(String precedingText) {
        return "";
    }

    public Dictionary(String dictType) {
        this.dictType = dictType;
    }

    public boolean isValidWord(String word) {
        return isWordValid(word);
    }

    /** Whether the language dictionaries alone know the word; {@link #isValidWord} without a learned model. */
    public boolean isDictionaryWord(String word) {
        return isWordValid(word);
    }

    
    private static final class PhonyDictionary extends Dictionary {
        @Override // dev.bbkb.ime.core.engine.Dictionary
        public ArrayList<SuggestedWords.SuggestedWordInfo> generateSuggestions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, SuggestionStripSettings stripSettings, int sessionId) {
            return null;
        }

        @Override // dev.bbkb.ime.core.engine.Dictionary
        public boolean isWordValid(String word) {
            return false;
        }

        private PhonyDictionary(String dictType) {
            super(dictType);
        }
    }
}
