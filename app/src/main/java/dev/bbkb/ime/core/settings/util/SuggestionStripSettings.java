package dev.bbkb.ime.core.settings.util;



/** Suggestion strip display configuration. */
public class SuggestionStripSettings {

    public final boolean isVisible;

    public final boolean isEnabledInCurrentInputField;

    public final int[] suggestionCounts;

    /**
     * @param z whether the strip is visible
     * @param iArr the suggestion counts
     * @param z2 whether suggestions are enabled in the current input field
     */
    public SuggestionStripSettings(boolean z, int[] iArr, boolean z2) {
        this.isVisible = z;
        this.suggestionCounts = iArr;
        this.isEnabledInCurrentInputField = z2;
    }
}
