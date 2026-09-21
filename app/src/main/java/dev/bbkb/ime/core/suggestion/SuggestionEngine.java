package dev.bbkb.ime.core.suggestion;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.Context;
import android.text.TextUtils;

import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.engine.Dictionary;
import dev.bbkb.ime.core.engine.DictionaryLoader;
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.keyboard.ProximityGrid;

import java.util.ArrayList;
import java.util.Locale;
import dev.bbkb.ime.BuildConfig;

/**
 * Orchestrates suggestion generation — retrieves raw suggestions from the
 * dictionary, applies case transforms, determines auto-correction eligibility,
 * and delivers results via callback.
 * ----------
 * Word Kind Constants
 * 0:   TYPED_WORD — the exact word the user typed
 * 1:   COMPLETION — SuggestedWordInfo completion of the typed prefix
 * 2:   CORRECTION — SuggestedWordInfo spelling correction
 * 3:   SHORTCUT — SuggestedWordInfo shortcut expansion
 * 6:   APP_COMPLETION — application-provided completion
 * 7:   PREDICTION — SuggestedWordInfo next-word prediction
 * 8:   BATCH_INPUT — from gesture/batch input
 */

public final class SuggestionEngine {

    public static final String TAG = "SuggestionEngine";

    private final DictionaryLoader mDictionaryLoader;

    private int mAutoCorrectThreshold;

    private int mAutoCorrectMode;

    private volatile boolean mBlockAutoCorrect = false;

    private volatile boolean mSkipNuanceCheck = false;

    
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

    public void getSuggestedWords(Context context, ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, boolean autoCorrectEnabled, int suggestionType, SuggestionCallback callback) {
        if (composingTracker.isPredictionMode()) {
            getPredictions(composingTracker, prevWordsInfo, proximityGrid, stripSettings, suggestionType, callback);
        } else {
            getCorrections(context, composingTracker, prevWordsInfo, proximityGrid, stripSettings, suggestionType, autoCorrectEnabled, callback);
        }
    }

    private static ArrayList<SuggestedWords.SuggestedWordInfo> applyCaseTransform(ComposingTextTracker composingTracker, SuggestionResult perfLogger, int trailingApostrophes) {
        boolean allCaps = composingTracker.isAllCaps();
        boolean firstCaps = composingTracker.shouldCapitalizeFirstLetter(); // first letter caps (start of sentence)
        // FIX: Add forceLowercase for predictions too
        boolean forceLowercase = !firstCaps && !allCaps;
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = perfLogger.getSuggestions();
        int size = suggestions.size();
        // UT-2: the old guard here was `firstCaps || allCaps || forceLowercase || ...`, and
        // forceLowercase is by construction `!firstCaps && !allCaps` — i.e. always true. The loop
        // therefore always ran; transformCase now returns its argument unchanged when there is
        // nothing to transform, which is where the per-keystroke allocations actually went.
        for (int i = 0; i < size; i++) {
            suggestions.set(i, transformCase(suggestions.get(i), perfLogger.locale, allCaps, firstCaps, forceLowercase, trailingApostrophes));
        }
        return suggestions;
    }

