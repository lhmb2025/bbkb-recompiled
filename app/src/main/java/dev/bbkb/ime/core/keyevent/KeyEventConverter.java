package dev.bbkb.ime.core.keyevent;

import android.content.Context;
import android.view.KeyEvent;

import dev.bbkb.ime.core.engine.PrimaryEngineFacts;
import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.KeyRole;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.layout.PhysicalKeyResolver;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;

import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.BuildConfig;




public class KeyEventConverter {
    private static String hex(int v) { return "0x" + Integer.toHexString(v); }

    final int mDeviceId;

    private final KeyCharacterInterpreter mKeyCharacterInterpreter;

    private boolean mVoiceKeyPending = false;

    /** Armed on the key-down of a held multifunction key so its repeats are swallowed. */
    private boolean mMultifunctionKeyPending = false;

    private final Context mContext;

    private AltMappingsTable mAltMappingsTable = null;

    private boolean mAltMappingsInitAttempted = false;

    /** Never null: HardwareKeyBridge.auxCharacterResolver() falls back to a default build. */
    private final AuxCharacterResolver auxCharacterResolver;

    /** The resolver for a key no profile speaks for: no Alt table, no layout overrides. */
    private static final AuxCharacterResolver PROFILELESS_AUX =
            new AuxCharacterResolver.Builder().withCombiningAccentFilter(true).build();

    /**
     * On: a plain press is voice input, Alt+press the character. Off: swapped. Read on the key
     * path, so it goes through the cached {@link PrefsManager} instance.
     */
    private boolean isDictationKeyEnabled() {
        if (mContext == null) return true;  // Default to enabled
        return PrefsManager.INSTANCE.getPrefs(mContext).getBoolean("pref_voice_input_key", true);
    }

    /** @param inputType an EditorInfo input type */
    private boolean isNumericFieldClass(int inputType) {
        int i2 = inputType & 15;
        return i2 == 2 || i2 == 4 || i2 == 3;
    }

    /** @param inputType an EditorInfo input type */
    private boolean isNumberOrDatetimeVariationField(int inputType) {
        int i2 = inputType & 15;
        return i2 == 2 || (i2 == 4 && (inputType & 4080) != 0);
    }

    /**
     * In a numeric field the mic key types its digit and never starts voice input; null when the
     * field is not numeric or there is no alt char. Ask the field type, not the number layout's
     * Alt mask: the mask can be reset, and with dictation off "Alt held" means voice input.
     */
    private InputEvent numericFieldDigit(int inputType, char altChar, int keyCode, boolean isRepeat,
            long eventTime) {
        if (altChar == 0 || !isNumericFieldClass(inputType)) {
            return null;
        }
        return InputEvent.createHardwareKeyPressEx((int) altChar, keyCode, null, isRepeat, eventTime, false);
    }

    /** The mapping's alt character, else the resolver's, else 0. */
    private char altCharFor(AuxCharacterResolver aux, ScancodeMapping mapping, KeyEvent keyEvent) {
        if (mapping != null && mapping.hasAltChar()) {
            return mapping.altChar;
        }
        AuxCharacterResolver.Result r = aux.resolve(keyEvent);
        return r.hasCharacter() ? r.character : 0;
    }

    /**
     * @param deviceId device id
     * @param keyCharacterInterpreter interprets the event's character under the computed meta state
     */
    public KeyEventConverter(int deviceId, KeyCharacterInterpreter keyCharacterInterpreter, Context context, AuxCharacterResolver resolver) {
        this.mDeviceId = deviceId;
        this.mKeyCharacterInterpreter = keyCharacterInterpreter;
        this.mContext = context;
        this.auxCharacterResolver = resolver;
    }

