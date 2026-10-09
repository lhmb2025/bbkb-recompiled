package dev.bbkb.ime.core.gesture.arbiter

import dev.bbkb.ime.core.device.config.model.KeyRole

/**
 * Keys a device's firmware synthesises from a swipe across the keys, read as the arbiter's own
 * gestures. The Titan 2's ROM turns a leftward swipe into keycode 322 (Android 15) or 404
 * (Android 16); its device config gives those keycodes the [KeyRole.GESTURE_SWIPE_LEFT] role, and
 * the key then does whatever the user assigned to the swipe-left slot — the same slot a pad swipe
 * left uses — instead of reaching the editor as a key.
 *
 * Pure: the IME side ([dev.bbkb.ime.core.ime.CkbGestureBridge]) resolves the role, consumes the
 * key and executes the action.
 */
object FirmwareGestureKeys {

    /** The gesture a key with [role] stands for, or null when it is not a firmware gesture key. */
    fun swipeFor(role: KeyRole?): GestureClassification.Swipe? = when (role) {
        // A deliberate, completed swipe: the slow (swipe) slot, not the flick one.
        KeyRole.GESTURE_SWIPE_LEFT ->
            GestureClassification.Swipe(GestureDirection.LEFT, fast = false, confidence = 1f)
        else -> null
    }

    /**
     * What the policy does with a firmware gesture key of [role], or null when [role] is not one.
     * The typing guard does not apply: the firmware already decided this was a swipe, and the
     * synthesised key would otherwise count as the "typing" that suppresses it.
     */
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
