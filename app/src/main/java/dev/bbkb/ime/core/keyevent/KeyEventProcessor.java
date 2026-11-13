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
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

/** BlackBerryIME's physical keyboard pipeline: key down/up and the accessibility state pre-pass. */
public class KeyEventProcessor {

    private static final String TAG = "KeyEventProcessor";

    /** Key codes whose key-down the emoji search consumed; their key-up is consumed too. */
    private final java.util.Set<Integer> emojiQueryKeysDown = new java.util.HashSet<>();

    /** The IME answered the last ENTER key-down itself, so the app must not see its key-up. */
    private boolean enterDownConsumed;

    private final BlackBerryIME ime;

    public KeyEventProcessor(BlackBerryIME ime) {
        this.ime = ime;
    }

    /**
     * State updates only (shift, alt, symbol mode), for keys the accessibility service
     * pre-processes without an active text field.
     */
    public void processKeyEventForState(KeyEvent event) {
        // Same ResolvedKey identity as the ordinary path (no remap here): the MP01's Sym is
        // KEYCODE_ALT_RIGHT on scancode 249. SymPrePassIdentityTest pins it.
        final ResolvedKey key = ResolvedKey.of(event);
        int keyCode = key.keyCode();
        int action = event.getAction();

        PhysicalKeyboardStateTracker tracker = ime.getPhysicalKeyboardStateTracker();

        // Snapshot Alt before the tracker sees the event: handleKeyDown() resets the Alt span on
        // Sym when usesMetaSymHandling() is false. Chord state, not interpreted (the symbol
        // board's Alt page would make a repeat Sym press look like Alt+Sym).
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
            // For isSymKeyHeld(); on some devices this is the key code the ROM attached.
            tracker.setSymKeyCode(keyCode);
        }
        AltSymShortcutHandler altSym = ime.getHardwareKeys().getAltSymShortcutHandler();
        if (action == KeyEvent.ACTION_UP) {
            // A consumed press's release must not reach the symbol board.
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
     * Show-on-keypress: summon the hidden IME window for a hardware key (also used by the
     * accessibility SYM path, since boards live in the window). The caller checks the event is
     * physical. Requires a real input binding: isInputActive() alone is true for the transient
     * bindings of home transitions.
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

    /** @param i the key code */
    public boolean onKeyDownInternal(int i, KeyEvent keyEvent) throws Resources.NotFoundException {
        boolean zM5997a;
        InputEventContext c0920gM4442a;
        boolean z2 = false;

        // isInputActive goes stale-false after onWindowHidden for a still-bound editor (re-show
        // delivers no onStartInput); heal it first or hardware keys bypass the pipeline.
        ime.refreshInputActive();

        // The RAW event's classification, for the gates that must run before remapKeyEvent.
        // Everything after the remap uses the ResolvedKey of the remapped event instead.
        final boolean rawIsPhysical =
                KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent);

        // Safety net: onCreate normally initializes the profile before any key arrives.
        if (rawIsPhysical && DeviceProfile.appContext() == null) {
            DeviceProfile.initializeForDevice(ime.getApplicationContext(), keyEvent.getDeviceId());
            Logger.info(TAG, "DeviceProfile initialized for device ID: " + keyEvent.getDeviceId());
        }

        // Last-resort keypad layout detection (KeypadLayoutDetector source 5) from the RAW key
        // code, which a remap would rewrite. Cheap; true only when the answer changes, and then
        // the profile is rebuilt. Phone keypad only: an external keyboard says nothing about it.
        if (rawIsPhysical
                && DeviceInputResolver.isPhoneKeyboard(keyEvent.getDeviceId())
                && dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
                .observeKeyEvent(keyEvent.getScanCode(), keyEvent.getKeyCode())) {
            DeviceProfile.reinitializeForKeypadLayoutChange(
                    ime.getApplicationContext(), keyEvent.getDeviceId());
        }

        // Device-profile builder capture: needs the RAW codes before anything acts on the key,
        // and consumes it so a Sym press cannot open a board over the settings screen.
        if (rawIsPhysical && HardwareKeyCaptureBus.offer(keyEvent.getScanCode(), keyEvent.getKeyCode(),
                keyEvent.getDeviceId(), keyEvent.getRepeatCount(), true, keyEvent.getEventTime())) {
            return true;
        }

        if (!rawIsPhysical) {
            ime.applyCursorModeState(false, false, false);
            return ime.superOnKeyDown(i, keyEvent);
        }

        // BACK goes straight to super.onKeyDown so startTracking() runs; otherwise
        // event.isTracking() is false and the dismiss-on-up branch in onKeyUpInternal fails.
        if (i == KeyEvent.KEYCODE_BACK) {
            return ime.superOnKeyDown(i, keyEvent);
        }

        if (handleAltEnterLanguageSwitch(i, keyEvent)) {
            return true;
        }
        KeyEvent keyEventRemapped = ime.remapKeyEvent(i, keyEvent);

        // Identity of the remapped event, resolved once. Board keys are exempt from the
        // ordinary-key behaviours below, e.g. the MP01's SYM (KEYCODE_ALT_RIGHT) would otherwise
        // fire onSymbolKeyLongPress() via isAltKey() on top of its board toggle.
        final ResolvedKey key = ResolvedKey.of(keyEventRemapped);
        final int keyCode = key.keyCode();
        if (key.isEnterKey()) {
            // Set again below only on the branch that answers the press itself.
            enterDownConsumed = false;
        }

        // Alt+Sym on the ordinary path (the only one live with stock settings). Must run before
        // handleKeyDown(), which resets the Alt span on Sym without usesMetaSymHandling().
        if (key.isPhysical() && keyEventRemapped.getRepeatCount() == 0 && key.isSymKey()) {
            // For isSymKeyHeld(); recorded before the chord check, since a consumed press is
            // still a Sym press.
            ime.getPhysicalKeyboardStateTracker().setSymKeyCode(keyCode);
            AltSymShortcutHandler altSym = ime.getHardwareKeys().getAltSymShortcutHandler();
            // Chord state: the interpreted state carries the symbol board's Alt page.
            int effectiveMeta = ime.getPhysicalKeyboardStateTracker()
                    .getModifierState(keyEventRemapped).getChordMetaState();
            // Alt+Sym as Ctrl: a held, already-spent Alt still counts, so "hold Alt, tap Sym
            // twice" is a Ctrl double tap.
            if (altSym.isCtrlAction() && ime.getPhysicalKeyboardStateTracker().isAltKeyConsumed()) {
                effectiveMeta |= KeyEvent.META_ALT_ON;
            }
            if (altSym.detectAndExecute(effectiveMeta)) {
                altSym.markSymPressConsumed();
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(0, true);
                return true;
            }
            altSym.clearPendingSymKeyUp();
        }

        // The open emoji board takes its text keys before anything else sees them (rules in
        // EmojiQueryKeyRouting); onKeyUpInternal swallows their release.
        if (routeKeyDownToEmojiQuery(key, keyEventRemapped)) {
            return true;
        }

        dev.bbkb.ime.keyboard.auxbar.AuxBarManager auxBarManager = ime.getAuxBarManager();
        if (auxBarManager != null) {
            int symbolPageOrder = ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder();
            if (auxBarManager.processKeyDown(keyEventRemapped, symbolPageOrder)) {
                return true;
            }
        }

        KeyHoldHandler.getInstance().processKeyDown(keyEventRemapped);

        if (ime.getNeedsKeyboardReload() && ime.getKeyboardSwitcher().getCurrentKeyboard() != null) {
            ime.setNeedsKeyboardReload(false);
            ime.getKeyboardSwitcher().startInput(ime.getCurrentInputEditorInfo(), ime.getCurrentInputType(), ime.getCurrentImeOptions());
        }
        if (LocaleUtils.isChineseCangjie(SubtypeManager.getInstance().getCurrentSubtypeLocale()) && ime.isInputViewShown() && !ime.getFccController().isViewActive() && key.isShiftKey()) {
            ime.toggleCangjieMode();
            ime.getKeyboardSwitcher().requestShiftOff();
            return true;
        }
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
            boolean z4 = key.isShiftKey();
            // Enter and Shift do not dismiss boards.
            boolean isEnterKey = key.isEnterKey();
            if (!z4 && !isEnterKey) {
                boolean isBackspace = key.isBackspaceKey();
                // Board keys act on key-up, which reads the state a key-down dismissal clears.
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
                // Retire the word and pass Shift+Enter to the app: committing "\n" ourselves made
                // enter-is-send apps (WhatsApp) send instead of inserting a newline.
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
                // Typing brings the strip back over a raised menu (AuxBarChoice); after the key's
                // processing, so a cursor mode this key ended has already restored its bar.
                if (c0914aMo5937a.editsText() && ime.getUiCoordinator() != null) {
                    ime.getUiCoordinator().onUserTyped(true);
                }
                boolean fccBlocking = ime.getFccController().isShowing() && ime.getFccController().isSelectModeActive();
                boolean flagFromInputLogic = c0920gM4442a.hasPendingEditorAction();
                zM5997a = fccBlocking ? false : flagFromInputLogic;
                // handleEnterKey leaves the word composing for the Enter pass-through to retire.
                // FCC select mode suppresses that (it owns Enter), so do the cleanup here.
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
                // Consumed mic/emoji key: superOnKeyDown would type its base char ('0' on Athena).
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(keyCode, key.isPhysical());
                z2 = true;
                zM5997a = false;
            } else {
                ime.getPhysicalKeyboardStateTracker().consumeModifiersAfterKey(keyCode, key.isPhysical());
                // Sym held per the tracker, not the event: the KEY2's Sym sets no META_SYM_ON.
                // The key-up test reads the same (SymHeldReadsTrackerTest).
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
                if (key.isEnterKey()) {
                    enterDownConsumed = true;
                }
                return true;
            }
            if (key.isEnterKey()) {
                // Hardware Enter: retire the word and pass the raw ENTER to the app, which picks
                // the action (performEditorAction ignores NO_ENTER_ACTION and apps like WhatsApp).
                // finishComposingText first keeps the WebView cursor after the word; clearAll and
                // cancel the late suggestion update or it re-inserts the word. Key-up passes too.
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

    /** @param i the key code */
    public boolean onKeyUpInternal(int i, KeyEvent keyEvent) {
        // Heal as on key-down, or a release after onWindowHidden skips onHardwareKeyUp and leaves
        // BackspaceController.mIsBackspaceActive stuck true.
        ime.refreshInputActive();
        // RAW classification: both users run before remapKeyEvent.
        final boolean rawIsPhysical =
                KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent);
        // Capture hook key-up: a release whose press was captured must not reach the system
        // alone (a modifier's meta state would stick on).
        if (HardwareKeyCaptureBus.isCapturing() && rawIsPhysical
                && HardwareKeyCaptureBus.offer(keyEvent.getScanCode(), keyEvent.getKeyCode(),
                        keyEvent.getDeviceId(), keyEvent.getRepeatCount(), false,
                        keyEvent.getEventTime())) {
            return true;
        }
        if (!rawIsPhysical) {
            // For BACK, super.onKeyUp() calls handleBack(true).
            return ime.superOnKeyUp(i, keyEvent);
        }
        // Physical BACK: record it and reset symbol mode; super.onKeyUp() dismisses.
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

        final ResolvedKey key = ResolvedKey.of(keyEventRemapped);
        final int keyCode = key.keyCode();

        // Alt+Sym key-up: reaches neither the symbol board nor the app. Gated on the key too:
        // users often release Alt first, and swallowing that would strand the Alt state.
        if (key.isPhysical() && key.isSymKey()
                && ime.getHardwareKeys().getAltSymShortcutHandler().consumeSymKeyUp()) {
            releaseConsumedBoardKey(keyEventRemapped);
            return true;
        }

        if (key.isPhysical() && emojiQueryKeysDown.remove(keyCode)) {
            releaseConsumedBoardKey(keyEventRemapped);
            return true;
        }

        KeyHoldHandler.getInstance().processKeyUp(keyEventRemapped);

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
        // Board key-up: spends the PendingKeyAction KeyEventConverter armed on key-down.
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
        // isPhysical() first: the pending action must not be spent by an event this branch ignores.
        if (key.isPhysical() && pairing.consume(PendingKeyAction.VOICE_INPUT)) {
            releaseConsumedBoardKey(keyEventRemapped);
            if (!ime.isInputActive() || !ime.isInputViewShown()) {
                ime.getHardwareKeys().launchVoiceAssistant();
            } else if (ime.getHardwareKeys().voiceKeyRefused()) {
                // No recognition service: the notice is up; open nothing (not even the UIM bar)
                // and type nothing.
            } else {
                UnifiedInputBoardManager unifiedManager = ime.getKeyboardSwitcher().getUnifiedInputBoardManager();
                if (unifiedManager != null && ime.isUimEnabled()) {
                    // Toggles: a second press closes the voice board.
                    unifiedManager.requestBoard(key.boardId(ResolvedKey.DEFAULT_VOICE_BOARD_ID));
                } else {
                    // UIM off: the shared toggle (accessibility path too); switchToVoiceIme only
                    // as its fallback, since another IME can never be closed by this key.
                    ime.getHardwareKeys().triggerVoiceInput();
                }
            }
            return true;
        }
        // Multifunction key-up runs the configured action (emoji armed EMOJI_BOARD above; ctrl
        // was remapped). Board actions toggle through the coordinator.
        if (key.isPhysical() && pairing.consume(PendingKeyAction.MULTIFUNCTION)) {
            // Release before dispatching: showFcc() refuses while this key counts as held.
            releaseConsumedBoardKey(keyEventRemapped);
            if (ime.isInputActive() && ime.isInputViewShown()) {
                // From the RELEASED key, not pairing.armedBy(MULTIFUNCTION). If they ever differ
                // this is null and the switch throws; pinned by BoardKeyPairingCharacterisationTest.
                final String multifunctionAction = key.getMultifunctionAction();
                switch (multifunctionAction) {
                    case MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH:
                        ime.switchToNextSubtype(InputSource.HARDWARE);
                        break;
                    case MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH:
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
                        toggleBoardOrFallback(FccController.KEY_CODE, () -> {
                            if (ime.getFccController() != null) {
                                ime.getFccController().toggle();
                            }
                        });
                        break;
                    case MultifunctionKeyHandler.ACTION_NUMBER_PAD:
                        toggleBoardOrFallback(NumberPadController.KEY_CODE, () -> {
                            if (ime.getNumberPadController() != null) {
                                ime.getNumberPadController().toggle();
                            }
                        });
                        break;
                    case MultifunctionKeyHandler.ACTION_SYMBOL_KEYBOARD:
                        ime.getKeyboardSwitcher().onSymbolShiftToggle(ime.getCurrentInputType(), ime.getCurrentImeOptions(), false, true);
                        break;
                    case MultifunctionKeyHandler.ACTION_TOGGLE_UIM:
                        // Works even where the menu is off; board actions then route via the menu.
                        ime.getUiCoordinator().toggleInputMenu();
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
                // A hinted key released without Sym held ends a one-shot symbol entry; holding
                // Sym keeps the board up. isSymHeld() includes the tracker (KEY2 sets no META_SYM_ON).
                if (!key.isBoardKey() && key.isPhysical()
                        && ime.getKeyboardSwitcher().getKeyByPhysicalScanCode(key.scanCode(), false) != null
                        && !ime.getPhysicalKeyboardStateTracker()
                                .getModifierState(keyEventRemapped).isSymHeld()) {
                    ime.getKeyboardSwitcher().onSymbolKeyLongPress(ime.getCurrentInputType(), ime.getCurrentImeOptions());
                }
            }
            ime.getInputLogic().untrackKeyEvent(keyEventRemapped);
            final boolean enterPressWasConsumed = key.isEnterKey() && enterDownConsumed;
            if (key.isEnterKey()) {
                enterDownConsumed = false;
            }
            if (!enterPressWasConsumed && shouldDelegateEnterKeyUp(keyCode)) {
                // The app got the ENTER down, so it must get the up: TextView clears its
                // enterDown latch and MetaKeyKeyListener balances meta state on the UP.
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
     * ENTER key-up goes to the app on a PKB device unless the shift page is active (Shift+Enter
     * has its own key-down branch). On a VKB shape the DOWN is consumed by performEditorAction,
     * so the UP must be too.
     */
    @VisibleForTesting
    boolean shouldDelegateEnterKeyUp(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_ENTER
                && DeviceProfile.isPkb()
                && ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder() != 1;
    }

    /** Test entry point for {@link ResolvedKey#isSymBoardKey}. */
    @VisibleForTesting
    static boolean isSymBoardKey(int keyCode, ScancodeMapping mapping) {
        return ResolvedKey.isSymBoardKey(keyCode, mapping);
    }

    /**
     * Tracker bookkeeping for a key-up consumed before the tracked-key block; otherwise the
     * keys-down count leaks (blocking showFcc()) and InputLogic keeps the event tracked.
     */
    private void releaseConsumedBoardKey(KeyEvent event) {
        if (ime.getInputLogic().isKeyTracked(event)) {
            ime.getPhysicalKeyboardStateTracker().handleKeyUp(event.getKeyCode(), event);
            ime.getInputLogic().untrackKeyEvent(event);
        }
    }

    /**
     * With the UIM enabled (including a menu raised by the toggle action), toggle through
     * {@link UnifiedInputBoardManager#requestBoard(int)} so a second press closes it; else run
     * {@code fallback}.
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

    /**
     * Feeds a key-down to the open emoji board's search ({@link EmojiQueryKeyRouting}). Characters
     * go through the typing converter so Alt chars and layouts match; Backspace edits a non-empty
     * query. Consumed keys are tracked so their release is swallowed.
     *
     * @return true when the board took the key
     */
    private boolean routeKeyDownToEmojiQuery(ResolvedKey key, KeyEvent event) {
        if (!key.isPhysical() || !ime.isInputViewShown()
                || !ime.getKeyboardSwitcher().isEmojiKeyboardShowing()
                || !EmojiQueryKeyRouting.mayFeedQuery(key)) {
            return false;
        }
        // "Enable emoji search" off: typing goes to the editor.
        if (!ime.getSettingsManager().getSettingsValues().isPkbEmojiSearchEnabled) {
            return false;
        }
        EmojiPalettesView board = ime.getKeyboardSwitcher().peekEmojiPalettesView();
        if (board == null) {
            return false;
        }
        final int keyCode = key.keyCode();
        if (key.isBackspaceKey()) {
            if (!board.backspaceQuery()) {
                return false;
            }
            trackEmojiQueryKey(keyCode, event);
            return true;
        }
        PhysicalKeyboardStateTracker tracker = ime.getPhysicalKeyboardStateTracker();
        EditorInfo editorInfo = ime.getCurrentInputEditorInfo();
        int inputType = editorInfo == null ? 0 : editorInfo.inputType;
        InputEvent converted = ime.getOrCreateKeyEventConverter(event.getDeviceId())
                .convertKeyEvent(event, tracker.getComputedMetaState(event), inputType);
        CharSequence text = EmojiQueryKeyRouting.queryText(converted);
        if (text == null) {
            return false;
        }
        tracker.handleKeyDown(keyCode, event);
        board.appendToQuery(text);
        // A one-shot Shift or Alt is spent on this character.
        tracker.consumeModifiersAfterKey(keyCode, true);
        trackEmojiQueryKey(keyCode, event);
        return true;
    }

    private void trackEmojiQueryKey(int keyCode, KeyEvent event) {
        emojiQueryKeysDown.add(keyCode);
        ime.getInputLogic().trackKeyEvent(event);
    }
}
