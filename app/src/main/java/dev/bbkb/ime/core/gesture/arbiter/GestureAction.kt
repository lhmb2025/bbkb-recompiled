package dev.bbkb.ime.core.gesture.arbiter

/**
 * The catalog of actions a CKB gesture can be assigned to. Each has a stable [key] for
 * persistence (the value stored under the `ckb_gesture_<slot>` prefs) and a [consumes] flag —
 * true means performing it should suppress the legacy gesture/key handling for that contact.
 *
 * [NONE] is "recognized but do nothing" (still consumes, so the gesture isn't double-handled).
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
        /** Action for a stored key, or [NONE] if unknown/null. */
        fun fromKey(key: String?): GestureAction = entries.firstOrNull { it.key == key } ?: NONE

        /** Actions the user may assign to a gesture slot (excludes reserved ones like commit). */
        val assignableActions: List<GestureAction> get() = entries.filter { it.assignable }
    }
}
