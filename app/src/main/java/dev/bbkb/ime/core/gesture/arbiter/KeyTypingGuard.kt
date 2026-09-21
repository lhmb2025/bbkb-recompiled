package dev.bbkb.ime.core.gesture.arbiter

/**
 * How a CKB contact relates in time to the hardware keys being typed. The policy reads it to keep
 * a resting or landing finger from firing a gesture mid-word.
 *
 * The original keyboard applied three windows off the last key event, and the arbiter replaced
 * the legacy listener that held them without carrying them over: a contact in progress was
 * cancelled by any key, a contact *starting* inside the touch-noise window was dropped, and a
 * gesture *ending* inside the suppression window was never dispatched. This is the same rule set,
 * evaluated once per classified gesture instead of inside the event stream.
 */
data class KeyTiming(
    /** The contact began inside the touch-noise window after a key event. */
    val startedInNoiseWindow: Boolean,
    /** A key went down or up while the contact was in progress. */
    val keyDuringContact: Boolean,
    /** The gesture ended (or, per the user's choice, started) inside the suppression window. */
    val insideSuppressionWindow: Boolean,
) {
    /** True when any window applies: the gesture must yield to the typing that surrounds it. */
    val suppressesGestures: Boolean
        get() = startedInNoiseWindow || keyDuringContact || insideSuppressionWindow

    companion object {
        /** No key anywhere near the contact. */
        val CLEAR = KeyTiming(startedInNoiseWindow = false, keyDuringContact = false, insideSuppressionWindow = false)
    }
}

/**
 * Tracks hardware key events against the current CKB contact and answers [keyTiming] for it.
 * Pure: the three thresholds come in as providers so the live values (a resource and two of the
 * original's preferences) are read when a contact is judged, not when the IME is created.
 *
 * Event times are the `MotionEvent`/`KeyEvent` uptime-millis clock; both streams share it.
 */
class KeyTypingGuard(
    /** Touch-noise window, ms: a contact starting this soon after a key is a landing finger. */
    private val noiseWindowMs: () -> Long,
    /** Suppression window, ms: a gesture this close to a key is part of typing, not a gesture. */
    private val suppressionWindowMs: () -> Long,
    /** Measure the suppression window from the gesture's end (true) or its start (false). */
    private val measureFromEnd: () -> Boolean,
) {
    /** Uptime of the last hardware key event seen, or [NO_KEY] before any. */
    var lastKeyEventTime: Long = NO_KEY
        private set

    private var contactActive = false
    private var contactStartTime = 0L
    /** The last key event *before* this contact began; a key during the contact must not move it. */
    private var keyBeforeContact = NO_KEY
    private var keyDuringContact = false

    /** A hardware key went down or up at [eventTime]. */
    fun onKeyEvent(eventTime: Long) {
        lastKeyEventTime = eventTime
        if (contactActive) keyDuringContact = true
    }

    /** A CKB contact (DOWN or POINTER_DOWN) began at [eventTime]. */
    fun onContactStart(eventTime: Long) {
        contactActive = true
        contactStartTime = eventTime
        keyBeforeContact = lastKeyEventTime
        keyDuringContact = false
    }

    /** The tracked contact lifted or was cancelled. */
    fun onContactEnd() {
        contactActive = false
    }

    /**
     * Judge the current (or just-ended) contact whose gesture completed at [endTime]. Safe to
     * call for a mid-contact hold as well, with the hold's fire time as [endTime].
     */
    fun keyTiming(endTime: Long): KeyTiming {
        if (lastKeyEventTime == NO_KEY) return KeyTiming.CLEAR
        val startedInNoiseWindow = keyBeforeContact != NO_KEY && contactStartTime - keyBeforeContact < noiseWindowMs()
        val insideSuppressionWindow = if (measureFromEnd()) {
            endTime - lastKeyEventTime < suppressionWindowMs()
        } else {
            keyBeforeContact != NO_KEY && contactStartTime - keyBeforeContact < suppressionWindowMs()
        }
        return KeyTiming(
            startedInNoiseWindow = startedInNoiseWindow,
            keyDuringContact = keyDuringContact,
            insideSuppressionWindow = insideSuppressionWindow,
        )
    }

    companion object {
        /** Sentinel for "no key event has been seen"; keeps a fresh guard from suppressing. */
        const val NO_KEY = Long.MIN_VALUE
    }
}
