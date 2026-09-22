package dev.bbkb.ime.keyboard.inputboard;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Single source of truth for the user-orderable Unified Input Menu toggles.
 *
 * The bar shows {@link #SHOWN_COUNT} toggles around the fixed center keyboard/settings
 * key — positions (1)(2) [keyboard] (3)(4). Items past position 4 exist only in the
 * Customize Menu settings screen and are not shown on the keyboard.
 *
 * The order is stored in {@link #PREF_KEY} as a comma-separated list of the ids below.
 * {@link #getOrder} always returns a complete, sane list: unknown ids are dropped,
 * duplicates removed, and missing ids appended in default order, so adding a new
 * toggle in a future version automatically surfaces it at the end of the list.
 */
public final class UimMenuOrder {

    public static final String PREF_KEY = "pref_uim_menu_order";

    public static final int SHOWN_COUNT = 4;

    public static final String VOICE = "voice";
    public static final String EMOJI = "emoji";
    public static final String FCC = "fcc";
    public static final String CLIPBOARD = "clipboard";
    public static final String NUMBER_PAD = "numpad";

    private static final List<String> DEFAULT_ORDER = Collections.unmodifiableList(
            Arrays.asList(VOICE, EMOJI, FCC, CLIPBOARD, NUMBER_PAD));

    private UimMenuOrder() {
    }

    public static List<String> defaultOrder() {
        return DEFAULT_ORDER;
    }

    /** The UIM board keycode for a toggle id (the componentMap key), or 0 if unknown. */
    public static int keyCodeFor(String id) {
        switch (id) {
            case VOICE: return -27;
            case EMOJI: return -11;
            case FCC: return -42;
            case CLIPBOARD: return -25;
            case NUMBER_PAD: return -46;
            default: return 0;
        }
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
