package dev.bbkb.ime.core.device.state;

import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.View;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.SymbolPageProvider;
import dev.bbkb.ime.core.keyevent.ModifierStatusBarUpdater;
import dev.bbkb.ime.core.keyevent.KeyCharacterInterpreter;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.BuildConfig;


public final class PhysicalKeyboardStateTracker extends MetaKeyStateTracker implements SymbolPageProvider {

    // ===== ALT CHORD DEBUG LOGGING HELPER =====
    private static String hex(int v) { return "0x" + Integer.toHexString(v); }
    // ==========================================

    private ModifierStatusBarUpdater mStatusBarUpdater;

    private Editable mMetaKeyText = new SpannableStringBuilder("");

    private int[] mShiftState = {-2, -2};

    private int[] mAltState = {-2, -2};

    private KeyCharacterInterpreter.MetaMask mKeyInterpreter = KeyCharacterInterpreter.MetaMask.IDENTITY;

    /**
     * The key codes of the physical keys currently held down — the set behind
     * {@link #getNumberOfKeysDown()}.
     *
     * <p>This used to be a bare {@code int} counter, incremented on every physical key-down and
     * decremented on every physical key-up. The two halves are not reliably paired: a key-down
     * increments unconditionally in {@code KeyEventProcessor.onKeyDownInternal}, while the
     * matching decrement runs only for a key the event was <em>tracked</em> for, and several
     * key-up branches (control-mode chords, the Cangjie shift, Alt+Enter) return before the
     * tracker is told anything at all. The accessibility all-keys callback can also pre-process
     * the same Alt press the IME then handles itself. Every one of those leaks left the counter
     * permanently positive, and a permanently positive counter permanently disables
     * {@code FccController.showFcc()}, whose one extra precondition is "no physical key is held".
     *
     * <p>A set of held key codes is self-healing where a counter is not: a duplicate down for a
     * key already held counts once, an up for a key that was never counted is a no-op, and a
     * leaked key is discharged by the next press-and-release of that same key rather than
     * surviving until the process (or the input connection) restarts.
     */
    private final SparseBooleanArray mKeysHeld = new SparseBooleanArray(4);

    private boolean mAltUsedWithKey = false;

    public PhysicalKeyboardStateTracker(ModifierStatusBarUpdater c0714p) {
        this.mStatusBarUpdater = c0714p;
    }

    public void onModifierListenerReset() {
        resetAllMetaState();
    }

