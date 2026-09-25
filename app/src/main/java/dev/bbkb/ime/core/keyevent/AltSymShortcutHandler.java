package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.Logger;

/**
 * Handles Alt+Sym chord detection and configurable action dispatch.
 *
 * Called from all three Sym key detection points:
 * 1. KeyInterceptorService → onSpecialKeyPressed(SYM) callback, via HardwareKeyBridge
 * 2. KeyEventProcessor.processKeyEventForState() → KEYCODE_SYM branch
 * 3. KeyEventProcessor.onKeyDownInternal() → the ordinary hardware key pipeline
 *
 * <p>(3) was added on 2026-09-21 and is the one that matters on a BlackBerry KEY2. Paths 1 and 2
 * both need the accessibility service: (1) additionally needs {@code pref_key_interceptor_enabled},
 * which defaults to <em>false</em>, and (2) only ever runs for a key the service pre-processes
 * while no input connection is bound — which a board key never is, because
 * {@code KeyInterceptorService} classifies it out before the all-keys callback. So on a KEY2,
 * whose Sym key reports a real {@code KEYCODE_SYM}, the chord was consulted by nobody and Alt+Sym
 * did exactly what a bare Sym does. That is the "Alt+Sym shortcut does nothing" report.
 *
 * <p>A fourth was listed here until 2026-09-16 - {@code SymKeyStateTracker → onSymKeyPressed()} -
 * but that tracker was never fed a key event by anything, so the path never existed; it has been
 * deleted. See the note at {@code KeyEventConverter}'s {@code usesMetaSymHandling()} branch.
 *
 * When Alt is active (held, sticky, or locked) and Sym is pressed,
 * this handler intercepts the Sym event and dispatches the user-configured
 * action instead of the default symbol keyboard toggle.
 *
 * <h3>Held Alt vs sticky Alt</h3>
 * These arrive by different routes and neither one alone is enough. A physically HELD Alt shows
 * up as {@code META_ALT_ON} in the event's own {@link KeyEvent#getMetaState()}; a tapped (sticky)
 * or double-tapped (locked) Alt lives only in this app's span state, in
 * {@code PhysicalKeyboardStateTracker.getInternalMetaState()} — and on a device where the
 * accessibility service's Alt bleed-through block eats hardware Alt, a held Alt is <em>only</em>
 * in the internal state. {@link #effectiveMetaState} is therefore the OR of the two, and every
 * call site must use it.
 */
public class AltSymShortcutHandler {

    private static final String TAG = "AltSymShortcutHandler";

    public static final String PREF_KEY = "pref_alt_sym_shortcut_action";
    public static final String ACTION_DISABLED = "disabled";
    // The rest share their ids with the multifunction key's actions (all of them but Ctrl).
    public static final String ACTION_EMOJI_BOARD = MultifunctionKeyHandler.ACTION_EMOJI_BOARD;
    public static final String ACTION_CLIPBOARD_BOARD = MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD;
    public static final String ACTION_FCC = MultifunctionKeyHandler.ACTION_FCC;
    public static final String ACTION_NUMBER_PAD = MultifunctionKeyHandler.ACTION_NUMBER_PAD;
    public static final String ACTION_LANGUAGE_SWITCH = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH;
    public static final String ACTION_SYMBOL_KEYBOARD = MultifunctionKeyHandler.ACTION_SYMBOL_KEYBOARD;

    /**
     * Retired ids: "emoji_picker" was renamed to {@link #ACTION_EMOJI_BOARD}; "ctrl_mode" and
     * "hide_keyboard" were dropped.
     */
    public static final String LEGACY_ACTION_EMOJI_PICKER = "emoji_picker";
    public static final String LEGACY_ACTION_CTRL_MODE = "ctrl_mode";

    /** Mask covering all possible Alt meta flags including ALT_LOCKED (0x200). */
    private static final int ALT_ANY_MASK =
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON
            | KeyEvent.META_ALT_RIGHT_ON | 0x200;

