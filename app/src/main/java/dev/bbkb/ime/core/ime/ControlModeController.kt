package dev.bbkb.ime.core.ime
import android.view.KeyEvent
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.Constants

/**
 * The Ctrl-shortcut / "control mode" state machine, for both surfaces:
 *
 * - **VKB control mode** (soft keys, behind the "Use the ?123 key as the Ctrl key" setting):
 *   holding ?123 (-3) and pressing a letter, Enter or Delete sends the key as a Ctrl chord.
 *   Holding ?123 until its long-press fires (-34, `key_control`) and letting go latches Ctrl for
 *   the next key; the ?123 key reads CTRL while it is armed ([Host.setVkbCtrlLabel]).
 * - **PKB Ctrl** (hardware keys): a physical Ctrl — or anything remapped onto one: a Shift via
 *   the control_mode setting ([remapModifierKeyEvent]), the multifunction key, a profile's Fn
 *   key, or the Alt+Sym shortcut ([tapCtrlFromShortcut]) — works like Shift and Alt: hold it and
 *   press a key for a chord, tap it to turn it on for the next key, double-tap it to lock it on
 *   (when "Double-tap Ctrl to lock" is on), tap it again to turn it off.
 *
 * Both surfaces add Shift to the chord when it is on for the next key or held (Ctrl+Shift+Z);
 * a locked Shift / caps lock does not, so caps lock never turns Ctrl+C into Ctrl+Shift+C.
 *
 * This class owns the state and the decisions only. Everything that touches the editor or the
 * UI goes through [Host], which keeps the machine unit-testable on the plain JVM.
 */
class ControlModeController(private val host: Host) {

    interface Host {
        /** Whether the on-screen ?123-as-Ctrl control mode is enabled in settings. */
        val isVkbControlModeEnabled: Boolean
        /** The control_mode setting: 0 = right Shift acts as Ctrl, 1 = left Shift acts as Ctrl, 2 = off. */
        val controlModeSetting: Int
        /**
         * Whether Alt counts as active for character interpretation right now —
         * `ModifierState.isAltActiveForCharacter`. Read only by [remapMultifunctionCtrlKey], and only
         * after that method's three early returns, so building a snapshot to answer it costs nothing
         * on the ordinary key path.
         */
        val isAltActiveForCharacter: Boolean
        /**
         * The device's Ctrl key sends no key-up and auto-repeats while held (the profile's
         * `fn-no-key-up` quirk; the Titan 2 Elite's Fn). Ctrl is then a one-shot modifier for the
         * next key, and it can be neither tapped nor double-tapped. False everywhere else.
         */
        val ctrlKeySendsNoKeyUp: Boolean get() = false
        /** The "Double-tap Ctrl to lock" setting. On by default, like Shift's and Alt's. */
        val isCtrlDoubleTapLockEnabled: Boolean get() = true
        /** How soon a second Ctrl press must follow the first to be a double tap; Shift's and Alt's window. */
        val ctrlDoubleTapTimeoutMs: Long get() = 600L
        /**
         * A physical Alt is on and not yet spent: held, tapped or locked, from the modifier keys'
         * own state. An Alt already spent on a key (the Alt of an Alt+Sym) does not count.
         */
        val isPkbAltActiveForChord: Boolean get() = false
        /** A physical Shift is held or tapped for the next key. A locked Shift (caps lock) is not. */
        val isPkbShiftActiveForChord: Boolean get() = false
        /** The on-screen keyboard is shifted by hand for the next key, or Shift is held; not auto-caps or caps lock. */
        val isVkbShiftActiveForChord: Boolean get() = false
        fun sendKeyDownWithMeta(keyCode: Int, metaState: Int)
        fun sendKeyUpWithMeta(keyCode: Int, metaState: Int)
        /** Ctrl was latched on (VKB long-press, or a PKB tap / double tap). */
        fun showControlModeUi()
        /** Ctrl's latch was dropped. */
        fun hideControlModeUi()
        /** The PKB Ctrl state ([isCtrlActive] / [isCtrlLocked]) changed; the status icon follows it. */
        fun onPkbCtrlStateChanged() {}
        /** The on-screen ?123 key should read CTRL ([active]) or its own label again. */
        fun setVkbCtrlLabel(active: Boolean) {}
        /** A PKB chord was sent for [keyCode]: spend a tapped Shift the way a typed key would. */
        fun consumePkbModifiersAfterChord(keyCode: Int) {}
        /** A VKB chord used the hand-set Shift: return the board to unshifted, as a typed letter would. */
        fun consumeVkbShiftAfterChord() {}
    }

