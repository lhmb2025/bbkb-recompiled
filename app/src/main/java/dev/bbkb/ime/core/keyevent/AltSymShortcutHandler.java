package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;

/**
 * Handles Alt+Sym chord detection and dispatches the user-configured action instead of the
 * symbol board toggle. Called from KeyInterceptorService (via HardwareKeyBridge), the
 * KEYCODE_SYM branch of KeyEventProcessor.processKeyEventForState(), and onKeyDownInternal(),
 * the ordinary hardware pipeline (the only path that runs without the accessibility service).
 * A held Alt is in the event's meta state; a sticky/locked Alt (and a held Alt the accessibility
 * block ate) lives only in the app's span state, so call sites must use {@link #effectiveMetaState}.
 */
public class AltSymShortcutHandler {

    private static final String TAG = "AltSymShortcutHandler";

    public static final String PREF_KEY = "pref_alt_sym_shortcut_action";
    public static final String ACTION_DISABLED = "disabled";
    /** Each Alt+Sym is one Ctrl tap: on for the next key, two in a row lock, one more off. */
    public static final String ACTION_CTRL = MultifunctionKeyHandler.ACTION_CTRL;
    // The rest share their ids with the multifunction key's actions.
    public static final String ACTION_EMOJI_BOARD = MultifunctionKeyHandler.ACTION_EMOJI_BOARD;
    public static final String ACTION_CLIPBOARD_BOARD = MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD;
    public static final String ACTION_FCC = MultifunctionKeyHandler.ACTION_FCC;
    public static final String ACTION_NUMBER_PAD = MultifunctionKeyHandler.ACTION_NUMBER_PAD;
    public static final String ACTION_LANGUAGE_SWITCH = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH;
    public static final String ACTION_LANGUAGE_QUICK_SWITCH = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH;
    public static final String ACTION_SYMBOL_KEYBOARD = MultifunctionKeyHandler.ACTION_SYMBOL_KEYBOARD;
    public static final String ACTION_TOGGLE_UIM = MultifunctionKeyHandler.ACTION_TOGGLE_UIM;

    /** Retired ids still accepted from stored prefs; see {@link #upgradeLegacyAction}. */
    public static final String LEGACY_ACTION_EMOJI_PICKER = "emoji_picker";
    public static final String LEGACY_ACTION_CTRL_MODE = "ctrl_mode";

    /** Mask covering all possible Alt meta flags including ALT_LOCKED (0x200). */
    private static final int ALT_ANY_MASK =
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
            | KeyEvent.META_ALT_RIGHT_ON | 0x200;

    /** IME-level actions the shortcut can run; implemented by BlackBerryIME. */
    public interface ActionCallback {
        void openSymbolKeyboard();
        /** Toggle the language bar on the suggestion strip. */
        void switchLanguage();
        /** Jump to the other of the last two languages. */
        void quickSwitchLanguage();
        void toggleEmojiPicker();
        void toggleClipboard();
        void toggleFcc();
        void toggleNumberPad();
        void toggleInputMenu();
        /** One tap of Ctrl; see {@link #ACTION_CTRL}. */
        void tapCtrl();
    }

    private ActionCallback callback;

    /**
     * Set when the chord consumed a Sym ACTION_DOWN, so its ACTION_UP is swallowed instead of
     * reaching the symbol board or the app. The accessibility path pairs press/release itself.
     */
    private boolean pendingSymKeyUp;

    public void setCallback(ActionCallback callback) {
        this.callback = callback;
    }

    /** Whether this meta state means Alt is active (held, sticky or locked). */
    public static boolean isAltActive(int metaState) {
        return (metaState & ALT_ANY_MASK) != 0;
    }

