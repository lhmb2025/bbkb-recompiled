package dev.bbkb.ime.core.gesture.arbiter

/**
 * Actions a CKB gesture can be assigned. [key] is the stored `ckb_gesture_<slot>` value; [consumes]
 * suppresses key handling for the contact. ([GesturePolicy] yields instead of acting on [NONE].)
 */
enum class GestureAction(val key: String, val consumes: Boolean, val assignable: Boolean) {
    NONE("none", true, true),
    /** Reserved for flick-up under the matching suggestion-strip word; not user-assignable. */
    COMMIT_SUGGESTION("commit_suggestion", true, false),
    DELETE_WORD("delete_word", true, true),
    ENTER_CURSOR_MODE("enter_cursor_mode", true, true),
    NEXT_LANGUAGE("next_language", true, true),
    CYCLE_SYMBOLS("cycle_symbols", true, true),
    DISMISS_KEYBOARD("dismiss_keyboard", true, true),
    UNDO("undo", true, true),
    EMOJI("emoji", true, true);

    companion object {
        /** [NONE] if unknown or null. */
        fun fromKey(key: String?): GestureAction = entries.firstOrNull { it.key == key } ?: NONE

        /** Excludes reserved ones like commit. */
        val assignableActions: List<GestureAction> get() = entries.filter { it.assignable }
    }
}
