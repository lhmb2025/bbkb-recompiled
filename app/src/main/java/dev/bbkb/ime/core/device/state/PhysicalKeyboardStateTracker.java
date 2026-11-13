package dev.bbkb.ime.core.device.state;

import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.View;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.SymbolPageProvider;
import dev.bbkb.ime.core.keyevent.BoardKeyPressTracker;
import dev.bbkb.ime.core.keyevent.ModifierResetReason;
import dev.bbkb.ime.core.keyevent.ModifierState;
import dev.bbkb.ime.core.keyevent.ModifierStateListener;
import dev.bbkb.ime.core.keyevent.ModifierStatusBarUpdater;
import dev.bbkb.ime.core.keyevent.KeyCharacterInterpreter;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.BuildConfig;


public final class PhysicalKeyboardStateTracker extends MetaKeyStateTracker implements SymbolPageProvider {
    private static String hex(int v) { return "0x" + Integer.toHexString(v); }

    /** The single modifier-state listener (the status bar in the IME; a test may substitute). */
    private ModifierStateListener mModifierStateListener;

    /**
     * Answers "is Ctrl active" from {@code ControlModeController}, the single Ctrl owner. This
     * tracker keeps no copy: its own "CTRL_SPAN" is the Sym key (key code 63 sets META_SYM_ON).
     */
    private CtrlStateSource mCtrlStateSource;

    /** Supplies {@link ModifierState#isCtrlActive()} from the one Ctrl owner. */
    public interface CtrlStateSource {
        boolean isCtrlActive();

        /** Ctrl is locked on (double tap). */
        default boolean isCtrlLocked() {
            return false;
        }
    }

    private Editable mMetaKeyText = new SpannableStringBuilder("");

    private int[] mShiftState = {-2, -2};

    private int[] mAltState = {-2, -2};

    private KeyCharacterInterpreter.MetaMask mKeyInterpreter = KeyCharacterInterpreter.MetaMask.IDENTITY;

    /**
     * Key codes of the physical keys held down. A set, not a counter, because downs and ups are
     * not reliably paired (several key-up branches return early): a duplicate down counts once,
     * an unmatched up is a no-op, and a leaked key clears on its next press-and-release.
     */
    private final SparseBooleanArray mKeysHeld = new SparseBooleanArray(4);

    /**
     * Key code the Sym key arrives as, for {@link #isSymKeyHeld()}. KEYCODE_SYM on BlackBerry;
     * the MP01 ROM sends KEYCODE_ALT_RIGHT, so the key-event path sets it via {@link #setSymKeyCode}.
     */
    private int mSymKeyCode = KeyEvent.KEYCODE_SYM;

    private boolean mAltUsedWithKey = false;

    public PhysicalKeyboardStateTracker(ModifierStatusBarUpdater statusBarUpdater) {
        this.mModifierStateListener = statusBarUpdater;
    }

    public void setModifierStateListener(ModifierStateListener listener) {
        this.mModifierStateListener = listener;
    }

    public void setCtrlStateSource(CtrlStateSource source) {
        this.mCtrlStateSource = source;
    }

    public void onModifierListenerReset() {
        resetModifiers(ModifierResetReason.UNSPECIFIED);
    }

    /**
     * The active modifiers now. Every Shift/Alt/Ctrl/Sym question in the physical-keyboard path
     * belongs here; the three meta states it carries are not interchangeable (see {@link ModifierState}).
     */
    public ModifierState getModifierState() {
        return buildModifierState(0);
    }

    /** The active modifiers, merged with what {@code event} says the system thinks. */
    public ModifierState getModifierState(KeyEvent event) {
        return buildModifierState(event == null ? 0 : event.getMetaState());
    }

    /** The active modifiers, merged with a raw system meta state (the accessibility path). */
    public ModifierState getModifierState(int eventMetaState) {
        return buildModifierState(eventMetaState);
    }

    /**
     * {@code !isShiftReleased() && isShiftLockedForLayout() && !isAltUsedWithKey()} of
     * {@link #getModifierState()} without building the snapshot; asked on every capacitive touch sample.
     */
    public boolean isShiftHeldAndLockedForLayout() {
        if (!isShiftKeyDown() && !isShiftKeyConsumed()) {
            return false;
        }
        if (this.mAltUsedWithKey) {
            return false;
        }
        return (computeInternalMetaState() & ModifierState.SPAN_SHIFT_LOCKED) != 0;
    }

