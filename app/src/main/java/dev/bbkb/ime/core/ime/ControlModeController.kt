package dev.bbkb.ime.core.ime
import android.view.KeyEvent
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.Constants

/**
 * Ctrl state machine for both surfaces. VKB: hold ?123 (-3) and press a letter, Enter or Delete
 * for a Ctrl chord; hold until the long-press (-34) fires and release to latch Ctrl for one key.
 * PKB: a physical or remapped Ctrl works like Shift/Alt (hold to chord, tap for next key,
 * double-tap to lock, tap again to clear). A held or one-shot Shift joins the chord; caps lock does not.
 * The only Ctrl state owner (`ModifierState` reads [isCtrlActive]); all side effects go through [Host].
 */
class ControlModeController(private val host: Host) {

    interface Host {
        val isVkbControlModeEnabled: Boolean
        /** The control_mode setting: 0 = right Shift acts as Ctrl, 1 = left Shift acts as Ctrl, 2 = off. */
        val controlModeSetting: Int
        /** `ModifierState.isAltActiveForCharacter`; read only after [remapMultifunctionCtrlKey]'s early returns, to keep the key path cheap. */
        val isAltActiveForCharacter: Boolean
        /**
         * The Ctrl key sends no key-up and auto-repeats while held (profile quirk `fn-no-key-up`).
         * Ctrl is then one-shot for the next key, with no tap or double tap.
         */
        val ctrlKeySendsNoKeyUp: Boolean get() = false
        val isCtrlDoubleTapLockEnabled: Boolean get() = true
        val ctrlDoubleTapTimeoutMs: Long get() = 600L
        /** A physical Alt is held, tapped or locked and not yet spent (the Alt of an Alt+Sym is spent). */
        val isPkbAltActiveForChord: Boolean get() = false
        /** A physical Shift is held or tapped for the next key; caps lock does not count. */
        val isPkbShiftActiveForChord: Boolean get() = false
        /** The on-screen keyboard is shifted by hand or Shift is held; not auto-caps or caps lock. */
        val isVkbShiftActiveForChord: Boolean get() = false
        fun sendKeyDownWithMeta(keyCode: Int, metaState: Int)
        fun sendKeyUpWithMeta(keyCode: Int, metaState: Int)
        fun showControlModeUi()
        fun hideControlModeUi()
        /** [isCtrlActive] or [isCtrlLocked] changed; the status icon follows it. */
        fun onPkbCtrlStateChanged() {}
        /** The on-screen ?123 key should read CTRL ([active]) or its own label again. */
        fun setVkbCtrlLabel(active: Boolean) {}
        /** A PKB chord was sent: spend a tapped Shift as a typed key would. */
        fun consumePkbModifiersAfterChord(keyCode: Int) {}
        /** A VKB chord used the hand-set Shift: unshift the board as a typed letter would. */
        fun consumeVkbShiftAfterChord() {}
    }

    companion object {
        const val META_CTRL_LEFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val META_CTRL_RIGHT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        private const val META_SHIFT_LEFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        /** Ctrl+Y is delivered as Ctrl+Shift+Z (redo). */
        private const val META_CTRL_SHIFT = META_CTRL_LEFT or KeyEvent.META_SHIFT_ON

        /** The ?123 key (`key_switch_alpha_symbol`); held, it is the on-screen Ctrl key. */
        private const val SOFT_TO_SYMBOL = -3
        /** ?123's long-press code (`key_control`). */
        private const val SOFT_CTRL = -34
        private const val SOFT_SHIFT = -1
        private const val SOFT_DELETE = -5
        private const val SOFT_ENTER = 10

        fun withKeyAndMeta(keyEvent: KeyEvent, keyCode: Int, metaState: Int): KeyEvent =
            KeyEvent(keyEvent.downTime, keyEvent.eventTime, keyEvent.action, keyCode, keyEvent.repeatCount,
                metaState, keyEvent.deviceId, keyEvent.scanCode, keyEvent.flags, keyEvent.source)

        /** KEYCODE_A..Z for ASCII letters, else KeyEvent.keyCodeFromString (digits resolve, symbols give KEYCODE_UNKNOWN). */
        fun keyCodeForChar(codePoint: Int): Int {
            val lower = Character.toLowerCase(codePoint)
            if (lower in 'a'.code..'z'.code) return KeyEvent.KEYCODE_A + (lower - 'a'.code)
            return KeyEvent.keyCodeFromString("KEYCODE_" + Character.toString(Character.toUpperCase(lower).toChar()))
        }

        /**
         * Modifier keys pass through an active Ctrl untouched: Shift adds to the chord, Alt is
         * judged on the next key, Sym may be half of Alt+Sym. Ctrl itself is handled before this.
         */
        private fun isPassThroughModifier(keyCode: Int) = when (keyCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SYM, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_FUNCTION,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
            else -> false
        }
    }

