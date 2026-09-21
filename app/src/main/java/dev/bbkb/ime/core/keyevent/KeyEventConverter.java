package dev.bbkb.ime.core.keyevent;

import android.content.Context;
import android.view.KeyEvent;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;

import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.BuildConfig;




public class KeyEventConverter {

    // ===== ALT CHORD DEBUG LOGGING HELPER =====
    private static String hex(int v) { return "0x" + Integer.toHexString(v); }
    // ==========================================

    final int mDeviceId;

    private final KeyCharacterInterpreter mKeyCharacterInterpreter;

    private boolean mVoiceKeyPending = false;

    /** Armed on the key-down of a held multifunction key so its repeats are swallowed. */
    private boolean mMultifunctionKeyPending = false;

    private final Context mContext;

    private AltMappingsTable mAltMappingsTable = null;

    private boolean mAltMappingsInitAttempted = false;

    /**
     * Never null: the sole construction site (BlackBerryIME.getOrCreateKeyEventConverter) passes
     * {@code HardwareKeyBridge.auxCharacterResolver()}, which falls back to
     * {@code new AuxCharacterResolver.Builder().build()} on any failure.
     */
    private final AuxCharacterResolver auxCharacterResolver;

    /**
     * Check if the dictation key setting is enabled.
     * When enabled: regular press = voice input, alt press = character
     * When disabled: regular press = character, alt press = voice input
     *
     * <p>Audit DK-17: this is read inside {@code convertKeyEvent} on every voice/multifunction key
     * press. It used to call {@code PreferenceManager.getDefaultSharedPreferences(mContext)},
     * which re-does the name/mode resolution on each call (and, on the very first call in a
     * process, blocks on the prefs file load) — on the key path. Routed through
     * {@link PrefsManager}, the project's single prefs accessor, which holds one instance.
     *
     * <p>The flag properly belongs in {@code SettingsValues} alongside
     * {@code multifunctionKeyAction}, so it participates in settings snapshotting and
     * {@code DeviceProfile}'s device overrides; that change spans files outside this package.
     */
    private boolean isDictationKeyEnabled() {
        if (mContext == null) return true;  // Default to enabled
        return PrefsManager.INSTANCE.getPrefs(mContext).getBoolean("pref_voice_input_key", true);
    }

    private boolean isNumericFieldClass(int i) {
        int i2 = i & 15;
        return i2 == 2 || i2 == 4 || i2 == 3;
    }

    private boolean isNumberOrDatetimeVariationField(int i) {
        int i2 = i & 15;
        return i2 == 2 || (i2 == 4 && (i & 4080) != 0);
    }

    public KeyEventConverter(int i, KeyCharacterInterpreter interfaceC0922i, Context context, AuxCharacterResolver resolver) {
        this.mDeviceId = i;
        this.mKeyCharacterInterpreter = interfaceC0922i;
        this.mContext = context;
        this.auxCharacterResolver = resolver;
    }

    /**
     * Load Alt mappings table if device requires custom mappings.
     * Only called once, result is cached.
     */
    private void initializeAltMappingsIfNeeded() {
        if (mAltMappingsInitAttempted) {
            return;  // Already attempted
        }
        
        mAltMappingsInitAttempted = true;
        
        if (mContext == null) {
            if (BuildConfig.DEBUG) android.util.Log.w("MicKeyDebug", "initializeAltMappingsIfNeeded: context is null");
            return;
        }
        
        try {
            // First try via DeviceProfile (if initialized)
            DeviceProfile profile = DeviceProfile.current();
            if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "DeviceProfile: hasCustomAltMappings=" + profile.hasCustomAltMappings());
            
            if (profile.hasCustomAltMappings()) {
                mAltMappingsTable = profile.getAltMappingsTable(mContext);
            }
            
            // If still null, try direct resolution with context
            if (mAltMappingsTable == null) {
                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Trying direct resolution via DeviceInputResolver");
                mAltMappingsTable = DeviceInputResolver.getAltMappingsTable(mContext, 0);
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "AltMappingsTable loaded: " + (mAltMappingsTable != null));
            if (mAltMappingsTable != null) {
                // Test if keycode 667 is mapped
                int micKeyCode = mAltMappingsTable.getSpecialFunctionCode(667);
                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Keycode 667 -> virtualKeyCode: " + micKeyCode);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) android.util.Log.e("MicKeyDebug", "Error initializing Alt mappings", e);
        }
    }

