package dev.bbkb.ime.core.suggestion;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.Context;
import android.text.TextUtils;

import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.engine.Dictionary;
import dev.bbkb.ime.core.engine.DictionaryLoader;
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.keyboard.ProximityGrid;
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil;

import java.util.ArrayList;
import java.util.Locale;
import java.util.function.Predicate;
import dev.bbkb.ime.BuildConfig;

/**
 * Builds suggestions from the dictionary, applies case, decides auto-correction, delivers via callback.
 *
 * Word kinds: 0 TYPED_WORD, 1 COMPLETION, 2 CORRECTION, 3 SHORTCUT, 6 APP_COMPLETION,
 * 7 PREDICTION (also a substitution macro at index 1), 8 BATCH_INPUT, 11 emoji.
 */

public final class SuggestionEngine {

    public static final String TAG = "SuggestionEngine";

    private final DictionaryLoader mDictionaryLoader;

    private int mAutoCorrectThreshold;

    private int mAutoCorrectMode;

    private volatile boolean mBlockAutoCorrect = false;

    private volatile boolean mSkipNuanceCheck = false;

    private volatile boolean mSuggestLearnedWords = true;

    /** Whether a word was added on purpose (the personal dictionary); replaced in tests. */
    private Predicate<String> mIsPersonalWord = PersonalDictionaryUtil::isPersonalWord;

    
    public interface SuggestionCallback {
        void onSuggestionsReady(SuggestedWords suggestedWords);
    }

    public SuggestionEngine(DictionaryLoader dictionaryLoader) {
        this.mDictionaryLoader = dictionaryLoader;
    }

    public Locale getLocale() {
        return this.mDictionaryLoader.getLocale();
    }

    public void setAutoCorrectThreshold(int threshold) {
        this.mAutoCorrectThreshold = threshold;
    }

    public void setAutoCorrectMode(int mode) {
        this.mAutoCorrectMode = mode;
    }

    public void setBlockAutoCorrect(boolean block) {
        this.mBlockAutoCorrect = block;
    }

    public void setSkipNuanceCheck(boolean skip) {
        this.mSkipNuanceCheck = skip;
    }

    /** "Suggest learned words": off leaves the words the keyboard learned out of the strip. */
    public void setSuggestLearnedWords(boolean suggest) {
        this.mSuggestLearnedWords = suggest;
        // The bridge reads each candidate's source on every request only while this is off.
        dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge.setWordSourcesNeeded(!suggest);
    }

    void setPersonalWordLookupForTest(Predicate<String> isPersonalWord) {
        this.mIsPersonalWord = isPersonalWord;
    }

    /**
     * "Suggest learned words" off: drops {@link com.blackberry.nuanceshim.WordInfo#fromLearnedModel}
     * candidates except personal-dictionary words (also tried lower-cased). The typed word stays,
     * including its index-1 duplicate, which the auto-correct heuristic needs.
     */
    private void dropLearnedOnlyWords(ArrayList<SuggestedWords.SuggestedWordInfo> suggestions, String typedWord, Locale locale) {
        if (this.mSuggestLearnedWords || suggestions.isEmpty()) {
            return;
        }
        Locale lower = locale != null ? locale : Locale.ROOT;
        for (int i = suggestions.size() - 1; i >= 0; i--) {
            SuggestedWords.SuggestedWordInfo info = suggestions.get(i);
            String word = info.word;
            if (word == null || word.equals(typedWord) || info.nuanceWordInfo == null
                    || !info.nuanceWordInfo.fromLearnedModel) {
                continue;
            }
            if (!this.mIsPersonalWord.test(word) && !this.mIsPersonalWord.test(word.toLowerCase(lower))) {
                suggestions.remove(i);
            }
        }
    }

    /** For callers on the tracker's own thread (tests): snapshots the tracker once. */
    public void getSuggestedWords(Context context, ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, boolean autoCorrectEnabled, int suggestionType, SuggestionCallback callback) {
        getSuggestedWords(context, ComposingSnapshot.of(composingTracker), prevWordsInfo, proximityGrid, stripSettings, autoCorrectEnabled, suggestionType, callback);
    }

    /**
     * Runs on the engine thread against a snapshot taken on the main thread; never reads the
     * tracker itself.
     */
    public void getSuggestedWords(Context context, ComposingSnapshot composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, boolean autoCorrectEnabled, int suggestionType, SuggestionCallback callback) {
        if (composingTracker.isPredictionMode) {
            getPredictions(composingTracker, prevWordsInfo, proximityGrid, stripSettings, suggestionType, callback);
        } else {
            getCorrections(context, composingTracker, prevWordsInfo, proximityGrid, stripSettings, suggestionType, autoCorrectEnabled, callback);
        }
    }

