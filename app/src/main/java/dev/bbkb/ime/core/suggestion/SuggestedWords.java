package dev.bbkb.ime.core.suggestion;

import android.text.TextUtils;
import android.view.inputmethod.CompletionInfo;

import dev.bbkb.ime.core.engine.Dictionary;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.locale.LocaleUtils;
import com.blackberry.nuanceshim.WordInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;



public class SuggestedWords {

    public final String mAutoCorrectWord;

    public final boolean mTypedWordValid;

    public final boolean mWillAutoCorrect;

    public final int mInputStyle;

    protected final ArrayList<SuggestedWordInfo> mSuggestionsList;

    /**
     * {@code ComposingTextTracker.getEditCount()} when the request that produced this list was
     * made, or -1 when unknown (then the list is taken as current). Set by
     * {@code SuggestionCoordinator} before the list leaves the worker.
     */
    public int mRequestEditCount = -1;

    private static final ArrayList<SuggestedWordInfo> EMPTY_SUGGESTIONS_LIST = new ArrayList<>(0);

    public static final SuggestedWords EMPTY = new SuggestedWords(EMPTY_SUGGESTIONS_LIST, false, false, 0);

    private static boolean isValidType(int inputStyle) {
        return 6 == inputStyle || 7 == inputStyle;
    }

    public boolean isAutoCorrection() {
        return false;
    }

    public SuggestedWords(ArrayList<SuggestedWordInfo> arrayList, boolean typedWordValid, boolean willAutoCorrect, int inputStyle) {
        this(arrayList, (arrayList.isEmpty() || isValidType(inputStyle)) ? null : arrayList.get(0).word, typedWordValid, willAutoCorrect, inputStyle);
    }

    public SuggestedWords(ArrayList<SuggestedWordInfo> arrayList, String typedWord, boolean typedWordValid, boolean willAutoCorrect, int inputStyle) {
        this.mSuggestionsList = arrayList;
        this.mTypedWordValid = typedWordValid;
        this.mWillAutoCorrect = willAutoCorrect;
        this.mInputStyle = inputStyle;
        this.mAutoCorrectWord = typedWord;
    }

    public boolean isEmpty() {
        return this.mSuggestionsList.isEmpty();
    }

    public int size() {
        return this.mSuggestionsList.size();
    }

    public String getWord(int index) {
        return this.mSuggestionsList.get(index).word;
    }

    public String getWordForDisplay(int index) {
        return this.mSuggestionsList.get(index).word;
    }

    public SuggestedWordInfo getWordInfo(int index) {
        return this.mSuggestionsList.get(index);
    }

    public String toString() {
        return "SuggestedWords: mTypedWordValid=" + this.mTypedWordValid + " mWillAutoCorrect=" + this.mWillAutoCorrect + " mInputStyle=" + this.mInputStyle + " words=" + Arrays.toString(this.mSuggestionsList.toArray());
    }

    public static ArrayList<SuggestedWordInfo> buildWithTypedWord(String typedWord, SuggestedWords suggestions) {
        ArrayList<SuggestedWordInfo> arrayList = new ArrayList<>();
        HashSet hashSet = new HashSet();
        arrayList.add(new SuggestedWordInfo(typedWord, Integer.MAX_VALUE, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null));
        hashSet.add(typedWord.toString());
        int iM4288c = suggestions.size();
        for (int i = 1; i < iM4288c; i++) {
            SuggestedWordInfo suggestedWordInfoVarMo4289C = suggestions.getWordInfo(i);
            String str2 = suggestedWordInfoVarMo4289C.word;
            if (!hashSet.contains(str2)) {
                arrayList.add(suggestedWordInfoVarMo4289C);
                hashSet.add(str2);
            }
        }
        return arrayList;
    }

    
    public static class SuggestedWordInfo {

        public final String word;

        public final CompletionInfo completionInfo;

        public final int score;

        public final int kindAndFlags;

        public final Dictionary sourceDictionary;

        public final int indexInDictionary;

        public final int indexInSuggestions;

        public WordInfo nuanceWordInfo;

        private String debugInfo;

