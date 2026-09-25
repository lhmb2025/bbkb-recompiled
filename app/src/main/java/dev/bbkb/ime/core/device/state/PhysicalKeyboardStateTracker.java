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

    // ===== ALT CHORD DEBUG LOGGING HELPER =====
    private static String hex(int v) { return "0x" + Integer.toHexString(v); }
    // ==========================================

    /**
     * The one place the modifier state is published. {@link ModifierStatusBarUpdater} is the
     * listener in the IME; a test can substitute its own. See {@link #publishModifierState}.
     */
    private ModifierStateListener mModifierStateListener;

    /**
     * Where "is Ctrl active" is answered from — {@code ControlModeController}, the single owner of
     * Ctrl state. This tracker deliberately keeps no copy: its own "CTRL_SPAN" is the Sym key
     * (key code 63 sets {@code META_SYM_ON}), and the only other Ctrl bookkeeping in the app is
     * {@code BlackBerryIME.multifunctionCtrlDown}, which exists solely to rewrite a multifunction
     * key into a real {@code KEYCODE_CTRL_LEFT} before the controller sees it.
     */
    private CtrlStateSource mCtrlStateSource;

    /** Supplies {@link ModifierState#isCtrlActive()} from the one Ctrl owner. */
    public interface CtrlStateSource {
        boolean isCtrlActive();
    }

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

    /**
     * The key code the Sym key arrives as on this device, for {@link #isSymKeyHeld()}.
     *
     * <p>{@code KEYCODE_SYM} on BlackBerry hardware, but the MP01's ROM attaches
     * {@code KEYCODE_ALT_RIGHT} to its Sym scancode, so the key-event path tells us which one it
     * resolved ({@code KeyEventProcessor.setSymKeyCode}) rather than this class guessing. The
     * default is the BlackBerry answer, which is also the right answer for any device whose
     * key-event path never gets as far as saying otherwise.
     */
    private int mSymKeyCode = KeyEvent.KEYCODE_SYM;

    private boolean mAltUsedWithKey = false;

    public PhysicalKeyboardStateTracker(ModifierStatusBarUpdater statusBarUpdater) {
        // The updater is held only as the modifier-state listener: it is one consumer of the
        // single feed, not a second owner of the state.
        this.mModifierStateListener = statusBarUpdater;
    }

    /** Replace the single modifier-state listener (the status bar in the IME). */
    public void setModifierStateListener(ModifierStateListener listener) {
        this.mModifierStateListener = listener;
    }

    /** Name the one owner of Ctrl state, so {@link ModifierState#isCtrlActive()} can answer. */
    public void setCtrlStateSource(CtrlStateSource source) {
        this.mCtrlStateSource = source;
    }

    public void onModifierListenerReset() {
        resetModifiers(ModifierResetReason.UNSPECIFIED);
    }

    // ============================================================ the one query API

    /**
     * The active modifiers right now, with no key event in the picture.
     *
     * <p>Every "is Shift/Alt/Ctrl/Sym on, and in what way" question in the physical-keyboard path
     * is meant to be asked here. The three meta states it carries are not interchangeable; see
     * {@link ModifierState} for which is which and why confusing them broke the Alt+Sym chord.
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
                .shiftSurvivesMask(
                        (this.mKeyInterpreter.apply(KeyEvent.META_SHIFT_ON) & KeyEvent.META_SHIFT_ON) != 0)
                .keysHeld(getNumberOfKeysDown())
                .build();
    }

    /**
     * The single status-bar / modifier-state feed. Every transition in this class ends here, and
     * nothing else posts: one source of truth, one notification path.
     *
     * @param force re-assert even when nothing changed (see {@link ModifierStateListener})
     */
    private void publishModifierState(boolean force) {
        ModifierStateListener listener = this.mModifierStateListener;
        if (listener != null) {
            listener.onModifierStateChanged(getModifierState(), force);
        }
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
                publishModifierState(false);
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
                publishModifierState(false);
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
        // A sticky Shift/Alt that this key has just spent is no longer on, and the status-bar
        // icon has to follow it down here: the modifier key's own key-up already ran (that is
        // what made it sticky), so no later event reports this transition. Without it the icon
        // sat on the last modifier the user tapped, and — because the updater only posts on a
        // CHANGE — the next press of that same modifier then posted nothing at all.
        publishModifierState(false);

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

    /**
     * The one reset entry. What each reason clears is stated on {@link ModifierResetReason}; this
     * method is the only place that clears modifier state, and every path out of it republishes
     * through {@link #publishModifierState}.
     */
    public void resetModifiers(ModifierResetReason reason) {
        // Phase 1g: the emoji / mic / multifunction down-up pairing is per-key press state, and it
        // belongs to the same "this key is down" picture as mKeysHeld — so it goes when that goes.
        // The owner decides which reasons clear it (a full reset does, leaving the Alt page does
        // not); this call is unconditional and the policy lives in BoardKeyPressTracker.
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
                // Force: the resulting state is "nothing set", and the system may have dropped
                // the last post, so the slot has to be cleared whether or not we think it moved.
                publishModifierState(true);
                return;
        }
        publishModifierState(false);
    }

    /**
     * Clear everything. Kept for the call sites outside this phase's ownership
     * (the IME lifecycle, the screen-off receiver, the keyboard switcher); prefer
     * {@link #resetModifiers(ModifierResetReason)} with the reason that applies.
     */
    public void resetAllMetaState() {
        resetModifiers(ModifierResetReason.UNSPECIFIED);
    }

    /**
     * Re-post the modifier status icon for the state we are in right now, changed or not.
     *
     * <p>The IME calls this when its window comes up and when an input view starts. The status-bar
     * slot is owned by the system and is cleared on every IME unbind, and a post made before the
     * IME's privileged operations are attached is dropped on the floor — in both cases the
     * updater's "last posted" cache goes on claiming the icon is up, and since it only posts on a
     * change, nothing ever puts it back.
     */
    public void refreshModifierStatus() {
        publishModifierState(true);
    }

    /** Leave the Alt page. See {@link ModifierResetReason#ALT_PAGE_LEFT}. */
    public void resetAltStateAndNotify() {
        resetModifiers(ModifierResetReason.ALT_PAGE_LEFT);
    }

    public void updateFilterAndAltGr(int i, KeyCharacterInterpreter.MetaMask aVar) {
        this.mKeyInterpreter = aVar;
        if (isShiftReleased()) {
            setAltGrPressed((!hasInternalMetaFlag(KeyEvent.META_SHIFT_ON) || isAltGrPressed()) && i != 0);
        }
        publishModifierState(false);
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

    /** A commit spent a manual Shift. See {@link ModifierResetReason#MANUAL_SHIFT_SPENT}. */
    public void clearManualShift() {
        resetModifiers(ModifierResetReason.MANUAL_SHIFT_SPENT);
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

    // The three per-key Shift predicates below are the storage the snapshot is built from. They
    // are package-private on purpose (Phase 1h): readers outside this class ask
    // getModifierState().isShiftHeld() / isShiftReleased() / isShiftConsumed(), which answer the
    // same in every state of the Shift lifecycle (ShiftLifecycleTest pins that), so there is one
    // way to ask. They stay visible to this package's tests, which compare the two.

    /** A Shift key is DOWN (−1). Not true for a key that is down but CONSUMED. */
    boolean isShiftKeyDown() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -1 || iArr[1] == -1;
    }

    /** Both Shift keys are RELEASED (−2): none is DOWN and none is CONSUMED. */
    boolean isShiftReleased() {
        int[] iArr = this.mShiftState;
        return iArr[0] == -2 && iArr[1] == -2;
    }

    /** A Shift key is still physically down but CONSUMED (−3). */
    boolean isShiftKeyConsumed() {
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


    /**
     * The interpreted meta state — see {@link ModifierState#getInterpretedMetaState()}, which is
     * where new readers should ask. Still public for the IME-level call sites outside this
     * phase's ownership.
     */
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
     * Tell the tracker which key code the Sym key arrives as on this device. Called from the
     * key-event path, which is the only place that can resolve it (a scancode mapping with the
     * {@code BOARD_SYM} role outranks whatever key code the ROM attached).
     */
    public void setSymKeyCode(int keyCode) {
        this.mSymKeyCode = keyCode;
    }

    /**
     * Whether the Sym key is physically held down right now.
     *
     * <p>This is what keeps the PKB symbol board open for more than one symbol: a symbol board
     * closes itself after the symbol that was typed on it, unless the user is holding Sym, in
     * which case the board stays up and symbols keep going in until Sym is released. Answered
     * from the held-key set, so it inherits that set's self-healing: a Sym press whose release
     * was swallowed is discharged by the next press-and-release of the same key.
     */
    public boolean isSymKeyHeld() {
        return this.mKeysHeld.get(this.mSymKeyCode, false);
    }

    /**
     * Returns true if Alt is currently in PRESSED state (physically held down),
     * as opposed to STICKY (one-shot) or LOCKED state. Used to distinguish
     * chorded Alt (held) from sticky Alt (tap-release).
     */
    public boolean isAltPressed() {
        return isAltSpanPressed(this.mMetaKeyText);
    }

    /**
     * @deprecated in intent only — see {@link ModifierState#isAltHeldBySpan()}, which is the same
     *     answer asked through the one query API. Not annotated {@code @Deprecated} because the
     *     remaining callers live in files this phase does not own.
     */
}
