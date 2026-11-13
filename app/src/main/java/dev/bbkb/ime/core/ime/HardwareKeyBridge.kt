package dev.bbkb.ime.core.ime
import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.core.device.config.model.ScancodeMapping
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorManager
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorService
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.keyevent.AltSymShortcutHandler
import dev.bbkb.ime.core.keyevent.AuxCharacterResolver
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.keyevent.ModifierState
import dev.bbkb.ime.core.keyevent.ResolvedKey
import dev.bbkb.ime.core.textinput.InputMethodHelper
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.keyevent.KeyEventProcessor
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController

/**
 * Physical-keyboard side channels that are not ordinary key events: KeyInterceptorService
 * callbacks (EMOJI/SYM/MIC keys, all-keys pre-processing), the Alt+Sym shortcut handler, the
 * per-device alt-character resolver and the voice-key actions. Ordinary key events enter through
 * [BlackBerryIME.onKeyDown] and [KeyEventProcessor].
 */
class HardwareKeyBridge(private val ime: BlackBerryIME) {

    private companion object {
        const val TAG = "HardwareKeyBridge"
        /** Alias of [ModifierState.ALT_ANY_MASK] (includes ALT_LOCKED 0x200); tests name it. */
        const val ALT_ANY_MASK = ModifierState.ALT_ANY_MASK
        const val EMOJI_BOARD = ResolvedKey.DEFAULT_EMOJI_BOARD_ID
        const val VOICE_BOARD = ResolvedKey.DEFAULT_VOICE_BOARD_ID
    }

    val altSymShortcutHandler = AltSymShortcutHandler()
    private var cachedAuxCharacterResolver: AuxCharacterResolver? = null

