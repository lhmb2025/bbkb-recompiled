package dev.bbkb.ime.core.ime
import android.view.KeyEvent
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.Constants

/**
 * The Ctrl-shortcut / "control mode" state machine, for both surfaces:
 *
 * - **VKB control mode** (soft keys): holding Shift (-3) and pressing a letter, Enter or Delete
 *   sends the key as a Ctrl chord; Shift+Sym (-34) latches a sticky Ctrl mode with a notice.
 * - **PKB Ctrl shortcuts** (hardware keys): a physical Ctrl (or a Shift remapped to Ctrl by the
 *   control_mode setting, see [remapModifierKeyEvent]) chords with printing keys; holding Ctrl
 *   alone (key repeats) latches the sticky mode.
 *
 * This class owns the state and the decisions only. Everything that touches the editor or the
 * UI goes through [Host], which keeps the machine unit-testable on the plain JVM.
 */
class ControlModeController(private val host: Host) {

    interface Host {
        /** Whether the on-screen Shift+Sym control mode is enabled in settings. */
        val isVkbControlModeEnabled: Boolean
        /** The control_mode setting: 0 = right Shift acts as Ctrl, 1 = left Shift acts as Ctrl, 2 = off. */
        val controlModeSetting: Int
        fun sendKeyDownWithMeta(keyCode: Int, metaState: Int)
        fun sendKeyUpWithMeta(keyCode: Int, metaState: Int)
        /** Show the control-mode notice and hide the strip (no-op if already showing). */
        fun showControlModeUi()
        /** Hide the control-mode notice and restore the strip (no-op if not showing). */
        fun hideControlModeUi()
    }

    companion object {
        /** META_CTRL_ON | META_CTRL_LEFT_ON */
        const val META_CTRL_LEFT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        /** META_CTRL_ON | META_CTRL_RIGHT_ON */
        const val META_CTRL_RIGHT = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_RIGHT_ON
        /** Ctrl+Y is delivered as Ctrl+Shift+Z (redo). */
        private const val META_CTRL_SHIFT = META_CTRL_LEFT or KeyEvent.META_SHIFT_ON

        private const val SOFT_SHIFT = -3
        private const val SOFT_SYM = -34
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
    }

    // ---- VKB (soft key) state
    private var symKeyPressed = false
    private var inSymMode = false
    private var shiftPressed = false
    private var shiftLockMode = false

    // ---- PKB (hardware Ctrl) state
    private var ctrlRepeating = false
    private var inCtrlMode = false
    private var ctrlKeyDown = false
    private var ctrlUsedWithKey = false

    /** True while a soft Shift chord (not the sticky Sym mode) should route keys through here. */
    val isVkbShiftChordActive: Boolean get() = shiftPressed && !inSymMode

    val isInCtrlMode: Boolean get() = inCtrlMode

    /** Drop all mode state without touching the UI (configuration change). */
    fun resetAll() {
        ctrlKeyDown = false
        shiftPressed = false
        inSymMode = false
        inCtrlMode = false
    }

    /** Leave both sticky modes and hide the notice. */
    fun clearControlState() {
        inCtrlMode = false
        inSymMode = false
        host.hideControlModeUi()
    }

    /** The Alt+Sym shortcut and the settings menu toggle the sticky PKB mode directly. */
    fun toggleCtrlMode() {
        if (inCtrlMode) {
            clearControlState()
        } else {
            inCtrlMode = true
            ctrlKeyDown = false
            ctrlUsedWithKey = false
            ctrlRepeating = false
            host.showControlModeUi()
        }
    }

    // ------------------------------------------------------------------ VKB (soft keys)

    /** Returns true when the press was consumed by control mode. */
    fun handleSoftKeyDown(code: Int): Boolean {
        if (!host.isVkbControlModeEnabled) return false
        if (code == SOFT_SHIFT) {
            shiftPressed = true
        } else {
            if (code == SOFT_SYM) return true
            if ((shiftPressed || inSymMode) && (code >= 0 || code == SOFT_DELETE)) {
                if (!inSymMode && shiftPressed) shiftLockMode = true
                sendSoftChord(code, down = true)
                return true
            }
        }
        clearControlState()
        return false
    }

