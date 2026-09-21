package dev.bbkb.ime.keyboard.internal;

import java.util.HashMap;



public final class KeyboardCodesSet {

    private static final HashMap<String, Integer> sNameToIdMap = new HashMap<>();

    private static final String[] KEY_NAMES = {"key_tab", "key_enter", "key_space", "key_shift", "key_capslock", "key_switch_alpha_symbol", "key_output_text", "key_delete", "key_settings", "key_shortcut", "key_action_next", "key_action_previous", "key_shift_enter", "key_language_switch", "key_emoji", "key_alpha_from_emoji", "key_symbols_page_switch", "key_diacritics_toggle", "key_unspecified", "key_show_input_menu", "key_hide_input_menu", "key_paste", "key_input_settings", "key_voice_dictation", "key_rapid_input", "key_arrow_left", "key_arrow_right", "key_arrow_up", "key_arrow_down", "key_control", "key_password_keeper_fill", "key_password_keeper_add", "key_password_keeper_back", "key_password_keeper_force_bar", "key_password_keeper_toggle_lock", "key_cangjie_regular_switch", "key_cangjie_quick_switch", "key_language_quick_switch", "key_fcc", "key_hide_symbol_page", "key_show_keyboard_settings", "key_number_pad"};

    private static final int[] KEY_CODES = {9, 10, 32, -1, -2, -3, -4, -5, -6, -7, -8, -9, -12, -10, -11, -14, -15, -16, -21, -23, -24, -25, -26, -27, -29, -30, -31, -32, -33, -34, -35, -36, -44, -37, -38, -39, -40, -41, -42, -43, -45, -46};

    static {
        int i = 0;
        while (true) {
            String[] strArr = KEY_NAMES;
            if (i >= strArr.length) {
                break;
            }
            sNameToIdMap.put(strArr[i], Integer.valueOf(i));
            i++;
        }
    }

    public static int getCode(String str) {
        Integer num = sNameToIdMap.get(str);
        if (num == null) {
            throw new RuntimeException("Unknown key code: " + str);
        }
        return KEY_CODES[num.intValue()];
    }
}
