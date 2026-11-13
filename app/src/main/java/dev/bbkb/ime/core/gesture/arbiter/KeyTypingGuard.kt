package dev.bbkb.ime.core.gesture.arbiter

/**
 * A CKB contact's timing against hardware keys, so a resting or landing finger never fires a
 * gesture mid-word: a key during the contact, a start inside the touch-noise window, or an end
 * inside the suppression window each suppress it. Evaluated once per classified gesture.
 */
data class KeyTiming(
    val startedInNoiseWindow: Boolean,
    val keyDuringContact: Boolean,
    /** The gesture ended (or, per the user's choice, started) inside the suppression window. */
    val insideSuppressionWindow: Boolean,
) {
    val suppressesGestures: Boolean
        get() = startedInNoiseWindow || keyDuringContact || insideSuppressionWindow

    companion object {
        val CLEAR = KeyTiming(startedInNoiseWindow = false, keyDuringContact = false, insideSuppressionWindow = false)
    }
}

/**
 * Tracks key events against the current CKB contact. Thresholds are providers so live values are
 * read when a contact is judged. Times are the shared MotionEvent/KeyEvent uptime-millis clock.
 */
class KeyTypingGuard(
    /** Touch-noise window, ms: a contact starting this soon after a key is a landing finger. */
    private val noiseWindowMs: () -> Long,
    /** Suppression window, ms: a gesture this close to a key is part of typing, not a gesture. */
    private val suppressionWindowMs: () -> Long,
    /** Measure the suppression window from the gesture's end (true) or its start (false). */
    private val measureFromEnd: () -> Boolean,
) {
    /** [NO_KEY] before any. */
    var lastKeyEventTime: Long = NO_KEY
        private set

    private var contactActive = false
    private var contactStartTime = 0L
    /** Last key before this contact began; a key during the contact must not move it. */
    private var keyBeforeContact = NO_KEY
    private var keyDuringContact = false

    fun onKeyEvent(eventTime: Long) {
        lastKeyEventTime = eventTime
        if (contactActive) keyDuringContact = true
    }

    /** DOWN or POINTER_DOWN. */
    fun onContactStart(eventTime: Long) {
        contactActive = true
        contactStartTime = eventTime
        keyBeforeContact = lastKeyEventTime
        keyDuringContact = false
    }

    fun onContactEnd() {
        contactActive = false
    }

    /** For the current or just-ended contact; for a mid-contact hold pass its fire time. */
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
        /** Keeps a fresh guard from suppressing. */
        const val NO_KEY = Long.MIN_VALUE
    }
}
