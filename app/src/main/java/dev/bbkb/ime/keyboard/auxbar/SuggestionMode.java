package dev.bbkb.ime.keyboard.auxbar;

/**
 * Modes for the UnifiedSuggestionView.
 * Determines how suggestions are displayed and what data source is used.
 */
public enum SuggestionMode {
    /**
     * Latin word suggestions with weighted 3-slot layout.
     * Data source: SuggestedWords from prediction engine.
     */
    LATIN,
    
    /**
     * CJK character suggestions with scrolling, tight packing.
     * Data source: SuggestedWords from CJK prediction engine.
     * Shows expand button on right side.
     */
    CJK,
    
    /**
     * Password autofill suggestions.
     * Data source: List<InlineSuggestion> from Android Autofill service.
     * Uses InlineContentView items.
     */
    AUTOFILL,

    /**
     * The language-chip bar: one chip per enabled keyboard language, the current one bold.
     * Data source: List<LanguageChip> from the IME. The hamburger slot shows the language
     * settings gear instead.
     */
    LANGUAGES
}