    private ModifierState buildModifierState(int eventMetaState) {
        int spanMeta = getMetaState((CharSequence) this.mMetaKeyText);
        return ModifierState.builder()
                .eventMeta(eventMetaState)
                .modifierKeyMeta(spanMeta)
                .interpretedMeta(computeInternalMetaState())
                .altGr(isAltGrPressed())
                .shiftKeyDown(isShiftKeyDown())
                .shiftKeyConsumed(isShiftKeyConsumed())
                .altKeyDown(isAltKeyDown())
                .altSpanHeld(isAltSpanPressed(this.mMetaKeyText))
                .altUsedWithKey(this.mAltUsedWithKey)
                .symKeyHeld(isSymKeyHeld())
                .ctrlActive(this.mCtrlStateSource != null && this.mCtrlStateSource.isCtrlActive())
                .ctrlLocked(this.mCtrlStateSource != null && this.mCtrlStateSource.isCtrlLocked())
                .shiftSurvivesMask(
                        (this.mKeyInterpreter.apply(KeyEvent.META_SHIFT_ON) & KeyEvent.META_SHIFT_ON) != 0)
                .keysHeld(getNumberOfKeysDown())
                .shiftKeyPhysicallyDown(this.mKeysHeld.get(KeyEvent.KEYCODE_SHIFT_LEFT, false)
                        || this.mKeysHeld.get(KeyEvent.KEYCODE_SHIFT_RIGHT, false))
                .altKeyPhysicallyDown(this.mKeysHeld.get(KeyEvent.KEYCODE_ALT_LEFT, false)
                        || this.mKeysHeld.get(KeyEvent.KEYCODE_ALT_RIGHT, false))
                .build();
    }

    /**
     * The single modifier-state feed; every transition in this class ends here.
     *
     * @param force re-assert even when nothing changed
     */
    private void publishModifierState(boolean force) {
        ModifierStateListener listener = this.mModifierStateListener;
        if (listener != null) {
            listener.onModifierStateChanged(getModifierState(), force);
        }
    }

    /** Called by the Ctrl owner on change; Ctrl is not span state, so nothing here reports it. */
    public void onCtrlStateChanged() {
        publishModifierState(false);
    }

    /** @param i the key code */
    public void handleKeyDown(int i, KeyEvent keyEvent) {
        if (KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent) && keyEvent.getRepeatCount() == 0) {
            if (BuildConfig.DEBUG) {
            int _internalMetaBefore = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyDown" +
                " keyCode=" + i +
                " isModifier=" + isModifierKey(i) +
                " keysDownBefore=" + getNumberOfKeysDown() +
                " internalMetaBefore=" + hex(_internalMetaBefore));
            }

            this.mKeysHeld.put(i, true);
            if (!isModifierKeyEvent(i, keyEvent)) {
                if (i == 63) {
                    if (!dev.bbkb.ime.core.device.profile.DeviceProfile.current().usesMetaSymHandling()) {
                        resetAltStateAndNotify();
                    }
                }
            } else {
                super.onKeyDown((View) null, this.mMetaKeyText, i, keyEvent);
                markModifierPressed(i);
                publishModifierState(false);
            }