    private void getCorrections(Context context, ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, int suggestionType, boolean autoCorrectEnabled, SuggestionCallback callback) {
        boolean shouldAutoCorrect;
        int finalSuggestionType;
        int trailingApostrophes = countTrailingApostrophes((CharSequence) composingTracker.getComposingText());
        SuggestionResult perfLogger = this.mDictionaryLoader.generateSuggestions(composingTracker, prevWordsInfo, proximityGrid, stripSettings, 0);
        ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = applyCaseTransform(composingTracker, perfLogger, trailingApostrophes);
        boolean isPrediction = !composingTracker.isComposing();
        boolean hasGoodCorrection = shouldAutoCorrect(composingTracker, suggestions);
        
        // Corrected control flow logic to match Smali bytecode
        boolean cond_acEnabled = autoCorrectEnabled;
        boolean cond_hasGood = hasGoodCorrection;
        boolean cond_notPred = !isPrediction;
        boolean cond_notEmpty = !perfLogger.isEmpty();
        boolean cond_noDigits = !composingTracker.hasDigits();
        boolean cond_noMultiUpper = !composingTracker.hasMultipleUpperCase();
        boolean cond_notGesture = !composingTracker.isGestureInput();
        boolean cond_dictReady = this.mDictionaryLoader.isDictionaryReady();
        boolean cond_notKind7 = cond_notEmpty && !perfLogger.getFirstSuggestion().isKind(7);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "SuggestionEngine.getCorrections: composing='" + composingTracker.getComposingText() + "'"
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
        
        // FIX-MACRO / D-4: the substitution LOOKUP is deliberately NOT gated on
        // autoCorrectEnabled. NuanceSDKDictionaryBridge.shouldAutoCorrect is the macro channel,
        // not the spelling heuristic: it asks the personal dictionary for a substitution of the
        // typed word (a user macro, or a shipped one from assets/substitution_macros — i→I,
        // bb→BlackBerry, alot→a lot, the %D/%T date macros) and inserts it at index 1 with
        // kind 7. The original ORs that answer into willAutoCorrect with no setting term:
        // sources/dev/bbkb/ime/core/ab.java:108-119 — `r24` (autoCorrectEnabled)
        // appears only at ab.java:84, guarding the heuristic above. A previous "Issue 1 fix"
        // added `autoCorrectEnabled &&` here, which silently disabled every macro whenever the
        // user switched auto-correct off. The kind-7 escape at the commit sites
        // (CommitController.shouldCommitAutoCorrectCandidate) is the other half of this.
        //
        // Ordering is load-bearing and matches the original: hasGoodCorrection above is computed
        // from the pre-substitution list (ab.java:81 before ab.java:118), so a kind-7 entry never
        // feeds the sugg[1] heuristic; and cond_notKind7 above disables the heuristic outright
        // when candidate 0 is already a substitution (ab.java:98-101).
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
        
        // Emojis should appear as suggestions but never as autocorrection targets
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

    private void getPredictions(ComposingTextTracker composingTracker, PrevWordsInfo prevWordsInfo, ProximityGrid proximityGrid, SuggestionStripSettings stripSettings, int suggestionType, SuggestionCallback callback) {
        SuggestionResult perfLogger = this.mDictionaryLoader.generateSuggestions(composingTracker, prevWordsInfo, proximityGrid, stripSettings, 1);
        ArrayList<SuggestedWords.SuggestedWordInfo> predictions = perfLogger.getSuggestions();
        int size = predictions.size();
        boolean isStartOfSentence = composingTracker.isStartOfSentence();
        String composingText = composingTracker.getComposingText();
        boolean userTypedUppercase = composingText.length() > 0 && Character.isUpperCase(composingText.codePointAt(0));
        boolean shouldCapitalize = isStartOfSentence || userTypedUppercase;
        boolean allCaps = composingTracker.isAllCaps();
        boolean forceLowercase = !isStartOfSentence && !userTypedUppercase && !allCaps;
        // UT-2: same always-true guard as applyCaseTransform — dropped; transformCase self-shorts.
        for (int i = 0; i < size; i++) {
            predictions.set(i, transformCase(predictions.get(i), perfLogger.locale, allCaps, shouldCapitalize, forceLowercase, 0));
        }
        if (predictions.size() > 1 && TextUtils.equals(predictions.get(0).word, composingTracker.getLastCommittedWord())) {
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

    static SuggestedWords.SuggestedWordInfo transformCase(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, Locale locale, boolean allCaps, boolean firstCaps, boolean forceLowercase, int trailingApostrophes) {
        // UT-2 (hot path): this runs for every entry of every suggestion request. When no case
        // change and no apostrophe fix-up applies, the body below rebuilds a byte-identical
        // SuggestedWordInfo; return the input instead. `completionInfo` is part of the guard
        // because the copying constructor drops it, so the copy is only identical when it is null.
        String word = suggestedWordInfoVar.word;
        if (!allCaps && !firstCaps && trailingApostrophes == 0
                && suggestedWordInfoVar.completionInfo == null
                && !(forceLowercase && word.length() > 0 && Character.isUpperCase(word.codePointAt(0)))) {
            return suggestedWordInfoVar;
        }
        StringBuilder sb = new StringBuilder(suggestedWordInfoVar.word.length());
        if (allCaps) {
            sb.append(suggestedWordInfoVar.word.toUpperCase(locale));
        } else if (firstCaps) {
            sb.append(capitalizeFirstCodePoint(suggestedWordInfoVar.word, locale));
        } else if (forceLowercase && suggestedWordInfoVar.word.length() > 0 && Character.isUpperCase(suggestedWordInfoVar.word.codePointAt(0))) {
            sb.append(lowercaseFirstCodePoint(suggestedWordInfoVar.word, locale));
        } else {
            sb.append(suggestedWordInfoVar.word);
        }
        for (int i2 = (trailingApostrophes - (-1 == suggestedWordInfoVar.word.indexOf(39) ? 0 : 1)) - 1; i2 >= 0; i2--) {
            sb.appendCodePoint(39);
        }
        return new SuggestedWords.SuggestedWordInfo(sb.toString(), suggestedWordInfoVar.score, suggestedWordInfoVar.kindAndFlags, suggestedWordInfoVar.sourceDictionary, suggestedWordInfoVar.indexInDictionary, suggestedWordInfoVar.indexInSuggestions, suggestedWordInfoVar.nuanceWordInfo);
    }

    private boolean shouldAutoCorrect(ComposingTextTracker composingTracker, ArrayList<SuggestedWords.SuggestedWordInfo> suggestions) {
        boolean meetsThreshold;
        if (suggestions.size() <= 1 || this.mBlockAutoCorrect) {
            if (BuildConfig.DEBUG) android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "shouldAutoCorrect: EARLY_RETURN size=" + suggestions.size() + " blocked=" + this.mBlockAutoCorrect);
            return false;
        }
        String typedWord = composingTracker.getComposingText();
        int trailingApostrophes = countTrailingApostrophes((CharSequence) typedWord);
        int length = (trailingApostrophes > 0 ? typedWord.substring(0, typedWord.length() - trailingApostrophes) : typedWord).length();
        if (length <= 1) {
            if (length == 1) {
                SuggestedWords.SuggestedWordInfo suggestion = suggestions.get(1);
                // FIX 2025-03-29: Changed isKind(1) to isKind(2) to match kind-swap in NuanceDictionary.
                // Genuine single-char corrections now use kind 2 (CORRECTION) not kind 1 (COMPLETION).
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
        // Parity restoration 2026-08-13 (Phase A): decision shape verified against the original
        // APK's bytecode (ab.smali, a(Lag;Ljava/util/ArrayList;)Z — remediation log §7).
        // Original: AC = (completion ? lengthGate : false) || correction || whitelist,
        // then && !url && !email. Our kind numbering is SWAPPED vs the original APK
        // (ours: 1=COMPLETION, 2=CORRECTION; original, AOSP-style: 1=CORRECTION, 2=COMPLETION —
        // see the bridge's kind assignment), so isKind(1)/isKind(2) here are intentionally
        // transposed relative to the original source. Corrections auto-correct at ANY length;
        // the length/threshold gate is word-completion-point logic and applies only to
        // completions. Valid typed words are protected upstream by the engine's default-word
        // index: the bridge duplicates the typed word into sugg[1] when the engine's default is
        // index 0 (NuanceSDKDictionaryBridge:233), which trips typedEqualsCorrection below.
        // That duplicate is the original's guard for validly-typed words; it supersedes the
        // interim "Option A" dictionary-validity gate that briefly lived here.
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
        return canAutoCorrect;
    }
}
