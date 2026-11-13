package dev.bbkb.ime.keyboard.auxbar.suggestions;

import dev.bbkb.ime.core.suggestion.SuggestedWords;



public interface SuggestionStripListener {
    boolean isShowingMoreSuggestions();

    void dismissMoreSuggestions();

    void setNeutralSuggestionStrip();

    void showSuggestionStrip(SuggestedWords suggestedWords);

    void showAddToDictionaryHint(String word);
}