    companion object {
        /** META_CTRL_ON | META_CTRL_LEFT_ON */
        const val META_CTRL_LEFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        /** META_CTRL_ON | META_CTRL_RIGHT_ON */
        const val META_CTRL_RIGHT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        /** META_SHIFT_ON | META_SHIFT_LEFT_ON, added to a chord when Shift is on. */
        private const val META_SHIFT_LEFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        /** Ctrl+Y is delivered as Ctrl+Shift+Z (redo). */
        private const val META_CTRL_SHIFT = META_CTRL_LEFT or KeyEvent.META_SHIFT_ON

        /** The ?123 key (`key_switch_alpha_symbol`); held, it is the on-screen Ctrl key. */
        private const val SOFT_TO_SYMBOL = -3
        /** ?123's long-press code (`key_control`): latches Ctrl for the next key. */
        private const val SOFT_CTRL = -34
        private const val SOFT_SHIFT = -1
        private const val SOFT_DELETE = -5
        private const val SOFT_ENTER = 10

        /** Copy of [keyEvent] with a different key code and meta state; everything else preserved. */
        fun withKeyAndMeta(keyEvent: KeyEvent, keyCode: Int, metaState: Int): KeyEvent =
            KeyEvent(keyEvent.downTime, keyEvent.eventTime, keyEvent.action, keyCode, keyEvent.repeatCount,
                metaState, keyEvent.deviceId, keyEvent.scanCode, keyEvent.flags, keyEvent.source)

        /** The hardware key code for a printable code point: KEYCODE_A..Z for ASCII letters, otherwise KeyEvent.keyCodeFromString (digits resolve, symbols become KEYCODE_UNKNOWN), exactly as the original charToKeyCode did. */
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

    // ---- VKB (soft key) state
    /** ?123 is held down. */
    private var toSymbolHeld = false
    /** ?123's long-press fired during this hold; letting go of ?123 latches Ctrl. */
    private var ctrlLongPressFired = false
    /** Ctrl is latched for the next on-screen key. */
    private var vkbLatched = false
    /** A chord was typed while ?123 was held, so its release must not latch. */
    private var chordedWhileToSymbolHeld = false
    /**
     * This press of ?123 (reading CTRL) only turned the latch off: the key's code input and its
     * release are swallowed too, so the tap does not also switch to the symbols.
     */
    private var cancelTapInProgress = false
    /** An on-screen chord that went down and whose release is still owed. */
    private class SoftChord(val sendCode: Int, val meta: Int, val usedShift: Boolean)
    /**
     * Owed on-screen chords by Android key code — not by the key's code point, because Shift can
     * change the case of the key between its press and its release.
     */
    private val softChords = HashMap<Int, SoftChord>()
    private var vkbLabelShown = false

    // ---- PKB (hardware Ctrl) state
    private enum class Latch { OFF, ONE_SHOT, LOCKED }
    private var latch = Latch.OFF
    private var ctrlKeyDown = false
    /** A key was chorded (or swallowed) while Ctrl was held, so its release must not latch. */
    private var ctrlUsedWithKey = false
    /** The latch the current Ctrl press found; a tap on a latched Ctrl turns it off. */
    private var latchAtPress = Latch.OFF
    /** This press is the second of a double tap; its release locks (or, lock off, clears). */
    private var doubleTapPending = false
    /** When the first press of a possible double tap went down. */
    private var firstTapDownTime = Long.MIN_VALUE
    /** Hardware chords that went down and whose key-up is still owed, by key code. */
    private val hardChords = HashMap<Int, Pair<Int, Int>>()
    /** Keys whose down this class swallowed (Space with Ctrl on); their key-up is swallowed too. */
    private val swallowedKeys = HashSet<Int>()

    /**
     * True while the device's MULTIFUNCTION key is held down remapped as Ctrl, so its key-up is
     * remapped consistently even if the Alt state changed mid-hold.
     *
     * <p>Phase 1f moved this here from `BlackBerryIME`, which was the last piece of Ctrl state
     * outside this class. It was never anything but Ctrl state — the flag that says "this key is
     * currently a Ctrl key" — and keeping it next to the machine that answers [isCtrlActive] is what
     * makes this class the single Ctrl owner that `ModifierState.isCtrlActive()` reads from.
     */
    private var multifunctionCtrlDown = false

    /**
     * `fn-no-key-up` devices only: the one-shot Ctrl of the current Fn hold has been spent on a
     * chord, so the hold's remaining repeats must not arm it again. Cleared by a fresh Ctrl press
     * (repeat 0) or by a plain key typed without Ctrl, which means the hold is over.
     */
    private var oneShotCtrlSpent = false

    /** True while ?123 is held as the on-screen Ctrl key (not the latched mode). */
    val isVkbShiftChordActive: Boolean get() = toSymbolHeld && !vkbLatched

    /** On-screen Ctrl is on: ?123 held, or latched for the next key. */
    private val isVkbCtrlActive: Boolean get() = toSymbolHeld || vkbLatched

    /** PKB Ctrl is latched (on for the next key, or locked). */
    val isInCtrlMode: Boolean get() = latch != Latch.OFF

    /**
     * **The one Ctrl question.** True while a physical Ctrl key is held or Ctrl is latched (on for
     * the next key, or locked) — this class owns both, and nothing else in the app keeps Ctrl state
     * of its own: the span tracker's "CTRL_SPAN" is really the Sym key (key code 63,
     * `META_SYM_ON`), and [multifunctionCtrlDown] lives here too.
     * `dev.bbkb.ime.core.keyevent.ModifierState` reads Ctrl from here through
     * `PhysicalKeyboardStateTracker.setCtrlStateSource`.
     *
     * Deliberately not widened to include [multifunctionCtrlDown]: a multifunction key held as Ctrl
     * arrives at [handleHardKeyDown] as a real `KEYCODE_CTRL_LEFT` (that is what the remap is for)
     * and sets [ctrlKeyDown] on the way through, so it is already counted.
     */
    val isCtrlActive: Boolean get() = ctrlKeyDown || latch != Latch.OFF

    /** PKB Ctrl is locked on (double tap). */
    val isCtrlLocked: Boolean get() = latch == Latch.LOCKED

    /**
     * Drop all mode state without the control-mode UI (configuration change). The ?123 label and
     * the status icon still follow, since they show state that no longer exists.
     */
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

    /**
     * Everything that changes PKB Ctrl goes through here, so the status icon hears about every
     * change and only about real ones.
     */
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

    // ------------------------------------------------------------------ VKB (soft keys)

    /**
     * Whether the IME must NOT type [code] for an on-screen key: on-screen Ctrl is on, so the
     * letter, Enter or Delete already went to the app as a Ctrl chord on press. The original
     * BlackBerry keyboard made exactly this test before typing; without it Ctrl+A selected
     * everything and the "a" then replaced it.
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

    // ------------------------------------------------------------------ PKB (hardware Ctrl)

    fun handleHardKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleHardKeyDown(keyCode, event.repeatCount, event.isPrintingKey, event.eventTime)

    fun handleHardKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleHardKeyUp(keyCode, event.repeatCount, event.isPrintingKey)

    /** Returns true when the key-down was consumed as a Ctrl key or a Ctrl chord. */
    fun handleHardKeyDown(keyCode: Int, repeatCount: Int, isPrintingKey: Boolean, eventTime: Long = 0L): Boolean = notifyingPkb {
        if (isCtrlKey(keyCode)) {
            if (host.ctrlKeySendsNoKeyUp) {
                // One-shot Ctrl: armed by the press — or, should the press itself never arrive, by
                // the first repeat. No tap, no double tap: on this device a repeating Ctrl is just
                // the Fn key being held.
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
            // On for the next key: this was it.
            if (latch == Latch.ONE_SHOT) dropLatch()
            return@notifyingPkb true
        }
        // Any other key (arrows, Tab, Escape...) goes through as it is. It spends a Ctrl that was
        // on for the next key; a locked Ctrl stays locked.
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

    /**
     * The Alt+Sym shortcut set to Ctrl: one Alt+Sym is one tap of a Ctrl key, so a second one in
     * the double-tap window locks Ctrl and one more turns it off — exactly what a Ctrl key does.
     */
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
        // A press that is not the second half of a double tap may be the first half of one —
        // unless it is the tap that turns a locked Ctrl off.
        firstTapDownTime = if (doubleTapPending || latch == Latch.LOCKED) Long.MIN_VALUE else eventTime
        // Pressing Ctrl takes it off any latch: while held it is simply held, and the release decides.
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

    /**
     * `fn-no-key-up` devices: the chord that used the one-shot Ctrl has ended and no Ctrl
     * release will ever come, so disarm here instead. A no-op everywhere else.
     */
    private fun spendOneShotCtrl() {
        if (!host.ctrlKeySendsNoKeyUp || !ctrlKeyDown) return
        ctrlKeyDown = false
        ctrlUsedWithKey = false
        oneShotCtrlSpent = true
    }

    private fun isCtrlKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_CTRL_RIGHT || keyCode == KeyEvent.KEYCODE_CTRL_LEFT

    /** The key and meta a Ctrl+[keyCode] chord goes out as: Ctrl+Y is Ctrl+Shift+Z (redo). */
    private fun chordFor(keyCode: Int, shift: Boolean): Pair<Int, Int> {
        val shiftMeta = if (shift) META_SHIFT_LEFT else 0
        return if (keyCode == KeyEvent.KEYCODE_Y) Pair(KeyEvent.KEYCODE_Z, META_CTRL_SHIFT or shiftMeta)
        else Pair(keyCode, META_CTRL_LEFT or shiftMeta)
    }

    // ------------------------------------------------------------------ control_mode remap

    /**
     * When the device's MULTIFUNCTION key is configured to act as Ctrl, rewrite its events to
     * `KEYCODE_CTRL_LEFT` so the physical-Ctrl machinery (chording, Ctrl+C/V/X shortcuts) treats it
     * as a real Ctrl key — the same trick [remapModifierKeyEvent] uses for Shift. Skipped while Alt
     * is active at press time so Alt+key can still type the mapping's alt character (e.g. '0' on the
     * Key2 mic key).
     *
     * Phase 1f moved this out of `BlackBerryIME` together with its [multifunctionCtrlDown] latch: it
     * is a Ctrl decision over Ctrl state, and this class is the Ctrl owner. The Alt question comes
     * from [Host.isAltActiveForCharacter] — asked only here, and only after the three early returns
     * below, so the ordinary key path pays nothing for it.
     *
     * The Alt check is `ModifierState.isAltActiveForCharacter` rather than the bit test it replaced,
     * which counts a LOCKED Alt that the old spelling missed; `ModifierReadSiteEquivalenceTest`
     * records that narrow change and why it is the answer the comment above always described.
     */
    fun remapMultifunctionCtrlKey(event: KeyEvent): KeyEvent {
        val mapping = dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
            .getInstance().resolve(event.scanCode, event.keyCode) ?: return event
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
     * A key the device config declares as a Ctrl-like modifier — role `MODIFIER` with
     * `treatAs="KEYCODE_CTRL_LEFT"` (or `_RIGHT`), the Titan 2's Fn key on scancode 251 — is
     * rewritten to that Ctrl key code, so the physical-Ctrl machinery treats it as Ctrl whatever key
     * code the ROM attached to it. Keys already arriving as that Ctrl are left alone, and so is
     * every key on a device whose config declares no such modifier (the KEY2, the MP01).
     */
    fun remapProfileCtrlKey(event: KeyEvent): KeyEvent {
        val mapping = dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
            .getInstance().resolve(event.scanCode, event.keyCode) ?: return event
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

    /**
     * Rewrite a Shift key (or a Shift-modified key) as the configured Ctrl so the physical-Ctrl
     * machinery treats it as a real Ctrl key. Returns [keyEvent] unchanged when the setting is off.
     */
    fun remapModifierKeyEvent(keyCode: Int, keyEvent: KeyEvent): KeyEvent {
        val ctrl = controlModifierKey()
        if (keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT && ctrl == KeyEvent.KEYCODE_CTRL_RIGHT) return withKeyAndMeta(keyEvent, KeyEvent.KEYCODE_CTRL_RIGHT, META_CTRL_RIGHT)
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT && ctrl == KeyEvent.KEYCODE_CTRL_LEFT) return withKeyAndMeta(keyEvent, KeyEvent.KEYCODE_CTRL_LEFT, META_CTRL_LEFT)
        if (keyEvent.hasModifiers(KeyEvent.META_SHIFT_RIGHT_ON) && ctrl == KeyEvent.KEYCODE_CTRL_RIGHT) return withKeyAndMeta(keyEvent, keyEvent.keyCode, META_CTRL_RIGHT)
        if (keyEvent.hasModifiers(KeyEvent.META_SHIFT_LEFT_ON) && ctrl == KeyEvent.KEYCODE_CTRL_LEFT) return withKeyAndMeta(keyEvent, keyEvent.keyCode, META_CTRL_LEFT)
        return keyEvent
    }
}