    public InputEvent convertKeyEvent(KeyEvent keyEvent, int i, int i2) {
        initializeAltMappingsIfNeeded();

        // Audit DK-11: three orphaned debug locals (_keyCode / _sysMeta / _altInMeta) were
        // assigned here and never read anywhere in the file — three dead JNI-backed getter calls
        // per hardware key event that R8 cannot elide. The surviving debug logs below re-read
        // getMetaState() themselves.
        //
        // Audit DK-45/DK-20: the physical-keyboard classification is invariant for one event, and
        // the branches below re-entered the singleton five separate times for it.
        final boolean isPhysical =
                dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
                        .getInstance().isPhysicalKeyboardEvent(keyEvent);
        CharSequence charSequence = null;
        int unicodeChar = 0;
        boolean isModifierKey = false;
        boolean isTextEvent = false;
        boolean z = false;
        KeyCharacterResult.Interpretation interpreted = this.mKeyCharacterInterpreter.interpretKeyCharacter(keyEvent, i);
        if (dev.bbkb.ime.core.shared.InputPathDebug.on()) {
            dev.bbkb.ime.core.shared.Logger.info("CKB_SWIPE_TYPE_DEBUG",
                    "KeyEventConverter: keyCode=" + keyEvent.getKeyCode() + " isPhysical=" + isPhysical
                    + " interpreted=" + (interpreted == null ? "null" : ("cp=" + interpreted.codePoint + " text=" + interpreted.text)));
        }
        if (interpreted != null) {
            int i3 = interpreted.codePoint;
            isModifierKey = interpreted.isModifier();
            boolean shiftLocked = interpreted.isShiftLocked();
            isTextEvent = interpreted.isTextOutput();
            charSequence = interpreted.text;
            unicodeChar = i3;
            z = shiftLocked;
        } else if (isNumericFieldClass(i2) && (LocaleUtils.isCurrentSubtypePinyin() || LocaleUtils.isCurrentSubtypeCangjie() || LocaleUtils.isCurrentSubtypeJapanese())) {
            charSequence = null;
            unicodeChar = keyEvent.getNumber();
            isModifierKey = false;
            isTextEvent = false;
            z = false;
        } else {
            // === AuxCharacterResolver path (consolidated P1 + P4) ===
            // When resolver is available and Alt is active, use it for alt character resolution.
            // Special function keys are NOT resolved here — they fall through to the special key handler below.
            if (isAltActive(keyEvent, i)) {
                int keyCode = keyEvent.getKeyCode();
                // Skip special function keys — they have their own handler below
                boolean isSpecialFunctionKey = (mAltMappingsTable != null && mAltMappingsTable.getSpecialFunctionCode(keyCode) != 0);
                if (!isSpecialFunctionKey) {
                    AuxCharacterResolver.Result result = auxCharacterResolver.resolve(keyEvent);
                    if (result.hasCharacter()) {
                        boolean isRepeat = keyEvent.getRepeatCount() != 0;
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("AltChordDebug", "AuxCharacterResolver: keyCode=" + keyCode +
                            " -> '" + result.character + "' (source=" + result.source + ")");
                        }
                        return InputEvent.createHardwareKeyPressEx((int) result.character, keyCode, null, isRepeat, keyEvent.getEventTime(), false);
                    }
                }
            }
            if (InputTypeUtils.isAnyPasswordInputType(i2) && isAltActive(keyEvent, i) && (LocaleUtils.isCurrentSubtypePinyin() || LocaleUtils.isCurrentSubtypeCangjie() || LocaleUtils.isCurrentSubtypeKorean() || LocaleUtils.isCurrentSubtypeJapanese())) {
                char number = keyEvent.getNumber();
                if (number == 0) {
                    charSequence = null;
                    unicodeChar = keyEvent.getUnicodeChar(i);
                    isModifierKey = false;
                    isTextEvent = false;
                    z = false;
                } else {
                    charSequence = null;
                    unicodeChar = number;
                    isModifierKey = false;
                    isTextEvent = false;
                    z = false;
                }
            } else {
                int unicodeChar2 = keyEvent.getUnicodeChar(i);
                if (keyEvent.getKeyCode() == 54 && unicodeChar2 == 37325 && NuanceSDKManager.getInstance().isChineseCangjieMode()) {
                    charSequence = null;
                    isModifierKey = false;
                    isTextEvent = false;
                    unicodeChar = 39;
                    z = false;
                } else {
                    charSequence = null;
                    unicodeChar = unicodeChar2;
                    isModifierKey = false;
                    isTextEvent = false;
                    z = false;
                }
            }
        }
        int keyCode2 = keyEvent.getKeyCode();
        boolean z2 = keyEvent.getRepeatCount() != 0;
        
