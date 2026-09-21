package dev.bbkb.ime.core.settings.util;




/**
 * Data class holding configuration for the suggestion strip display.
 * Contains visibility, suggestion count, and feature flags.
 */
public class SuggestionStripSettings {

    public final boolean isVisible;

    public final boolean isEnabledInCurrentInputField;

    public final int[] suggestionCounts;

    public SuggestionStripSettings(boolean z, int[] iArr, boolean z2) {
        this.isVisible = z;
        this.suggestionCounts = iArr;
        this.isEnabledInCurrentInputField = z2;
    }
}