    /**
     * The event's system meta OR the app's sticky/locked Alt span (same as
     * {@link ModifierState#getChordMetaState()}). Read {@code internalMetaState} before
     * PhysicalKeyboardStateTracker.handleKeyDown, which resets Alt on Sym when
     * {@code usesMetaSymHandling()} is false.
     *
     * @param internalMetaState getModifierKeyMetaState(), not getInternalMetaState() (that one is
     *                          masked, and the PKB symbol board's Alt page adds Alt)
     */
    public static int effectiveMetaState(KeyEvent event, int internalMetaState) {
        return (event == null ? 0 : event.getMetaState()) | internalMetaState;
    }

    /** The Sym ACTION_DOWN fired a chord action; {@link #consumeSymKeyUp} swallows its release. */
    public void markSymPressConsumed() {
        this.pendingSymKeyUp = true;
    }

    /** True exactly once, for the release of a Sym press {@link #markSymPressConsumed} flagged. */
    public boolean consumeSymKeyUp() {
        boolean pending = this.pendingSymKeyUp;
        this.pendingSymKeyUp = false;
        return pending;
    }

    /** A Sym press that did not fire the chord owns its own key-up. */
    public void clearPendingSymKeyUp() {
        this.pendingSymKeyUp = false;
    }

    /**
     * @param internalMetaState the app's sticky/locked modifier state, read before the tracker
     *                          processed {@code symDownEvent}
     * @return true if the chord was detected and the action executed
     */
    public boolean detectAndExecute(KeyEvent symDownEvent, int internalMetaState) {
        return detectAndExecute(effectiveMetaState(symDownEvent, internalMetaState));
    }

    /** Runs the configured action if Alt is active; true means the caller consumes the Sym. */
    public boolean detectAndExecute(int effectiveMetaState) {
        String action = getConfiguredAction();
        if (ACTION_DISABLED.equals(action)) {
            return false;
        }

        if (!isAltActive(effectiveMetaState)) {
            return false;
        }

        if (callback == null) {
            Logger.warn(TAG, "Alt+Sym chord detected but no callback set");
            return false;
        }

        Logger.debug(TAG, "Alt+Sym chord detected, action=" + action
                + ", metaState=0x" + Integer.toHexString(effectiveMetaState));

        switch (action) {
            case ACTION_SYMBOL_KEYBOARD:
                callback.openSymbolKeyboard();
                break;
            case ACTION_LANGUAGE_SWITCH:
                callback.switchLanguage();
                break;
            case ACTION_LANGUAGE_QUICK_SWITCH:
                callback.quickSwitchLanguage();
                break;
            case ACTION_EMOJI_BOARD:
                callback.toggleEmojiPicker();
                break;
            case ACTION_CLIPBOARD_BOARD:
                callback.toggleClipboard();
                break;
            case ACTION_FCC:
                callback.toggleFcc();
                break;
            case ACTION_NUMBER_PAD:
                callback.toggleNumberPad();
                break;
            case ACTION_TOGGLE_UIM:
                callback.toggleInputMenu();
                break;
            case ACTION_CTRL:
                callback.tapCtrl();
                break;
            default:
                Logger.warn(TAG, "Unknown Alt+Sym action: " + action);
                return false;
        }

        return true;
    }

    public boolean isCtrlAction() {
        return ACTION_CTRL.equals(getConfiguredAction());
    }

    private String getConfiguredAction() {
        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (sv != null && sv.altSymShortcutAction != null) {
            return upgradeLegacyAction(sv.altSymShortcutAction);
        }
        return ACTION_DISABLED;
    }

    /** Map a retired action id onto its replacement; anything else is returned as-is. */
    public static String upgradeLegacyAction(String action) {
        if (LEGACY_ACTION_EMOJI_PICKER.equals(action)) return ACTION_EMOJI_BOARD;
        if (LEGACY_ACTION_CTRL_MODE.equals(action)) return ACTION_CTRL;
        if (MultifunctionKeyHandler.LEGACY_ACTION_HIDE_KEYBOARD.equals(action)) return ACTION_DISABLED;
        return action;
    }
}
