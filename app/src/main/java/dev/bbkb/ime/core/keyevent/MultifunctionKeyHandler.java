package dev.bbkb.ime.core.keyevent;

import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;

/**
 * Action resolution for device multifunction keys (KeyRole.MULTIFUNCTION).
 *
 * A device config can declare one of its physical keys as a multifunction key
 * (e.g. the Key2 mic key): {@code <key rawKeyCode="7" role="MULTIFUNCTION"
 * altChar="0" default-action="emoji_board"/>}. The user then picks what the key
 * does from the "Multifunction key" setting on the Physical keyboard screen
 * (shown only when the active device config declares such a key).
 *
 * Dispatch is split across the existing key pipeline, mirroring how BOARD_*
 * keys work:
 * <ul>
 *   <li>{@link #ACTION_CTRL} — the key's events are rewritten to
 *       KEYCODE_CTRL_LEFT in BlackBerryIME.remapKeyEvent(), so the physical
 *       Ctrl machinery (chording, Ctrl+C/V/X shortcuts) treats it as a real
 *       Ctrl key.</li>
 *   <li>{@link #ACTION_EMOJI_BOARD} — reuses the BOARD_EMOJI path
 *       (KeyEventConverter arms {@link PendingKeyAction#EMOJI_BOARD};
 *       KeyEventProcessor opens the board on key-up).</li>
 *   <li>All other actions — KeyEventConverter arms
 *       {@link PendingKeyAction#MULTIFUNCTION}; KeyEventProcessor dispatches the
 *       action on key-up. {@link BoardKeyPressTracker} owns that pairing.</li>
 * </ul>
 *
 * Alt+key always types the mapping's {@code altChar} (e.g. '0' on the Key2),
 * regardless of the configured action.
 */
public final class MultifunctionKeyHandler {

    /** SharedPreferences key holding the chosen action; "" means "use the device default". */
    public static final String PREF_KEY = "pref_multifunction_key_action";

    public static final String ACTION_CTRL = "ctrl";
    public static final String ACTION_EMOJI_BOARD = "emoji_board";
    public static final String ACTION_CLIPBOARD_BOARD = "clipboard_board";
    /** The Fine Cursor Control BOARD (full input board, UIM keycode -42). */
    public static final String ACTION_FCC = "fcc";
    /** The Number Pad board (UIM keycode -46). */
    public static final String ACTION_NUMBER_PAD = "number_pad";
    public static final String ACTION_LANGUAGE_SWITCH = "language_switch";
    public static final String ACTION_SYMBOL_KEYBOARD = "symbol_keyboard";

    /** Fallback when neither the user pref nor the device config provides an action. */
    public static final String DEFAULT_ACTION = ACTION_EMOJI_BOARD;

    /**
     * Actions that were offered once and are no longer valid. "voice_input" was dropped (the KEY2
     * has a dedicated mic key); "cursor_mode" (the arrow bar) folded into {@link #ACTION_FCC};
     * "hide_keyboard" was dropped.
     */
    public static final String LEGACY_ACTION_VOICE_INPUT = "voice_input";
    public static final String LEGACY_ACTION_CURSOR_MODE = "cursor_mode";
    public static final String LEGACY_ACTION_HIDE_KEYBOARD = "hide_keyboard";

    private MultifunctionKeyHandler() {
        // Static utility
    }

    /**
     * Resolve the effective action for a multifunction key:
     * user pref → mapping's XML default-action → {@link #DEFAULT_ACTION}. A retired action id
     * from either source is upgraded by {@link #upgradeLegacyAction}.
     *
     * @param mapping the key's ScancodeMapping (may be null; then only pref/global default apply)
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

    /**
     * Map a retired action id onto its replacement: cursor_mode becomes {@link #ACTION_FCC}, and
     * voice_input and hide_keyboard become "" (unset) so the device default applies. Anything else is returned as-is.
     */
    public static String upgradeLegacyAction(String action) {
        if (LEGACY_ACTION_CURSOR_MODE.equals(action)) return ACTION_FCC;
        if (LEGACY_ACTION_VOICE_INPUT.equals(action) || LEGACY_ACTION_HIDE_KEYBOARD.equals(action)) return "";
        return action;
    }
}
