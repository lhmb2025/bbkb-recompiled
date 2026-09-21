package dev.bbkb.ime.core.gesture.arbiter

import android.content.SharedPreferences

/**
 * The user-configurable mapping of assignable gesture slots to [GestureAction]s.
 *
 * Slots (see [[ckb-gesture-rebuild]] memory): flick/swipe left & right, swipe up, swipe down
 * (flick-down folds in here), double-tap, and tap-and-hold. Flick **up** is reserved (commit) and
 * is not a slot. Swipe-down defaults to Cycle symbols.
 *
 * Cursor mode is entered by **double-tap**, matching the original engine's gesture
 * (see docs/legacy-gesture-engine.md, onDoubleTapEvent) and leaving tap-and-hold free — with [hold] unassigned
 * the policy yields the stroke, so holding a key falls through to its normal behavior.
 */
data class GestureAssignments(
    val flickLeft: GestureAction = GestureAction.DELETE_WORD,
    val flickRight: GestureAction = GestureAction.NONE,
    val swipeLeft: GestureAction = GestureAction.DELETE_WORD,
    val swipeRight: GestureAction = GestureAction.NONE,
    val swipeDown: GestureAction = GestureAction.CYCLE_SYMBOLS,
    val doubleTap: GestureAction = GestureAction.ENTER_CURSOR_MODE,
    val hold: GestureAction = GestureAction.NONE,
) {
    companion object {
        const val KEY_FLICK_LEFT = "ckb_gesture_flick_left"
        const val KEY_FLICK_RIGHT = "ckb_gesture_flick_right"
        const val KEY_SWIPE_LEFT = "ckb_gesture_swipe_left"
        const val KEY_SWIPE_RIGHT = "ckb_gesture_swipe_right"
        const val KEY_SWIPE_DOWN = "ckb_gesture_swipe_down"
        const val KEY_DOUBLE_TAP = "ckb_gesture_double_tap"
        const val KEY_HOLD = "ckb_gesture_hold"

        /** Load assignments from prefs, falling back to each slot's default when unset. */
        fun fromPrefs(prefs: SharedPreferences): GestureAssignments {
            val d = GestureAssignments()
            return GestureAssignments(
                flickLeft = read(prefs, KEY_FLICK_LEFT, d.flickLeft),
                flickRight = read(prefs, KEY_FLICK_RIGHT, d.flickRight),
                swipeLeft = read(prefs, KEY_SWIPE_LEFT, d.swipeLeft),
                swipeRight = read(prefs, KEY_SWIPE_RIGHT, d.swipeRight),
                swipeDown = read(prefs, KEY_SWIPE_DOWN, d.swipeDown),
                doubleTap = read(prefs, KEY_DOUBLE_TAP, d.doubleTap),
                hold = read(prefs, KEY_HOLD, d.hold),
            )
        }

        private fun read(prefs: SharedPreferences, key: String, default: GestureAction): GestureAction {
            val stored = prefs.getString(key, null) ?: return default
            return GestureAction.fromKey(stored)
        }
    }
}
