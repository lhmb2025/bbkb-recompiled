package dev.bbkb.ime.keyboard.auxbar;

/** Which child of AuxBarView is visible and what it shows. */
public enum AuxBarState {
    NONE,

    /** Shown in UnifiedSuggestionView. */
    LATIN_SUGGESTIONS,

    /** Shown in UnifiedSuggestionView. */
    CJK_SUGGESTIONS,

    /** Password autofill, shown in UnifiedSuggestionView. */
    AUTOFILL,

    /** Unified Input Menu in SharedKeyView, keyboard layout 138. */
    UNIFIED_INPUT_MENU,

    /** Cursor keys in SharedKeyView, keyboard layout 39. */
    ARROW_BAR,

    /** Accent picker in SharedKeyView, from a generated keyboard. */
    ACCENT_BAR,

    /** This keyboard's languages in UnifiedSuggestionView; a tap switches. */
    LANGUAGE_CHIPS
}
