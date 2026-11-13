package dev.bbkb.ime.keyboard.auxbar;

/** How UnifiedSuggestionView lays out its items and where they come from. */
public enum SuggestionMode {
    /** Weighted 3-slot layout of SuggestedWords. */
    LATIN,

    /** Scrolling, tightly packed SuggestedWords with an expand button on the right. */
    CJK,

    /** InlineSuggestion items from the Android Autofill service. */
    AUTOFILL,

    /**
     * One chip per enabled keyboard language, the current one bold. The hamburger slot shows
     * the language settings gear instead.
     */
    LANGUAGES
}
