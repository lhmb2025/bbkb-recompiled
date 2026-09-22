package dev.bbkb.ime.core.keyevent;

import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;

/**
 * Action resolution for device multifunction keys (KeyRole.MULTIFUNCTION).
 *
 * A device config can declare one of its physical keys as a multifunction key
 * (e.g. the Key2 mic key): {@code <key rawKeyCode="7" role="MULTIFUNCTION"
 * altChar="0" default-action="voice_input"/>}. The user then picks what the key
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
 *   <li>{@link #ACTION_VOICE_INPUT} / {@link #ACTION_EMOJI_BOARD} — reuse the
 *       BOARD_VOICE / BOARD_EMOJI paths (KeyEventConverter arms
 *       InputMethodHelper.micKeyPressed/emojiKeyPressed; KeyEventProcessor
 *       opens the board on key-up).</li>
 *   <li>All other actions — KeyEventConverter arms
 *       InputMethodHelper.multifunctionKeyPressed; KeyEventProcessor
 *       dispatches the action on key-up.</li>
 * </ul>
 *
 * Alt+key always types the mapping's {@code altChar} (e.g. '0' on the Key2),
 * regardless of the configured action.
 */
public final class MultifunctionKeyHandler {

    /** SharedPreferences key holding the chosen action; "" means "use the device default". */
    public static final String PREF_KEY = "pref_multifunction_key_action";

    public static final String ACTION_VOICE_INPUT = "voice_input";
    public static final String ACTION_CTRL = "ctrl";
    public static final String ACTION_EMOJI_BOARD = "emoji_board";
    public static final String ACTION_CLIPBOARD_BOARD = "clipboard_board";
    /** The Fine Cursor Control BOARD (full input board, UIM keycode -42). */
    public static final String ACTION_FCC = "fcc";
    /** Cursor MODE: the transient arrow-key bar (BlackBerryIME.toggleCursorMode). */
    public static final String ACTION_CURSOR_MODE = "cursor_mode";
    public static final String ACTION_LANGUAGE_SWITCH = "language_switch";
    public static final String ACTION_SYMBOL_KEYBOARD = "symbol_keyboard";
    public static final String ACTION_HIDE_KEYBOARD = "hide_keyboard";

    /** Fallback when neither the user pref nor the device config provides an action. */
    public static final String DEFAULT_ACTION = ACTION_VOICE_INPUT;

    private MultifunctionKeyHandler() {
        // Static utility
    }

    /**
     * Resolve the effective action for a multifunction key:
     * user pref → mapping's XML default-action → {@link #DEFAULT_ACTION}.
     *
     * @param mapping the key's ScancodeMapping (may be null; then only pref/global default apply)
     */
    public static String getConfiguredAction(ScancodeMapping mapping) {
        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        String action = (sv != null) ? sv.multifunctionKeyAction : null;
        if (action == null || action.isEmpty()) {
            if (mapping != null && mapping.defaultAction != null && !mapping.defaultAction.isEmpty()) {
                action = mapping.defaultAction;
            } else {
                action = DEFAULT_ACTION;
            }
        }
        return action;
    }
}