            if (BuildConfig.DEBUG) {
            int _internalMetaAfter = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyDown_after" +
                " keyCode=" + i +
                " keysDownAfter=" + getNumberOfKeysDown() +
                " internalMetaAfter=" + hex(_internalMetaAfter));
            }
        }
    }

    /** @param i the key code */
    public void handleKeyUp(int i, KeyEvent keyEvent) {
        if (KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent)) {
            boolean trackPrev = isAltKey(i) ? isAltKeyConsumed() : isShiftKey(i) ? isShiftKeyConsumed() : false;
            if (BuildConfig.DEBUG) {
            int _internalMetaBefore = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyUp" +
                " keyCode=" + i +
                " isModifier=" + isModifierKey(i) +
                " trackPrev=" + trackPrev +
                " keysDownBefore=" + getNumberOfKeysDown() +
                " internalMetaBefore=" + hex(_internalMetaBefore));
            }

            this.mKeysHeld.delete(i);
            if (isModifierKeyEvent(i, keyEvent)) {
                super.onKeyUp(null, this.mMetaKeyText, i, keyEvent, trackPrev);
                markModifierReleased(i);
                publishModifierState(false);
            }

            if (BuildConfig.DEBUG) {
            int _internalMetaAfter = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyUp_after" +
                " keyCode=" + i +
                " keysDownAfter=" + getNumberOfKeysDown() +
                " internalMetaAfter=" + hex(_internalMetaAfter));
            }
        }
    }

    /**
     * Spends sticky/pressed modifiers after a non-modifier key.
     *
     * @param i the key code
     * @param z true if the key came from the physical keyboard
     */
    public void consumeModifiersAfterKey(int i, boolean z) {
        if (BuildConfig.DEBUG) {
        int _internalMetaBefore = getInternalMetaState();
        android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
            " where=PhysicalKeyboardStateTracker#consumeModifiersAfterKey" +
            " keyCode=" + i +
            " isPhysicalKb=" + z +
            " isModifierKey=" + KeyEvent.isModifierKey(i) +
            " internalMetaBefore=" + hex(_internalMetaBefore));
        }

        if (KeyEvent.isModifierKey(i) || !z) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#consumeModifiersAfterKey_SKIP" +
                " reason=" + (KeyEvent.isModifierKey(i) ? "isModifierKey" : "notPhysicalKb"));
            }
            return;
        }
        clearPressedModifiers(this.mMetaKeyText);
        if (!isShiftReleased()) {
            this.mAltUsedWithKey = i != 0;
        }
        commitModifierState(true);
        // A sticky modifier this key just spent is off: publish now, since its key-up already
        // ran and no later event reports the transition.
        publishModifierState(false);

        if (BuildConfig.DEBUG) {
        int _internalMetaAfter = getInternalMetaState();
        android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
            " where=PhysicalKeyboardStateTracker#consumeModifiersAfterKey_after" +
            " internalMetaAfter=" + hex(_internalMetaAfter));
        }
    }

    public int getComputedMetaState(KeyEvent keyEvent) {
        int _sysMeta = keyEvent.getMetaState();
        boolean _isPhysical = KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent);
        int _result;
        String _path;
        
        if (_isPhysical) {
            int iA = getMetaStateKcmOverride((CharSequence) this.mMetaKeyText, keyEvent);
            if (isAltGrPressed()) {
                iA |= META_SHIFT_LOCKED;
            }
            _result = this.mKeyInterpreter.apply(iA);
            _path = "physical_kcmOverride";
        } else if (isOverrideDeviceMetaStateEnabled()) {
            int metaState = _sysMeta | getMetaState((CharSequence) this.mMetaKeyText);
            if (isAltGrPressed()) {
                metaState |= META_SHIFT_LOCKED;
            }
            _result = this.mKeyInterpreter.apply(metaState);
            _path = "override_merge";
        } else {
            _result = _sysMeta;
            _path = "system_only";
        }

        if (BuildConfig.DEBUG) {
        android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
            " where=PhysicalKeyboardStateTracker#getComputedMetaState" +
            " path=" + _path +
            " sysMeta=" + hex(_sysMeta) +
            " spanMeta=" + hex(getMetaState((CharSequence) this.mMetaKeyText)) +
            " isPhysical=" + _isPhysical +
            " overrideEnabled=" + isOverrideDeviceMetaStateEnabled() +
            " altGrPressed=" + isAltGrPressed() +
            " computed=" + hex(_result));
        }

        return _result;
    }

    private int computeInternalMetaState() {
        int iA = getMetaState((CharSequence) this.mMetaKeyText);
        if (isAltGrPressed()) {
            iA |= 1;
        }
        return this.mKeyInterpreter.apply(iA);
    }

    /**
     * The only place modifier state is cleared (scope per {@link ModifierResetReason}); every
     * path republishes.
     */
    public void resetModifiers(ModifierResetReason reason) {
        // Board-key down/up pairing is per-key press state; BoardKeyPressTracker decides per reason.
        BoardKeyPressTracker.getInstance().onModifiersReset(reason);
        switch (reason.scope()) {
            case ALT_ONLY:
                resetAltState(this.mMetaKeyText);
                resetAltKeyState();
                break;
            case MANUAL_SHIFT_ONLY:
                if (!isManualShiftAndShiftPressing()) {
                    return;
                }
                resetShiftState(this.mMetaKeyText);
                break;
            case ALL:
            default:
                resetMetaState((Spannable) this.mMetaKeyText);
                this.mKeysHeld.clear();
                this.mKeyInterpreter = KeyCharacterInterpreter.MetaMask.IDENTITY;
                this.mAltUsedWithKey = false;
                resetKeyState();
                // Force: the system may have dropped the last post, so clear the slot regardless.
                publishModifierState(true);
                // Every full reset but a reload is a session boundary. After the publish, so the
                // reset's own post does not use up the next show's re-assert.
                ModifierStateListener listener = this.mModifierStateListener;
                if (listener != null && reason != ModifierResetReason.KEYBOARD_RELOADED) {
                    listener.onInputSessionEnded();
                }
                return;
        }
        publishModifierState(false);
    }

    /** Clears everything; prefer {@link #resetModifiers(ModifierResetReason)} with a real reason. */
    public void resetAllMetaState() {
        resetModifiers(ModifierResetReason.UNSPECIFIED);
    }

    /**
     * Re-posts the modifier status icon, changed or not. The system clears the slot on unbind and
     * drops posts made before the IME is attached, while the updater only posts on change.
     */
    public void refreshModifierStatus() {
        publishModifierState(true);
    }

    public void resetAltStateAndNotify() {
        resetModifiers(ModifierResetReason.ALT_PAGE_LEFT);
    }

    /**
     * @param i the current editor input type (0 clears AltGr)
     * @param aVar the keyboard's meta mask
     */
    public void updateFilterAndAltGr(int i, KeyCharacterInterpreter.MetaMask aVar) {
        this.mKeyInterpreter = aVar;
        if (isShiftReleased()) {
            setAltGrPressed((!hasInternalMetaFlag(KeyEvent.META_SHIFT_ON) || isAltGrPressed()) && i != 0);
        }
        publishModifierState(false);
    }

    /** @param i the key code */
    public static boolean isModifierKey(int i) {
        return isShiftKey(i) || isAltKey(i);
    }

    /**
     * Whether to track this event as a modifier. Unlike {@link #isModifierKey(int)}, a key the
     * device config gives a board role (SYM/EMOJI/VOICE) is not a modifier even if the ROM sends a
     * modifier keycode (the MP01 maps its Sym scancode 249 to KEY_RIGHTALT).
     */
    public static boolean isModifierKeyEvent(int keyCode, KeyEvent keyEvent) {
        return isModifierKey(keyCode) && !isBoardRoleEvent(keyEvent);
    }

    /** True when the device config gives this key a board role (SYM/EMOJI/VOICE). */
    public static boolean isBoardRoleEvent(KeyEvent keyEvent) {
        if (keyEvent == null) {
            return false;
        }
        dev.bbkb.ime.core.device.config.model.ScancodeMapping mapping =
                dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
                        .keyRole(keyEvent.getScanCode(), keyEvent.getKeyCode(),
                                keyEvent.getDeviceId());
        return mapping != null && mapping.role != null
                && mapping.role.isConsumedAtAccessibilityLevel();
    }

    /**
     * @param abstractC0918e the tracker whose key repeated
     * @param i the repeating key code
     */
    @Override // KeyRepeatHandler.Callback
    public void onKeyRepeat(MetaKeyStateTracker abstractC0918e, int i) {
        // No-op: modifier lock is double-tap only.
    }

    /** Cancels the long-press and double-tap timers (safe at any time). */
    public void cancelModifierTimers() {
        super.cancelLongPressTimer();
        super.cancelDoubleTapTimer();
    }

    public void clearManualShift() {
        resetModifiers(ModifierResetReason.MANUAL_SHIFT_SPENT);
    }

    /** @param i the modifier key code */
    private void markModifierPressed(int i) {
        switch (i) {
            case 57:
                this.mAltState[0] = -1;
                break;
            case 58:
                this.mAltState[1] = -1;
                break;
            case 59:
                this.mShiftState[0] = -1;
                break;
            case 60:
                this.mShiftState[1] = -1;
                break;
        }
        commitModifierState(false);
    }

    /** @param z consume held modifiers even when only one key is down */
    private void commitModifierState(boolean z) {
        if (z || getNumberOfKeysDown() > 1) {
            super.cancelLongPressTimer();
            super.cancelDoubleTapTimer();
            markShiftConsumed();
            markAltConsumed();
        }
    }

    private void markShiftConsumed() {
        int[] iArr = this.mShiftState;
        if (iArr[0] == -1) {
            iArr[0] = -3;
        }
        int[] iArr2 = this.mShiftState;
        if (iArr2[1] == -1) {
            iArr2[1] = -3;
        }
    }

    private void markAltConsumed() {
        int[] iArr = this.mAltState;
        if (iArr[0] == -1) {
            iArr[0] = -3;
        }
        int[] iArr2 = this.mAltState;
        if (iArr2[1] == -1) {
            iArr2[1] = -3;
        }
    }

    /** @param i the modifier key code */
    private void markModifierReleased(int i) {
        switch (i) {
            case 57:
                this.mAltState[0] = -2;
                break;
            case 58:
                this.mAltState[1] = -2;
                break;
            case 59:
                this.mShiftState[0] = -2;
                break;
            case 60:
                this.mShiftState[1] = -2;
                break;
        }
        if (isShiftReleased()) {
            this.mAltUsedWithKey = false;
        }
    }

    private void resetKeyState() {
        this.mKeysHeld.clear();
        int[] iArr = this.mShiftState;
        iArr[0] = -2;
        iArr[1] = -2;
        int[] iArr2 = this.mAltState;
        iArr2[0] = -2;
        iArr2[1] = -2;
    }

    private void resetAltKeyState() {
        int[] iArr = this.mAltState;
        iArr[0] = -2;
        iArr[1] = -2;
    }

    @Override // SymbolPageProvider
    public int getSymbolPageOrder() {
        int state;
        if (isAltGrPressed()) {
            state = 5;
        } else if (hasMetaFlag(1)) {
            state = 1;
        } else {
            state = hasInternalMetaFlag(META_SHIFT_LOCKED) ? 3 : 0;
        }
        return state;
    }

    // Package-private on purpose: outside readers use getModifierState(); tests compare the two.

    /** A Shift key is DOWN (-1); false for one that is down but CONSUMED. */
    boolean isShiftKeyDown() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -1 || iArr[1] == -1;
    }

    /** Both Shift keys are RELEASED (-2). */
    boolean isShiftReleased() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -2 && iArr[1] == -2;
    }

    /** A Shift key is still down but CONSUMED (-3). */
    boolean isShiftKeyConsumed() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -3 || iArr[1] == -3;
    }

    /** @param i the meta flag bits to test */
    public boolean hasInternalMetaFlag(int i) {
        return (i & computeInternalMetaState()) != 0;
    }

    /** @param i the meta flag bits to test */
    public boolean hasMetaFlag(int i) {
        return (getMetaState((CharSequence) this.mMetaKeyText) & i) != 0 && (this.mKeyInterpreter.apply(i) & i) == i;
    }

    @Override // SymbolPageProvider
    public boolean isManualShiftAndShiftPressing() {
        return hasMetaFlag(KeyEvent.META_SHIFT_ON) && isShiftReleased();
    }

    public boolean isAltKeyDown() {
        int[] iArr = this.mAltState;
        return iArr[0] == -1 || iArr[1] == -1;
    }

    public boolean isAltKeyConsumed() {
        int[] iArr = this.mAltState;
        return iArr[0] == -3 || iArr[1] == -3;
    }

    public boolean isAltUsedWithKey() {
        return this.mAltUsedWithKey;
    }

    /** Interpreted meta state; new readers use {@link ModifierState#getInterpretedMetaState()}. */
    public int getInternalMetaState() {
        return computeInternalMetaState();
    }

    /**
     * Meta state from real modifier key presses only: {@link #getInternalMetaState()} without
     * AltGr and the keyboard's MetaMask (the symbol board's Alt page adds META_ALT_ON). Chord
     * detection must use this, or the Alt page reads as a held Alt.
     */
    public int getModifierKeyMetaState() {
        return getMetaState((CharSequence) this.mMetaKeyText);
    }


    public int getNumberOfKeysDown() {
        return this.mKeysHeld.size();
    }

    /** Whether {@code keyCode} is counted as physically held. */
    public boolean isKeyHeld(int keyCode) {
        return this.mKeysHeld.get(keyCode, false);
    }

    /** Set by the key-event path, which resolves Sym (a BOARD_SYM scancode mapping outranks the ROM keycode). */
    public void setSymKeyCode(int keyCode) {
        this.mSymKeyCode = keyCode;
    }

    /** Whether Sym is held; keeps the PKB symbol board open for more than one symbol. */
    public boolean isSymKeyHeld() {
        return this.mKeysHeld.get(this.mSymKeyCode, false);
    }

    /** True if Alt is PRESSED (held), as opposed to STICKY or LOCKED. */
    public boolean isAltPressed() {
        return isAltSpanPressed(this.mMetaKeyText);
    }
}