        public SuggestedWordInfo(String word, int score, int kindAndFlags, Dictionary dictionary, int indexInDictionary, int indexInSuggestions, WordInfo wordInfo) {
            this.debugInfo = "";
            this.word = word;
            this.completionInfo = null;
            this.score = score;
            this.kindAndFlags = kindAndFlags;
            this.sourceDictionary = dictionary;
            this.indexInDictionary = indexInDictionary;
            this.indexInSuggestions = indexInSuggestions;
            this.nuanceWordInfo = wordInfo;
        }

        public SuggestedWordInfo(CompletionInfo completionInfo) {
            this.debugInfo = "";
            this.word = completionInfo.getText().toString();
            this.completionInfo = completionInfo;
            this.score = Integer.MAX_VALUE;
            this.kindAndFlags = 6;
            this.sourceDictionary = Dictionary.DICTIONARY_APPLICATION_DEFINED;
            this.indexInDictionary = -1;
            this.indexInSuggestions = -1;
            this.nuanceWordInfo = null;
        }

        public int getKind() {
            return this.kindAndFlags & 255;
        }

        public boolean isKind(int kind) {
            return getKind() == kind;
        }

        public boolean isObsolete() {
            return (this.kindAndFlags & 536870912) != 0;
        }

        public boolean isFromContacts() {
            return (this.kindAndFlags & 268435456) != 0;
        }

        public String toString() {
            if (TextUtils.isEmpty(this.debugInfo)) {
                return this.word;
            }
            return this.word + " (" + this.debugInfo + ")";
        }

        public static void dedupeSuggestions(String typedWord, ArrayList<SuggestedWordInfo> arrayList) {
            if (arrayList.isEmpty()) {
                return;
            }
            if (!TextUtils.isEmpty(typedWord)) {
                removeDuplicatesOf(typedWord, arrayList, -1, false);
            }
            for (int i = 0; i < arrayList.size(); i++) {
                removeDuplicatesOf(arrayList.get(i).word, arrayList, i, false);
            }
        }

        private static boolean removeDuplicatesOf(String word, ArrayList<SuggestedWordInfo> arrayList, int startIndexExclusive, boolean ignoreCase) {
            int i2 = startIndexExclusive + 1;
            boolean z2 = false;
            while (i2 < arrayList.size()) {
                SuggestedWordInfo suggestedWordInfoVar = arrayList.get(i2);
                if (word.equals(suggestedWordInfoVar.word) || (ignoreCase && word.equalsIgnoreCase(suggestedWordInfoVar.word))) {
                    arrayList.remove(i2);
                    i2--;
                    z2 = true;
                }
                i2++;
            }
            return z2;
        }
    }

    public boolean isValidInputStyle() {
        return isValidType(this.mInputStyle);
    }

    public SuggestedWords getSuggestedWordsForDisplay() {
        ArrayList arrayList = new ArrayList();
        String str = null;
        for (int i = 0; i < this.mSuggestionsList.size(); i++) {
            SuggestedWordInfo suggestedWordInfoVar = this.mSuggestionsList.get(i);
            if (!suggestedWordInfoVar.isKind(0)) {
                arrayList.add(suggestedWordInfoVar);
            } else if (str == null) {
                // A second typed-word entry is expected (the typed word is also the engine's first
                // candidate, e.g. a Chinese stroke); never throw here.
                str = suggestedWordInfoVar.word;
            }
        }
        return new SuggestedWords(arrayList, str, true, false, 5);
    }

    public static int getMaxSuggestionCount() {
        return (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) ? 150 : 18;
    }

    public static SuggestedWordInfo findByNuanceWord(SuggestedWords suggestedWords, String word) {
        Iterator<SuggestedWordInfo> it = suggestedWords.mSuggestionsList.iterator();
        while (it.hasNext()) {
            SuggestedWordInfo next = it.next();
            if (next.nuanceWordInfo != null && next.nuanceWordInfo.word != null && word != null) {
                java.util.Locale locale = SubtypeManager.getInstance().getCurrentSubtypeLocale();
                if (locale != null && next.nuanceWordInfo.word.toLowerCase(locale).equals(word.toLowerCase(locale))) {
                    return next;
                }
            }
        }
        return null;
    }

    public static int getMinSuggestionsIndex() {
        return !LocaleUtils.isCurrentSubtypeJapanese() ? 1 : 0;
    }
}
