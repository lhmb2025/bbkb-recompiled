package dev.bbkb.ime.keyboard.inputboard;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiBoardController;
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController;
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController;

/**
 * Order of the user-orderable input menu toggles. The bar shows {@link #SHOWN_COUNT} around the
 * fixed centre key: (1)(2) [keyboard] (3)(4); the rest appear only in the customize screen.
 * Stored in {@link #PREF_KEY} as comma-separated ids; {@link #getOrder} drops unknown ids and
 * duplicates and appends missing ones, so a new toggle surfaces at the end.
 *
 * <p>The set of tools and their default order is the {@link #TOOLS} table. Each row binds a
 * toggle's stored id (also carried by the menu_pool key and the customize screen) to the dispatch
 * code owned by the tool's controller — the componentMap key for a panel tool, or
 * {@link QrScanLauncher#KEY_CODE} for the QR action tool. The codes live in those controllers, not
 * here, so this table references them rather than repeating the literals.
 *
 * <p>Adding a tool: append one row to {@link #TOOLS}, add its key to
 * {@code res/xml/kbd_unified_input_menu_pool.xml}, and declare its icon (an {@code iconXxxKey}
 * attr set in {@code attrs.xml} and {@code styles.xml}, mapped in {@code KeyboardIconSet}). A panel
 * tool also registers its controller with {@code UnifiedInputBoardManager}; an action tool is
 * dispatched directly (see {@code UnifiedInputBoardManager.dispatchBoardAction}).
 */
public final class UimMenuOrder {

    public static final String PREF_KEY = "pref_uim_menu_order";

    public static final int SHOWN_COUNT = 4;

    public static final String VOICE = "voice";
    public static final String EMOJI = "emoji";
    public static final String FCC = "fcc";
    public static final String CLIPBOARD = "clipboard";
    public static final String NUMBER_PAD = "numpad";
    public static final String QR_SCAN = "qrscan";

    /** One toggle: its stored id and the dispatch code its controller declares. */
    private static final class Tool {
        final String id;
        final int code;

        Tool(String id, int code) {
            this.id = id;
            this.code = code;
        }
    }

    /** The tools and their default bar order. Single source of truth for the set of toggles. */
    private static final List<Tool> TOOLS = Collections.unmodifiableList(Arrays.asList(
            new Tool(VOICE, VoiceInputController.KEY_CODE),
            new Tool(EMOJI, EmojiBoardController.KEY_CODE),
            new Tool(FCC, FccController.KEY_CODE),
            new Tool(CLIPBOARD, ClipboardController.KEY_CODE),
            new Tool(NUMBER_PAD, NumberPadController.KEY_CODE),
            new Tool(QR_SCAN, QrScanLauncher.KEY_CODE)));

    private static final List<String> DEFAULT_ORDER;

    static {
        List<String> ids = new ArrayList<>(TOOLS.size());
        for (Tool tool : TOOLS) {
            ids.add(tool.id);
        }
        DEFAULT_ORDER = Collections.unmodifiableList(ids);
    }

    private UimMenuOrder() {
        // Static utility.
    }

    public static List<String> defaultOrder() {
        return DEFAULT_ORDER;
    }

    /** The UIM board keycode for a toggle id (the componentMap key), or 0 if unknown. */
    public static int keyCodeFor(String id) {
        for (Tool tool : TOOLS) {
            if (tool.id.equals(id)) {
                return tool.code;
            }
        }
        return 0;
    }

    /** The full sanitized order (all toggles, shown ones first). */
    public static List<String> getOrder(SharedPreferences prefs) {
        String stored = prefs.getString(PREF_KEY, null);
        List<String> order = new ArrayList<>(DEFAULT_ORDER.size());
        if (stored != null) {
            for (String id : stored.split(",")) {
                if (DEFAULT_ORDER.contains(id) && !order.contains(id)) {
                    order.add(id);
                }
            }
        }
        for (String id : DEFAULT_ORDER) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }

    public static void saveOrder(SharedPreferences prefs, List<String> order) {
        prefs.edit().putString(PREF_KEY, String.join(",", order)).apply();
    }

    /** Keycodes of the toggles that occupy the four bar slots, in slot order. */
    public static List<Integer> getShownKeyCodes(SharedPreferences prefs) {
        List<String> order = getOrder(prefs);
        List<Integer> codes = new ArrayList<>(SHOWN_COUNT);
        for (int i = 0; i < SHOWN_COUNT && i < order.size(); i++) {
            codes.add(keyCodeFor(order.get(i)));
        }
        return codes;
    }
}