    public void handleKeyDown(int i, KeyEvent keyEvent) {
        if (KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent) && keyEvent.getRepeatCount() == 0) {
            // ===== ALT CHORD DEBUG: handleKeyDown =====
            if (BuildConfig.DEBUG) {
            int _internalMetaBefore = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyDown" +
                " keyCode=" + i +
                " isModifier=" + isModifierKey(i) +
                " keysDownBefore=" + getNumberOfKeysDown() +
                " internalMetaBefore=" + hex(_internalMetaBefore));
            }
            // =============================================
            
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
                ModifierStatusBarUpdater c0714p = this.mStatusBarUpdater;
                if (c0714p != null) {
                    c0714p.updateModifierStatus(computeInternalMetaState(), false);
                }
            }
            
            // ===== ALT CHORD DEBUG: handleKeyDown after =====
            if (BuildConfig.DEBUG) {
            int _internalMetaAfter = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyDown_after" +
                " keyCode=" + i +
                " keysDownAfter=" + getNumberOfKeysDown() +
                " internalMetaAfter=" + hex(_internalMetaAfter));
            }
            // =============================================
        }
    }

    public void handleKeyUp(int i, KeyEvent keyEvent) {
        if (KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent)) {
            // ===== ALT CHORD DEBUG: handleKeyUp =====
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
            // =============================================
            
            this.mKeysHeld.delete(i);
            if (isModifierKeyEvent(i, keyEvent)) {
                super.onKeyUp(null, this.mMetaKeyText, i, keyEvent, trackPrev);
                markModifierReleased(i);
                ModifierStatusBarUpdater c0714p = this.mStatusBarUpdater;
                if (c0714p != null) {
                    c0714p.updateModifierStatus(computeInternalMetaState(), false);
                }
            }
            
            // ===== ALT CHORD DEBUG: handleKeyUp after =====
            if (BuildConfig.DEBUG) {
            int _internalMetaAfter = getInternalMetaState();
            android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
                " where=PhysicalKeyboardStateTracker#handleKeyUp_after" +
                " keyCode=" + i +
                " keysDownAfter=" + getNumberOfKeysDown() +
                " internalMetaAfter=" + hex(_internalMetaAfter));
            }
            // =============================================
        }
    }

    public void consumeModifiersAfterKey(int i, boolean z) {
        // ===== ALT CHORD DEBUG: consumeModifiersAfterKey CRITICAL =====
        if (BuildConfig.DEBUG) {
        int _internalMetaBefore = getInternalMetaState();
        android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
            " where=PhysicalKeyboardStateTracker#consumeModifiersAfterKey" +
            " keyCode=" + i +
            " isPhysicalKb=" + z +
            " isModifierKey=" + KeyEvent.isModifierKey(i) +
            " internalMetaBefore=" + hex(_internalMetaBefore));
        }
        // =============================================
        
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
        
        // ===== ALT CHORD DEBUG: consumeModifiersAfterKey after =====
        if (BuildConfig.DEBUG) {
        int _internalMetaAfter = getInternalMetaState();
        android.util.Log.d("AltChordDebug", "ts=" + android.os.SystemClock.uptimeMillis() +
            " where=PhysicalKeyboardStateTracker#consumeModifiersAfterKey_after" +
            " internalMetaAfter=" + hex(_internalMetaAfter));
        }
        // =============================================
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
            // The span meta and the override flag used to be computed unconditionally above,
            // but the _isPhysical branch -- the one every KEY2 keystroke takes -- uses neither
            // (getMetaStateKcmOverride recomputes the span meta itself), so outside
            // BuildConfig.DEBUG they were a wasted 4x getSpanFlags scan plus a
            // SettingsManager hop on the per-keystroke meta-state computation.
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
        
        // ===== ALT CHORD DEBUG: getComputedMetaState CRITICAL =====
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
        // =============================================
        
        return _result;
    }

    private int computeInternalMetaState() {
        int iA = getMetaState((CharSequence) this.mMetaKeyText);
        if (isAltGrPressed()) {
            iA |= 1;
        }
        return this.mKeyInterpreter.apply(iA);
    }

    public void resetAllMetaState() {
        resetMetaState((Spannable) this.mMetaKeyText);
        this.mKeysHeld.clear();
        this.mKeyInterpreter = KeyCharacterInterpreter.MetaMask.IDENTITY;
        this.mAltUsedWithKey = false;
        resetKeyState();
        ModifierStatusBarUpdater c0714p = this.mStatusBarUpdater;
        if (c0714p != null) {
            c0714p.updateModifierStatus(0, true);
        }
    }

    public void resetAltStateAndNotify() {
        resetAltState(this.mMetaKeyText);
        resetAltKeyState();
        ModifierStatusBarUpdater c0714p = this.mStatusBarUpdater;
        if (c0714p != null) {
            c0714p.updateModifierStatus(computeInternalMetaState(), false);
        }
    }

    public void updateFilterAndAltGr(int i, KeyCharacterInterpreter.MetaMask aVar) {
        this.mKeyInterpreter = aVar;
        if (isShiftReleased()) {
            setAltGrPressed((!hasInternalMetaFlag(KeyEvent.META_SHIFT_ON) || isAltGrPressed()) && i != 0);
        }
        ModifierStatusBarUpdater c0714p = this.mStatusBarUpdater;
        if (c0714p != null) {
            c0714p.updateModifierStatus(computeInternalMetaState(), false);
        }
    }

    public static boolean isModifierKey(int i) {
        return isShiftKey(i) || isAltKey(i);
    }

    /**
     * Whether this key event should be tracked as a modifier press/release.
     *
     * <p>Differs from {@link #isModifierKey(int)} in one case, and it is the case the Minimal
     * Phone MP01 turns on: a key whose device config gives it a board role (SYM / EMOJI / VOICE)
     * is a board key even when the ROM's key layout attached a modifier keycode to it. The MP01
     * maps the Sym key's scancode 249 to {@code KEY_RIGHTALT}, so every Sym press used to land
     * here as a {@code KEYCODE_ALT_RIGHT} down and latch sticky Alt — the second half of "Sym key
     * does nothing" (beta triage #10, 2026-09). The scancode is what identifies the key; the
     * keycode is just what the .kl file says.
     *
     * <p>Returns {@link #isModifierKey(int)} unchanged whenever no device config matched, so
     * devices without {@code <scancode-mappings>} are untouched.
     */
    public static boolean isModifierKeyEvent(int keyCode, KeyEvent keyEvent) {
        return isModifierKey(keyCode) && !isBoardRoleEvent(keyEvent);
    }

    /**
     * True when the active device config classifies this event's key as a board key
     * (SYM / EMOJI / VOICE), i.e. one whose press opens a board rather than typing or modifying.
     */
    public static boolean isBoardRoleEvent(KeyEvent keyEvent) {
        if (keyEvent == null) {
            return false;
        }
        dev.bbkb.ime.core.device.config.model.ScancodeMapping mapping =
                dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
                        .getInstance().resolve(keyEvent.getScanCode(), keyEvent.getKeyCode());
        return mapping != null && mapping.role != null
                && mapping.role.isConsumedAtAccessibilityLevel();
    }

    @Override // KeyRepeatHandler.Callback
    public void onKeyRepeat(MetaKeyStateTracker abstractC0918e, int i) {
        // No-op: modifier lock is double-tap only. KeyRepeatHandler still needs the callback
        // for the Shift symbol-page side-effects (onModifierKeyRepeatStart/End).
    }

    /** Cancels the long-press and double-tap timers. Both are harmless to cancel at any time. */
    public void cancelModifierTimers() {
        super.cancelLongPressTimer();
        super.cancelDoubleTapTimer();
    }

    public void clearManualShift() {
        if (isManualShiftAndShiftPressing()) {
            resetShiftState(this.mMetaKeyText);
            ModifierStatusBarUpdater c0714p = this.mStatusBarUpdater;
            if (c0714p != null) {
                c0714p.updateModifierStatus(computeInternalMetaState(), false);
            }
        }
    }

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

    public boolean isShiftKeyDown() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -1 || iArr[1] == -1;
    }

    public boolean isShiftReleased() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -2 && iArr[1] == -2;
    }

    public boolean isShiftKeyConsumed() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -3 || iArr[1] == -3;
    }

    public boolean hasInternalMetaFlag(int i) {
        return (i & computeInternalMetaState()) != 0;
    }

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


    public int getInternalMetaState() {
        return computeInternalMetaState();
    }

    /**
     * The modifier state the user's own modifier KEYS have established: held, sticky or locked
     * Shift/Alt/Ctrl from real key presses, and nothing else.
     *
     * <p>This is {@link #getInternalMetaState()} minus two things that method deliberately adds:
     * the AltGr flag and, above all, the current keyboard's {@link KeyCharacterInterpreter.MetaMask}.
     * On a PKB the symbol board's Alt page is such a mask — it adds {@code META_ALT_ON} so letter
     * keys type their Alt-layer symbols — which is exactly right for character interpretation and
     * exactly wrong for asking "is the user holding Alt?". The Alt+Sym chord read the masked state
     * and took the symbol board's own Alt page for a chord, so paging with Sym landed on the emoji
     * board (owner report, KEY2, 2026-09-21). Chord detection must use this method.
     */
    public int getModifierKeyMetaState() {
        return getMetaState((CharSequence) this.mMetaKeyText);
    }


    public int getNumberOfKeysDown() {
        return this.mKeysHeld.size();
    }

    /** Whether {@code keyCode} is currently counted as a physically held key. */
    public boolean isKeyHeld(int keyCode) {
        return this.mKeysHeld.get(keyCode, false);
    }

    /**
     * Returns true if Alt is currently in PRESSED state (physically held down),
     * as opposed to STICKY (one-shot) or LOCKED state. Used to distinguish
     * chorded Alt (held) from sticky Alt (tap-release).
     */
    public boolean isAltPressed() {
        return isAltSpanPressed(this.mMetaKeyText);
    }
}