    private var toSymbolHeld = false
    /** ?123's long-press fired during this hold; releasing ?123 latches Ctrl. */
    private var ctrlLongPressFired = false
    private var vkbLatched = false
    /** A chord was typed while ?123 was held, so its release must not latch. */
    private var chordedWhileToSymbolHeld = false
    /** This ?123 press only cleared the latch: its code input and release are swallowed so it does not open symbols. */
    private var cancelTapInProgress = false
    private class SoftChord(val sendCode: Int, val meta: Int, val usedShift: Boolean)
    /** Owed chord releases, keyed by Android key code (Shift may change the code point between press and release). */
    private val softChords = HashMap<Int, SoftChord>()
    private var vkbLabelShown = false

    private enum class Latch { OFF, ONE_SHOT, LOCKED }
    private var latch = Latch.OFF
    private var ctrlKeyDown = false
    /** A key was chorded (or swallowed) while Ctrl was held, so its release must not latch. */
    private var ctrlUsedWithKey = false
    /** The latch the current Ctrl press found; a tap on a latched Ctrl turns it off. */
    private var latchAtPress = Latch.OFF
    /** This press is the second of a double tap; its release locks (or, lock off, clears). */
    private var doubleTapPending = false
    private var firstTapDownTime = Long.MIN_VALUE
    /** Hardware chords whose key-up is still owed, by key code. */
    private val hardChords = HashMap<Int, Pair<Int, Int>>()
    /** Keys whose down was swallowed (Space with Ctrl on); their key-up is swallowed too. */
    private val swallowedKeys = HashSet<Int>()

    /** The MULTIFUNCTION key is held as Ctrl, so its key-up is remapped too even if Alt changed mid-hold. */
    private var multifunctionCtrlDown = false

    /**
     * `fn-no-key-up` only: this Fn hold's one-shot Ctrl was spent, so its remaining repeats must not
     * re-arm it. Cleared by a fresh press (repeat 0) or a plain key typed without Ctrl.
     */
    private var oneShotCtrlSpent = false

    /** ?123 is held as the on-screen Ctrl key (not latched). */
    val isVkbShiftChordActive: Boolean get() = toSymbolHeld && !vkbLatched

    private val isVkbCtrlActive: Boolean get() = toSymbolHeld || vkbLatched

    /** PKB Ctrl is latched (on for the next key, or locked). */
    val isInCtrlMode: Boolean get() = latch != Latch.OFF

    /**
     * Physical Ctrl held or latched; `ModifierState` reads it via `PhysicalKeyboardStateTracker.setCtrlStateSource`.
     * A multifunction key held as Ctrl arrives remapped to `KEYCODE_CTRL_LEFT` and sets [ctrlKeyDown],
     * so [multifunctionCtrlDown] need not be included. The span tracker's "CTRL_SPAN" is the Sym key.
     */
    val isCtrlActive: Boolean get() = ctrlKeyDown || latch != Latch.OFF

    val isCtrlLocked: Boolean get() = latch == Latch.LOCKED

