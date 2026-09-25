package dev.bbkb.ime.core.ime
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
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
 * The physical-keyboard side channels that are not ordinary key events: the accessibility
 * KeyInterceptorService callbacks (special keys such as EMOJI / SYM / MIC on third-party PKB
 * devices, and the optional all-keys pre-processing), the META_SYM_ON tracker and the Alt+Sym
 * shortcut handler, the per-device alt-character resolver, and the voice-key actions.
 *
 * Ordinary hardware key events still enter through [BlackBerryIME.onKeyDown] and
 * [KeyEventProcessor].
 */
class HardwareKeyBridge(private val ime: BlackBerryIME) {

    private companion object {
        const val TAG = "HardwareKeyBridge"
        /**
         * META_ALT_ON | META_ALT_LEFT_ON | META_ALT_RIGHT_ON | ALT_LOCKED (0x200), so Alt-lock is
         * detected. Kept as an alias of the one definition in [ModifierState]; tests name it.
         */
        const val ALT_ANY_MASK = ModifierState.ALT_ANY_MASK
        const val EMOJI_BOARD = ResolvedKey.DEFAULT_EMOJI_BOARD_ID
        const val VOICE_BOARD = ResolvedKey.DEFAULT_VOICE_BOARD_ID
    }

    val altSymShortcutHandler = AltSymShortcutHandler()
    private var cachedAuxCharacterResolver: AuxCharacterResolver? = null

