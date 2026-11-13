package dev.bbkb.ime.core.gesture.arbiter

import dev.bbkb.ime.core.device.config.model.KeyRole

/**
 * Firmware-synthesised swipe keys read as the arbiter's gestures: the Titan 2 ROM sends keycode
 * 322 (Android 15) or 404 (Android 16) for a left swipe; its config gives them
 * [KeyRole.GESTURE_SWIPE_LEFT], so they run the swipe-left slot. Pure; the IME side is
 * [dev.bbkb.ime.core.ime.CkbGestureBridge].
 */
object FirmwareGestureKeys {
    fun swipeFor(role: KeyRole?): GestureClassification.Swipe? = when (role) {
        // The slow (swipe) slot, not the flick one.
        KeyRole.GESTURE_SWIPE_LEFT ->
            GestureClassification.Swipe(GestureDirection.LEFT, fast = false, confidence = 1f)
        else -> null
    }

    /** Null when [role] is not one. Skips the typing guard: the synthesised key would count as typing. */
    fun outcome(
        role: KeyRole?,
        mode: ModeState,
        assignments: GestureAssignments,
        policy: GesturePolicy = GesturePolicy(),
    ): PolicyOutcome? {
        val swipe = swipeFor(role) ?: return null
        return policy.resolve(swipe, mode.copy(keyTiming = KeyTiming.CLEAR), assignments)
    }
}
