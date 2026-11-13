package dev.bbkb.ime.core.gesture.arbiter

import android.content.SharedPreferences

/**
 * Assignable gesture slots: flick/swipe left and right, swipe down (flick-down folds in), double-tap
 * and hold. Up is reserved (commit). Double-tap enters cursor mode so an unassigned [hold] yields
 * and holding a key keeps its normal behavior.
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

        /**
         * App defaults overridden per slot by [deviceDefault] (a profile's
         * `<setting key="ckb_gesture_..." default-value="..."/>`); stored choices still win.
         */
        fun defaults(deviceDefault: (key: String) -> String?): GestureAssignments {
            val d = GestureAssignments()
            fun slot(key: String, appDefault: GestureAction): GestureAction =
                deviceDefault(key)?.let { GestureAction.fromKey(it) } ?: appDefault
            return GestureAssignments(
                flickLeft = slot(KEY_FLICK_LEFT, d.flickLeft),
                flickRight = slot(KEY_FLICK_RIGHT, d.flickRight),
                swipeLeft = slot(KEY_SWIPE_LEFT, d.swipeLeft),
                swipeRight = slot(KEY_SWIPE_RIGHT, d.swipeRight),
                swipeDown = slot(KEY_SWIPE_DOWN, d.swipeDown),
                doubleTap = slot(KEY_DOUBLE_TAP, d.doubleTap),
                hold = slot(KEY_HOLD, d.hold),
            )
        }

        /** [defaults] for the active profile (Titans default double-tap to none: the OEM Cursor assistant owns it). */
        fun deviceDefaults(): GestureAssignments = defaults { key ->
            dev.bbkb.ime.core.device.profile.DeviceProfile.current().getDefaultStringValue(key, null)
        }

        /** Unset slots fall back to [defaults]. */
        fun fromPrefs(prefs: SharedPreferences, defaults: GestureAssignments = GestureAssignments()): GestureAssignments {
            val d = defaults
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