    /** Drop all state without the control-mode UI (configuration change); the ?123 label and status icon still follow. */
    fun resetAll() = notifyingPkb {
        ctrlKeyDown = false
        ctrlUsedWithKey = false
        latch = Latch.OFF
        doubleTapPending = false
        firstTapDownTime = Long.MIN_VALUE
        hardChords.clear()
        swallowedKeys.clear()
        toSymbolHeld = false
        ctrlLongPressFired = false
        vkbLatched = false
        chordedWhileToSymbolHeld = false
        cancelTapInProgress = false
        softChords.clear()
        multifunctionCtrlDown = false
        oneShotCtrlSpent = false
        updateVkbLabel()
    }

    /** Drop both latches (a held key stays held) and hide the control-mode UI. */
    fun clearControlState() = notifyingPkb {
        latch = Latch.OFF
        vkbLatched = false
        ctrlLongPressFired = false
        host.hideControlModeUi()
        updateVkbLabel()
    }

    /** Every PKB Ctrl change goes through here, so the status icon hears each real change once. */
    private inline fun <T> notifyingPkb(block: () -> T): T {
        val activeBefore = isCtrlActive
        val lockedBefore = isCtrlLocked
        val result = block()
        if (activeBefore != isCtrlActive || lockedBefore != isCtrlLocked) host.onPkbCtrlStateChanged()
        return result
    }

    private fun updateVkbLabel() {
        val show = ctrlLongPressFired || vkbLatched
        if (show != vkbLabelShown) {
            vkbLabelShown = show
            host.setVkbCtrlLabel(show)
        }
    }

    /**
     * True when the IME must not type [code]: it already went out as a Ctrl chord on press
     * (otherwise Ctrl+A selects all and the "a" then replaces it).
     */
    fun suppressesSoftCodeInput(code: Int): Boolean =
        host.isVkbControlModeEnabled &&
            ((isVkbCtrlActive && (code >= 0 || code == SOFT_DELETE)) ||
                (cancelTapInProgress && code == SOFT_TO_SYMBOL))

    /** Returns true when the press was consumed by control mode. */
    fun handleSoftKeyDown(code: Int): Boolean {
        if (!host.isVkbControlModeEnabled) return false
        try {
            when {
                code == SOFT_TO_SYMBOL && vkbLatched -> {
                    // A tap on CTRL turns Ctrl off, and that is all it does.
                    vkbLatched = false
                    ctrlLongPressFired = false
                    cancelTapInProgress = true
                    host.hideControlModeUi()
                    return true
                }
                code == SOFT_TO_SYMBOL -> {
                    cancelTapInProgress = false
                    toSymbolHeld = true
                }
                code == SOFT_CTRL && cancelTapInProgress -> return true
                code == SOFT_CTRL -> {
                    // The long-press fired on a held ?123: Ctrl is armed, show it now.
                    ctrlLongPressFired = true
                    return true
                }
                isVkbCtrlActive && (code >= 0 || code == SOFT_DELETE) -> {
                    if (!vkbLatched && toSymbolHeld) chordedWhileToSymbolHeld = true
                    sendSoftChord(code, down = true)
                    return true
                }
                // Shift adds to the chord instead of cancelling Ctrl.
                code == SOFT_SHIFT && isVkbCtrlActive -> return false
            }
            clearControlState()
            return false
        } finally {
            updateVkbLabel()
        }
    }

    /** Returns true when the release was consumed by control mode. */
    fun handleSoftKeyUp(code: Int): Boolean {
        if (!host.isVkbControlModeEnabled) return false
        try {
            if (code == SOFT_TO_SYMBOL && cancelTapInProgress) {
                cancelTapInProgress = false
                return true
            }
            if (code == SOFT_TO_SYMBOL) {
                toSymbolHeld = false
                if (chordedWhileToSymbolHeld) {
                    // ?123 was used as a held Ctrl: its release ends it, and is not a ?123 tap.
                    chordedWhileToSymbolHeld = false
                    ctrlLongPressFired = false
                    vkbLatched = false
                    return true
                }
                if (ctrlLongPressFired) {
                    // Held until the long-press fired, then let go: latch Ctrl for the next key.
                    ctrlLongPressFired = false
                    vkbLatched = true
                    host.showControlModeUi()
                    return true
                }
                vkbLatched = false
            } else {
                if (code == SOFT_CTRL) return true
                if (softChordKeyCode(code)?.let { softChords.containsKey(it) } == true) {
                    sendSoftChord(code, down = false)
                    clearControlState()
                    return true
                }
                if (code == SOFT_SHIFT && isVkbCtrlActive) return false
            }
            clearControlState()
            return false
        } finally {
            updateVkbLabel()
        }
    }

