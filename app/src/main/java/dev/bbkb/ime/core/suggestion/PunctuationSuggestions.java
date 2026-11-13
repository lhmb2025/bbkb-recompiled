package dev.bbkb.ime.core.suggestion;

import dev.bbkb.ime.core.engine.Dictionary;
import dev.bbkb.ime.keyboard.internal.KeySpecParser;

import java.util.ArrayList;
import java.util.Arrays;



public final class PunctuationSuggestions extends SuggestedWords {
    @Override // dev.bbkb.ime.core.suggestion.SuggestedWords
    public boolean isAutoCorrection() {
        return true;
    }

    private PunctuationSuggestions(ArrayList<SuggestedWordInfo> arrayList) {
        super(arrayList, false, false, 0);
    }

    public static PunctuationSuggestions newPunctuationSuggestions(String[] punctuationSpecs) {
        ArrayList arrayList = new ArrayList();
        for (String str : punctuationSpecs) {
            arrayList.add(newHardCodedWordInfo(str));
        }
        return new PunctuationSuggestions(arrayList);
    }

    @Override // dev.bbkb.ime.core.suggestion.SuggestedWords
    public String getWord(int index) {
        String strMo4284a = super.getWord(index);
        int iM7454c = KeySpecParser.getCode(strMo4284a);
        if (iM7454c == -4) {
            return KeySpecParser.getOutputText(strMo4284a);
        }
        return new String(Character.toChars(iM7454c));
    }

    @Override // dev.bbkb.ime.core.suggestion.SuggestedWords
    public String getWordForDisplay(int index) {
        return KeySpecParser.getLabel(super.getWord(index));
    }

    @Override // dev.bbkb.ime.core.suggestion.SuggestedWords
    public SuggestedWordInfo getWordInfo(int index) {
        return newHardCodedWordInfo(getWord(index));
    }

    @Override // dev.bbkb.ime.core.suggestion.SuggestedWords
    public String toString() {
        return "PunctuationSuggestions:  words=" + Arrays.toString(this.mSuggestionsList.toArray());
    }

    private static SuggestedWordInfo newHardCodedWordInfo(String keySpec) {
        return new SuggestedWordInfo(keySpec, Integer.MAX_VALUE, 5, Dictionary.DICTIONARY_HARDCODED, -1, -1, null);
    }
}