    /**
     * Callback interface for executing the shortcut action.
     * Implemented by BlackBerryIME to provide access to IME-level actions.
     */
    public interface ActionCallback {
        void openSymbolKeyboard();
        void switchLanguage();
        void toggleEmojiPicker();
        void toggleClipboard();
        void toggleFcc();
        void toggleNumberPad();
    }

    private ActionCallback callback;

    /**
     * Set by a key-event call site whose Sym ACTION_DOWN the chord consumed, so the matching
     * ACTION_UP can be swallowed instead of falling through to the symbol-board machinery (and
     * from there to the app as a stray Sym release). Not used by the accessibility path, which
     * pairs the press and release itself through
     * {@code KeyInterceptorService.sConsumedBoardKeyScanCode}.
     */
    private boolean pendingSymKeyUp;

    public void setCallback(ActionCallback callback) {
        this.callback = callback;
    }

    /** Whether this meta state means "Alt is active" — held, sticky or locked. */
    public static boolean isAltActive(int metaState) {
        return (metaState & ALT_ANY_MASK) != 0;
    }

    /**
     * The meta state a Sym press must be judged against: the system meta the event carries OR the
     * app's own sticky/locked Alt span. See the class note on held vs sticky Alt.
     *
     * <p>Callers must read {@code internalMetaState} <em>before</em> handing the Sym event to
     * {@code PhysicalKeyboardStateTracker.handleKeyDown}: that method resets the internal Alt
     * state for key code 63 ({@link KeyEvent#KEYCODE_SYM}) on every profile whose
     * {@code usesMetaSymHandling()} is false, so an Alt read afterwards is always gone.
     */
    public static int effectiveMetaState(KeyEvent event, int internalMetaState) {
        // Phase 1b: this is {@link ModifierState#getChordMetaState()}. The key-event call sites
        // ask the query API directly; this overload stays for detectAndExecute(KeyEvent, int).
        // Callers pass PhysicalKeyboardStateTracker.getModifierKeyMetaState(): the span state the
        // user's modifier keys set. NOT getInternalMetaState() — that is run through the current
        // keyboard's meta mask, and the PKB symbol board's Alt page is a mask that adds Alt.
        return (event == null ? 0 : event.getMetaState()) | internalMetaState;
    }

    /**
     * Record that the Sym ACTION_DOWN just dispatched a chord action, so {@link #consumeSymKeyUp}
     * will swallow its release.
     */
    public void markSymPressConsumed() {
        this.pendingSymKeyUp = true;
    }

    /** True exactly once, for the release of a Sym press {@link #markSymPressConsumed} flagged. */
    public boolean consumeSymKeyUp() {
        boolean pending = this.pendingSymKeyUp;
        this.pendingSymKeyUp = false;
        return pending;
    }

    /** Drop any outstanding flag — a Sym press that did NOT fire the chord owns its own key-up. */
    public void clearPendingSymKeyUp() {
        this.pendingSymKeyUp = false;
    }

    /**
     * Check if Alt+Sym chord is active and execute the configured action.
     *
     * @param symDownEvent      the Sym ACTION_DOWN event, for its system meta state
     * @param internalMetaState the app's sticky/locked modifier state, read BEFORE the tracker
     *                          processed {@code symDownEvent}
     * @return true if the chord was detected and the action executed
     */
    public boolean detectAndExecute(KeyEvent symDownEvent, int internalMetaState) {
        return detectAndExecute(effectiveMetaState(symDownEvent, internalMetaState));
    }

    /**
     * Check if Alt+Sym chord is active and execute the configured action.
     *
     * @param effectiveMetaState combined system + internal meta state
     * @return true if chord was detected and action was executed (Sym should be consumed),
     *         false if no chord detected (caller should proceed with normal Sym handling)
     */
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
            default:
                Logger.warn(TAG, "Unknown Alt+Sym action: " + action);
                return false;
        }

        return true;
    }

    /**
     * Read the configured action from settings.
     */
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
        if (LEGACY_ACTION_CTRL_MODE.equals(action)
                || MultifunctionKeyHandler.LEGACY_ACTION_HIDE_KEYBOARD.equals(action)) return ACTION_DISABLED;
        return action;
    }
}
