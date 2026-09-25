package dev.bbkb.ime.core.keyevent;

import android.content.res.Resources;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.builder.HardwareKeyCaptureBus;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPalettesView;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

/**
 * Processes physical keyboard events extracted from BlackBerryIME.
 * Handles onKeyDown, onKeyUp, processKeyEventForState, and related helpers.
 *
 * This class reduces BlackBerryIME's responsibility count by encapsulating
 * the key event processing pipeline into a focused processor.
 */
public class KeyEventProcessor {

    private static final String TAG = "KeyEventProcessor";

    /** Cancel token for the emoji dynamic-search composing-text push (audit CT-3). */
    private static final Object EMOJI_SEARCH_TOKEN = new Object();

    private final BlackBerryIME ime;

    public KeyEventProcessor(BlackBerryIME ime) {
        this.ime = ime;
    }

    /**
     * Process a key event for state updates only (shift, alt, symbol mode).
     * Called by the accessibility service when pre-processing keys without an active text field.
     */
    public void processKeyEventForState(KeyEvent event) {
        // Phase 1g: WHICH KEY IS THIS, answered by the same ResolvedKey the ordinary key path
        // asks. No remap happens on this path — the accessibility service hands the raw event
        // straight over — so this is the event every branch below acts on.
        //
        // It used to be a raw `keyCode == KEYCODE_SYM` with no mapping resolution at all, and
        // that was the one place in the key path where identity was decided by the key code
        // alone. On an MP01 the Sym key arrives as KEYCODE_ALT_RIGHT on scancode 249
        // (device_config_minimal.xml gives that scancode the BOARD_SYM role), and
        // HardwareKeyBridge's all-keys callback routes every KEYCODE_ALT_RIGHT into this method
        // AND consumes it — so with the interceptor on, the MP01's Sym key arrived here, was not
        // recognised as Sym, and did nothing at all. Owner decision, 2026-09-22: both paths use
        // the same identity. That is a deliberate MP01-only behaviour change; on a KEY2 the
        // shipped device_config_athena.xml names no Sym key, so the mapping-aware test falls back
        // to KEYCODE_SYM and answers exactly what the old comparison answered
        // (SymPrePassIdentityTest pins both halves).
        final ResolvedKey key = ResolvedKey.of(event);
        int keyCode = key.keyCode();
        int action = event.getAction();

        PhysicalKeyboardStateTracker tracker = ime.getPhysicalKeyboardStateTracker();

        // Snapshot the Alt state BEFORE the tracker sees this event. Two reasons, both of which
        // dropped the Alt+Sym chord here:
        //
        //  1. This used to pass `tracker.getInternalMetaState()` and nothing else, so a
        //     physically HELD Alt — which lives in the event's own meta state — was invisible.
        //  2. `tracker.handleKeyDown()` resets the internal Alt span for key code 63
        //     (KEYCODE_SYM) on every profile whose usesMetaSymHandling() is false, so even a
        //     sticky or locked Alt was already gone by the time the old code read it.
        // ModifierState.getChordMetaState(), not the interpreted state: the latter is passed
        // through the current keyboard's meta mask, and the symbol board's Alt page is a mask
        // that adds Alt — reading it here made the second/third Sym press look like Alt+Sym
        // (KEY2, 2026-09-21).
        int effectiveMeta = (tracker != null ? tracker.getModifierState(event) : ModifierState.ofEvent(event))
                .getChordMetaState();

        if (tracker != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                tracker.handleKeyDown(keyCode, event);
            } else if (action == KeyEvent.ACTION_UP) {
                tracker.handleKeyUp(keyCode, event);
            }
        }

