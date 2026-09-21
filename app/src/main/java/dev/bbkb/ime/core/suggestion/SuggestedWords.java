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

    private static final ArrayList<SuggestedWordInfo> EMPTY_SUGGESTIONS_LIST = new ArrayList<>(0);

    public static final SuggestedWords EMPTY = new SuggestedWords(EMPTY_SUGGESTIONS_LIST, false, false, 0);

    private static boolean isValidType(int i) {
        return 6 == i || 7 == i;
    }

    public boolean isAutoCorrection() {
        return false;
    }

    public SuggestedWords(ArrayList<SuggestedWordInfo> arrayList, boolean z, boolean z2, int i) {
        this(arrayList, (arrayList.isEmpty() || isValidType(i)) ? null : arrayList.get(0).word, z, z2, i);
    }

    public SuggestedWords(ArrayList<SuggestedWordInfo> arrayList, String str, boolean z, boolean z2, int i) {
        this.mSuggestionsList = arrayList;
        this.mTypedWordValid = z;
        this.mWillAutoCorrect = z2;
        this.mInputStyle = i;
        this.mAutoCorrectWord = str;
    }

    public boolean isEmpty() {
        return this.mSuggestionsList.isEmpty();
    }

    public int size() {
        return this.mSuggestionsList.size();
    }

    public String getWord(int i) {
        return this.mSuggestionsList.get(i).word;
    }

    public String getWordForDisplay(int i) {
        return this.mSuggestionsList.get(i).word;
    }

    public SuggestedWordInfo getWordInfo(int i) {
        return this.mSuggestionsList.get(i);
    }

    public String toString() {
        return "SuggestedWords: mTypedWordValid=" + this.mTypedWordValid + " mWillAutoCorrect=" + this.mWillAutoCorrect + " mInputStyle=" + this.mInputStyle + " words=" + Arrays.toString(this.mSuggestionsList.toArray());
    }

    public static ArrayList<SuggestedWordInfo> buildWithTypedWord(String str, SuggestedWords suggestions) {
        ArrayList<SuggestedWordInfo> arrayList = new ArrayList<>();
        HashSet hashSet = new HashSet();
        arrayList.add(new SuggestedWordInfo(str, Integer.MAX_VALUE, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null));
        hashSet.add(str.toString());
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

        public SuggestedWordInfo(String str, int i, int i2, Dictionary dictionary, int i3, int i4, WordInfo wordInfo) {
            this.debugInfo = "";
            this.word = str;
            this.completionInfo = null;
            this.score = i;
            this.kindAndFlags = i2;
            this.sourceDictionary = dictionary;
            this.indexInDictionary = i3;
            this.indexInSuggestions = i4;
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

        public boolean isKind(int i) {
            return getKind() == i;
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

        public static void dedupeSuggestions(String str, ArrayList<SuggestedWordInfo> arrayList) {
            if (arrayList.isEmpty()) {
                return;
            }
            if (!TextUtils.isEmpty(str)) {
                removeDuplicatesOf(str, arrayList, -1, false);
            }
            for (int i = 0; i < arrayList.size(); i++) {
                removeDuplicatesOf(arrayList.get(i).word, arrayList, i, false);
            }
        }

        private static boolean removeDuplicatesOf(String str, ArrayList<SuggestedWordInfo> arrayList, int i, boolean z) {
            int i2 = i + 1;
            boolean z2 = false;
            while (i2 < arrayList.size()) {
                SuggestedWordInfo suggestedWordInfoVar = arrayList.get(i2);
                if (str.equals(suggestedWordInfoVar.word) || (z && str.equalsIgnoreCase(suggestedWordInfoVar.word))) {
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
            } else {
                if (str != null) {
                    throw new IllegalStateException("More than one typed-word entry in the suggestion list");
                }
                str = suggestedWordInfoVar.word;
            }
        }
        return new SuggestedWords(arrayList, str, true, false, 5);
    }

    public static int getMaxSuggestionCount() {
        return (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) ? 150 : 18;
    }

    public static SuggestedWordInfo findByNuanceWord(SuggestedWords c0666ac, String str) {
        Iterator<SuggestedWordInfo> it = c0666ac.mSuggestionsList.iterator();
        while (it.hasNext()) {
            SuggestedWordInfo next = it.next();
            if (next.nuanceWordInfo != null && next.nuanceWordInfo.word != null && str != null) {
                java.util.Locale locale = SubtypeManager.getInstance().getCurrentSubtypeLocale();
                if (locale != null && next.nuanceWordInfo.word.toLowerCase(locale).equals(str.toLowerCase(locale))) {
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