    private fun sendSoftChord(code: Int, down: Boolean) {
        val keyCode = softChordKeyCode(code) ?: return
        if (down) {
            val shift = host.isVkbShiftActiveForChord
            val chord = chordFor(keyCode, shift)
            softChords[keyCode] = SoftChord(chord.first, chord.second, shift)
            host.sendKeyDownWithMeta(chord.first, chord.second)
        } else {
            val chord = softChords.remove(keyCode) ?: return
            host.sendKeyUpWithMeta(chord.sendCode, chord.meta)
            // Spent on release, not press: unshifting swaps the board under the finger, and the
            // release has to find the key it pressed.
            if (chord.usedShift) host.consumeVkbShiftAfterChord()
        }
    }

    /** The Android key code an on-screen [code] chords as, or null when it does not chord. */
    private fun softChordKeyCode(code: Int): Int? = when {
        code == SOFT_DELETE -> KeyEvent.KEYCODE_DEL
        code == SOFT_ENTER -> KeyEvent.KEYCODE_ENTER
        Constants.isLetterCode(code) -> keyCodeForChar(code) // isLetterCode is really "printable" (>= 32)
        else -> null
    }

    fun handleHardKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleHardKeyDown(keyCode, event.repeatCount, event.isPrintingKey, event.eventTime)

    fun handleHardKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleHardKeyUp(keyCode, event.repeatCount, event.isPrintingKey)

