package dev.bbkb.ime.core.keyevent;

import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;

/**
 * Resolves the action of a device multifunction key (config {@code role="MULTIFUNCTION"},
 * {@code default-action}). {@link #ACTION_CTRL} rewrites the events to KEYCODE_CTRL_LEFT in
 * remapKeyEvent; {@link #ACTION_EMOJI_BOARD} arms {@link PendingKeyAction#EMOJI_BOARD}; every other
 * action arms {@link PendingKeyAction#MULTIFUNCTION} and runs on key-up. Alt+key always types the
 * mapping's {@code altChar}.
 */
public final class MultifunctionKeyHandler {

    /** SharedPreferences key holding the chosen action; "" means "use the device default". */
    public static final String PREF_KEY = "pref_multifunction_key_action";

    public static final String ACTION_CTRL = "ctrl";
    public static final String ACTION_EMOJI_BOARD = "emoji_board";
    public static final String ACTION_CLIPBOARD_BOARD = "clipboard_board";
    /** The Fine Cursor Control board (UIM keycode -42). */
    public static final String ACTION_FCC = "fcc";
    /** The Number Pad board (UIM keycode -46). */
    public static final String ACTION_NUMBER_PAD = "number_pad";
    /** The language bar on the suggestion strip (toggled). */
    public static final String ACTION_LANGUAGE_SWITCH = "language_switch";
    /** Straight to the other of the last two languages, as the on-screen quick-switch key does. */
    public static final String ACTION_LANGUAGE_QUICK_SWITCH = "language_quick_switch";
    public static final String ACTION_SYMBOL_KEYBOARD = "symbol_keyboard";
    /** Toggles the input menu bar even where its settings keep it off. */
    public static final String ACTION_TOGGLE_UIM = "toggle_uim";

    /** Fallback when neither the user pref nor the device config provides an action. */
    public static final String DEFAULT_ACTION = ACTION_EMOJI_BOARD;

    /** Retired action ids, upgraded by {@link #upgradeLegacyAction}. */
    public static final String LEGACY_ACTION_VOICE_INPUT = "voice_input";
    public static final String LEGACY_ACTION_CURSOR_MODE = "cursor_mode";
    public static final String LEGACY_ACTION_HIDE_KEYBOARD = "hide_keyboard";

    private MultifunctionKeyHandler() {
        // Static utility.
    }

    /**
     * User pref, then the mapping's XML default-action, then {@link #DEFAULT_ACTION}; retired ids upgraded.
     * @param mapping the key's mapping, or null for pref and global default only
     */
    public static String getConfiguredAction(ScancodeMapping mapping) {
        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        String action = upgradeLegacyAction((sv != null) ? sv.multifunctionKeyAction : null);
        if (action == null || action.isEmpty()) {
            action = (mapping != null) ? upgradeLegacyAction(mapping.defaultAction) : null;
            if (action == null || action.isEmpty()) {
                action = DEFAULT_ACTION;
            }
        }
        return action;
    }

    /** cursor_mode becomes {@link #ACTION_FCC}; voice_input and hide_keyboard become "" (device default). */
    public static String upgradeLegacyAction(String action) {
        if (LEGACY_ACTION_CURSOR_MODE.equals(action)) return ACTION_FCC;
        if (LEGACY_ACTION_VOICE_INPUT.equals(action) || LEGACY_ACTION_HIDE_KEYBOARD.equals(action)) return "";
        return action;
    }
}