    /**
     * Wire the Alt+Sym callbacks. Requires [DeviceProfile] to be initialised.
     *
     * **The board actions must not be gated on [BlackBerryIME.isInputViewShown] alone.** On a PKB
     * device the IME window is down for most of the time the user is typing on the hardware
     * keyboard, and [requestShowOnKeyPress] — which the SYM entry points call before dispatching
     * — only *asks* for the window: `showSoftInputFromInputMethod` is asynchronous, so
     * `isInputViewShown()` is still false when the action runs. A bare `if (isInputViewShown())`
     * therefore threw the chord away silently, while the plain-Sym fallback
     * (`onSymbolShiftToggle`, which has no such gate) kept working — exactly the shape of the
     * owner's "Sym works, Alt+Sym does nothing" report. Ask for the window and then act.
     */
    fun install() {
        // One Ctrl tracker: ControlModeController owns it, and the modifier query API asks it
        // rather than keeping a copy. The span tracker's own "CTRL_SPAN" is the Sym key (key
        // code 63 sets META_SYM_ON), so it never was Ctrl state.
        // Nullable on purpose: install() runs early in IME construction, and tests build a bridge
        // over an IME that has no tracker at all.
        val tracker: PhysicalKeyboardStateTracker? = ime.getPhysicalKeyboardStateTracker()
        tracker?.setCtrlStateSource { ime.getControlMode().isCtrlActive }

        altSymShortcutHandler.setCallback(object : AltSymShortcutHandler.ActionCallback {
            override fun openSymbolKeyboard() {
                if (canShowBoard()) {
                    ime.getKeyboardSwitcher().onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), false, true)
                }
            }
            override fun switchLanguage() {
                ime.switchToNextSubtype(InputSource.HARDWARE)
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
                if (canShowBoard()) toggleBoard(FccController.KEY_CODE) {}
            }
            override fun toggleNumberPad() {
                if (canShowBoard()) toggleBoard(NumberPadController.KEY_CODE) {}
            }
        })
    }

    /**
     * Whether a board may be opened now: the IME window is up, or show-on-keypress has just been
     * asked to put it up. See the note on [install] for why "is it up *right now*" is the wrong
     * question on a physical keyboard.
     */
    private fun canShowBoard(): Boolean = ime.isInputViewShown() || ime.requestShowOnKeyPress()

    /**
     * Toggle a UIM board through the coordinator, or run [fallback] when the UIM is off. FCC and
     * the number pad are UIM-only boards, so their fallback does nothing.
     */
    private inline fun toggleBoard(boardKeyCode: Int, fallback: () -> Unit) {
        val uibm = ime.getKeyboardSwitcher().getUnifiedInputBoardManager()
        if (uibm != null && ime.isUimEnabled()) uibm.requestBoard(boardKeyCode) else fallback()
    }

    /**
     * Register the KeyInterceptor callbacks for third-party PKB devices (Minimal Phone, Titan Pocket).
     *
     * Idempotent, and re-fired from [BlackBerryIME.loadSettings] so that flipping "Enable special
     * key support" in Settings takes effect on the next input session instead of only at the next
     * IME process start. Before that, this ran once from the deferred-init token in `onCreate`, so
     * a user who enabled the accessibility service and the in-app toggle saw no change until the
     * IME was restarted.
     *
     * **The two callbacks have different lifetimes, and conflating them was a bug.**
     *
     * [KeyInterceptorService.setAllKeysCallback] is not a feature: it is the *compensation* for
     * the service's Alt bleed-through block, which consumes hardware Alt and relies on this
     * callback to keep [dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker]
     * in sync. It is registered for every PKB device for the whole life of this IME instance,
     * regardless of any preference, and cleared in [unregisterInterceptorCallbacks] (called from
     * `BlackBerryIME.releaseResources`). Gating it on `pref_key_interceptor_enabled` — default
     * false — meant that on an MP01 the modifier tracker saw none of the events the service was
     * eating (beta triage #10, 2026-09).
     *
     * [KeyInterceptorManager.setCallback] *is* the feature the "Enable special key support" switch
     * describes ("capture Emoji, SYM, and Voice keys"): it is what turns an intercepted board key
     * into an open board. That one stays on the preference, so the switch keeps its user-visible
     * meaning. With it off, a board key is simply not consumed at the accessibility level and
     * falls through to the IME's ordinary key pipeline, which has its own handling for it.
     */
    fun registerInterceptorCallbacks() {
        if (!DeviceProfile.current().isPkbDevice()) {
            unregisterInterceptorCallbacks()
            return
        }

        // Always, for every PKB device: see the lifetime note above.
        KeyInterceptorService.setAllKeysCallback(allKeysCallback)
        val preprocessEnabled = KeyInterceptorManager.isPreprocessAllKeysEnabled(ime)
        KeyInterceptorService.setPreprocessAllKeysEnabled(preprocessEnabled)
        Logger.info(BlackBerryIME.LOG_TAG, "KeyInterceptor: preprocess all keys = $preprocessEnabled")

        // Enable unified key mapping pipeline if feature flag is set
        val unifiedMapping = PrefsManager.getPrefs(ime)
            .getBoolean(KeyInterceptorManager.PREF_USE_UNIFIED_KEY_MAPPING, false)
        KeyInterceptorService.setUnifiedKeyMappingEnabled(unifiedMapping)
        Logger.info(BlackBerryIME.LOG_TAG, "KeyInterceptor: unified key mapping = $unifiedMapping")

        // The preference-gated half.
        val specialKeysEnabled = KeyInterceptorManager.isFeatureEnabled(ime)
        if (specialKeysEnabled) {
            KeyInterceptorManager.setCallback(keyInterceptorCallback)
        } else {
            KeyInterceptorManager.clearCallback()
        }
        Logger.info(BlackBerryIME.LOG_TAG, "KeyInterceptor: special key support = $specialKeysEnabled")
    }

    /**
     * Drop every callback this IME instance installed. Called from `BlackBerryIME.releaseResources`
     * so a destroyed IME can never leave [KeyInterceptorService]'s Alt bleed-through block
     * consuming hardware Alt with nothing left to compensate — and so the dead service is not
     * retained by a static field. The service's own `onDestroy` deliberately does *not* do this:
     * toggling the accessibility service off and on must not silently unregister a live IME.
     */
    fun unregisterInterceptorCallbacks() {
        KeyInterceptorManager.clearCallback()
        KeyInterceptorService.setAllKeysCallback(null)
        KeyInterceptorService.setPreprocessAllKeysEnabled(false)
    }

    /**
     * The alt-character resolver for the current device profile. Cached once the profile has an
     * alt-mappings table; a profile without one gets a fresh fallback resolver per call.
     */
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
     * Toggle voice input without the unified input bar in the loop — the mic key's behaviour when
     * the UIM is off (or its manager is not built yet). Public because [KeyEventProcessor] needs
     * exactly this for its own non-UIM leg: it used to call `switchToVoiceIme` there, which hands
     * off to another IME and can therefore never CLOSE anything, so with the UIM disabled the
     * physical mic key re-opened voice on every press. The toggle lives in the controller
     * ([VoiceInputController.toggleVoiceInput]) and the IME-switch fallbacks below are preserved.
     */
    fun triggerVoiceInput() {
        if (!ime.isInputActive || !ime.isInputViewShown()) {
            launchVoiceAssistant()
            return
        }
        // Bug #4 fix: the voice toggle itself must not be gated on isUimEnabled(). The voice view
        // is independent of the unified input bar; requiring UIM caused the physical voice key to
        // fall through to the IME switcher when UIM was disabled. UIM active-component
        // bookkeeping is still performed, but only when UIM is enabled.
        val voice = ime.voiceInputController
        if (voice == null) {
            InputMethodHelper.getInstance().switchToVoiceIme(ime)
            return
        }
        val wasInVoiceMode = voice.isInVoiceMode()
        voice.toggleVoiceInput()
        if (ime.isUimEnabled()) {
            val unifiedManager = ime.getKeyboardSwitcher().getUnifiedInputBoardManager() ?: return
            if (wasInVoiceMode) {
                // Scoped to the voice board: reporting a bare "nothing is open" would clear the
                // coordinator's state for whatever OTHER board it is holding (see
                // UnifiedInputBoardManager.reportBoardClosed).
                unifiedManager.reportBoardClosed(voice.keyCode)
            } else {
                unifiedManager.setActiveComponent(voice)
                if (!unifiedManager.isShowing()) unifiedManager.show(false)
            }
        }
    }

    /**
     * Start the system voice assistant (used when no editor is bound to receive dictation) — the
     * fallback for the physical mic key pressed outside a text field.
     *
     * Audit CT-29: this used to gate each candidate on `intent.resolveActivity(packageManager)`.
     * With `targetSdk = 36`, Android 11+ package-visibility filtering makes that return null for
     * every assist intent unless the manifest's `<queries>` declares them — which it does not —
     * and `getLaunchIntentForPackage` is filtered the same way. So every candidate was rejected
     * and the method silently fell through to "no voice assistant available" on any modern
     * device. Attempting the start and letting `ActivityNotFoundException` select the next
     * candidate needs no visibility declaration at all.
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

    /** Direct callback for AccessibilityService key interception (minimal latency). */
    private val keyInterceptorCallback = object : KeyInterceptorService.KeyEventCallback {
        override fun onSpecialKeyPressed(keyType: KeyInterceptorService.SpecialKeyType, metaState: Int): Boolean {
            // Legacy path: no mapping
            return handleSpecialKey(keyType, metaState, null)
        }

        override fun onSpecialKeyPressed(keyType: KeyInterceptorService.SpecialKeyType, metaState: Int,
                mapping: ScancodeMapping?): Boolean {
            // Unified path: carry mapping data for alt char / board ID resolution
            return handleSpecialKey(keyType, metaState, mapping)
        }

        private fun handleSpecialKey(keyType: KeyInterceptorService.SpecialKeyType, metaState: Int,
                mapping: ScancodeMapping?): Boolean {
            // SYM opens a board, and a board lives in the IME window, so a hidden window means
            // "summon it" rather than "give up" — on a PKB device the window is down for most of
            // the time the user is typing on the hardware keyboard, which is why bailing here was
            // half of "Sym key does nothing" on the MP01 (beta triage #10). If show-on-keypress
            // declines (mode set to never, no editor bound), we return false and the key falls
            // through to the system and thence to the IME's ordinary pipeline.
            if (!ime.isInputViewShown()) {
                if (keyType != KeyInterceptorService.SpecialKeyType.SYM) return false
                if (!ime.requestShowOnKeyPress()) return false
            }

            // One query, both questions: the special-key branches below ask the CHARACTER
            // question ("should this key type its Alt character"), which the interpreted state
            // answers, while the SYM branch asks the CHORD question, which only the modifier
            // keys' own state may answer. ModifierState names the two apart.
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
                    // Chord detection reads the modifier KEYS' state, not the masked internal
                    // state: on the symbol board's Alt page the mask adds Alt, and that is the
                    // board paging, not the user chording (KEY2, 2026-09-21).
                    if (!altSymShortcutHandler.detectAndExecute(modifiers.getChordMetaState())) {
                        keyboardSwitcher.onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), false, true)
                    }
                    handled = true
                }
                KeyInterceptorService.SpecialKeyType.MIC -> {
                    val dictationEnabled = PrefsManager.getPrefs().getBoolean("pref_voice_input_key", true)
                    val shouldTriggerVoice = if (dictationEnabled) !altPressed else altPressed
                    if (shouldTriggerVoice) {
                        val voiceBoardId = if (mapping != null && mapping.boardId != 0) mapping.boardId else VOICE_BOARD
                        if (ime.isInputActive && ime.isInputViewShown()) {
                            val uibm = keyboardSwitcher.getUnifiedInputBoardManager()
                            if (uibm != null && ime.isUimEnabled()) {
                                uibm.requestBoard(voiceBoardId)
                            } else {
                                triggerVoiceInput()
                            }
                        } else {
                            launchVoiceAssistant()
                        }
                    } else {
                        val micAltChar = resolveAltChar(mapping, ResolvedKey.PSEUDO_KEYCODE_VOICE, ".")
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

        /**
         * Resolve the alt character for a special key.
         * Priority: ScancodeMapping.altChar -> AuxCharacterResolver -> hardcoded fallback.
         */
        private fun resolveAltChar(mapping: ScancodeMapping?, fallbackKeyCode: Int, hardcodedFallback: String): String {
            if (mapping != null && mapping.hasAltChar()) return mapping.altChar.toString()
            val resolver = auxCharacterResolver()
            val result = resolver.resolve(fallbackKeyCode)
            if (result.hasCharacter()) return result.character.toString()
            if (fallbackKeyCode == ResolvedKey.PSEUDO_KEYCODE_VOICE) {
                val result2 = resolver.resolve(231)
                if (result2.hasCharacter()) return result2.character.toString()
            }
            return hardcodedFallback
        }
    }

    /** Callback for all-key pre-processing (when enabled). */
    private val allKeysCallback = object : KeyInterceptorService.AllKeysCallback {
        override fun onKeyEvent(event: KeyEvent): Boolean {
            val keyCode = event.keyCode
            val tracker = ime.getPhysicalKeyboardStateTracker()

            // ALT BLEED-THROUGH FIX: always process Alt key events for IME state tracking.
            if (keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT) {
                ime.processKeyEventForState(event)
                return true // Already consumed by the accessibility service
            }

            // ALT CHORD FIX: when a non-Alt key is pressed while Alt is HELD (chorded), cancel the
            // long-press timer and consume the modifier.
            if (event.action == KeyEvent.ACTION_DOWN && tracker.getModifierState().isAltHeldBySpan()) {
                tracker.cancelLongPressTimer()
                tracker.consumeModifiersAfterKey(keyCode, true)
            }

            // Only preprocess non-Alt keys if there is no active input connection.
            // Heal a stale-false flag first (window re-shown without onStartInput).
            ime.refreshInputActive()
            if (ime.isInputActive && ime.currentInputConnection != null) {
                return false // Let the normal flow handle it
            }

            // Audit CT-30: this is the all-keys pre-processing callback, so the interpolation ran
            // per key event before Logger.debug could reject it.
            if (BuildConfig.DEBUG) Logger.debug(BlackBerryIME.LOG_TAG, "Pre-processing key event: keyCode=$keyCode")
            ime.processKeyEventForState(event)
            // Don't consume: let the system also process it for the target app.
            return false
        }
    }
}
