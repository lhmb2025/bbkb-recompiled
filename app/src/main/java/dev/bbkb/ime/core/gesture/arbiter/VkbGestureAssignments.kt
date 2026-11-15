package dev.bbkb.ime.core.gesture.arbiter

import android.content.SharedPreferences

/** The assignable swipe directions on the on-screen keyboard; up is reserved (prediction flick). */
enum class VkbSwipe { LEFT, RIGHT, DOWN }

/**
 * On-screen keyboard swipe slots, read under the main switch [KEY_ENABLED]. Same actions as the
 * capacitive gestures minus [excludedActions]. The slideboard's number panel uses the same slots.
 */
data class VkbGestureAssignments(
    val swipeLeft: GestureAction = GestureAction.DELETE_WORD,
    val swipeRight: GestureAction = GestureAction.NONE,
    val swipeDown: GestureAction = GestureAction.DISMISS_KEYBOARD,
) {
    fun forSwipe(swipe: VkbSwipe): GestureAction = when (swipe) {
        VkbSwipe.LEFT -> swipeLeft
        VkbSwipe.RIGHT -> swipeRight
        VkbSwipe.DOWN -> swipeDown
    }

    companion object {
        /** The main switch ("Swipe gestures"); the key predates the slots. */
        const val KEY_ENABLED = "swipe_gesture_vkb"
        const val KEY_SWIPE_LEFT = "vkb_gesture_swipe_left"
        const val KEY_SWIPE_RIGHT = "vkb_gesture_swipe_right"
        const val KEY_SWIPE_DOWN = "vkb_gesture_swipe_down"

        /** Before the slots: on = swipe down dismisses, off = it shows symbols. Removed by [migrate]. */
        const val LEGACY_KEY_SWIPE_DOWN_DISMISS = "swipe_down_vkb"
        /** Set once [migrate] has run. */
        const val KEY_MIGRATED = "vkb_gestures_migrated"
        private const val KEY_SWIPE_TYPING = "type_by_swiping_vkb"

        /** Cursor mode is a capacitive keyboard action; the on-screen keyboard does not offer it. */
        val excludedActions: Set<GestureAction> = setOf(GestureAction.ENTER_CURSOR_MODE)

        val assignableActions: List<GestureAction>
            get() = GestureAction.assignableActions.filter { it !in excludedActions }

        /** App defaults overridden per slot by a profile's `<setting key="vkb_gesture_..." default-value="..."/>`. */
        fun defaults(deviceDefault: (key: String) -> String?): VkbGestureAssignments {
            val d = VkbGestureAssignments()
            fun slot(key: String, appDefault: GestureAction): GestureAction =
                deviceDefault(key)?.let { usable(GestureAction.fromKey(it)) } ?: appDefault
            return VkbGestureAssignments(
                swipeLeft = slot(KEY_SWIPE_LEFT, d.swipeLeft),
                swipeRight = slot(KEY_SWIPE_RIGHT, d.swipeRight),
                swipeDown = slot(KEY_SWIPE_DOWN, d.swipeDown),
            )
        }

        fun deviceDefaults(): VkbGestureAssignments = defaults { key ->
            dev.bbkb.ime.core.device.profile.DeviceProfile.current().getDefaultStringValue(key, null)
        }

        /** Unset slots, and stored actions this keyboard does not offer, read as [defaults]. */
        fun fromPrefs(prefs: SharedPreferences, defaults: VkbGestureAssignments = VkbGestureAssignments()): VkbGestureAssignments =
            VkbGestureAssignments(
                swipeLeft = read(prefs, KEY_SWIPE_LEFT, defaults.swipeLeft),
                swipeRight = read(prefs, KEY_SWIPE_RIGHT, defaults.swipeRight),
                swipeDown = read(prefs, KEY_SWIPE_DOWN, defaults.swipeDown),
            )

        private fun read(prefs: SharedPreferences, key: String, default: GestureAction): GestureAction {
            val stored = prefs.getString(key, null) ?: return default
            return usable(GestureAction.fromKey(stored)) ?: default
        }

        /** Null for actions this keyboard does not run. */
        private fun usable(action: GestureAction): GestureAction? =
            if (action in excludedActions || !action.assignable) null else action

        /**
         * [migrate] with this app's swipe-typing default. Runs at keyboard start, after an app
         * update and after a restore; never while drawing settings (they must not write).
         */
        @JvmStatic
        fun migrate(context: android.content.Context) {
            migrate(dev.bbkb.ime.core.settings.PrefsManager.getPrefs(context),
                context.resources.getBoolean(dev.bbkb.ime.R.bool.config_vkb_type_by_swiping_enabled_by_build_config))
        }

        /**
         * One-time move from the two switches to the main switch and slots, keeping every user's
         * behavior. Before the slots, "Swipe gestures" only mattered with swipe typing on; with
         * swipe typing off, swipe left and down always worked. Runs again when a restored backup
         * brings [LEGACY_KEY_SWIPE_DOWN_DISMISS] back.
         *
         * @param swipeTypingDefault the swipe-typing default when the user never set it
         * @param oldSwitchDefault the old "Swipe gestures" default when the user never set it
         */
        @JvmStatic
        @JvmOverloads
        fun migrate(prefs: SharedPreferences, swipeTypingDefault: Boolean, oldSwitchDefault: Boolean = false) {
            val legacyDown = prefs.contains(LEGACY_KEY_SWIPE_DOWN_DISMISS)
            if (prefs.getBoolean(KEY_MIGRATED, false) && !legacyDown) return
            val swipeTyping = prefs.getBoolean(KEY_SWIPE_TYPING, swipeTypingDefault)
            val oldSwitch = prefs.getBoolean(KEY_ENABLED, oldSwitchDefault)
            val edit = prefs.edit()
                // Swipe typing on with the switch off left only the prediction flick.
                .putBoolean(KEY_ENABLED, !swipeTyping || oldSwitch)
                .putBoolean(KEY_MIGRATED, true)
            if (legacyDown) {
                val dismiss = prefs.getBoolean(LEGACY_KEY_SWIPE_DOWN_DISMISS, true)
                edit.putString(KEY_SWIPE_DOWN,
                    (if (dismiss) GestureAction.DISMISS_KEYBOARD else GestureAction.CYCLE_SYMBOLS).key)
                    .remove(LEGACY_KEY_SWIPE_DOWN_DISMISS)
            }
            edit.apply()
        }
    }
}