    /** Loads the device's Alt mappings table once. */
    private void initializeAltMappingsIfNeeded() {
        if (mAltMappingsInitAttempted) {
            return;
        }
        
        mAltMappingsInitAttempted = true;
        
        if (mContext == null) {
            if (BuildConfig.DEBUG) android.util.Log.w("MicKeyDebug", "initializeAltMappingsIfNeeded: context is null");
            return;
        }
        
        try {
            DeviceProfile profile = DeviceProfile.current();
            if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "DeviceProfile: hasCustomAltMappings=" + profile.hasCustomAltMappings());
            
            if (profile.hasCustomAltMappings()) {
                mAltMappingsTable = profile.getAltMappingsTable(mContext);
            }

            if (mAltMappingsTable == null) {
                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Trying direct resolution via DeviceInputResolver");
                mAltMappingsTable = DeviceInputResolver.getAltMappingsTable(mContext, 0);
            }
            
            if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "AltMappingsTable loaded: " + (mAltMappingsTable != null));
            if (mAltMappingsTable != null) {
                int micKeyCode = mAltMappingsTable.getSpecialFunctionCode(ResolvedKey.PSEUDO_KEYCODE_VOICE);
                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug",
                        "Keycode " + ResolvedKey.PSEUDO_KEYCODE_VOICE + " -> virtualKeyCode: " + micKeyCode);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) android.util.Log.e("MicKeyDebug", "Error initializing Alt mappings", e);
        }
    }

    /**
     * @param metaState the computed (interpreted) meta state
     * @param inputType the editor's input type
     */
    public InputEvent convertKeyEvent(KeyEvent keyEvent, int metaState, int inputType) {
        initializeAltMappingsIfNeeded();

        // The profile's Alt table and special-function keys describe the phone's own keys. A key
        // from an external keyboard the profile does not name is resolved as on a phone with no
        // profile (DeviceInputResolver.keyProfile).
        final boolean phoneKey = DeviceInputResolver.isPhoneKeyboard(keyEvent.getDeviceId());
        final AuxCharacterResolver aux = phoneKey ? auxCharacterResolver : PROFILELESS_AUX;
        final AltMappingsTable altTable = phoneKey ? mAltMappingsTable : null;

        final boolean isPhysical =
                dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
                        .getInstance().isPhysicalKeyboardEvent(keyEvent);

        // This is only the DOWN half of board keys: it arms a PendingKeyAction, and
        // KeyEventProcessor.onKeyUpInternal performs it on release.
        final BoardKeyPressTracker pairing = BoardKeyPressTracker.getInstance();
        CharSequence charSequence = null;
        int unicodeChar = 0;
        boolean isModifierKey = false;
        boolean isTextEvent = false;
        boolean z = false;
        KeyCharacterResult.Interpretation interpreted = this.mKeyCharacterInterpreter.interpretKeyCharacter(keyEvent, metaState);
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
        } else if (isNumericFieldClass(inputType) && (LocaleUtils.isCurrentSubtypePinyin() || LocaleUtils.isCurrentSubtypeCangjie() || LocaleUtils.isCurrentSubtypeJapanese())) {
            charSequence = null;
            unicodeChar = keyEvent.getNumber();
            isModifierKey = false;
            isTextEvent = false;
            z = false;
        } else {
            if (isAltActive(keyEvent, metaState)) {
                int keyCode = keyEvent.getKeyCode();
                // Special function keys and BOARD_VOICE skip this: their own branches below handle
                // Alt (with dictation off, Alt+voice key starts voice input).
                ScancodeMapping altMapping = DeviceInputResolver.keyRole(
                        keyEvent.getScanCode(), keyCode, keyEvent.getDeviceId());
                boolean isSpecialFunctionKey = (altTable != null && altTable.getSpecialFunctionCode(keyCode) != 0)
                        || (altMapping != null && altMapping.role == KeyRole.BOARD_VOICE);
                if (!isSpecialFunctionKey) {
                    AuxCharacterResolver.Result result = aux.resolve(keyEvent);
                    if (result.hasCharacter()) {
                        boolean isRepeat = keyEvent.getRepeatCount() != 0;
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("AltChordDebug", "AuxCharacterResolver: keyCode=" + keyCode +
                            " -> '" + result.character + "' (source=" + result.source + ")");
                        }
                        return InputEvent.createHardwareKeyPressEx(result.codePoint, keyCode, null, isRepeat, keyEvent.getEventTime(), false);
                    }
                }
            }
            if (InputTypeUtils.isAnyPasswordInputType(inputType) && isAltActive(keyEvent, metaState) && (LocaleUtils.isCurrentSubtypePinyin() || LocaleUtils.isCurrentSubtypeCangjie() || LocaleUtils.isCurrentSubtypeKorean() || LocaleUtils.isCurrentSubtypeJapanese())) {
                char number = keyEvent.getNumber();
                if (number == 0) {
                    charSequence = null;
                    unicodeChar = keyEvent.getUnicodeChar(metaState);
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
                // A physical letter key follows the user's letter layout, then the active
                // keyboard's alphabet (Arabic, Cyrillic, Hebrew, Greek, Hangul), before the system
                // key map, which spells it in Latin.
                int unicodeChar2 = PhysicalKeyResolver.letter(keyEvent, metaState);
                if (unicodeChar2 == 0) {
                    unicodeChar2 = keyEvent.getUnicodeChar(metaState);
                }
                if (keyEvent.getKeyCode() == 54 && unicodeChar2 == 37325 && PrimaryEngineFacts.get().isChineseCangjieMode()) {
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

        if (keyCode2 == 7) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("MIC_DEBUG",
                "KEC#convertKeyEvent entry: keyCode=7 scanCode=" + keyEvent.getScanCode() +
                " sysMeta=" + hex(keyEvent.getMetaState()) +
                " computedMetaIn=" + hex(metaState) +
                " repeatCount=" + keyEvent.getRepeatCount() +
                " unicodeChar(afterKCI)=" + unicodeChar +
                " isModifierKey(modifier)=" + isModifierKey +
                " isTextEvent(textEvent)=" + isTextEvent +
                " charSequence=" + charSequence +
                " inputType=" + inputType);
            }
        }
        if (isTextEvent && charSequence != null) {
            return InputEvent.createTextEvent(unicodeChar, charSequence, keyCode2, (InputEvent) null, z2, keyEvent.getEventTime(), false);
        }
        // Special keys from the device config.
        ScancodeMapping resolvedMapping = DeviceInputResolver.keyRole(
                keyEvent.getScanCode(), keyCode2, keyEvent.getDeviceId());
        if (resolvedMapping != null && resolvedMapping.role != null) {
            boolean unifiedAltPressed = (KeyEvent.normalizeMetaState(metaState) & 2) != 0;

            // The emoji multifunction action reuses the BOARD_EMOJI branch. The ctrl action
            // never gets here without Alt: remapKeyEvent() already made it KEYCODE_CTRL_LEFT.
            String multifunctionAction = (resolvedMapping.role == KeyRole.MULTIFUNCTION)
                    ? MultifunctionKeyHandler.getConfiguredAction(resolvedMapping) : null;

            if (resolvedMapping.role == KeyRole.BOARD_VOICE) {
                // In a numeric field it is its digit (held: repeats), whatever dictation and Alt say.
                InputEvent digit = numericFieldDigit(inputType, altCharFor(aux, resolvedMapping, keyEvent),
                        keyCode2, z2, keyEvent.getEventTime());
                if (digit != null) {
                    this.mVoiceKeyPending = false;
                    return digit;
                }
                // A letter layout that names this key (Thai ป) answers a letter only with Shift,
                // since its unshifted entry is the digit.
                if (!unifiedAltPressed) {
                    int layoutLetter = PhysicalKeyResolver.letter(keyEvent, metaState);
                    if (layoutLetter != 0 && Character.isLetter(layoutLetter)) {
                        this.mVoiceKeyPending = false;
                        return InputEvent.createHardwareKeyPressEx(layoutLetter, keyCode2, null, z2, keyEvent.getEventTime(), false);
                    }
                }
                if (keyEvent.getRepeatCount() == 0) {
                    boolean dictEnabled = isDictationKeyEnabled();
                    boolean shouldVoice = dictEnabled ? !unifiedAltPressed : unifiedAltPressed;
                    this.mVoiceKeyPending = shouldVoice;
                    if (shouldVoice) {
                        pairing.arm(keyEvent, PendingKeyAction.VOICE_INPUT);
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        char altCh = altCharFor(aux, resolvedMapping, keyEvent);
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
                if (keyEvent.getRepeatCount() == 0) {
                    if (!unifiedAltPressed) {
                        pairing.arm(keyEvent, PendingKeyAction.EMOJI_BOARD);
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        char altCh = resolvedMapping.hasAltChar() ? resolvedMapping.altChar : '0';
                        return InputEvent.createHardwareKeyPressEx((int) altCh, keyCode2, null, z2, keyEvent.getEventTime(), false);
                    }
                }
            } else if (multifunctionAction != null) {
                // Other actions (and Alt+ctrl): a plain press arms the key-up dispatch in
                // KeyEventProcessor; Alt+key types the mapping's alt character.
                if (keyEvent.getRepeatCount() == 0) {
                    boolean isCtrlAction = MultifunctionKeyHandler.ACTION_CTRL.equals(multifunctionAction);
                    this.mMultifunctionKeyPending = !unifiedAltPressed && !isCtrlAction;
                    if (this.mMultifunctionKeyPending) {
                        pairing.arm(keyEvent, PendingKeyAction.MULTIFUNCTION);
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                    if (unifiedAltPressed) {
                        char altCh = resolvedMapping.hasAltChar() ? resolvedMapping.altChar : 0;
                        if (altCh == 0) {
                            AuxCharacterResolver.Result r = aux.resolve(keyEvent);
                            if (r.hasCharacter()) altCh = r.character;
                        }
                        if (altCh != 0) {
                            return InputEvent.createHardwareKeyPressEx((int) altCh, keyCode2, null, z2, keyEvent.getEventTime(), false);
                        }
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    }
                    // ctrl without Alt is unreachable (remapped earlier); fall through.
                } else if (this.mMultifunctionKeyPending) {
                    // Held: swallow repeats; the action fires once on key-up.
                    return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                }
            } else if (resolvedMapping.role == KeyRole.BOARD_SYM) {
                // -22 reaches BlackBerryIME.updateSymbolShift, which ignores repeats, so holding
                // SYM does not flap the board.
                return InputEvent.createHardwareKeyPress(-1, -22, null, z2, keyEvent.getEventTime());
            }
        }

        // Fallbacks when the device config has no mapping.
        // Only a BlackBerry keypad puts its mic key on KEYCODE_0 (alt char "0"); on any other
        // hardware keyboard key code 7 is the 0 key and must type zero.
        if (7 == keyCode2 && DeviceProfile.current().isBlackBerryDevice()) {
            AuxCharacterResolver.Result legacyAlt = aux.resolve(keyEvent);
            char legacyAltChar = legacyAlt.hasCharacter() ? legacyAlt.character : '0';
            InputEvent digit = numericFieldDigit(inputType, legacyAltChar, keyCode2, z2, keyEvent.getEventTime());
            if (digit != null) {
                this.mVoiceKeyPending = false;
                return digit;
            }
            boolean altPressed = (KeyEvent.normalizeMetaState(metaState) & 2) != 0;
            boolean dictationEnabled = isDictationKeyEnabled();

            boolean shouldTriggerVoice = dictationEnabled ? !altPressed : altPressed;

            if (BuildConfig.DEBUG) {
            android.util.Log.d("MIC_DEBUG",
                "KEC#keyCode7: scanCode=" + keyEvent.getScanCode() +
                " computedMetaState=" + hex(metaState) +
                " normalizedMeta=" + hex(KeyEvent.normalizeMetaState(metaState)) +
                " altPressed=" + altPressed +
                " dictationEnabled=" + dictationEnabled +
                " shouldTriggerVoice=" + shouldTriggerVoice +
                " repeatCount=" + keyEvent.getRepeatCount() +
                " unicodeCharFromKCI=" + unicodeChar +
                " altTable(AltMappingsTable)=" + (altTable != null ? "non-null" : "NULL") +
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
                        pairing.arm(keyEvent, PendingKeyAction.VOICE_INPUT);
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("MIC_DEBUG",
                            "KEC#keyCode7: returning createGestureEndCopy(createEmptyEvent())" +
                            " WARNING: hasData()=false → KeyEventProcessor will call superOnKeyDown → '0' inserted!");
                        }
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
                        AuxCharacterResolver.Result result = aux.resolve(keyEvent);
                        if (result.hasCharacter()) {
                            return InputEvent.createHardwareKeyPressEx(result.codePoint, keyCode2, null, z2, keyEvent.getEventTime(), false);
                        }
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
        // KEYCODE_VOICE_ASSIST on the mic pseudo-scancode is the Minimal Phone MIC key; handle it
        // before the system voice assistant launches.
        if (231 == keyCode2 && isPhysical
                && keyEvent.getScanCode() == ResolvedKey.PSEUDO_KEYCODE_VOICE) {
            boolean altPressed = (KeyEvent.normalizeMetaState(metaState) & 2) != 0;
            boolean dictationEnabled = isDictationKeyEnabled();

            // Dictation on: MIC alone = voice, Alt+MIC = period. Off: swapped.
            boolean shouldTriggerVoice = dictationEnabled ? !altPressed : altPressed;
            
            if (BuildConfig.DEBUG) {
            android.util.Log.d("MicKeyDebug", "KEYCODE_VOICE_ASSIST (231): dictation=" + dictationEnabled + 
                ", alt=" + altPressed + ", shouldTriggerVoice=" + shouldTriggerVoice);
            }
            
            switch (keyEvent.getRepeatCount()) {
                case 0:
                    this.mVoiceKeyPending = shouldTriggerVoice;
                    if (shouldTriggerVoice) {
                        pairing.arm(keyEvent, PendingKeyAction.VOICE_INPUT);
                        return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                    } else {
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
        // Emoji key by scancode: "EM" in the .kl file is not a valid keycode name, so the key
        // code is KEYCODE_UNKNOWN (0) or the pseudo-keycode.
        int scanCode = keyEvent.getScanCode();
        if (isPhysical && scanCode == ResolvedKey.PSEUDO_KEYCODE_EMOJI
                && (keyCode2 == 0 || keyCode2 == ResolvedKey.PSEUDO_KEYCODE_EMOJI)) {
            if (keyEvent.getRepeatCount() == 0) {
                boolean altPressed = (KeyEvent.normalizeMetaState(metaState) & 2) != 0;
                
                if (BuildConfig.DEBUG) {
                android.util.Log.d("EmojiKeyDebug", "Emoji key (scancode "
                    + ResolvedKey.PSEUDO_KEYCODE_EMOJI + "): keycode=" + keyCode2 + 
                    ", alt=" + altPressed);
                }
                
                if (!altPressed) {
                    pairing.arm(keyEvent, PendingKeyAction.EMOJI_BOARD);
                    return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                } else {
                    // Alt+Emoji: the resolver's char for the pseudo-keycode, else '0'.
                    AuxCharacterResolver.Result result =
                            aux.resolve(ResolvedKey.PSEUDO_KEYCODE_EMOJI);
                    if (result.hasCharacter()) {
                        return InputEvent.createHardwareKeyPressEx(result.codePoint, keyCode2, null, z2, keyEvent.getEventTime(), false);
                    }
                    return InputEvent.createHardwareKeyPressEx((int)'0', keyCode2, null, z2, keyEvent.getEventTime(), false);
                }
            }
        }
        // Special-function keys from the Alt table: 7 = voice, 8888 = emoji.
        if (altTable != null) {
            int virtualKeyCode = altTable.getSpecialFunctionCode(keyCode2);
            
            if (virtualKeyCode != 0) {
                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Special function: keyCode=" + keyCode2 + " -> virtualKeyCode=" + virtualKeyCode);

                if (keyEvent.getRepeatCount() == 0) {
                boolean altPressed = (KeyEvent.normalizeMetaState(metaState) & 2) != 0;
                    
                    if (7 == virtualKeyCode) {
                        // In a numeric field the key is its digit, never voice input.
                        AuxCharacterResolver.Result mapped = aux.resolve(keyCode2);
                        InputEvent digit = numericFieldDigit(inputType,
                                mapped.hasCharacter() ? mapped.character : 0,
                                keyCode2, z2, keyEvent.getEventTime());
                        if (digit != null) {
                            this.mVoiceKeyPending = false;
                            return digit;
                        }
                        boolean dictationEnabled = isDictationKeyEnabled();
                        boolean shouldTriggerVoice = dictationEnabled ? !altPressed : altPressed;
                        
                        if (BuildConfig.DEBUG) {
                        android.util.Log.d("MicKeyDebug", "Voice key: dictation=" + dictationEnabled + 
                            ", alt=" + altPressed + ", shouldTriggerVoice=" + shouldTriggerVoice);
                        }
                        
                        if (shouldTriggerVoice) {
                            this.mVoiceKeyPending = true;
                            pairing.arm(keyEvent, PendingKeyAction.VOICE_INPUT);
                            return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                        } else {
                            char altChar = 0;
                            AuxCharacterResolver.Result result = aux.resolve(keyCode2);
                            if (result.hasCharacter()) altChar = result.character;
                            if (altChar != 0) {
                                if (BuildConfig.DEBUG) android.util.Log.d("MicKeyDebug", "Outputting alt char: '" + altChar + "'");
                                return InputEvent.createHardwareKeyPressEx((int)altChar, keyCode2, null, z2, keyEvent.getEventTime(), false);
                            }
                            return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                        }
                    } else if (8888 == virtualKeyCode) {
                        // No dictation swap: only a plain press opens the board.
                        if (!altPressed) {
                            pairing.arm(keyEvent, PendingKeyAction.EMOJI_BOARD);
                            return InputEvent.createGestureEndCopy(InputEvent.createEmptyEvent());
                        } else {
                            char altChar = 0;
                            AuxCharacterResolver.Result result2 = aux.resolve(keyCode2);
                            if (result2.hasCharacter()) altChar = result2.character;
                            if (altChar != 0) {
                                return InputEvent.createHardwareKeyPressEx((int)altChar, keyCode2, null, z2, keyEvent.getEventTime(), false);
                            }
                        }
                    }
                }
            }
        } else if (keyCode2 == ResolvedKey.PSEUDO_KEYCODE_VOICE) {
            if (BuildConfig.DEBUG) android.util.Log.w("MicKeyDebug", "Keycode "
                    + ResolvedKey.PSEUDO_KEYCODE_VOICE
                    + " pressed but altTable (AltMappingsTable) is null!");
        }
        if (KeyEvent.KEYCODE_SPACE == keyCode2 && keyEvent.getRepeatCount() == 1) {
            if (!isNumberOrDatetimeVariationField(inputType)) {
                return InputEvent.createHardwareKeyPress(-1, -5, InputEvent.createHardwareKeyPress(-1, -10, null, false, -1L), false, -1L);
            }
            return InputEvent.createHardwareKeyPress(-1, -10, null, false, -1L);
        }
        if (KeyEvent.KEYCODE_DEL == keyCode2) {
            return InputEvent.createHardwareKeyPress(-1, -5, null, z2, keyEvent.getEventTime());
        }
        // Every device family takes this keycode path for SYM; nothing handles it via META_SYM_ON.
        if (KeyEvent.KEYCODE_SYM == keyCode2 && isPhysical) {
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
        // Dead accent.
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
        return InputEvent.createHardwareKeyPressEx(unicodeChar, keyCode2, (InputEvent) null, z2, keyEvent.getEventTime(), (metaState & 514) != 0);
    }

    /**
     * Whether the key should produce its Alt character (event Alt, or interpreted Alt/alt-lock).
     * This is the character question, not "is Alt held": the symbol board's Alt page counts here
     * but not for a chord. See {@link ModifierState}.
     */
    private boolean isAltActive(KeyEvent keyEvent, int computedMetaState) {
        return ModifierState.builder()
                .event(keyEvent)
                .interpretedMeta(computedMetaState)
                .build()
                .isAltActiveForCharacter();
    }
}