    /** Returns true when the release was consumed by control mode. */
    fun handleSoftKeyUp(code: Int): Boolean {
        if (!host.isVkbControlModeEnabled) return false
        if (code == SOFT_SHIFT) {
            shiftPressed = false
            if (shiftLockMode) {
                symKeyPressed = false
                inSymMode = false
                shiftLockMode = false
                return true
            }
            if (symKeyPressed) {
                // Shift+Sym released in that order: latch the sticky mode.
                symKeyPressed = false
                inSymMode = true
                host.showControlModeUi()
                return true
            }
            inSymMode = false
            shiftLockMode = false
        } else {
            if (code == SOFT_SYM) {
                symKeyPressed = true
                return true
            }
            if ((shiftPressed || inSymMode) && (code >= 0 || code == SOFT_DELETE)) {
                sendSoftChord(code, down = false)
                clearControlState()
                return true
            }
        }
        clearControlState()
        return false
    }

    private fun sendSoftChord(code: Int, down: Boolean) {
        val keyCode = when {
            code == SOFT_DELETE -> KeyEvent.KEYCODE_DEL
            code == SOFT_ENTER -> KeyEvent.KEYCODE_ENTER
            Constants.isLetterCode(code) -> keyCodeForChar(code) // isLetterCode is really "printable" (>= 32)
            else -> return
        }
        sendChord(keyCode, down)
    }

    // ------------------------------------------------------------------ PKB (hardware Ctrl)

    fun handleHardKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleHardKeyDown(keyCode, event.repeatCount, event.isPrintingKey)

    fun handleHardKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleHardKeyUp(keyCode, event.repeatCount, event.isPrintingKey)

    /** Returns true when the key-down was consumed as a Ctrl key or a Ctrl chord. */
    fun handleHardKeyDown(keyCode: Int, repeatCount: Int, isPrintingKey: Boolean): Boolean {
        if (isCtrlKey(keyCode)) {
            if (repeatCount == 0) ctrlKeyDown = true
            else if (repeatCount > 1) ctrlRepeating = true
            return true
        }
        if (ctrlKeyDown || inCtrlMode) {
            if (ctrlKeyDown && repeatCount > 0) return true
            if (!inCtrlMode && ctrlKeyDown) ctrlUsedWithKey = true
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                clearControlState()
                return true
            }
            if (isPrintingKey || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DEL) {
                if (BuildConfig.DEBUG) Logger.info("CTRL_SHORTCUT_DEBUG", "handleHardKeyDown: chord keyCode=$keyCode")
                sendChord(keyCode, down = true)
                return true
            }
        }
        clearControlState()
        return false
    }

    /** Returns true when the key-up was consumed as a Ctrl key or a Ctrl chord. */
    fun handleHardKeyUp(keyCode: Int, repeatCount: Int, isPrintingKey: Boolean): Boolean {
        if (isCtrlKey(keyCode) && repeatCount >= 0) {
            if (repeatCount == 0) {
                ctrlKeyDown = false
                when {
                    ctrlUsedWithKey -> {
                        // Ctrl was chorded: a plain release ends everything.
                        ctrlRepeating = false
                        inCtrlMode = false
                        ctrlUsedWithKey = false
                        host.hideControlModeUi()
                    }
                    !ctrlRepeating -> {
                        inCtrlMode = false
                        ctrlUsedWithKey = false
                        host.hideControlModeUi()
                    }
                    else -> {
                        // Ctrl was held alone until it repeated: latch the sticky mode.
                        ctrlRepeating = false
                        inCtrlMode = true
                        host.showControlModeUi()
                    }
                }
            } else {
                ctrlRepeating = true
            }
            return true
        }
        if (ctrlKeyDown || inCtrlMode) {
            if (ctrlKeyDown && repeatCount > 0) return true
            if (inCtrlMode && !ctrlKeyDown) inCtrlMode = false
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                clearControlState()
                return true
            }
            if (isPrintingKey || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DEL) {
                if (BuildConfig.DEBUG) Logger.info("CTRL_SHORTCUT_DEBUG", "handleHardKeyUp: chord keyCode=$keyCode")
                sendChord(keyCode, down = false)
                clearControlState()
                return true
            }
        }
        clearControlState()
        return false
    }

    private fun isCtrlKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_CTRL_RIGHT || keyCode == KeyEvent.KEYCODE_CTRL_LEFT

    /** Ctrl+[keyCode], except Ctrl+Y which is delivered as Ctrl+Shift+Z (redo). */
    private fun sendChord(keyCode: Int, down: Boolean) {
        val (code, meta) = if (keyCode == KeyEvent.KEYCODE_Y) Pair(KeyEvent.KEYCODE_Z, META_CTRL_SHIFT) else Pair(keyCode, META_CTRL_LEFT)
        if (down) host.sendKeyDownWithMeta(code, meta) else host.sendKeyUpWithMeta(code, meta)
    }

    // ------------------------------------------------------------------ control_mode remap

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