    private static ArrayList<SuggestedWords.SuggestedWordInfo> applyCaseTransform(ComposingSnapshot composingTracker, SuggestionResult perfLogger, int trailingApostrophes) {
        boolean allCaps = composingTracker.isAllCaps;
        boolean firstCaps = composingTracker.shouldCapitalizeFirstLetter; // first letter caps (start of sentence)
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = perfLogger.getSuggestions();
        int size = suggestions.size();
        // Case is only raised, never lowered: a dictionary capital (BlackBerry) is kept.
        for (int i = 0; i < size; i++) {
            suggestions.set(i, transformCase(suggestions.get(i), perfLogger.locale, allCaps, firstCaps, trailingApostrophes));
        }
        return suggestions;
    }

    private void getCorrections(Context context, ComposingSnapshot composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, int suggestionType, boolean autoCorrectEnabled, SuggestionCallback callback) {
        boolean shouldAutoCorrect;
        int finalSuggestionType;
        int trailingApostrophes = countTrailingApostrophes((CharSequence) composingTracker.text);
        // Typed-word guard: ask before the list is built, so the bridge's clear-and-replay undoes
        // any engine side effect of the lookup; only when auto-correct is on and length > 1.
        final String composingText = composingTracker.text;
        final boolean typedWordIsValid = autoCorrectEnabled && composingTracker.isComposing
                && composingText != null && composingText.length() > 1
                && this.mDictionaryLoader.isWordValid(composingText, true);
        SuggestionResult perfLogger = this.mDictionaryLoader.generateSuggestions(composingTracker, prevWordsInfo, proximityGrid, stripSettings, 0);
        dropLearnedOnlyWords(perfLogger.getSuggestions(), composingText, perfLogger.locale);
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = applyCaseTransform(composingTracker, perfLogger, trailingApostrophes);
        boolean isPrediction = !composingTracker.isComposing;
        boolean hasGoodCorrection = shouldAutoCorrect(composingTracker, suggestions, typedWordIsValid, perfLogger.locale);

        boolean cond_acEnabled = autoCorrectEnabled;
        boolean cond_hasGood = hasGoodCorrection;
        boolean cond_notPred = !isPrediction;
        boolean cond_notEmpty = !perfLogger.isEmpty();
        boolean cond_noDigits = !composingTracker.hasDigits;
        boolean cond_noMultiUpper = !composingTracker.hasMultipleUpperCase;
        boolean cond_notGesture = !composingTracker.isGestureInput;
        boolean cond_dictReady = this.mDictionaryLoader.isDictionaryReady();
        boolean cond_notKind7 = cond_notEmpty && !perfLogger.getFirstSuggestion().isKind(7);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "SuggestionEngine.getCorrections: composing='" + composingTracker.text + "'"
            + " acEnabled=" + cond_acEnabled
            + " hasGood=" + cond_hasGood
            + " notPred=" + cond_notPred
            + " notEmpty=" + cond_notEmpty
            + " noDigits=" + cond_noDigits
            + " noMultiUpper=" + cond_noMultiUpper
            + " notGesture=" + cond_notGesture
            + " dictReady=" + cond_dictReady
            + " notKind7=" + cond_notKind7
            + " suggCount=" + suggestions.size()
            + " acMode=" + this.mAutoCorrectMode
            + " acThreshold=" + this.mAutoCorrectThreshold);
        }
        if (cond_acEnabled && cond_hasGood && cond_notPred && cond_notEmpty && cond_noDigits &&
            cond_noMultiUpper && cond_notGesture && cond_dictReady && cond_notKind7) {
            shouldAutoCorrect = hasGoodCorrection;
        } else {
            shouldAutoCorrect = false;
        }

        // Not gated on autoCorrectEnabled: bridge.shouldAutoCorrect is the substitution-macro
        // channel (user or assets/substitution_macros, e.g. i->I, %D), inserted at index 1 as kind
        // 7, and macros must work with auto-correct off (see the kind-7 escape in
        // CommitController.shouldCommitAutoCorrectCandidate). Order matters: hasGoodCorrection
        // uses the pre-substitution list, and cond_notKind7 disables the heuristic when
        // candidate 0 is already a substitution.
        boolean willAutoCorrect;
        if (this.mSkipNuanceCheck || !this.mDictionaryLoader.isDictionaryReady()) {
            willAutoCorrect = shouldAutoCorrect;
        } else {
            Dictionary mainDict = this.mDictionaryLoader.getMainDictionary();
            boolean isNuanceDict = mainDict instanceof NuanceSDKDictionaryBridge;
            if (isNuanceDict) {
                boolean nuanceAC = ((NuanceSDKDictionaryBridge) mainDict).shouldAutoCorrect(composingTracker, suggestions, perfLogger);
                willAutoCorrect = nuanceAC | shouldAutoCorrect;
            } else {
                willAutoCorrect = shouldAutoCorrect;
            }
        }

        // Strip emoji come from emojibase: engine emoji dropped, top emojibase hit appended.
        EmojibaseStripSuggestion.apply(context, suggestions, composingText, perfLogger.locale);

        // Emoji are suggestions, never auto-correction targets.
        if (willAutoCorrect) {
            int acIdx = SuggestedWords.getMinSuggestionsIndex();
            if (acIdx < suggestions.size() && suggestions.get(acIdx).isKind(11)) {
                willAutoCorrect = false;
            }
        }
        
        if (isPrediction) {
            finalSuggestionType = perfLogger.hasNextWordSuggestions ? 7 : 6;
        } else {
            finalSuggestionType = suggestionType;
        }
        callback.onSuggestionsReady(new SuggestedWords(suggestions, !isPrediction && !hasGoodCorrection, willAutoCorrect, finalSuggestionType));
    }

    private void getPredictions(ComposingSnapshot composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, int suggestionType, SuggestionCallback callback) {
        SuggestionResult perfLogger = this.mDictionaryLoader.generateSuggestions(composingTracker, prevWordsInfo, proximityGrid, stripSettings, 1);
        dropLearnedOnlyWords(perfLogger.getSuggestions(), null, perfLogger.locale);
        ArrayList<SuggestedWords.SuggestedWordInfo> predictions = perfLogger.getSuggestions();
        // Drop any engine emoji that slips through; strip emoji come from emojibase.
        EmojibaseStripSuggestion.dropEngineEmoji(predictions);
        int size = predictions.size();
        boolean isStartOfSentence = composingTracker.isStartOfSentence;
        String composingText = composingTracker.text;
        boolean userTypedUppercase = composingText.length() > 0 && Character.isUpperCase(composingText.codePointAt(0));
        boolean shouldCapitalize = isStartOfSentence || userTypedUppercase;
        boolean allCaps = composingTracker.isAllCaps;
        for (int i = 0; i < size; i++) {
            predictions.set(i, transformCase(predictions.get(i), perfLogger.locale, allCaps, shouldCapitalize, 0));
        }
        if (predictions.size() > 1 && TextUtils.equals(predictions.get(0).word, composingTracker.lastCommittedWord)) {
            predictions.add(1, predictions.remove(0));
        }
        SuggestedWords.SuggestedWordInfo.dedupeSuggestions(null, predictions);
        for (int j = predictions.size() - 1; j >= 0; j--) {
            if (predictions.get(j).score < -2000000000) {
                predictions.remove(j);
            }
        }
        callback.onSuggestionsReady(new SuggestedWords(predictions, true, false, suggestionType));
    }

    static SuggestedWords.SuggestedWordInfo transformCase(SuggestedWords.SuggestedWordInfo suggestedWordInfo, Locale locale, boolean allCaps, boolean firstCaps, int trailingApostrophes) {
        // Hot path: return the input when nothing changes. `completionInfo` is in the guard
        // because the copying constructor drops it.
        if (!allCaps && !firstCaps && trailingApostrophes == 0
                && suggestedWordInfo.completionInfo == null) {
            return suggestedWordInfo;
        }
        StringBuilder sb = new StringBuilder(suggestedWordInfo.word.length());
        if (allCaps) {
            sb.append(suggestedWordInfo.word.toUpperCase(locale));
        } else if (firstCaps) {
            sb.append(capitalizeFirstCodePoint(suggestedWordInfo.word, locale));
        } else {
            sb.append(suggestedWordInfo.word);
        }
        for (int i2 = (trailingApostrophes - (-1 == suggestedWordInfo.word.indexOf(39) ? 0 : 1)) - 1; i2 >= 0; i2--) {
            sb.appendCodePoint(39);
        }
        return new SuggestedWords.SuggestedWordInfo(sb.toString(), suggestedWordInfo.score, suggestedWordInfo.kindAndFlags, suggestedWordInfo.sourceDictionary, suggestedWordInfo.indexInDictionary, suggestedWordInfo.indexInSuggestions, suggestedWordInfo.nuanceWordInfo);
    }

    /**
     * The spelling heuristic: does {@code suggestions[1]} replace the typed word on space?
     *
     * @param typedWordIsValid the dictionary knows the typed word; then only a same-word
     *                         correction ({@link TypedWordGuard}) is allowed
     */
    private boolean shouldAutoCorrect(ComposingSnapshot composingTracker, ArrayList<SuggestedWords.SuggestedWordInfo> suggestions,
            boolean typedWordIsValid, Locale locale) {
        boolean meetsThreshold;
        if (suggestions.size() <= 1 || this.mBlockAutoCorrect) {
            if (BuildConfig.DEBUG) android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "shouldAutoCorrect: EARLY_RETURN size=" + suggestions.size() + " blocked=" + this.mBlockAutoCorrect);
            return false;
        }
        String typedWord = composingTracker.text;
        int trailingApostrophes = countTrailingApostrophes((CharSequence) typedWord);
        int length = (trailingApostrophes > 0 ? typedWord.substring(0, typedWord.length() - trailingApostrophes) : typedWord).length();
        if (length <= 1) {
            if (length == 1) {
                SuggestedWords.SuggestedWordInfo suggestion = suggestions.get(1);
                // Single-char corrections are kind 2 (CORRECTION).
                boolean result = suggestion.isKind(2) && suggestion.word.length() == 1 && typedWord.length() == 1 &&
                       !suggestion.word.toLowerCase(java.util.Locale.getDefault()).equals(typedWord.toLowerCase(java.util.Locale.getDefault()));
                return result;
            }
            return false;
        }
        SuggestedWords.SuggestedWordInfo suggestedWordInfoVar = suggestions.get(1);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "shouldAutoCorrect: typed='" + typedWord + "' len=" + length
            + " sugg[1]='" + suggestedWordInfoVar.word + "' kind=" + suggestedWordInfoVar.kindAndFlags
            + " isKind2=" + suggestedWordInfoVar.isKind(2)
            + " acMode=" + this.mAutoCorrectMode + " acThresh=" + this.mAutoCorrectThreshold);
        }
        // AC = (completion ? lengthGate : false) || correction || shortcut, then !url && !email.
        // Corrections apply at any length; the gate is for completions only. A valid typed word
        // is guarded twice: the bridge duplicates it into sugg[1] when the engine's default index
        // is 0 (typedEqualsCorrection), and TypedWordGuard refuses letter-changing corrections,
        // since a learned-model boost can move the default off a valid word. Space corrects
        // misspellings, never a dictionary word.
        if (!suggestedWordInfoVar.isKind(1)) {
            meetsThreshold = false;
        } else if (this.mAutoCorrectMode == 0) {
            meetsThreshold = length > 3 && this.mAutoCorrectThreshold == 3;
        } else {
            meetsThreshold = length > 3 || this.mAutoCorrectThreshold == 3;
        }
        boolean isUrl = android.util.Patterns.WEB_URL.matcher(suggestedWordInfoVar.word).matches();
        boolean isEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(suggestedWordInfoVar.word).matches();
        boolean canAutoCorrect = (meetsThreshold || suggestedWordInfoVar.isKind(2) || suggestedWordInfoVar.isKind(3))
                && !isUrl && !isEmail;
        if (BuildConfig.DEBUG) {
            android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "shouldAutoCorrect: completionGate="
                + meetsThreshold + " canAC=" + canAutoCorrect);
        }

        boolean isExactMatch = composingTracker.looksLikeURL();
        boolean isUserOverride = composingTracker.looksLikeEmail();
        boolean typedEqualsCorrection = typedWord.equals(suggestedWordInfoVar.word);
        if (isExactMatch || isUserOverride || typedEqualsCorrection) {
            return false;
        }
        if (canAutoCorrect && typedWordIsValid
                && !TypedWordGuard.allowsCorrection(typedWord, suggestedWordInfoVar.word, locale)) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "shouldAutoCorrect: typed='" + typedWord
                    + "' is a dictionary word; '" + suggestedWordInfoVar.word + "' would change it -> keep typed");
            }
            return false;
        }
        return canAutoCorrect;
    }
}