        if (!key.isSymKey()) {
            return;
        }
        if (tracker != null) {
            // Same note as in onKeyDownInternal: name the Sym key to the tracker so its
            // isSymKeyHeld() ("hold Sym to keep the symbol board open") can answer. On a device
            // whose Sym key is not KEYCODE_SYM this is the key code the ROM attached — which is
            // the point, and is what the ordinary key path has always recorded.
            tracker.setSymKeyCode(keyCode);
        }
        AltSymShortcutHandler altSym = ime.getHardwareKeys().getAltSymShortcutHandler();
        if (action == KeyEvent.ACTION_UP) {
            // The press already dispatched the configured action; its release is not a second
            // Sym press and must not reach the symbol board.
            altSym.consumeSymKeyUp();
            return;
        }
        if (action != KeyEvent.ACTION_DOWN) {
            return;
        }
        if (altSym.detectAndExecute(effectiveMeta)) {
            altSym.markSymPressConsumed();
            if (tracker != null) {
                tracker.consumeModifiersAfterKey(0, true);
            }
            return;
        }
        altSym.clearPendingSymKeyUp();
        KeyboardSwitcher ks = ime.getKeyboardSwitcher();
        if (ks != null) {
            ks.onSymbolShiftToggle(
                    ime.getCurrentInputType(),
                    ime.getCurrentImeOptions(),
                    false, true);
        }
    }

    /**
     * Show-on-keypress: summon the IME window for a hardware key press that arrived while it was
     * hidden, and report whether the input view is showing afterwards.
     *
     * <p>Extracted from {@link #onKeyDownInternal} so the accessibility-service SYM path can use
     * it too. A SYM press opens a board, and a board lives in the IME window, so "the window is
     * not up yet" has to mean "put it up", not "do nothing" — on a PKB device the window is
     * routinely down while the user types on the hardware keyboard, which is most of the time
     * (beta triage #10, MP01).
     *
     * <p>The caller is responsible for establishing that the event came from a physical keyboard;
     * the rest of the original gate is reproduced here unchanged, including ISSUE 3's requirement
     * of a real editor binding ({@code getCurrentInputBinding() != null}) before auto-summoning.
     * The prior gate only checked {@code isInputActive()}, which is true whenever an input
     * connection exists — including for transient bindings the system creates during home
     * transitions.
     *
     * @return true if the input view is (now) shown
     */
    public boolean requestShowOnKeyPress() {
        if (ime.isInputViewShown()) {
            return true;
        }
        if (!ime.isInputActive()
                || ime.getCurrentInputBinding() == null
                || !ime.onEvaluateInputViewShown()) {
            return false;
        }
        boolean z;
        switch (ime.getSettingsManager().getSettingsValues().showOnKeyPressMode) {
            case 0:
                z = ime.getWasBackPressed();
                break;
            case 1:
                z = ime.getShouldShowOnFirstKeypress();
                break;
            default:
                z = true;
                break;
        }
        if (!z && !EditorCapabilities.hasPrivateImeOption(
                ime.getPackageName(), "showOnKeyPress", ime.getCurrentInputEditorInfo())) {
            return false;
        }
        Logger.debug(TAG, "Showing IME for key press");
        ime.getRichInputMethodManager().getInputMethodManager().showSoftInputFromInputMethod(
                ime.getWindow().getWindow().getAttributes().token, 0);
        ime.updatePhysicalKeyboardFilter();
        ime.loadSettings();
        return true;
    }

    /**
     * Internal onKeyDown handler.
     * Extracted from BlackBerryIME.handlePhysicalKeyDown().
     */
    public boolean onKeyDownInternal(int i, KeyEvent keyEvent) throws Resources.NotFoundException {
        boolean zM5997a;
        InputEventContext c0920gM4442a;
        boolean z2 = false;

        // isInputActive goes stale-false when the window was hidden for a still-bound
        // editor (onWindowHidden clears it; re-show delivers no onStartInput). Heal it
        // before any of the gates below read it, or hardware keys bypass the whole
        // pipeline — dead autocomplete + dead SYM — while superOnKeyDown still types.
        ime.refreshInputActive();

        // Audit CT-12: the physical-keyboard classification is invariant for one event, and this
        // method used to evaluate it up to eight times (a singleton lookup plus a classification
        // each) on the KEY2's most latency-sensitive path.
        //
        // Phase 1e: this is the RAW event's classification, and it stays a plain local because
        // the four gates below it all run before remapKeyEvent — the keypad-layout detector and
        // the profile-builder capture bus both need the raw scancode/keycode, and the
        // "not a physical keyboard" bail-out has to happen before we remap anything. The identity
        // the rest of the method acts on is the ResolvedKey built from the REMAPPED event; this
        // local is deliberately never reassigned to it, which is what the old code did.
        final boolean rawIsPhysical =
                KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent);

        // §5.4: this used to initialize DeviceSettingsManager, the second facade over the same
        // resolved DeviceInputMapping. DeviceProfile is that facade now, and appContext() != null
        // is the same "has initialize() ever run" test DeviceSettingsManager.isInitialized() was.
        // In the IME this is already true (BlackBerryIME.onCreate initializes the profile before
        // any key can arrive); it stays as the safety net it always was, and resolving against
        // this event's own device id rather than scanning is what the targeted form is for.
        if (rawIsPhysical && DeviceProfile.appContext() == null) {
            DeviceProfile.initializeForDevice(ime.getApplicationContext(), keyEvent.getDeviceId());
            Logger.info(TAG, "DeviceProfile initialized for device ID: " + keyEvent.getDeviceId());
        }

        // Last-resort physical keypad layout detection (KeypadLayoutDetector source 5): what the
        // keypad actually sent is proof, and on a ROM that sets no keypad-language sysprop and
        // names its keypad generically it is the only proof there is. Reads the RAW event, before
        // remapKeyEvent — a remap rewrites the key code the detector fingerprints. Almost every
        // call is two integer compares; it returns true only on the rare keystroke that changes
        // the answer, and then the profile is rebuilt so the symbol rows and layout-set names
        // follow.
        if (rawIsPhysical && dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
                .observeKeyEvent(keyEvent.getScanCode(), keyEvent.getKeyCode())) {
            DeviceProfile.reinitializeForKeypadLayoutChange(
                    ime.getApplicationContext(), keyEvent.getDeviceId());
        }

        // Device-profile builder: while its capture screen is open it needs the RAW scancode and
        // keycode of the key the user just pressed, and it needs them before anything acts on the
        // key — the whole point of capturing is to find out what a key IS on this ROM, which is
        // not knowable from the role the current config assigns it. Consumed rather than
        // observed, so a Sym press during capture cannot open a board over the settings screen.
        // One volatile read when no capture is running, which is always, except on this screen.
        if (rawIsPhysical && HardwareKeyCaptureBus.offer(keyEvent.getScanCode(), keyEvent.getKeyCode(),
                keyEvent.getDeviceId(), keyEvent.getRepeatCount(), true, keyEvent.getEventTime())) {
            return true;
        }

        if (!rawIsPhysical) {
            // Audit CT-11: an empty `if (!ime.getFccController().isViewActive()) {}` stood here.
            // Its body had been deleted and the predicate left behind — an unguarded call that
            // would NPE once fccController is nulled in releaseResources, for zero benefit.
            ime.applyCursorModeState(false, false, false);
            return ime.superOnKeyDown(i, keyEvent);
        }

        // ISSUE 2 FIX: Route KEYCODE_BACK straight to super.onKeyDown so that
        // InputMethodService.onKeyDown can call getKeyDispatcherState().startTracking(),
        // which is what makes KeyEvent.isTracking() return true at onKeyUp time.
        // Without this, the BACK key is consumed by the AuxBar / KeyHold / InputLogic
        // pipelines, never reaches super.onKeyDown, and the dismiss-on-up branch in
        // onKeyUpInternal silently fails because event.isTracking() is false.
        if (i == KeyEvent.KEYCODE_BACK) {
            return ime.superOnKeyDown(i, keyEvent);
        }

        if (handleAltEnterLanguageSwitch(i, keyEvent)) {
            return true;
        }
        KeyEvent keyEventRemapped = ime.remapKeyEvent(i, keyEvent);

        // Phase 1e: WHICH KEY IS THIS, answered once, here, for the remapped event — the one
        // every branch below acts on. It carries the key code, the scancode, the physical-keyboard
        // classification (the remap can hand back a different KeyEvent, so this is its own
        // classification, not the raw one above), the device config's role, and the 666/667
        // pseudo-keycode translation. Board/multifunction keys are exempt from several "an
        // ordinary text key went down" behaviours below, including the Alt symbol-long-press
        // fallthroughs at the bottom of this method: the MP01's SYM key arrives as
        // KEYCODE_ALT_RIGHT (scancode 249), and isAltKey() would otherwise fire
        // onSymbolKeyLongPress() on top of the board toggle the key already performed.
        final ResolvedKey key = ResolvedKey.of(keyEventRemapped);
        final int keyCode = key.keyCode();

        // === Alt+Sym, on the ordinary hardware key path ===
        //
        // The chord used to be consulted only from the two accessibility-fed call sites, and
        // neither of them is live on a BlackBerry KEY2 with stock settings: the interceptor's
        // special-key callback is gated on pref_key_interceptor_enabled (default false), and
        // processKeyEventForState() only sees keys the service pre-processes while no input
        // connection is bound — which a board key never is, since KeyInterceptorService
        // classifies it out first. So the KEY2's real KEYCODE_SYM arrived HERE, where nothing
        // asked about Alt, and Alt+Sym did exactly what a bare Sym does. (Owner report,
        // 2026-09-21: "Alt+Sym shortcut does nothing".)
        //
        // Placement matters: this must run before handleKeyDown() below, which resets the
        // internal Alt span for key code 63 on every profile without usesMetaSymHandling().
        if (key.isPhysical() && keyEventRemapped.getRepeatCount() == 0 && key.isSymKey()) {
            // Which key code the Sym key answers to on this device, for the tracker's
            // isSymKeyHeld() — the "hold Sym to keep the symbol board open" gate below and in
            // KeyboardState. Recorded before the chord check, because a press the chord consumes
            // is still a press of the Sym key.
            ime.getPhysicalKeyboardStateTracker().setSymKeyCode(keyCode);
            AltSymShortcutHandler altSym = ime.getHardwareKeys().getAltSymShortcutHandler();
            // Modifier-KEY state only (see ModifierState.getChordMetaState): the interpreted
            // state carries the symbol board's own Alt page as META_ALT_ON.
            int effectiveMeta = ime.getPhysicalKeyboardStateTracker()
                    .getModifierState(keyEventRemapped).getChordMetaState();
            if (altSym.detectAndExecute(effectiveMeta)) {
                altSym.markSymPressConsumed();
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(0, true);
                return true;
            }
            altSym.clearPendingSymKeyUp();
        }

        dev.bbkb.ime.keyboard.auxbar.AuxBarManager auxBarManager = ime.getAuxBarManager();
        if (auxBarManager != null) {
            int symbolPageOrder = ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder();
            if (auxBarManager.processKeyDown(keyEventRemapped, symbolPageOrder)) {
                return true;
            }
        }

        KeyHoldHandler.getInstance().processKeyDown(keyEventRemapped);

        if (handleEmojiSearchIntercept(keyCode, keyEventRemapped)) {
            return true;
        }

        if (ime.getNeedsKeyboardReload() && ime.getKeyboardSwitcher().getCurrentKeyboard() != null) {
            ime.setNeedsKeyboardReload(false);
            ime.getKeyboardSwitcher().startInput(ime.getCurrentInputEditorInfo(), ime.getCurrentInputType(), ime.getCurrentImeOptions());
        }
        if (LocaleUtils.isChineseCangjie(SubtypeManager.getInstance().getCurrentSubtypeLocale()) && ime.isInputViewShown() && !ime.getFccController().isViewActive() && key.isShiftKey()) {
            ime.toggleCangjieMode();
            ime.getKeyboardSwitcher().requestShiftOff();
            return true;
        }
        // Audit CT-11: a second empty `if (!ime.getFccController().isViewActive()) {}` stood here.
        if (ime.getVkbGestureListener() != null) {
            ime.getVkbGestureListener().setLastKeyEventTime(keyEventRemapped.getDownTime());
        }
        ime.updateMainKeyboardViewForKeyEvent(keyEventRemapped, true);
        if (!(ime.isInputActive() && ime.isInputViewShown()) && ime.getInputLogic().isTrackedKeyRepeat(keyEventRemapped)) {
            return true;
        }
        boolean zIsInputViewShown = ime.isInputViewShown();
        if (!zIsInputViewShown && key.isPhysical()) {
            zIsInputViewShown = requestShowOnKeyPress();
        }
        if (ime.isInputActive() && zIsInputViewShown && ime.getControlMode().handleHardKeyDown(keyCode, keyEventRemapped)) {
            return true;
        }
        if (ime.isInputActive() && zIsInputViewShown && !ime.getInputLogic().isUntrackedKeyRepeat(keyEventRemapped)) {
            // Audit CT-9: a `boolean z3 = getSymbolPageOrder() == 1 || == 3;` stood here. It was
            // never read anywhere in the file — two getSymbolPageOrder() calls per physical
            // key-down for nothing — and it is the fossil of a predicate that once gated the
            // block below. If that gate is wanted back it belongs here as ime.isInSymbolMode().
            boolean z4 = key.isShiftKey();
            // FIX Bug #1: Don't hide UIM boards (clipboard, emoji, voice) on Enter key press.
            // Only non-Enter, non-Shift keys should trigger board dismissal.
            boolean isEnterKey = key.isEnterKey();
            if (!z4 && !isEnterKey) {
                // Audit CT-10: this tested `i == -5`, the project's SOFT-key code for delete,
                // against the raw framework key code that onKeyDown delivers — a constant false.
                // So applyCursorModeState always got `false` here and the shift-state repost
                // below was unreachable: "backspace while cursor mode is on re-derives shift
                // state" never happened on a physical keyboard.
                boolean isBackspace = key.isBackspaceKey();
                // Board/multifunction keys are exempt from the text-key dismissals:
                // their key-UP toggles the board (or cursor mode) through its own
                // action, and disabling on key-DOWN would clear the state that
                // key-up decision reads — the historical toggle-reopen fight.
                // Text keys still dismiss boards AND disable cursor mode.
                // (ResolvedKey is computed once near the top of this method.)
                if (!key.isBoardKey()) {
                    ime.applyCursorModeState(false, isBackspace, false);
                    ime.dismissBoardsForTextKey();
                }
                if (ime.isCursorModeEnabled() && isBackspace) {
                    ime.getUiUpdateHandler().postUpdateShiftState(true, true);
                }
            } else if (z4) {
                ime.getUiUpdateHandler().removeCallbacks(ime.getDisableCursorModeRunnable());
            }
            if (ime.hasFccController()) {
                ime.getFccController().onKeyEventWhileShowing();
            }

            if (key.isEnterKey()
                    && ModifierState.ofEvent(keyEventRemapped).isShiftHeld()) {
                // Parity with the original (WhatsApp enter fix, 2026-08-27): retire the composing
                // word, then PASS THE KEY THROUGH to the app instead of committing "\n" ourselves.
                // Self-committing a newline here made WhatsApp (whose enter-is-send watches the
                // text buffer for '\n') SEND on Shift+Enter; passing the raw event lets the app
                // apply its own convention (WhatsApp/EditText: Shift+Enter = newline).
                ime.getInputLogic().mRichInputConnection.finishComposingText();
                ime.getInputLogic().mRichInputConnection.invalidateConnection();
                ime.getInputLogic().mComposingTracker.clearAll();
                ime.getUiUpdateHandler().cancelPendingSuggestionUpdates();
                return ime.superOnKeyDown(keyCode, keyEventRemapped);
            }

            ime.setLastKeyEventTime(keyEventRemapped.getEventTime());
            ime.getInputLogic().onHardwareKeyDown(keyEventRemapped);
            ime.getPhysicalKeyboardStateTracker().handleKeyDown(keyCode, keyEventRemapped);
            if (z4) {
                ime.getInputLogic().sendKeyDown(keyCode);
            }
            int i2 = ime.getCurrentInputEditorInfo().inputType;
            int computedMetaState = ime.getPhysicalKeyboardStateTracker().getComputedMetaState(keyEventRemapped);
            InputEvent c0914aMo5937a = ime.getOrCreateKeyEventConverter(keyEventRemapped.getDeviceId()).convertKeyEvent(keyEventRemapped, computedMetaState, i2);
            if (c0914aMo5937a.hasData()) {
                ime.getMultitapEventHandler().onInputEvent(c0914aMo5937a);
                if (c0914aMo5937a.mText != null) {
                    c0920gM4442a = ime.getInputLogic().commitVoiceInput(ime.getSettingsManager().getSettingsValues(), c0914aMo5937a, ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder(), false, ime.getUiUpdateHandler());
                } else {
                    c0920gM4442a = ime.getInputLogic().processInputEvent(ime.getSettingsManager().getSettingsValues(), c0914aMo5937a, ime.getPhysicalKeyboardStateTracker(), InputSource.HARDWARE, ime.getKeyboardSwitcher().getKeyboardElementId(), ime.getUiUpdateHandler());
                }
                boolean fccBlocking = ime.getFccController().isShowing() && ime.getFccController().isSelectModeActive();
                boolean flagFromInputLogic = c0920gM4442a.hasPendingEditorAction();
                zM5997a = fccBlocking ? false : flagFromInputLogic;
                // Audit LC-14: handleEnterKey sets pendingEditorAction and deliberately leaves the
                // tracker composing for the consumer below to retire. When FCC select mode
                // suppresses that consumer, nobody discharges the handoff: the editor action never
                // fires AND the word is left composing with its queued suggestion update alive.
                // Suppressing the action is intentional (the cursor-selection UI owns Enter), so
                // only the cleanup half is owed here.
                if (fccBlocking && flagFromInputLogic) {
                    ime.getInputLogic().mComposingTracker.clearAll();
                    ime.getUiUpdateHandler().cancelPendingSuggestionUpdates();
                }
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(keyCode, key.isPhysical());
                if (key.isPhysical()) {
                    c0920gM4442a.setUiUpdateMode(1);
                }
                ime.applyPostEventUpdates(c0920gM4442a, true);
                if (key.isPhysical()) {
                    while (c0914aMo5937a != null) {
                        if (c0914aMo5937a.mCodePoint == -23) {
                            if (ime.hasCjkSuggestionGrid()) {
                                ime.getCjkSuggestionGridView().setVisible(false);
                            }
                        }
                        ime.getKeyboardSwitcher().onInputCodeChanged(c0914aMo5937a.mCodePoint, ime.getCurrentInputType(), ime.getCurrentImeOptions());
                        c0914aMo5937a = c0914aMo5937a.mNextEvent;
                    }
                }
                if (key.isAltKey() && !key.isBoardKey()) {
                    ime.getKeyboardSwitcher().onSymbolKeyLongPress(ime.getCurrentInputType(), ime.getCurrentImeOptions());
                }
                z2 = true;
            } else if (c0914aMo5937a.isModifierKey()) {
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(keyCode, key.isPhysical());
                z2 = true;
                zM5997a = false;
            } else if (c0914aMo5937a.isGestureEnd()) {
                // Mic/emoji key consumed by IME — do not fall through to superOnKeyDown,
                // which would type the key's base character (e.g. '0' for the Athena mic key).
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(keyCode, key.isPhysical());
                z2 = true;
                zM5997a = false;
            } else {
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(keyCode, key.isPhysical());
                // "Is Sym held?" is the TRACKER's answer, not the event's. The KEY2's Sym key
                // sets no META_SYM_ON, so ModifierState.ofEvent(...) — which is all this site
                // used to read — said "not held" on the one device the branch exists for, while
                // the matching key-UP test a few hundred lines down said "held". Owner decision,
                // 2026-09-22: both read the tracker (SymHeldReadsTrackerTest).
                if (key.isAltKey() && !key.isBoardKey()
                        && !ime.getPhysicalKeyboardStateTracker()
                                .getModifierState(keyEventRemapped).isSymHeld()) {
                    ime.getKeyboardSwitcher().onSymbolKeyLongPress(ime.getCurrentInputType(), ime.getCurrentImeOptions());
                }
                zM5997a = false;
            }
        } else {
            zM5997a = false;
        }
        if (z2) {
            ime.getInputLogic().trackKeyEvent(keyEventRemapped);
            if (!zM5997a) {
                return true;
            }
            if (key.isEnterKey()) {
                // Parity with the original (WhatsApp enter fix, 2026-08-27). The original never
                // performs the editor action itself on a hardware Enter: handleEnterKey's
                // "pendingEditorAction" means "retire the word, then return super.onKeyDown(66)"
                // — the raw ENTER reaches the app, and THE APP decides (WhatsApp's enter-is-send
                // sends; EditText inserts a newline; search fields submit). Our previous block
                // called performEditorAction(imeOptions & 255) instead, which (a) ignored
                // IME_FLAG_NO_ENTER_ACTION and actionLabel, and (b) fired actions like SEND that
                // apps such as WhatsApp ignore — enter did nothing and the strip emptied.
                // Order still matters: finishComposingText BEFORE the app receives Enter keeps
                // the cursor after the word in WebView editors (the Outlook fix), and the word
                // must be FULLY retired (clearAll + cancel the delayed suggestion update) or the
                // late reply re-inserts it (launcher-search "instagraminstagram" bug).
                // The key-UP side passes Enter through too, so the app gets the whole press and
                // not just its ACTION_DOWN half: onKeyUpInternal + shouldDelegateEnterKeyUp.
                ime.getInputLogic().mRichInputConnection.finishComposingText();
                ime.getInputLogic().mRichInputConnection.invalidateConnection();
                ime.getInputLogic().mComposingTracker.clearAll();
                ime.getUiUpdateHandler().cancelPendingSuggestionUpdates();
                return ime.superOnKeyDown(keyCode, keyEventRemapped);
            }
        } else {
            ime.getInputLogic().untrackKeyEvent(keyEventRemapped);
        }
        ime.getMultitapEventHandler().commitMultitapIfActive();
        return ime.superOnKeyDown(keyCode, keyEventRemapped);
    }

    /**
     * Internal onKeyUp handler.
     * Extracted from BlackBerryIME.onKeyUp().
     */
    public boolean onKeyUpInternal(int i, KeyEvent keyEvent) {
        // Audit LC-9: the key-DOWN path heals isInputActive (line ~96) but this one did not,
        // so a key released just after onWindowHidden skipped InputLogic.onHardwareKeyUp and
        // left BackspaceController.mIsBackspaceActive stuck true — which then suppresses the
        // composing/auto-correct-indicator update in onSuggestionsReceived until the next
        // backspace. Heal here too, for the same reason and by the same means.
        ime.refreshInputActive();
        // Audit CT-12 / Phase 1e: the RAW event's classification, once. It has to be the raw one:
        // both users below run before remapKeyEvent. (It used to be evaluated twice here — once
        // inside the capture hook and once for the delegate gate — and once more further down,
        // which the ResolvedKey below replaces.)
        final boolean rawIsPhysical =
                KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent);
        // The key-UP half of the profile-builder capture hook in onKeyDownInternal: a step ends on
        // the release of the key it recorded, and a release the capture swallowed the press of
        // must not reach the system alone (for a modifier that is how its meta state sticks on).
        if (HardwareKeyCaptureBus.isCapturing() && rawIsPhysical
                && HardwareKeyCaptureBus.offer(keyEvent.getScanCode(), keyEvent.getKeyCode(),
                        keyEvent.getDeviceId(), keyEvent.getRepeatCount(), false,
                        keyEvent.getEventTime())) {
            return true;
        }
        if (!rawIsPhysical) {
            // Non-physical events (deviceId==0): delegate to framework.
            // For KEYCODE_BACK, super.onKeyUp() calls handleBack(true) → requestHideSelf(0).
            return ime.superOnKeyUp(i, keyEvent);
        }
        // Physical keyboard BACK handling (matches original smali onKeyUp for deviceId != 0):
        // Set wasBackPressed and reset symbol mode, then fall through to super.onKeyUp()
        // which handles the actual keyboard dismissal via handleBack(true).
        if (ime.isInputViewShown() && keyEvent.getKeyCode() == KeyEvent.KEYCODE_BACK
                && keyEvent.isTracking() && !keyEvent.isCanceled()) {
            ime.setWasBackPressed(true);
            ime.getKeyboardSwitcher().onBackspaceInSymbolMode(ime.getCurrentInputType(), ime.getCurrentImeOptions());
            return ime.superOnKeyUp(i, keyEvent);
        }
        if (ime.isAltEnterPressed()) {
            ime.setAltEnterPressed(false);
            if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ModifierState.ofEvent(keyEvent).isAltHeld()) {
                return true;
            }
        }
        KeyEvent keyEventRemapped = ime.remapKeyEvent(i, keyEvent);

        // Phase 1e: WHICH KEY IS THIS, answered once for the remapped event. This method used to
        // resolve the same scancode mapping twice — once for the Alt+Sym key-up gate below and
        // again for the emoji/voice/multifunction block — and classify the device a third time.
        final ResolvedKey key = ResolvedKey.of(keyEventRemapped);
        final int keyCode = key.keyCode();

        // The key-UP half of the Alt+Sym chord: the press already dispatched the configured
        // action, so this release must reach neither the symbol-board machinery nor (through
        // superOnKeyUp) the app, which would otherwise see a Sym release with no matching press.
        // Gated on the key itself, not just on the outstanding flag: the user routinely lets Alt
        // go before Sym, and swallowing the Alt release would strand the tracker's Alt state.
        if (key.isPhysical() && key.isSymKey()
                && ime.getHardwareKeys().getAltSymShortcutHandler().consumeSymKeyUp()) {
            releaseConsumedBoardKey(keyEventRemapped);
            return true;
        }

        KeyHoldHandler.getInstance().processKeyUp(keyEventRemapped);

        // Notify AuxBarManager of key release for hold-to-auto-commit state reset
        dev.bbkb.ime.keyboard.auxbar.AuxBarManager auxBarManager = ime.getAuxBarManager();
        if (auxBarManager != null) {
            auxBarManager.processKeyUp(keyEventRemapped);
        }

        if (ime.getControlMode().handleHardKeyUp(keyCode, keyEventRemapped)) {
            return true;
        }
        if (LocaleUtils.isChineseCangjie(SubtypeManager.getInstance().getCurrentSubtypeLocale()) && ime.isInputViewShown() && key.isShiftKey()) {
            return true;
        }
        // === UNIFIED: emoji/voice key-up handling, off the one resolved identity ===
        // ResolvedKey.isEmojiKey() is the BOARD_EMOJI role, a MULTIFUNCTION key whose configured
        // action is the emoji board (its down-event armed the same PendingKeyAction.EMOJI_BOARD, so
        // it takes the same key-up path), or the legacy 666 pseudo-keycode. isBoardKey() additionally
        // covers MULTIFUNCTION and 667: those keys are consumed by the IME, and their base
        // character must never leak through the symbol-long-press / extra-key fallthroughs below.
        //
        // Phase 1g: the three branches below used to read (and clear) the three mutable
        // InputMethodHelper flags. BoardKeyPressTracker owns that pairing now — armed by
        // KeyEventConverter on the way down, spent here on the way up, discarded with the rest of
        // the per-key state on a full modifier reset.
        final BoardKeyPressTracker pairing = BoardKeyPressTracker.getInstance();

        if (key.isEmojiKey() && key.isPhysical()) {
            releaseConsumedBoardKey(keyEventRemapped);
            if (pairing.consume(PendingKeyAction.EMOJI_BOARD)) {
                UnifiedInputBoardManager unifiedManager = ime.getKeyboardSwitcher().getUnifiedInputBoardManager();
                if (unifiedManager != null && ime.isUimEnabled()) {
                    unifiedManager.requestBoard(key.boardId(ResolvedKey.DEFAULT_EMOJI_BOARD_ID));
                } else {
                    ime.getKeyboardSwitcher().onEmojiKeyPressed();
                }
            }
            return true;
        }
        // isPhysical() first, then consume(): the pending action must not be spent by an event
        // this branch will not act on. (The old form read the flag first and the classification
        // second, which came to the same thing because the clear was inside the branch.)
        if (key.isPhysical() && pairing.consume(PendingKeyAction.VOICE_INPUT)) {
            releaseConsumedBoardKey(keyEventRemapped);
            if (!ime.isInputActive() || !ime.isInputViewShown()) {
                ime.getHardwareKeys().launchVoiceAssistant();
            } else {
                UnifiedInputBoardManager unifiedManager = ime.getKeyboardSwitcher().getUnifiedInputBoardManager();
                if (unifiedManager != null && ime.isUimEnabled()) {
                    // TOGGLES: the coordinator owns "which board is open", so a second press
                    // closes the voice board (same as the clipboard/FCC multifunction actions).
                    unifiedManager.requestBoard(key.boardId(ResolvedKey.DEFAULT_VOICE_BOARD_ID));
                } else {
                    // UIM disabled (or its manager not built yet). This used to call
                    // switchToVoiceIme(), which hands off to ANOTHER IME and so could never close
                    // anything — the mic key re-opened voice on every press. The accessibility
                    // path already routes here (bug #4); both paths now share this one toggle,
                    // which keeps switchToVoiceIme as its fallback when there is no controller.
                    ime.getHardwareKeys().triggerVoiceInput();
                }
            }
            return true;
        }
        // Multifunction key released: dispatch the user-configured action (the emoji
        // action armed EMOJI_BOARD above; ctrl was remapped).
        // Board actions (clipboard, fcc, number pad) route through the board coordinator, which
        // TOGGLES the board — a second press closes it if it's open.
        if (key.isPhysical() && pairing.consume(PendingKeyAction.MULTIFUNCTION)) {
            // Release BEFORE dispatching: FccController.showFcc() refuses to open while
            // the tracker still counts this key as held (keysDown == 0 guard).
            releaseConsumedBoardKey(keyEventRemapped);
            if (ime.isInputActive() && ime.isInputViewShown()) {
                // The action comes from the key being RELEASED, not from the key that armed the
                // pending action — pairing.armedBy(MULTIFUNCTION) knows which that was. They are
                // the same key in every real sequence, and where they are not this resolves null
                // and the switch throws; preserved rather than fixed, and pinned by
                // BoardKeyPairingCharacterisationTest, because changing it is a behaviour change.
                final String multifunctionAction = key.getMultifunctionAction();
                switch (multifunctionAction) {
                    case MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH:
                        ime.updateSuggestionsFromSubtype(InputSource.HARDWARE);
                        break;
                    case MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD:
                        toggleBoardOrFallback(-25, () -> {
                            if (ime.getClipboardController() != null) {
                                if (ime.getClipboardController().isShowing()) {
                                    ime.getClipboardController().hide();
                                } else {
                                    ime.getClipboardController().show();
                                }
                            }
                        });
                        break;
                    case MultifunctionKeyHandler.ACTION_FCC:
                        // FCC lives in the UIM; -42 toggles it. No non-UIM fallback (FCC is
                        // a UIM-only board), so pass a no-op.
                        toggleBoardOrFallback(-42, () -> {});
                        break;
                    case MultifunctionKeyHandler.ACTION_NUMBER_PAD:
                        // UIM-only board, like FCC.
                        toggleBoardOrFallback(NumberPadController.KEY_CODE, () -> {});
                        break;
                    case MultifunctionKeyHandler.ACTION_SYMBOL_KEYBOARD:
                        ime.getKeyboardSwitcher().onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), false, true);
                        break;
                    default:
                        if (BuildConfig.DEBUG) Log.w(TAG, "Unknown multifunction key action: " + multifunctionAction);
                        break;
                }
            }
            return true;
        }

        ime.enableCursorMode(false);
        if (ime.getVkbGestureListener() != null) {
            ime.getVkbGestureListener().setLastKeyEventTime(keyEventRemapped.getEventTime());
        }
        ime.updateMainKeyboardViewForKeyEvent(keyEventRemapped, false);
        if (ime.getInputLogic().isKeyTracked(keyEventRemapped)) {
            ime.getKeyboardSwitcher().onHardwareKeyEvent(keyEventRemapped, ime.getCurrentInputType(), ime.getCurrentImeOptions());
            ime.getPhysicalKeyboardStateTracker().handleKeyUp(keyCode, keyEventRemapped);
            if (key.isShiftKey()) {
                ime.getInputLogic().sendKeyUp(keyCode);
            }
            if ((keyEventRemapped.getFlags() & KeyEvent.FLAG_CANCELED) != 0) {
                ime.updatePhysicalKeyboardFilter();
                ime.superOnKeyUp(keyCode, keyEventRemapped);
            }
            if (ime.isInputActive() && ime.isInputViewShown()) {
                ime.getInputLogic().onHardwareKeyUp(keyEventRemapped);
                ime.setLastKeyEventTime(keyEventRemapped.getEventTime());
                // A hinted key released while Sym is not held ends a one-shot symbol entry
                // (tap Sym, type one symbol, back to letters) — the default and only
                // behaviour since the "Close symbol keyboard after symbol" setting was
                // removed. Holding Sym is what keeps the board up instead, so the hold is
                // the one thing that suppresses it. The event's own META_SYM_ON alone is not
                // that test — the KEY2's Sym key does not set it — so ModifierState.isSymHeld()
                // is the OR of that bit and the tracker's held-key set, which is exactly the
                // pair of conditions this branch used to spell out separately.
                if (!key.isBoardKey() && key.isPhysical()
                        && ime.getKeyboardSwitcher().getKeyByPhysicalScanCode(key.scanCode(), false) != null
                        && !ime.getPhysicalKeyboardStateTracker()
                                .getModifierState(keyEventRemapped).isSymHeld()) {
                    ime.getKeyboardSwitcher().onSymbolKeyLongPress(ime.getCurrentInputType(), ime.getCurrentImeOptions());
                }
            }
            ime.getInputLogic().untrackKeyEvent(keyEventRemapped);
            if (shouldDelegateEnterKeyUp(keyCode)) {
                // Parity with the original, and the other half of the 2026-08-27 WhatsApp fix.
                // That change made the key-DOWN return superOnKeyDown so the APP receives the
                // raw ENTER and applies its own convention, and its comment states that "the
                // key-UP side already passes Enter through" — it did not. This branch consumed
                // every tracked key-up, so the app got a key-DOWN with no matching ACTION_UP.
                // The original delegates it (BlackBerryIME.onKeyUp:
                //   return !(isShiftKey(keyCode) || (keyCode == 66 && N() != 1))
                //           || super.onKeyUp(keyCode, event);
                // with N() == getSymbolPageOrder()), so on a physical keyboard the app sees the
                // complete DOWN/UP pair. Half a confirm-key press is what a lock screen, and any
                // other editor that pairs the two halves (TextView only clears its
                // InputContentType.enterDown latch on the UP, and MetaKeyKeyListener balances
                // its meta state there), never sees from the stock keyboard or from any IME
                // that leaves hardware keys alone.
                return ime.superOnKeyUp(keyCode, keyEventRemapped);
            }
            return true;
        }
        if (!key.isFunctionKey() && !key.isBoardKey() && ime.isInputActive() && ime.isInputViewShown()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Extra key event for keycode: " + keyCode + ", consuming.");
            return true;
        }
        return ime.superOnKeyUp(keyCode, keyEventRemapped);
    }

    /**
     * Whether a tracked hardware ENTER key-up must be handed on to the app
     * ({@code super.onKeyUp}) instead of being consumed here.
     *
     * <p>This is the original's rule, keyCode-for-keyCode: ENTER on a device the IME treats as
     * having a physical keyboard, unless the shift page is active — the key-DOWN side answers
     * Shift+Enter in its own branch, and the original's {@code N() != 1} is the same
     * {@code getSymbolPageOrder()} test. The device gate matters: on a VKB shape
     * {@code handleEnterKey} answers Enter with {@code performEditorAction} and the DOWN is
     * consumed, so releasing the UP on its own would hand the app a confirm-key release whose
     * press it never saw.
     *
     * @param keyCode the remapped key code of the key-up being processed
     * @return {@code true} to delegate the key-up to the framework (and thus the app)
     */
    @VisibleForTesting
    boolean shouldDelegateEnterKeyUp(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER
                && DeviceProfile.isPkb()
                && ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder() != 1;
    }

    /**
     * Board/multifunction key-ups are consumed by their branches above before the
     * generic tracked-key block runs, so that block's tracker bookkeeping must happen
     * here instead: without it the PhysicalKeyboardStateTracker keys-down count leaks
     * +1 per press (permanently blocking FccController.showFcc()'s keysDown == 0
     * guard) and InputLogic keeps the event tracked. Only releases keys the key-down
     * path actually tracked.
     */
    /**
     * Whether this key is the Sym key. The rule lives in {@link ResolvedKey#isSymBoardKey}, which
     * is the one place key identity is decided; this stays as the name the existing suites call.
     *
     * @param mapping the mapping already resolved for this event, or null if none matched
     */
    @VisibleForTesting
    static boolean isSymBoardKey(int keyCode, ScancodeMapping mapping) {
        return ResolvedKey.isSymBoardKey(keyCode, mapping);
    }

    private void releaseConsumedBoardKey(KeyEvent event) {
        if (ime.getInputLogic().isKeyTracked(event)) {
            ime.getPhysicalKeyboardStateTracker().handleKeyUp(event.getKeyCode(), event);
            ime.getInputLogic().untrackKeyEvent(event);
        }
    }

    /**
     * Toggle a UIM board by keycode. When the Unified Input Menu is enabled, routes through
     * {@link UnifiedInputBoardManager#requestBoard(int)} — the coordinator's single toggle
     * choke point, which decides from the active-board state so a second press reliably
     * closes the board. Otherwise runs {@code fallback} for the non-UIM path.
     */
    private void toggleBoardOrFallback(int boardKeyCode, Runnable fallback) {
        UnifiedInputBoardManager unifiedManager = ime.getKeyboardSwitcher().getUnifiedInputBoardManager();
        if (unifiedManager != null && ime.isUimEnabled()) {
            unifiedManager.requestBoard(boardKeyCode);
        } else {
            fallback.run();
        }
    }

    private boolean handleAltEnterLanguageSwitch(int keyCode, KeyEvent keyEvent) {
        if (ime.isInputViewShown() && ModifierState.ofEvent(keyEvent).isAltHeld()
                && keyCode == KeyEvent.KEYCODE_ENTER
                && ime.getRichInputMethodManager().hasMultipleEnabledSubtypesInThisIme(false)) {
            ime.setAltEnterPressed(true);
            ime.getInputLogic().commitOrResetComposing(ime.getSettingsManager().getSettingsValues(), InputSource.INTERNAL);
            ime.updateSuggestionsFromSubtype(InputSource.HARDWARE);
            return true;
        }
        return false;
    }

    private boolean handleEmojiSearchIntercept(int keyCode, KeyEvent keyEvent) {
        if (!ime.isInputViewShown() || !ime.getKeyboardSwitcher().isEmojiKeyboardShowing()) {
            return false;
        }

        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (sv != null && sv.isEmojiDynamicSearchEnabled) {
            // Audit CT-3: this allocated a throwaway Handler PER KEYSTROKE and the post was
            // never cancelled, so a burst of typing queued N stale composing-text pushes into
            // EmojiPalettesView. Route through the IME's own handler under a token and drop any
            // still-pending push first, so only the latest composing text is delivered.
            ime.getUiUpdateHandler().removeCallbacksAndMessages(EMOJI_SEARCH_TOKEN);
            ime.getUiUpdateHandler().postTokenized(EMOJI_SEARCH_TOKEN, 0L, () -> {
                String composing = ime.getKeyboardSwitcher().getComposingText();
                EmojiPalettesView emojiView = ime.getKeyboardSwitcher().peekEmojiPalettesView();
                if (emojiView != null) {
                    emojiView.onComposingTextChanged(composing);
                }
            });
        }

        return false;
    }
}
