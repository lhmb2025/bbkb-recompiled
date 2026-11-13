package dev.bbkb.ime.core.settings.util;



/** Suggestion strip display configuration. */
public class SuggestionStripSettings {

    public final boolean isVisible;

    public final boolean isEnabledInCurrentInputField;

    public final int[] suggestionCounts;

    /**
     * @param isVisible whether the strip is visible
     * @param suggestionCounts the suggestion counts
     * @param isEnabledInCurrentInputField whether suggestions are enabled in the current input field
     */
    public SuggestionStripSettings(boolean isVisible, int[] suggestionCounts, boolean isEnabledInCurrentInputField) {
        this.isVisible = isVisible;
        this.suggestionCounts = suggestionCounts;
        this.isEnabledInCurrentInputField = isEnabledInCurrentInputField;
    }
}