    /** Returns true when the key-down was consumed as a Ctrl key or a Ctrl chord. */
    fun handleHardKeyDown(keyCode: Int, repeatCount: Int, isPrintingKey: Boolean, eventTime: Long = 0L): Boolean = notifyingPkb {
        if (isCtrlKey(keyCode)) {
            if (host.ctrlKeySendsNoKeyUp) {
                // One-shot Ctrl, armed by the press or (if the press never arrives) the first repeat.
                // No tap or double tap: a repeating Ctrl here is just Fn being held.
                if (repeatCount == 0) {
                    oneShotCtrlSpent = false
                    ctrlKeyDown = true
                } else if (!ctrlKeyDown && !oneShotCtrlSpent) {
                    ctrlKeyDown = true
                }
            } else if (repeatCount == 0) {
                onCtrlPress(eventTime)
            }
            // Repeats of a held Ctrl mean nothing: holding Ctrl alone does not lock it.
            return@notifyingPkb true
        }
        if (hardChords.containsKey(keyCode) || swallowedKeys.contains(keyCode)) {
            // An auto-repeat of a key that already went out as a chord (or was swallowed).
            return@notifyingPkb true
        }
        if (!isCtrlActive) {
            // A key typed with no Ctrl armed: whatever Fn hold spent the one-shot is over.
            if (host.ctrlKeySendsNoKeyUp) oneShotCtrlSpent = false
            return@notifyingPkb false
        }
        if (isPassThroughModifier(keyCode)) return@notifyingPkb false
        if (!ctrlKeyDown && host.isPkbAltActiveForChord) {
            // Alt turns a latched Ctrl off: this key types its Alt character instead.
            dropLatch()
            return@notifyingPkb false
        }
        if (ctrlKeyDown) ctrlUsedWithKey = true
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            swallowedKeys += keyCode
            dropLatch()
            spendOneShotCtrl()
            return@notifyingPkb true
        }
        if (isPrintingKey || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DEL) {
            if (BuildConfig.DEBUG) Logger.info("CTRL_SHORTCUT_DEBUG", "handleHardKeyDown: chord keyCode=$keyCode")
            val chord = chordFor(keyCode, host.isPkbShiftActiveForChord)
            hardChords[keyCode] = chord
            host.sendKeyDownWithMeta(chord.first, chord.second)
            host.consumePkbModifiersAfterChord(keyCode)
            if (latch == Latch.ONE_SHOT) dropLatch()
            return@notifyingPkb true
        }
        // Other keys (arrows, Tab, Escape) pass through unchanged but spend a one-shot Ctrl.
        if (latch == Latch.ONE_SHOT) dropLatch()
        false
    }

    /** Returns true when the key-up was consumed as a Ctrl key or a Ctrl chord. */
    fun handleHardKeyUp(keyCode: Int, repeatCount: Int, isPrintingKey: Boolean): Boolean = notifyingPkb {
        if (isCtrlKey(keyCode) && host.ctrlKeySendsNoKeyUp) {
            // Should a release arrive after all, it ends the hold.
            ctrlKeyDown = false
            ctrlUsedWithKey = false
            dropLatch()
            return@notifyingPkb true
        }
        if (isCtrlKey(keyCode)) {
            if (ctrlKeyDown) onCtrlRelease()
            return@notifyingPkb true
        }
        hardChords.remove(keyCode)?.let { (sendCode, meta) ->
            if (BuildConfig.DEBUG) Logger.info("CTRL_SHORTCUT_DEBUG", "handleHardKeyUp: chord keyCode=$keyCode")
            host.sendKeyUpWithMeta(sendCode, meta)
            spendOneShotCtrl()
            return@notifyingPkb true
        }
        swallowedKeys.remove(keyCode)
    }

    /** Alt+Sym set to Ctrl: each one is a Ctrl tap, so double-tap lock and tap-to-clear work the same. */
    fun tapCtrlFromShortcut(eventTime: Long) = notifyingPkb {
        if (ctrlKeyDown) return@notifyingPkb
        onCtrlPress(eventTime)
        onCtrlRelease()
    }

    private fun onCtrlPress(eventTime: Long) {
        latchAtPress = latch
        ctrlKeyDown = true
        ctrlUsedWithKey = false
        val window = host.ctrlDoubleTapTimeoutMs
        doubleTapPending = latch == Latch.ONE_SHOT && firstTapDownTime != Long.MIN_VALUE &&
            eventTime - firstTapDownTime in 0..window
        // Any other press may start a double tap, except the tap that clears a locked Ctrl.
        firstTapDownTime = if (doubleTapPending || latch == Latch.LOCKED) Long.MIN_VALUE else eventTime
        // While held Ctrl is simply held; the release decides the latch.
        latch = Latch.OFF
    }

    private fun onCtrlRelease() {
        ctrlKeyDown = false
        latch = when {
            ctrlUsedWithKey -> Latch.OFF
            doubleTapPending -> if (host.isCtrlDoubleTapLockEnabled) Latch.LOCKED else Latch.OFF
            latchAtPress != Latch.OFF -> Latch.OFF   // a tap on a latched Ctrl turns it off
            else -> Latch.ONE_SHOT
        }
        if (ctrlUsedWithKey) firstTapDownTime = Long.MIN_VALUE
        ctrlUsedWithKey = false
        doubleTapPending = false
        latchAtPress = Latch.OFF
        if (latch != Latch.OFF) host.showControlModeUi() else host.hideControlModeUi()
    }

    private fun dropLatch() {
        if (latch == Latch.OFF) return
        latch = Latch.OFF
        firstTapDownTime = Long.MIN_VALUE
        host.hideControlModeUi()
    }

    /** `fn-no-key-up` only: no Ctrl release will come, so disarm when the chord ends. */
    private fun spendOneShotCtrl() {
        if (!host.ctrlKeySendsNoKeyUp || !ctrlKeyDown) return
        ctrlKeyDown = false
        ctrlUsedWithKey = false
        oneShotCtrlSpent = true
    }

    private fun isCtrlKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_CTRL_RIGHT || keyCode == KeyEvent.KEYCODE_CTRL_LEFT

    /** Ctrl+Y goes out as Ctrl+Shift+Z (redo). */
    private fun chordFor(keyCode: Int, shift: Boolean): Pair<Int, Int> {
        val shiftMeta = if (shift) META_SHIFT_LEFT else 0
        return if (keyCode == KeyEvent.KEYCODE_Y) Pair(KeyEvent.KEYCODE_Z, META_CTRL_SHIFT or shiftMeta)
        else Pair(keyCode, META_CTRL_LEFT or shiftMeta)
    }

    /**
     * A MULTIFUNCTION key set to Ctrl is rewritten to `KEYCODE_CTRL_LEFT`. Skipped when Alt (locked
     * included) is active at press time, so Alt+key still types its alt character ('0' on the mic key).
     */
    fun remapMultifunctionCtrlKey(event: KeyEvent): KeyEvent {
        val mapping = dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
            .keyRole(event.scanCode, event.keyCode, event.deviceId) ?: return event
        if (mapping.role != dev.bbkb.ime.core.device.config.model.KeyRole.MULTIFUNCTION) return event
        if (dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler.getConfiguredAction(mapping)
            != dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler.ACTION_CTRL) return event
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            multifunctionCtrlDown = !host.isAltActiveForCharacter
        }
        if (!multifunctionCtrlDown) return event
        if (event.action == KeyEvent.ACTION_UP) multifunctionCtrlDown = false
        return withKeyAndMeta(event, KeyEvent.KEYCODE_CTRL_LEFT,
            event.metaState or META_CTRL_LEFT)
    }

    /**
     * A config key with role `MODIFIER` and `treatAs="KEYCODE_CTRL_LEFT"` (or `_RIGHT`), such as the
     * Titan 2's Fn on scancode 251, is rewritten to that Ctrl key code whatever key code the ROM sends.
     */
    fun remapProfileCtrlKey(event: KeyEvent): KeyEvent {
        val mapping = dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
            .keyRole(event.scanCode, event.keyCode, event.deviceId) ?: return event
        if (mapping.role != dev.bbkb.ime.core.device.config.model.KeyRole.MODIFIER) return event
        val (ctrlCode, ctrlMeta) = when (mapping.treatAs) {
            "KEYCODE_CTRL_LEFT" -> KeyEvent.KEYCODE_CTRL_LEFT to META_CTRL_LEFT
            "KEYCODE_CTRL_RIGHT" -> KeyEvent.KEYCODE_CTRL_RIGHT to META_CTRL_RIGHT
            else -> return event
        }
        if (event.keyCode == ctrlCode) return event
        return withKeyAndMeta(event, ctrlCode, event.metaState or ctrlMeta)
    }

    /** The Ctrl key code the control_mode setting maps a Shift key onto, or 2 when the remap is off. */
    private fun controlModifierKey(): Int = when (host.controlModeSetting) {
        0 -> KeyEvent.KEYCODE_CTRL_RIGHT
        2 -> 2
        else -> KeyEvent.KEYCODE_CTRL_LEFT
    }

    /** Rewrite a Shift key (or Shift-modified key) as the configured Ctrl; unchanged when the setting is off. */
    fun remapModifierKeyEvent(keyCode: Int, keyEvent: KeyEvent): KeyEvent {
        val ctrl = controlModifierKey()
        if (keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT && ctrl == KeyEvent.KEYCODE_CTRL_RIGHT) return withKeyAndMeta(keyEvent, KeyEvent.KEYCODE_CTRL_RIGHT, META_CTRL_RIGHT)
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT && ctrl == KeyEvent.KEYCODE_CTRL_LEFT) return withKeyAndMeta(keyEvent, KeyEvent.KEYCODE_CTRL_LEFT, META_CTRL_LEFT)
        if (keyEvent.hasModifiers(KeyEvent.META_SHIFT_RIGHT_ON) && ctrl == KeyEvent.KEYCODE_CTRL_RIGHT) return withKeyAndMeta(keyEvent, keyEvent.keyCode, META_CTRL_RIGHT)
        if (keyEvent.hasModifiers(KeyEvent.META_SHIFT_LEFT_ON) && ctrl == KeyEvent.KEYCODE_CTRL_LEFT) return withKeyAndMeta(keyEvent, keyEvent.keyCode, META_CTRL_LEFT)
        return keyEvent
    }
}
