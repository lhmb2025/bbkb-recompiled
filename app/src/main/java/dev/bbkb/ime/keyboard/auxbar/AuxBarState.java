package dev.bbkb.ime.keyboard.auxbar;

/**
 * States for the AuxBarView container.
 * Determines which child view is visible and what content is displayed.
 */
public enum AuxBarState {
    /**
     * No auxiliary bar visible.
     */
    NONE,
    
    /**
     * Latin word suggestions displayed in UnifiedSuggestionView.
     */
    LATIN_SUGGESTIONS,
    
    /**
     * CJK character suggestions displayed in UnifiedSuggestionView.
     */
    CJK_SUGGESTIONS,
    
    /**
     * Password autofill suggestions displayed in UnifiedSuggestionView.
     */
    AUTOFILL,
    
    /**
     * Unified Input Menu bar displayed in SharedKeyView.
     * Uses keyboard layout 138.
     */
    UNIFIED_INPUT_MENU,
    
    /**
     * Arrow/cursor keys bar displayed in SharedKeyView.
     * Uses keyboard layout 39.
     */
    ARROW_BAR,
    
    /**
     * Accent character selection bar displayed in SharedKeyView.
     * Uses dynamically generated accent keyboard.
     */
    ACCENT_BAR
}
