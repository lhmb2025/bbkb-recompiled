package dev.bbkb.ime.core.suggestion;

import java.util.ArrayList;
import java.util.Locale;



public final class SuggestionResult {

    public final Locale locale;

    public final boolean hasNextWordSuggestions;

    private int maxSuggestions;

    private ArrayList<SuggestedWords.SuggestedWordInfo> suggestions = new ArrayList<>();

    public SuggestionResult(Locale locale, int i, boolean z) {
        this.locale = locale;
        this.maxSuggestions = i;
        this.hasNextWordSuggestions = z;
    }

    public ArrayList<SuggestedWords.SuggestedWordInfo> getSuggestions() {
        return this.suggestions;
    }

    public void addSuggestions(ArrayList<SuggestedWords.SuggestedWordInfo> arrayList) {
        int size = arrayList.size();
        int i = this.maxSuggestions;
        if (size <= i) {
            this.suggestions.addAll(arrayList);
        } else {
            this.suggestions.addAll(arrayList.subList(0, i));
        }
    }

    public boolean isEmpty() {
        ArrayList<SuggestedWords.SuggestedWordInfo> arrayList = this.suggestions;
        return arrayList == null || arrayList.size() == 0;
    }

    public SuggestedWords.SuggestedWordInfo getFirstSuggestion() {
        if (this.suggestions.size() > 0) {
            return this.suggestions.get(0);
        }
        return null;
    }
}