    /**
     * Wire the Alt+Sym callbacks; requires [DeviceProfile]. Board actions must not gate on
     * [BlackBerryIME.isInputViewShown] alone: on a PKB device the window is usually down and
     * `showSoftInputFromInputMethod` is async, so the check is still false when the action runs.
     */
    fun install() {
        // Ctrl state comes from ControlModeController. Nullable: install() runs early in IME
        // construction, and tests build a bridge over an IME with no tracker.
        val tracker: PhysicalKeyboardStateTracker? = ime.getPhysicalKeyboardStateTracker()
        tracker?.setCtrlStateSource(object : PhysicalKeyboardStateTracker.CtrlStateSource {
            override fun isCtrlActive(): Boolean = ime.getControlMode().isCtrlActive
            override fun isCtrlLocked(): Boolean = ime.getControlMode().isCtrlLocked
        })

        altSymShortcutHandler.setCallback(object : AltSymShortcutHandler.ActionCallback {
            override fun openSymbolKeyboard() {
                if (canShowBoard()) {
                    ime.getKeyboardSwitcher().onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), false, true)
                }
            }
            override fun switchLanguage() {
                ime.switchToNextSubtype(InputSource.HARDWARE)
            }
            override fun quickSwitchLanguage() {
                ime.updateSuggestionsFromSubtype(InputSource.HARDWARE)
            }
            override fun toggleEmojiPicker() {
                if (canShowBoard()) {
                    val uibm = ime.getKeyboardSwitcher().getUnifiedInputBoardManager()
                    if (uibm != null && ime.isUimEnabled()) {
                        uibm.requestBoard(EMOJI_BOARD)
                    } else {
                        ime.getKeyboardSwitcher().onEmojiKeyPressed()
                    }
                }
            }
            override fun toggleClipboard() {
                if (canShowBoard()) {
                    toggleBoard(ClipboardController.KEY_CODE) {
                        ime.clipboardController?.let { if (it.isShowing) it.hide() else it.show() }
                    }
                }
            }
            override fun toggleFcc() {
                if (canShowBoard()) toggleBoard(FccController.KEY_CODE) { ime.fccController?.toggle() }
            }
            override fun toggleNumberPad() {
                if (canShowBoard()) toggleBoard(NumberPadController.KEY_CODE) { ime.numberPadController?.toggle() }
            }
            override fun toggleInputMenu() {
                if (canShowBoard()) ime.getUiCoordinator().toggleInputMenu()
            }
            override fun tapCtrl() {
                ime.getControlMode().tapCtrlFromShortcut(android.os.SystemClock.uptimeMillis())
            }
        })
    }

    /** The IME window is up, or show-on-keypress was just asked to raise it (see [install]). */
    private fun canShowBoard(): Boolean = ime.isInputViewShown() || ime.requestShowOnKeyPress()

    /** Toggle a UIM board through the coordinator, or run the board's own [fallback] toggle when the UIM is off. */
    private inline fun toggleBoard(boardKeyCode: Int, fallback: () -> Unit) {
        val uibm = ime.getKeyboardSwitcher().getUnifiedInputBoardManager()
        if (uibm != null && ime.isUimEnabled()) uibm.requestBoard(boardKeyCode) else fallback()
    }

    /**
     * Register KeyInterceptor callbacks on PKB devices. Idempotent; re-run from [BlackBerryIME.loadSettings]
     * so the "special key support" switch applies next session. The all-keys callback is always on:
     * it keeps the modifier tracker in sync with the Alt the service consumes. Only the special-key
     * callback follows the preference; with it off, board keys fall through to the ordinary pipeline.
     */
    fun registerInterceptorCallbacks() {
        if (!DeviceProfile.current().isPkbDevice()) {
            unregisterInterceptorCallbacks()
            return
        }

        KeyInterceptorService.setAllKeysCallback(allKeysCallback)
        val preprocessEnabled = KeyInterceptorManager.isPreprocessAllKeysEnabled(ime)
        KeyInterceptorService.setPreprocessAllKeysEnabled(preprocessEnabled)
        Logger.info(BlackBerryIME.LOG_TAG, "KeyInterceptor: preprocess all keys = $preprocessEnabled")

        val specialKeysEnabled = KeyInterceptorManager.isFeatureEnabled(ime)
        if (specialKeysEnabled) {
            KeyInterceptorManager.setCallback(keyInterceptorCallback)
        } else {
            KeyInterceptorManager.clearCallback()
        }
        Logger.info(BlackBerryIME.LOG_TAG, "KeyInterceptor: special key support = $specialKeysEnabled")
    }

    /**
     * Called from `releaseResources` so a dead IME never leaves the service eating Alt uncompensated
     * or retained in a static. The service's `onDestroy` must not do this: toggling it would unregister a live IME.
     */
    fun unregisterInterceptorCallbacks() {
        KeyInterceptorManager.clearCallback()
        KeyInterceptorService.setAllKeysCallback(null)
        KeyInterceptorService.setPreprocessAllKeysEnabled(false)
    }

    /** Cached once the profile has an alt-mappings table; otherwise a fresh fallback per call. */
    fun auxCharacterResolver(): AuxCharacterResolver {
        cachedAuxCharacterResolver?.let { return it }
        return try {
            val profile = DeviceProfile.current()
            val table = profile.getAltMappingsTable(ime)
            val layout = profile.getKeypadLayout()
            val layoutOverrides = profile.getLayoutAltOverridesTable(ime, layout)
            val resolver = AuxCharacterResolver.Builder()
                .withAltMappingsTable(table)
                .withLayoutOverridesTable(layoutOverrides)
                .withCombiningAccentFilter(true)
                .build()
            if (table != null) cachedAuxCharacterResolver = resolver
            resolver
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to build AuxCharacterResolver, using fallback", e)
            AuxCharacterResolver.Builder().build()
        }
    }

    /**
     * Toggle voice input without the UIM (the mic key when the UIM is off); also used by
     * [KeyEventProcessor]. A plain IME switch cannot close voice, so the toggle must be here.
     */
    fun triggerVoiceInput() {
        if (!ime.isInputActive || !ime.isInputViewShown()) {
            launchVoiceAssistant()
            return
        }
        // The toggle is independent of the UIM; only the UIM bookkeeping below needs it enabled.
        val voice = ime.voiceInputController
        if (voice == null) {
            InputMethodHelper.getInstance().switchToVoiceIme(ime)
            return
        }
        // Asked before the toggle: the open branch raises the UIM bar, which a refused open must not do.
        if (voiceKeyRefused()) return
        val wasInVoiceMode = voice.isInVoiceMode()
        voice.toggleVoiceInput()
        if (ime.isUimEnabled()) {
            val unifiedManager = ime.getKeyboardSwitcher().getUnifiedInputBoardManager() ?: return
            if (wasInVoiceMode) {
                // Scoped to the voice board, or the coordinator would clear another board's state.
                unifiedManager.reportBoardClosed(voice.keyCode)
            } else if (voice.isInVoiceMode()) {
                unifiedManager.setActiveComponent(voice)
                if (!unifiedManager.isShowing()) unifiedManager.show(false)
            }
        }
    }

    /**
     * True when there is no recognition service: the notice is shown and the press must open nothing.
     * A press that would close voice input is never refused.
     */
    fun voiceKeyRefused(): Boolean {
        val voice = ime.voiceInputController ?: return false
        if (voice.isInVoiceMode() || voice.isShowing) return false
        return !voice.ensureRecognitionService()
    }

    /**
     * Mic key outside a text field: start the system voice assistant. Tries each action and catches
     * `ActivityNotFoundException`; `resolveActivity` would return null under package visibility.
     */
    fun launchVoiceAssistant() {
        for (action in arrayOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, Intent.ACTION_SEARCH_LONG_PRESS)) {
            try {
                ime.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // No handler for this action; try the next candidate.
            } catch (e: Exception) {
                Logger.debugWithException(BlackBerryIME.LOG_TAG, e, "Failed to launch voice assistant")
                return
            }
        }
        Logger.debug(BlackBerryIME.LOG_TAG, "No voice assistant available on this device")
    }

    private val keyInterceptorCallback = object : KeyInterceptorService.KeyEventCallback {
        override fun onSpecialKeyPressed(keyType: KeyInterceptorService.SpecialKeyType, metaState: Int): Boolean {
            return handleSpecialKey(keyType, metaState, null)
        }

        override fun onSpecialKeyPressed(keyType: KeyInterceptorService.SpecialKeyType, metaState: Int,
                mapping: ScancodeMapping?): Boolean {
            // The profile's entry carries the Alt character and the board id.
            return handleSpecialKey(keyType, metaState, mapping)
        }

        private fun handleSpecialKey(keyType: KeyInterceptorService.SpecialKeyType, metaState: Int,
                mapping: ScancodeMapping?): Boolean {
            // A hidden window means "summon it" for SYM (the window is usually down on a PKB device).
            // If show-on-keypress declines, the key falls through to the ordinary pipeline.
            if (!ime.isInputViewShown()) {
                if (keyType != KeyInterceptorService.SpecialKeyType.SYM) return false
                if (!ime.requestShowOnKeyPress()) return false
            }

            // Alt-character questions use the interpreted state; the SYM chord uses the modifier keys' own state.
            val tracker = ime.getPhysicalKeyboardStateTracker()
            val modifiers = tracker.getModifierState(metaState)
            val altPressed = modifiers.isAltActiveForCharacter()
            val keyboardSwitcher = ime.getKeyboardSwitcher()
            val inputLogic = ime.getInputLogic()

            var handled = false
            when (keyType) {
                KeyInterceptorService.SpecialKeyType.EMOJI -> {
                    if (!altPressed) {
                        val emojiBoardId = if (mapping != null && mapping.boardId != 0) mapping.boardId else EMOJI_BOARD
                        val uibm = keyboardSwitcher.getUnifiedInputBoardManager()
                        if (uibm != null && ime.isUimEnabled()) {
                            uibm.requestBoard(emojiBoardId)
                        } else {
                            keyboardSwitcher.onEmojiKeyPressed()
                        }
                    } else {
                        val emojiAltChar = resolveAltChar(mapping, ResolvedKey.PSEUDO_KEYCODE_EMOJI, "0")
                        inputLogic.commitTypedWord(ime.getSettingsManager().getSettingsValues(), "", InputSource.HARDWARE)
                        inputLogic.mRichInputConnection.commitText(emojiAltChar, 1)
                    }
                    handled = true
                }
                KeyInterceptorService.SpecialKeyType.SYM -> {
                    // Not the masked state: the symbol board's Alt page adds Alt to the mask, which is paging, not a chord.
                    if (!altSymShortcutHandler.detectAndExecute(modifiers.getChordMetaState())) {
                        keyboardSwitcher.onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), false, true)
                    }
                    handled = true
                }
                KeyInterceptorService.SpecialKeyType.MIC -> {
                    val micAltChar = resolveAltChar(mapping, ResolvedKey.PSEUDO_KEYCODE_VOICE, ".")
                    val dictationEnabled = PrefsManager.getPrefs().getBoolean("pref_voice_input_key", true)
                    // A mic key that doubles as a digit types it in numeric fields regardless of
                    // dictation and Alt, as KeyEventConverter does on the ordinary path.
                    val digitInNumericField = micKeyTypesDigit(
                        ime.getCurrentInputEditorInfo()?.inputType ?: 0, micAltChar)
                    val shouldTriggerVoice = !digitInNumericField
                        && (if (dictationEnabled) !altPressed else altPressed)
                    if (shouldTriggerVoice) {
                        val voiceBoardId = if (mapping != null && mapping.boardId != 0) mapping.boardId else VOICE_BOARD
                        if (ime.isInputActive && ime.isInputViewShown()) {
                            val uibm = keyboardSwitcher.getUnifiedInputBoardManager()
                            if (voiceKeyRefused()) {
                                // No recognition service: the notice is up; open nothing.
                            } else if (uibm != null && ime.isUimEnabled()) {
                                uibm.requestBoard(voiceBoardId)
                            } else {
                                triggerVoiceInput()
                            }
                        } else {
                            launchVoiceAssistant()
                        }
                    } else {
                        inputLogic.commitTypedWord(ime.getSettingsManager().getSettingsValues(), "", InputSource.HARDWARE)
                        inputLogic.mRichInputConnection.commitText(micAltChar, 1)
                    }
                    handled = true
                }
                else -> {}
            }
            if (handled) tracker.consumeModifiersAfterKey(0, true)
            return handled
        }

        /** Priority: ScancodeMapping.altChar, then AuxCharacterResolver, then the hardcoded fallback. */
        private fun resolveAltChar(mapping: ScancodeMapping?, fallbackKeyCode: Int, hardcodedFallback: String): String {
            if (mapping != null && mapping.hasAltChar()) return mapping.altChar.toString()
            val resolver = auxCharacterResolver()
            val result = resolver.resolve(fallbackKeyCode)
            if (result.hasCharacter()) return result.text()
            if (fallbackKeyCode == ResolvedKey.PSEUDO_KEYCODE_VOICE) {
                val result2 = resolver.resolve(231)
                if (result2.hasCharacter()) return result2.text()
            }
            return hardcodedFallback
        }
    }

    private val allKeysCallback = object : KeyInterceptorService.AllKeysCallback {
        override fun onKeyEvent(event: KeyEvent): Boolean {
            val keyCode = event.keyCode
            val tracker = ime.getPhysicalKeyboardStateTracker()

            // The service consumes Alt, so always feed it to the tracker here.
            if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
                ime.processKeyEventForState(event)
                return true
            }

            // A key chorded with a held Alt: cancel the long-press timer and spend the modifier.
            if (event.action == KeyEvent.ACTION_DOWN && tracker.getModifierState().isAltHeldBySpan()) {
                tracker.cancelLongPressTimer()
                tracker.consumeModifiersAfterKey(keyCode, true)
            }

            // Preprocess other keys only with no input connection. Refresh first: the flag can be
            // stale-false when the window is re-shown without onStartInput.
            ime.refreshInputActive()
            if (ime.isInputActive && ime.currentInputConnection != null) {
                return false
            }

            if (BuildConfig.DEBUG) Logger.debug(BlackBerryIME.LOG_TAG, "Pre-processing key event: keyCode=$keyCode")
            ime.processKeyEventForState(event)
            return false
        }
    }
}

/** A caught mic key types its Alt character instead: the field is numeric/phone/date and the Alt character is a digit. */
@VisibleForTesting
internal fun micKeyTypesDigit(inputType: Int, altChar: String): Boolean {
    if (altChar.length != 1 || !altChar[0].isDigit()) return false
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME -> true
        else -> false
    }
}