        // MIC_DEBUG C-entry: Log state for keyCode=7 (0/mic key) after interpretKeyCharacter
        if (keyCode2 == 7) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("MIC_DEBUG",
                "KEC#convertKeyEvent entry: keyCode=7 scanCode=" + keyEvent.getScanCode() +
                " sysMeta=" + hex(keyEvent.getMetaState()) +
                " computedMetaIn=" + hex(i) +
                " repeatCount=" + keyEvent.getRepeatCount() +
                " unicodeChar(afterKCI)=" + unicodeChar +
                " isModifierKey(modifier)=" + isModifierKey +
                " isTextEvent(textEvent)=" + isTextEvent +
                " charSequence=" + charSequence +
                " inputType=" + i2);
            }
        }
        if (isTextEvent && charSequence != null) {
            return InputEvent.createTextEvent(unicodeChar, charSequence, keyCode2, (InputEvent) null, z2, keyEvent.getEventTime(), false);
        }
        // === UNIFIED KEY MAPPING (Phase 4): Resolve special keys from XML config ===
        ScancodeMapping resolvedMapping = ScancodeMappingResolver.getInstance().resolve(
                keyEvent.getScanCode(), keyCode2);
        if (resolvedMapping != null && resolvedMapping.role != null) {
            InputMethodHelper unifiedHelper = InputMethodHelper.getInstance();
            boolean unifiedAltPressed = (KeyEvent.normalizeMetaState(i) & 2) != 0;

            // MULTIFUNCTION keys dispatch a user-configured action. Voice/emoji actions
            // reuse the BOARD_VOICE/BOARD_EMOJI branches below so their behavior (incl.
            // the dictation-key alt-swap) is identical to a dedicated key. The ctrl
            // action never reaches here without Alt: BlackBerryIME.remapKeyEvent()
            // already rewrote the event to KEYCODE_CTRL_LEFT.
            String multifunctionAction = (resolvedMapping.role == KeyRole.MULTIFUNCTION)
                    ? MultifunctionKeyHandler.getConfiguredAction(resolvedMapping) : null;

            if (resolvedMapping.role == KeyRole.BOARD_VOICE
                    || MultifunctionKeyHandler.ACTION_VOICE_INPUT.equals(multifunctionAction)) {
                // Voice/MIC key — resolved from XML config
                if (keyEvent.getRepeatCount() == 0) {
                    boolean dictEnabled = isDictationKeyEnabled();
                    boolean shouldVoice = dictEnabled ? !unifiedAltPressed : unifiedAltPressed;
                    this.mVoiceKeyPending = shouldVoice;
                    if (shouldVoice) {
                        unifiedHelper.micKeyPressed = true;
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        // Output alt character from mapping or fallback
                        char altCh = resolvedMapping.hasAltChar() ? resolvedMapping.altChar : 0;
                        if (altCh == 0) {
                            AuxCharacterResolver.Result r = auxCharacterResolver.resolve(keyEvent);
                            if (r.hasCharacter()) altCh = r.character;
                        }
                        if (altCh != 0) {
                            return InputEvent.createHardwareKeyPressEx((int) altCh, keyCode2, null, z2, keyEvent.getEventTime(), false);
                        }
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                } else if (keyEvent.getRepeatCount() == 1 && this.mVoiceKeyPending) {
                    return InputEvent.createGestureEndCopy(InputEvent.createHardwareKeyPress(-23, 0, null, false, -1L));
                } else if (this.mVoiceKeyPending) {
                    return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                }
            } else if (resolvedMapping.role == KeyRole.BOARD_EMOJI
                    || MultifunctionKeyHandler.ACTION_EMOJI_BOARD.equals(multifunctionAction)) {
                // Emoji key — resolved from XML config
                if (keyEvent.getRepeatCount() == 0) {
                    if (!unifiedAltPressed) {
                        unifiedHelper.emojiKeyPressed = true;
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        char altCh = resolvedMapping.hasAltChar() ? resolvedMapping.altChar : '0';
                        return InputEvent.createHardwareKeyPressEx((int) altCh, keyCode2, null, z2, keyEvent.getEventTime(), false);
                    }
                }
            } else if (multifunctionAction != null) {
                // Remaining multifunction actions (clipboard, language switch, symbol
                // keyboard, hide keyboard — and the alt-active leg of ctrl): plain press
                // arms the key-up dispatch in KeyEventProcessor; Alt+key types the
                // mapping's alt character.
                if (keyEvent.getRepeatCount() == 0) {
                    boolean isCtrlAction = MultifunctionKeyHandler.ACTION_CTRL.equals(multifunctionAction);
                    this.mMultifunctionKeyPending = !unifiedAltPressed && !isCtrlAction;
                    if (this.mMultifunctionKeyPending) {
                        unifiedHelper.multifunctionKeyPressed = true;
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                    if (unifiedAltPressed) {
                        char altCh = resolvedMapping.hasAltChar() ? resolvedMapping.altChar : 0;
                        if (altCh == 0) {
                            AuxCharacterResolver.Result r = auxCharacterResolver.resolve(keyEvent);
                            if (r.hasCharacter()) altCh = r.character;
                        }
                        if (altCh != 0) {
                            return InputEvent.createHardwareKeyPressEx((int) altCh, keyCode2, null, z2, keyEvent.getEventTime(), false);
                        }
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                    // ctrl action without Alt: unreachable (remapped before conversion);
                    // fall through to normal key processing as a safe default.
                } else if (this.mMultifunctionKeyPending) {
                    // Held: swallow repeats; the action fires once on key-up.
                    return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                }
            } else if (resolvedMapping.role == KeyRole.BOARD_SYM) {
                // SYM key — resolved from XML config. There used to be a
                // usesMetaSymHandling() arm here returning an empty event; see the KEYCODE_SYM
                // site below for why it is gone. -22 reaches
                // InputLogic.handleFunctionalKeyEvent → BlackBerryIME.updateSymbolShift, which
                // ignores key repeats, so holding SYM does not flap the board.
                return InputEvent.createHardwareKeyPress(-1, -22, null, z2, keyEvent.getEventTime());
            }
        }
        
        // === LEGACY SPECIAL KEY HANDLING (fallback when no XML mapping) ===
        // Handle keycode 7 (voice key) for BlackBerry OEM devices
        // Uses micKeyPressed flag (same as keycode 667 for Minimal Phone)
        // Alt character for keycode 7 on BlackBerry is "0"
        if (7 == keyCode2) {
            InputMethodHelper inputMethodHelper = InputMethodHelper.getInstance();
            boolean altPressed = (KeyEvent.normalizeMetaState(i) & 2) != 0;
            boolean dictationEnabled = isDictationKeyEnabled();
            
            // Swap logic: when disabled, alt+key triggers voice instead of regular press
            boolean shouldTriggerVoice = dictationEnabled ? !altPressed : altPressed;
            
            // MIC_DEBUG C: KeyEventConverter reached keyCode=7 handler
            if (BuildConfig.DEBUG) {
            android.util.Log.d("MIC_DEBUG",
                "KEC#keyCode7: scanCode=" + keyEvent.getScanCode() +
                " computedMetaState=" + hex(i) +
                " normalizedMeta=" + hex(KeyEvent.normalizeMetaState(i)) +
                " altPressed=" + altPressed +
                " dictationEnabled=" + dictationEnabled +
                " shouldTriggerVoice=" + shouldTriggerVoice +
                " repeatCount=" + keyEvent.getRepeatCount() +
                " unicodeCharFromKCI=" + unicodeChar +
                " mAltMappingsTable(AltMappingsTable)=" + (mAltMappingsTable != null ? "non-null" : "NULL") +
                " auxResolver=" + (auxCharacterResolver != null ? "non-null" : "NULL"));
            }
            if (BuildConfig.DEBUG) {
            android.util.Log.d("MicKeyDebug", "Keycode 7: dictation=" + dictationEnabled + 
                ", alt=" + altPressed + ", shouldTriggerVoice=" + shouldTriggerVoice);
            }
            
            switch (keyEvent.getRepeatCount()) {
                case 0:
                    this.mVoiceKeyPending = shouldTriggerVoice;
                    if (shouldTriggerVoice) {
                        inputMethodHelper.micKeyPressed = true;
                        // MIC_DEBUG C2: Returning gesture-end-empty (hasData=FALSE → will fall through to superOnKeyDown!)
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("MIC_DEBUG",
                            "KEC#keyCode7: returning createGestureEndCopy(createEmptyEvent())" +
                            " WARNING: hasData()=false → KeyEventProcessor will call superOnKeyDown → '0' inserted!");
                        }
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        // Output the alt character via the resolver
                        AuxCharacterResolver.Result result = auxCharacterResolver.resolve(keyEvent);
                        if (result.hasCharacter()) {
                            return InputEvent.createHardwareKeyPressEx((int) result.character, keyCode2, null, z2, keyEvent.getEventTime(), false);
                        }
                        // No alt character defined, consume silently
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                case 1:
                    if (this.mVoiceKeyPending) {
                        return InputEvent.createGestureEndCopy(InputEvent.createHardwareKeyPress(-23, 0, null, false, -1L));
                    }
                    break;
                default:
                    if (this.mVoiceKeyPending) {
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                    break;
            }
        }
        // Handle KEYCODE_VOICE_ASSIST (231) - system maps MIC key to this after .kl update
        // Scancode 667 confirms this is the Minimal Phone MIC key
        // This allows the IME to intercept and handle it before system voice assistant launches
        if (231 == keyCode2 && isPhysical && keyEvent.getScanCode() == 667) {
            InputMethodHelper inputMethodHelper = InputMethodHelper.getInstance();
            boolean altPressed = (KeyEvent.normalizeMetaState(i) & 2) != 0;
            boolean dictationEnabled = isDictationKeyEnabled();
            
            // Apply same swap logic as keycode 667/7:
            // When enabled (default): MIC alone = voice, Alt+MIC = period
            // When disabled: MIC alone = period, Alt+MIC = voice
            boolean shouldTriggerVoice = dictationEnabled ? !altPressed : altPressed;
            
            if (BuildConfig.DEBUG) {
            android.util.Log.d("MicKeyDebug", "KEYCODE_VOICE_ASSIST (231): dictation=" + dictationEnabled + 
                ", alt=" + altPressed + ", shouldTriggerVoice=" + shouldTriggerVoice);
            }
            
            switch (keyEvent.getRepeatCount()) {
                case 0:
                    this.mVoiceKeyPending = shouldTriggerVoice;
                    if (shouldTriggerVoice) {
                        inputMethodHelper.micKeyPressed = true;
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        // Output period character (Alt+MIC behavior)
                        return InputEvent.createHardwareKeyPressEx((int)'.', keyCode2, null, z2, keyEvent.getEventTime(), false);
                    }
                case 1:
                    if (this.mVoiceKeyPending) {
                        return InputEvent.createGestureEndCopy(InputEvent.createHardwareKeyPress(-23, 0, null, false, -1L));
                    }
                    break;
                default:
                    if (this.mVoiceKeyPending) {
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                    break;
            }
        }
        // Handle Emoji key when system maps scancode 666 to KEYCODE_UNKNOWN (0)
        // This happens because "EM" in .kl file is not a valid Android keycode name
        // Detect by scancode when keycode is 0 or 666
        int scanCode = keyEvent.getScanCode();
        if (isPhysical && scanCode == 666 && (keyCode2 == 0 || keyCode2 == 666)) {
            if (keyEvent.getRepeatCount() == 0) {
                InputMethodHelper inputMethodHelper = InputMethodHelper.getInstance();
                boolean altPressed = (KeyEvent.normalizeMetaState(i) & 2) != 0;
                
                if (BuildConfig.DEBUG) {
                android.util.Log.d("EmojiKeyDebug", "Emoji key (scancode 666): keycode=" + keyCode2 + 
                    ", alt=" + altPressed);
                }
                
                if (!altPressed) {
                    // Emoji alone → toggle emoji board
                    inputMethodHelper.emojiKeyPressed = true;
                    return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                } else {
                    // Alt+Emoji → output alt character via resolver or hardcoded '0'
                    // Use the hardware keyCode (666) for XML lookup
                    AuxCharacterResolver.Result result = auxCharacterResolver.resolve(666);
                    if (result.hasCharacter()) {
                        return InputEvent.createHardwareKeyPressEx((int) result.character, keyCode2, null, z2, keyEvent.getEventTime(), false);
                    }
                    return InputEvent.createHardwareKeyPressEx((int)'0', keyCode2, null, z2, keyEvent.getEventTime(), false);
                }
            }
        }
        // Handle custom hardware keys with special function remapping
        if (mAltMappingsTable != null) {
            int virtualKeyCode = mAltMappingsTable.getSpecialFunctionCode(keyCode2);
            
            if (virtualKeyCode != 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Special function: keyCode=" + keyCode2 + " -> virtualKeyCode=" + virtualKeyCode);
                InputMethodHelper inputMethodHelper = InputMethodHelper.getInstance();
                
                if (keyEvent.getRepeatCount() == 0) {
                    boolean altPressed = (KeyEvent.normalizeMetaState(i) & 2) != 0;
                    
                    if (7 == virtualKeyCode) {
                        // Voice input key - apply dictation key swap logic
                        boolean dictationEnabled = isDictationKeyEnabled();
                        // When enabled: regular=voice, alt=character
                        // When disabled: regular=character, alt=voice
                        boolean shouldTriggerVoice = dictationEnabled ? !altPressed : altPressed;
                        
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("MicKeyDebug", "Voice key: dictation=" + dictationEnabled + 
                            ", alt=" + altPressed + ", shouldTriggerVoice=" + shouldTriggerVoice);
                        }
                        
                        if (shouldTriggerVoice) {
                            // Trigger voice input
                            this.mVoiceKeyPending = true;
                            inputMethodHelper.micKeyPressed = true;
                            return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                        } else {
                            // Output the alt character via the resolver
                            char altChar = 0;
                            AuxCharacterResolver.Result result = auxCharacterResolver.resolve(keyCode2);
                            if (result.hasCharacter()) altChar = result.character;
                            if (altChar != 0) {
                                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Outputting alt char: '" + altChar + "'");
                                return InputEvent.createHardwareKeyPressEx((int)altChar, keyCode2, null, z2, keyEvent.getEventTime(), false);
                            }
                            // No alt character defined, consume silently
                            return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                        }
                    } else if (8888 == virtualKeyCode) {
                        // Emoji toggle - no swap logic, only triggers without Alt
                        if (!altPressed) {
                            inputMethodHelper.emojiKeyPressed = true;
                            return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                        } else {
                            // Alt+emoji key - output the alt character via the resolver
                            char altChar = 0;
                            AuxCharacterResolver.Result result2 = auxCharacterResolver.resolve(keyCode2);
                            if (result2.hasCharacter()) altChar = result2.character;
                            if (altChar != 0) {
                                return InputEvent.createHardwareKeyPressEx((int)altChar, keyCode2, null, z2, keyEvent.getEventTime(), false);
                            }
                        }
                    }
                }
            }
        } else if (keyCode2 == 667) {
            if (BuildConfig.DEBUG) android.util.Log.w("MicKeyDebug", "Keycode 667 pressed but mAltMappingsTable (AltMappingsTable) is null!");
        }
        if (KeyEvent.KEYCODE_SPACE == keyCode2 && keyEvent.getRepeatCount() == 1) {
            if (!isNumberOrDatetimeVariationField(i2)) {
                return InputEvent.createHardwareKeyPress(-1, -5, InputEvent.createHardwareKeyPress(-1, -10, null, false, -1L), false, -1L);
            }
            return InputEvent.createHardwareKeyPress(-1, -10, null, false, -1L);
        }
        if (KeyEvent.KEYCODE_DEL == keyCode2) {
            return InputEvent.createHardwareKeyPress(-1, -5, null, z2, keyEvent.getEventTime());
        }
        // Handle the SYM key - device-aware (audit DK-45: these were bare keycode ints)
        if (KeyEvent.KEYCODE_SYM == keyCode2 && isPhysical) {
            // The KNOWN GAP recorded here on 2026-09-16 is now closed (beta triage #10, MP01).
            // For the record: a `usesMetaSymHandling()` arm used to return an empty event on
            // every third-party PKB device, on the theory that BlackBerryIME's
            // SymKeyStateTracker would handle SYM via META_SYM_ON. That tracker had zero callers
            // and was deleted, so on those devices SYM was swallowed here and handled by nobody
            // — which is half of "Sym key does nothing" on the Minimal Phone MP01. Both device
            // families now take the same keycode-based path, which is the one that has always
            // worked on BlackBerry hardware.
            return InputEvent.createHardwareKeyPress(-1, -22, null, z2, keyEvent.getEventTime());
        }
        if ((KeyEvent.KEYCODE_SHIFT_RIGHT == keyCode2 || KeyEvent.KEYCODE_SHIFT_LEFT == keyCode2) && isPhysical) {
            return InputEvent.createHardwareKeyPress(-1, -1, null, z2, keyEvent.getEventTime());
        }
        if ((KeyEvent.KEYCODE_ALT_LEFT == keyCode2 || KeyEvent.KEYCODE_ALT_RIGHT == keyCode2) && isPhysical) {
            return InputEvent.createHardwareKeyPress(-1, -28, null, z2, keyEvent.getEventTime());
        }
        if (!keyEvent.isPrintingKey() && KeyEvent.KEYCODE_SPACE != keyCode2 && KeyEvent.KEYCODE_ENTER != keyCode2) {
            return InputEvent.createEmptyEvent();
        }
        // Audit DK-25: Integer.MIN_VALUE / Integer.MAX_VALUE were stand-ins for
        // KeyCharacterMap.COMBINING_ACCENT (0x80000000) and COMBINING_ACCENT_MASK (0x7FFFFFFF).
        // AuxCharacterResolver already spells the identical test with the named constants; two
        // spellings of one bit test meant only one of them read as a dead-accent check.
        if ((unicodeChar & android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
            return InputEvent.createFunctionalKeyEvent(
                    unicodeChar & android.view.KeyCharacterMap.COMBINING_ACCENT_MASK, keyCode2, null);
        }
        if (KeyEvent.KEYCODE_ENTER == keyCode2) {
            return InputEvent.createHardwareKeyPress(-1, 10, null, z2, keyEvent.getEventTime());
        }
        if (isModifierKey) {
            return InputEvent.createModifierKeyEvent(unicodeChar, keyCode2, (InputEvent) null, z2, z, keyEvent.getEventTime());
        }
        return InputEvent.createHardwareKeyPressEx(unicodeChar, keyCode2, (InputEvent) null, z2, keyEvent.getEventTime(), (i & 514) != 0);
    }

    private boolean isAltActive(KeyEvent keyEvent, int i) {
        return keyEvent.isAltPressed() || (i & 2) == 2 || (i & 512) == 512;
    }
}
