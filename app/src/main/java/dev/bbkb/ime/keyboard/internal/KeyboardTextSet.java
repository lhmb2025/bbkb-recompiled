package dev.bbkb.ime.keyboard.internal;

import java.util.HashMap;
import java.util.Locale;



public final class KeyboardTextSet {

    /**
     * WARNING: {@link #NAMES} contains duplicate entries -- "morekeys_c" at indices 6 and 7,
     * "morekeys_t" at 15 and 17, "morekeys_r" at 18 and 19. This map is built with put(), so the
     * later index wins and the earlier slot of every TEXTS_* locale table is unreachable.
     *
     * <p>The original APK's table has the identical duplicates, and AOSP LatinIME's
     * KeyboardTextsTable has each of these names once, so no evidence names the earlier slots.
     * Harmless because every locale table (here and in the original) holds the same value in
     * both slots of each pair; KeyboardTextSetDuplicateNamesTest fails if one ever diverges.
     */
    private static final HashMap<String, Integer> sNameToIndexesMap = new HashMap<>();

    private static final HashMap<String, String[]> sLocaleToTextsTableMap = new HashMap<>();

    private static final HashMap<String[], String> sTextsTableToLocaleMap = new HashMap<>();

    private static final String[] NAMES = {"morekeys_o", "morekeys_a", "keylabel_to_alpha", "morekeys_e", "morekeys_u", "morekeys_i", "morekeys_c", "morekeys_c", "morekeys_n", "keyspec_currency", "morekeys_s", "double_quotes", "single_quotes", "morekeys_y", "morekeys_d", "morekeys_t", "morekeys_z", "morekeys_t", "morekeys_r", "morekeys_r", "keyspec_comma", "morekeys_l", "morekeys_g", "keyspec_symbols_1", "keyspec_symbols_2", "keyspec_symbols_3", "keyspec_symbols_4", "keyspec_symbols_5", "keyspec_symbols_6", "keyspec_symbols_7", "keyspec_symbols_8", "keyspec_symbols_9", "keyspec_symbols_0", "keyspec_period", "single_angle_quotes", "double_angle_quotes", "additional_morekeys_symbols_1", "additional_morekeys_symbols_2", "additional_morekeys_symbols_3", "additional_morekeys_symbols_4", "additional_morekeys_symbols_5", "additional_morekeys_symbols_6", "additional_morekeys_symbols_7", "additional_morekeys_symbols_8", "additional_morekeys_symbols_9", "additional_morekeys_symbols_0", "morekeys_symbols_1", "morekeys_symbols_2", "morekeys_symbols_3", "morekeys_symbols_4", "morekeys_symbols_5", "morekeys_symbols_6", "morekeys_symbols_7", "morekeys_symbols_8", "morekeys_symbols_9", "morekeys_symbols_0", "morekeys_k", "keyspec_nordic_row1_11", "keyspec_nordic_row2_10", "keyspec_nordic_row2_11", "morekeys_nordic_row2_10", "morekeys_pkb_nordic_a", "morekeys_pkb_nordic_o", "keyspec_east_slavic_row1_9", "keyspec_east_slavic_row2_2", "keyspec_east_slavic_row2_11", "keyspec_east_slavic_row3_5", "morekeys_cyrillic_ie", "morekeys_cyrillic_soft_sign", "morekeys_nordic_row2_11", "morekeys_punctuation", "morekeys_star", "keyspec_tablet_comma", "keyhintlabel_period", "morekeys_w", "morekeys_cyrillic_u", "keyspec_swiss_row1_11", "keyspec_swiss_row2_10", "keyspec_swiss_row2_11", "morekeys_swiss_row1_11", "morekeys_swiss_row2_10", "morekeys_swiss_row2_11", "keylabel_to_symbol", "keyspec_left_parenthesis", "keyspec_right_parenthesis", "keyspec_left_square_bracket", "keyspec_right_square_bracket", "keyspec_left_curly_bracket", "keyspec_right_curly_bracket", "keyspec_less_than", "keyspec_greater_than", "keyspec_less_than_equal", "keyspec_greater_than_equal", "keyspec_left_double_angle_quote", "keyspec_right_double_angle_quote", "keyspec_left_single_angle_quote", "keyspec_right_single_angle_quote", "morekeys_tablet_comma", "morekeys_tablet_period", "morekeys_question", "keyspecs_left_parenthesis_more_keys", "keyspecs_right_parenthesis_more_keys", "morekeys_h", "morekeys_j", "morekeys_x", "morekeys_east_slavic_row2_2", "morekeys_cyrillic_en", "morekeys_cyrillic_ghe", "morekeys_cyrillic_o", "morekeys_tablet_punctuation", "keyspec_spanish_row2_10", "morekeys_bullet", "morekeys_left_parenthesis", "morekeys_right_parenthesis", "morekeys_arabic_diacritics", "keyhintlabel_tablet_comma", "morekeys_period", "keyspec_tablet_period", "keyhintlabel_tablet_period", "keyspec_symbols_question", "keyspec_symbols_semicolon", "keyspec_symbols_percent", "morekeys_symbols_semicolon", "morekeys_symbols_percent", "morekeys_v", "morekeys_q", "morekeys_f", "keyspec_i", "keyspec_q", "keyspec_w", "keyspec_y", "keyspec_x", "morekeys_east_slavic_row2_11", "morekeys_cyrillic_ka", "morekeys_cyrillic_a", "morekeys_cyrillic_i", "keyspec_south_slavic_row1_6", "keyspec_south_slavic_row2_11", "keyspec_south_slavic_row3_1", "keyspec_south_slavic_row3_8", "morekeys_currency_dollar", "morekeys_plus", "morekeys_less_than", "morekeys_greater_than", "morekeys_symbols_tilde", "morekeys_exclamation", "morekeys_currency_generic", "keyspec_symbols_fullwidth_1", "keyspec_symbols_fullwidth_2", "keyspec_symbols_fullwidth_3", "keyspec_symbols_fullwidth_4", "keyspec_symbols_fullwidth_5", "keyspec_symbols_fullwidth_6", "keyspec_symbols_fullwidth_7", "keyspec_symbols_fullwidth_8", "keyspec_symbols_fullwidth_9", "keyspec_symbols_fullwidth_0", "keyspec_symbols_tilde", "keyspec_left_parenthesis_fullwidth", "keyspec_right_parenthesis_fullwidth", "keyspec_left_square_bracket_fullwidth", "keyspec_right_square_bracket_fullwidth", "keyspec_left_curly_bracket_fullwidth", "keyspec_right_curly_bracket_fullwidth", "keyspec_less_than_fullwidth", "keyspec_greater_than_fullwidth", "keyspec_less_than_equal_fullwidth", "keyspec_greater_than_equal_fullwidth", "keyspec_left_double_angle_quote_fullwidth", "keyspec_right_double_angle_quote_fullwidth", "keyspec_left_single_angle_quote_fullwidth", "keyspec_right_single_angle_quote_fullwidth", "keyspec_fullwidth_comma", "keyspec_fullwidth_period", "morekeys_am_pm", "keyspec_settings", "keyspec_shortcut", "keyspec_action_next", "keyspec_action_previous", "keylabel_to_more_symbol", "keylabel_tablet_to_more_symbol", "keylabel_to_phone_numeric", "keylabel_to_phone_symbols", "keylabel_time_am", "keylabel_time_pm", "keyspec_popular_domain", "morekeys_popular_domain", "single_laqm_raqm", "single_raqm_laqm", "double_laqm_raqm", "double_raqm_laqm", "single_lqm_rqm", "single_9qm_lqm", "single_9qm_rqm", "single_rqm_9qm", "double_lqm_rqm", "double_9qm_lqm", "double_9qm_rqm", "double_rqm_9qm", "morekeys_single_quote", "morekeys_double_quote", "morekeys_tablet_double_quote", "keyspec_emoji_action_key", "keylabel_to_cangjie_switch", "keylabel_to_cangjie_quick_switch"};

    private static final String[] TEXTS_DEFAULT = {"", "", "ABC", "", "", "", "©", "©", "", "$", "", "!text/double_lqm_rqm", "!text/single_lqm_rqm", "", "", "™", "", "™", "®", "®", ",", "", "", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ".", "!text/single_laqm_raqm", "!text/double_laqm_raqm", "", "", "", "", "", "", "", "", "", "", "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "!autoColumnOrder!8,\\,,?,!,#,!text/keyspec_right_parenthesis,!text/keyspec_left_parenthesis,/,;,',@,:,-,\",+,\\%,&", "†,‡,★", ",", "", "", "", "", "", "", "", "", "", "?123", "(", ")", "[", "]", "{", "}", "<", ">", "≤", "≥", "«", "»", "‹", "›", "", "", "¿", "!text/keyspec_left_curly_bracket,!text/keyspec_left_square_bracket,!text/keyspec_less_than,!text/keyspec_left_double_angle_quote,〖,《,〔,「,『,【,〈", "!text/keyspec_right_curly_bracket,!text/keyspec_right_square_bracket,!text/keyspec_greater_than,!text/keyspec_right_double_angle_quote,〗,》,〕,」,』,】,〉", "", "", "", "", "", "", "", "!autoColumnOrder!7,\\,,',#,!text/keyspec_right_parenthesis,!text/keyspec_left_parenthesis,/,;,@,:,-,\",+,\\%,&", "ñ", "♪,♥,♠,♦,♣", "!fixedColumnOrder!6,!text/keyspecs_left_parenthesis_more_keys", "!fixedColumnOrder!6,!text/keyspecs_right_parenthesis_more_keys", "", "", "", ".", "", "?", ";", "%", "", "‰,°", "", "", "", "i", "q", "w", "y", "x", "", "", "", "", "", "", "", "", "€,¥,£,₱,¢", "±", "!fixedColumnOrder!3,!text/keyspec_left_single_angle_quote,!text/keyspec_less_than_equal,!text/keyspec_left_double_angle_quote", "!fixedColumnOrder!3,!text/keyspec_right_single_angle_quote,!text/keyspec_greater_than_equal,!text/keyspec_right_double_angle_quote", "§,¶", "¡", "!fixedColumnOrder!7,₪,₹,¢,₱,¥,$,€,₽,₺,₫,¤,฿,£,₩", "１", "２", "３", "４", "５", "６", "７", "８", "９", "０", "~", "（", "）", "［", "］", "｛", "｝", "＜", "＞", "≦", "≧", "《", "》", "〈", "〉", "，", "。", "!fixedColumnOrder!2,!hasLabels!,!text/keylabel_time_am,!text/keylabel_time_pm", "!icon/settings_key|!code/key_settings", "!icon/shortcut_key|!code/key_shortcut", "!hasLabels!,!text/label_next_key|!code/key_action_next", "!hasLabels!,!text/label_previous_key|!code/key_action_previous", "! ? .", "~ [ <", "@123", "＊＃", "am", "pm", ".com", "!hasLabels!,.net,.org,.gov,.edu", "!text/keyspec_left_single_angle_quote,!text/keyspec_right_single_angle_quote", "!text/keyspec_right_single_angle_quote,!text/keyspec_left_single_angle_quote", "!text/keyspec_left_double_angle_quote,!text/keyspec_right_double_angle_quote", "!text/keyspec_right_double_angle_quote,!text/keyspec_left_double_angle_quote", "‚,‘,’", "’,‚,‘", "‘,‚,’", "‘,’,‚", "„,“,”", "”,„,“", "“,„,”", "“,”,„", "!fixedColumnOrder!5,!text/single_quotes,!text/single_angle_quotes", "!fixedColumnOrder!5,!text/double_quotes,!text/double_angle_quotes", "!fixedColumnOrder!6,!text/double_quotes,!text/single_quotes,!text/double_angle_quotes,!text/single_angle_quotes", "!icon/emoji_action_key|!code/key_emoji", "速", "倉"};

    private static final String[] TEXTS_af = {"ó,ô,ö,ò,õ,œ,ø,ō", "á,â,ä,à,æ,ã,å,ā", null, "é,è,ê,ë,ę,ė,ē", "ú,û,ü,ù,ū", "í,ì,ï,î,į,ī,ĳ", null, null, "ñ,ń", null, null, null, null, "ý,ĳ"};

    private static final String[] TEXTS_ar = {null, null, "أ\u200cب\u200cت", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "،", null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "★,٭", "،", "ّ", null, null, null, null, null, null, null, null, null, "(|)", ")|(", "[|]", "]|[", "{|}", "}|{", "<|>", ">|<", "≤|≥", "≥|≤", "«|»", "»|«", "‹|›", "›|‹", "!fixedColumnOrder!4,:,!,؟,؛,-,\",'", "!text/morekeys_arabic_diacritics", "?,¿", "!text/keyspec_left_curly_bracket,!text/keyspec_left_square_bracket,!text/keyspec_less_than,!text/keyspec_left_double_angle_quote,〖|〗,《|》,〔|〕,「|」,『|』,【|】,〈|〉", "!text/keyspec_right_curly_bracket,!text/keyspec_right_square_bracket,!text/keyspec_greater_than,!text/keyspec_right_double_angle_quote,〗|〖,》|《,〕|〔,」|「,』|『,】|【,〉|〈", null, null, null, null, null, null, null, null, null, "♪", "!fixedColumnOrder!4,﴾,!text/keyspecs_left_parenthesis_more_keys", "!fixedColumnOrder!4,﴿,!text/keyspecs_right_parenthesis_more_keys", "!fixedColumnOrder!7, ٕ|ٕ, ٔ|ٔ, ْ|ْ, ٍ|ٍ, ٌ|ٌ, ً|ً, ّ|ّ, ٖ|ٖ, ٰ|ٰ, ٓ|ٓ, ِ|ِ, ُ|ُ, َ|َ,ـــ|ـ", "؟", "!text/morekeys_arabic_diacritics", null, "ّ", "؟", "؛", "٪", ";", "\\%,‰,°"};

    private static final String[] TEXTS_az_AZ = {"ö,ô,œ,ò,ó,õ,ø,ō", "â", null, "ə", "ü,û,ù,ú,ū", "ı,î,ï,ì,í,į,ī", "ç,ć,č,©", "ç,ć,č,©", null, null, "ş,ß,ś,š", null, null, null, null, "™", null, "™", "®", "®", null, null, "ğ"};

    private static final String[] TEXTS_be = {null, null, "АБВ", null, null, null, null, null, null, null, null, "!text/double_9qm_lqm", "!text/single_9qm_lqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ў", "ы", "э", "і", "ё", "ъ", null, null, null, null, null, null, "ў"};

    private static final String[] TEXTS_bg = {null, null, "АБВ", null, null, null, null, null, null, null, null, "!text/double_9qm_lqm"};

    private static final String[] TEXTS_bn_IN = {null, null, "কখগ", null, null, null, null, null, null, "₹", null, null, null, null, null, null, null, null, null, null, null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅", "১", "২", "৩", "৪", "৫", "৬", "৭", "৮", "৯", "০"};

    private static final String[] TEXTS_bs = {null, null, null, null, null, null, "č,ć,ç,©", "č,ć,ç,©", "ñ,ń", null, "š,ś,ß", "!text/double_9qm_rqm", "!text/single_9qm_rqm", null, "đ", "™", "ž,ź,ż", "™", "®", "®", null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm"};

    private static final String[] TEXTS_ca = {"ò,ó,ö,ô,õ,ø,œ,ō,º", "à,á,ä,â,ã,å,ą,æ,ā,ª", null, "è,é,ë,ê,ę,ė,ē", "ú,ü,ù,û,ū", "í,ï,ì,î,į,ī", "ç,ć,č,©", "ç,ć,č,©", "ñ,ń", null, null, null, null, null, null, null, null, null, null, null, null, "l·l,ł", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!autoColumnOrder!9,\\,,?,!,·,#,),(,/,;,',@,:,-,\",+,\\%,&", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!autoColumnOrder!8,\\,,',·,#,),(,/,;,@,:,-,\",+,\\%,&", "ç"};

    private static final String[] TEXTS_cs = {"ó,ö,ô,ò,õ,œ,ø,ō", "á,à,â,ä,æ,ã,å,ā", null, "é,ě,è,ê,ë,ę,ė,ē", "ú,ů,û,ü,ù,ū", "í,î,ï,ì,į,ī", "č,ç,ć,©", "č,ç,ć,©", "ň,ñ,ń", null, "š,ß,ś", "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ď", "ť,™", "ž,ź,ż", "ť,™", "ř,®", "ř,®", null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm"};

    private static final String[] TEXTS_cy = {"ó,ô,ö,ò,ō", "à,á,â,ä,ā", null, "é,è,ê,ë,ē", "ú,û,ü,ù,ū", "í,î,ï,ī,ì", null, null, null, null, null, null, null, "ý,ŷ,ÿ,ỳ,ȳ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ẃ,ŵ,ẅ,ẁ"};

    private static final String[] TEXTS_da = {"ó,ô,ò,õ,œ,ō,ö", "á,ä,à,â,ã,ā", null, "é,ë", "ú,ü,û,ù,ū", "í,ï", null, null, "ñ,ń", null, "ß,ś,š", "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ð", null, null, null, null, null, null, "ł", null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "å", "æ", "ø", "ä", "å,æ,ä,á,à,â,ã,ā", "ø,ö,ó,ô,ò,õ,œ,ō", null, null, null, null, null, null, "ö"};

    private static final String[] TEXTS_de = {"ö,%,ô,ò,ó,õ,œ,ø,ō", "ä,%,â,à,á,æ,ã,å,ā", null, "é,è,ê,ë,ė", "ü,%,û,ù,ú,ū", "í,î,ï,ī,ì", null, null, "ñ,ń", null, "ß,ś,š", "!text/double_9qm_lqm", "!text/single_9qm_lqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ü", "ö", "ä", "è", "é", "à"};

    private static final String[] TEXTS_el = {null, null, "ΑΒΓ"};

    private static final String[] TEXTS_en = {"ó,ô,ö,ò,œ,ø,ō,õ", "à,á,â,ä,æ,ã,å,ā", null, "é,è,ê,ë,ē", "ú,û,ü,ù,ū", "í,î,ï,ī,ì", "ç,©", "ç,©", "ñ", null, "ß"};

    private static final String[] TEXTS_eo = {"ó,ö,ô,ò,õ,œ,ø,ō,ő,º", "á,à,â,ä,æ,ã,å,ā,ă,ą,ª", null, "é,ě,è,ê,ë,ę,ė,ē", "ú,ů,û,ü,ù,ū,ũ,ű,ų,µ", "í,î,ï,ĩ,ì,į,ī,ı,ĳ", "ć,č,ç,ċ,©", "ć,č,ç,ċ,©", "ñ,ń,ņ,ň,ŉ,ŋ", null, "ß,š,ś,ș,ş", null, null, "y,ý,ŷ,ÿ,þ", "ð,ď,đ", "ť,ț,ţ,ŧ,™", "ź,ż,ž", "ť,ț,ţ,ŧ,™", "ř,ŕ,ŗ,®", "ř,ŕ,ŗ,®", null, "ĺ,ļ,ľ,ŀ,ł", "ğ,ġ,ģ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ķ,ĸ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "w,ŵ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ĥ,ħ", null, "x", null, null, null, null, null, "ĵ", null, null, null, null, null, null, null, null, null, null, null, null, null, "w,ŵ", "q", null, null, "ŝ", "ĝ", "ŭ", "ĉ"};

    private static final String[] TEXTS_es = {"ó,ò,ö,ô,õ,ø,œ,ō,º", "á,à,ä,â,ã,å,ą,æ,ā,ª", null, "é,è,ë,ê,ę,ė,ē", "ú,ü,ù,û,ū", "í,ï,ì,î,į,ī", "ç,ć,č,©", "ç,ć,č,©", "ñ,ń", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!autoColumnOrder!9,\\,,?,!,#,),(,/,;,¡,',@,:,-,\",+,\\%,&,¿"};

    private static final String[] TEXTS_et_EE = {"ö,õ,ò,ó,ô,œ,ő,ø", "ä,ā,à,á,â,ã,å,æ,ą", null, "ē,è,ė,é,ê,ë,ę,ě", "ü,ū,ų,ù,ú,û,ů,ű", "ī,ì,į,í,î,ï,ı", "č,ç,ć,©", "č,ç,ć,©", "ņ,ñ,ń", null, "š,ß,ś,ş", "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ď", "ţ,ť,™", "ž,ż,ź", "ţ,ť,™", "ŗ,ř,ŕ,®", "ŗ,ř,ŕ,®", null, "ļ,ł,ĺ,ľ", "ģ,ğ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ķ", "ü", "ö", "ä", "õ", "ä,ā,à,á,â,ã,å,æ,ą", "ö,õ,ò,ó,ô,œ,ő,ø"};

    private static final String[] TEXTS_eu_ES = {"ó,ò,ö,ô,õ,ø,œ,ō,º", "á,à,ä,â,ã,å,ą,æ,ā,ª", null, "é,è,ë,ê,ę,ė,ē", "ú,ü,ù,û,ū", "í,ï,ì,î,į,ī", "ç,ć,č,©", "ç,ć,č,©", "ñ,ń"};

    private static final String[] TEXTS_fa = {null, null, "ا\u200cب\u200cپ", null, null, null, null, null, null, "﷼", null, null, null, null, null, null, null, null, null, null, "،", null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅,٫,٬", "۱", "۲", "۳", "۴", "۵", "۶", "۷", "۸", "۹", "۰", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "★,٭", "،", "ً", null, null, null, null, null, null, null, null, null, "(|)", ")|(", "[|]", "]|[", "{|}", "}|{", "<|>", ">|<", "≤|≥", "≥|≤", "«|»", "»|«", "‹|›", "›|‹", "!fixedColumnOrder!4,:,!,؟,؛,-,!text/keyspec_left_double_angle_quote,!text/keyspec_right_double_angle_quote", "!text/morekeys_arabic_diacritics", "?,¿", "!text/keyspec_left_curly_bracket,!text/keyspec_left_square_bracket,!text/keyspec_less_than,!text/keyspec_left_double_angle_quote,〖|〗,《|》,〔|〕,「|」,『|』,【|】,〈|〉", "!text/keyspec_right_curly_bracket,!text/keyspec_right_square_bracket,!text/keyspec_greater_than,!text/keyspec_right_double_angle_quote,〗|〖,》|《,〕|〔,」|「,』|『,】|【,〉|〈", null, null, null, null, null, null, null, null, null, "♪", "!fixedColumnOrder!4,﴾,!text/keyspecs_left_parenthesis_more_keys", "!fixedColumnOrder!4,﴿,!text/keyspecs_right_parenthesis_more_keys", "!fixedColumnOrder!7, ٕ|ٕ, ْ|ْ, ّ|ّ, ٌ|ٌ, ٍ|ٍ, ً|ً, ٔ|ٔ, ٖ|ٖ, ٰ|ٰ, ٓ|ٓ, ُ|ُ, ِ|ِ, َ|َ,ـــ|ـ", "؟", "!text/morekeys_arabic_diacritics", null, "ً", "؟", "؛", "٪", ";", "\\%,‰,°", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!fixedColumnOrder!3,!text/keyspec_left_single_angle_quote,!text/keyspec_less_than_equal,!text/keyspec_less_than", "!fixedColumnOrder!3,!text/keyspec_right_single_angle_quote,!text/keyspec_greater_than_equal,!text/keyspec_greater_than"};

    private static final String[] TEXTS_fi = {"ø,ô,ò,ó,õ,œ,ō", "æ,à,á,â,ã,ā", null, null, "ü", null, "©", "©", null, null, "š,ß,ś", null, null, null, null, "™", "ž,ź,ż", "™", "®", "®", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "å", "ö", "ä", "ø", "ä,å,æ,à,á,â,ã,ā", "ö,ø,ô,ò,ó,õ,œ,ō", null, null, null, null, null, null, "æ"};

    private static final String[] TEXTS_fil = {"ó,ò,ö,ô,õ,ø,œ,ō,º", "á,à,ä,â,ã,å,ą,æ,ā,ª", null, "é,è,ë,ê,ę,ė,ē", "ú,ü,ù,û,ū", "í,ï,ì,î,į,ī", "ç,ć,č,©", "ç,ć,č,©", "ñ,ń"};

    private static final String[] TEXTS_fr = {"ô,œ,%,ö,ò,ó,õ,ø,ō,º", "à,â,%,æ,á,ä,ã,å,ā,ª", null, "é,è,ê,ë,%,ę,ė,ē", "ù,û,%,ü,ú,ū", "î,%,ï,ì,í,į,ī", "ç,%,ć,č,©", "ç,%,ć,č,©", null, null, null, null, null, "%,ÿ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "è", "é", "à", "ü", "ö", "ä"};

    private static final String[] TEXTS_ga = {"ó,ô,ö,ò,œ,ø,õ", "á,à,â,ä,æ,ã,å", null, "é,è,ê,ë", "ú,û,ü,ù", "í,î,ï,ì"};

    private static final String[] TEXTS_gl_ES = {"ó,ò,ö,ô,õ,ø,œ,ō,º", "á,à,ä,â,ã,å,ą,æ,ā,ª", null, "é,è,ë,ê,ę,ė,ē", "ú,ü,ù,û,ū", "í,ï,ì,î,į,ī", "ç,ć,č,©", "ç,ć,č,©", "ñ,ń"};

    private static final String[] TEXTS_hi = {null, null, "कखग", null, null, null, null, null, null, "₹", null, null, null, null, null, null, null, null, null, null, null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅", "१", "२", "३", "४", "५", "६", "७", "८", "९", "०", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "?१२३"};

    private static final String[] TEXTS_hr = {null, null, null, null, null, null, "č,ć,ç,©", "č,ć,ç,©", "ñ,ń", null, "š,ś,ß", "!text/double_9qm_rqm", "!text/single_9qm_rqm", null, "đ", null, "ž,ź,ż", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm"};

    private static final String[] TEXTS_hu = {"ó,ö,ő,ô,ò,õ,œ,ø,ō", "á,à,â,ä,æ,ã,å,ā", null, "é,è,ê,ë,ę,ė,ē", "ú,ü,ű,û,ù,ū", "í,î,ï,ì,į,ī", null, null, null, null, null, "!text/double_9qm_rqm", "!text/single_9qm_rqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm"};

    private static final String[] TEXTS_hy_AM = {null, null, "ԱԲԳ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "՝", null, null, null, null, null, null, null, null, null, null, null, null, "։", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!autoColumnOrder!8,\\,,՞,՜,.,՚,ՙ,?,!,՝,՛,֊,»,«,՟,;,:", null, "՝", "՞", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/morekeys_punctuation", "՞,¿", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "։", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "՜,¡"};

    private static final String[] TEXTS_is = {"ó,ö,ô,ò,õ,œ,ø,ō", "á,ä,æ,å,à,â,ã,ā", null, "é,ë,è,ê,ę,ė,ē", "ú,ü,û,ù,ū", "í,ï,î,ì,į,ī", null, null, null, null, null, "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ð", "þ,™", null, "þ,™"};

    private static final String[] TEXTS_it = {"ò,ó,ô,ö,õ,œ,ø,ō,º", "à,á,â,ä,æ,ã,å,ā,ª", null, "è,é,ê,ë,ę,ė,ē", "ù,ú,û,ü,ū", "ì,í,î,ï,į,ī", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ü", "ö", "ä", "è", "é", "à"};

    private static final String[] TEXTS_iw = {null, null, "אבג", null, null, null, null, null, null, "₪", null, "!text/double_rqm_9qm", "!text/single_rqm_9qm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "★", null, null, null, null, null, null, null, null, null, null, null, "(|)", ")|(", "[|]", "]|[", "{|}", "}|{", "<|>", ">|<", "≤|≥", "≥|≤", "«|»", "»|«", "‹|›", "›|‹", null, null, null, "!text/keyspec_left_curly_bracket,!text/keyspec_left_square_bracket,!text/keyspec_less_than,!text/keyspec_left_double_angle_quote,〖|〗,《|》,〔|〕,「|」,『|』,【|】,〈|〉", "!text/keyspec_right_curly_bracket,!text/keyspec_right_square_bracket,!text/keyspec_greater_than,!text/keyspec_right_double_angle_quote,〗|〖,》|《,〕|〔,」|「,』|『,】|【,〉|〈", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "±,﬩"};

    private static final String[] TEXTS_ja = {null, null, "あいう", null, null, null, null, null, null, "¥", null, null, null, null, null, null, null, null, null, null, "、", null, null, null, null, null, null, null, null, null, null, null, null, "。", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "†,‡,★,・,※,＊", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "〜,§,¶"};

    private static final String[] TEXTS_jv = {"ò,ó,ô,ö,œ,ø,õ", "â,å,à,á,ä,æ,ã", null, "è,é,ê,ë", "ù,ú,û,ü", "ì,í,î,ï"};

    private static final String[] TEXTS_ka_GE = {null, null, "აბგ", null, null, null, null, null, null, null, null, "!text/double_9qm_lqm", "!text/single_9qm_lqm"};

    private static final String[] TEXTS_kk = {null, null, "АБВ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "щ", "ы", "э", "и", "ё", "ъ", null, null, null, null, null, null, "ү,ұ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "і", "ң", "ғ", "ө", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "һ", "қ", "ә"};

    private static final String[] TEXTS_km_KH = {null, null, "កខគ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "៛,¢,£,€,¥,₱"};

    private static final String[] TEXTS_kn_IN = {null, null, "ಅಆಇ", null, null, null, null, null, null, "₹", null, null, null, null, null, null, null, null, null, null, null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅", "೧", "೨", "೩", "೪", "೫", "೬", "೭", "೮", "೯", "೦"};

    private static final String[] TEXTS_ko = {null, null, "한글", null, null, null, null, null, null, "₩"};

    private static final String[] TEXTS_ky = {null, null, "АБВ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "щ", "ы", "э", "и", "ё", "ъ", null, null, null, null, null, null, "ү", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ң", null, "ө"};

    private static final String[] TEXTS_lo_LA = {null, null, "ກຂຄ", null, null, null, null, null, null, "₭"};

    private static final String[] TEXTS_lt = {"ö,õ,ò,ó,ô,œ,ő,ø", "ą,ä,ā,à,á,â,ã,å,æ", null, "ė,ę,ē,è,é,ê,ë,ě", "ū,ų,ü,ū,ù,ú,û,ů,ű", "į,ī,ì,í,î,ï,ı", "č,ç,ć,©", "č,ç,ć,©", "ņ,ñ,ń", null, "š,ß,ś,ş", "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ď", "ţ,ť,™", "ž,ż,ź", "ţ,ť,™", "ŗ,ř,ŕ,®", "ŗ,ř,ŕ,®", null, "ļ,ł,ĺ,ľ", "ģ,ğ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ķ"};

    private static final String[] TEXTS_lv = {"ò,ó,ô,õ,ö,œ,ő,ø", "ā,à,á,â,ã,ä,å,æ,ą", null, "ē,ė,è,é,ê,ë,ę,ě", "ū,ų,ù,ú,û,ü,ů,ű", "ī,į,ì,í,î,ï,ı", "č,ç,ć,©", "č,ç,ć,©", "ņ,ñ,ń", null, "š,ß,ś,ş", "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ď", "ţ,ť,™", "ž,ż,ź", "ţ,ť,™", "ŗ,ř,ŕ,®", "ŗ,ř,ŕ,®", null, "ļ,ł,ĺ,ľ", "ģ,ğ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ķ"};

    private static final String[] TEXTS_mk = {null, null, "АБВ", null, null, null, null, null, null, null, null, "!text/double_9qm_lqm", "!text/single_9qm_lqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ѐ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ѝ", "ѕ", "ќ", "з", "ѓ"};

    private static final String[] TEXTS_ml_IN = {null, null, "അ", null, null, null, null, null, null, "₹", null, null, null, null, null, null, null, null, null, null, null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,൴,⅓,¼,൳,⅛,൱,൲", "²,⅔", "³,¾,൵,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅,൰,൹", "൧", "൨", "൩", "൪", "൫", "൬", "൭", "൮", "൯", "൦"};

    private static final String[] TEXTS_mn_MN = {null, null, "АБВ", null, null, null, null, null, null, "₮"};

    private static final String[] TEXTS_mr_IN = {null, null, "कखग", null, null, null, null, null, null, "₹", null, null, null, null, null, null, null, null, null, null, null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅", "१", "२", "३", "४", "५", "६", "७", "८", "९", "०", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "?१२३"};

    private static final String[] TEXTS_my_MM = {null, null, "ကခဂ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "။", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!autoColumnOrder!9,၊,.,?,!,#,),(,/,;,...,',@,:,-,\",+,\\%,&", null, "၊", "၊", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "\\,", null, null, null, null, null, null, null, null, null, null, null, "!autoColumnOrder!8,.,',#,),(,/,;,@,...,:,-,\",+,\\%,&", null, null, null, null, null, null, null, "။"};

    private static final String[] TEXTS_nb = {"ô,ò,ó,ö,õ,œ,ō", "à,ä,á,â,ã,ā", null, "é,è,ê,ë,ę,ė,ē", "ü,û,ù,ú,ū", null, null, null, null, null, null, "!text/double_9qm_rqm", "!text/single_9qm_rqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "å", "ø", "æ", "ö", "å,æ,ä,à,á,â,ã,ā", "ø,ö,ô,ò,ó,õ,œ,ō", null, null, null, null, null, null, "ä"};

    private static final String[] TEXTS_ne_NP = {null, null, "कखग", null, null, null, null, null, null, "रु.", null, null, null, null, null, null, null, null, null, null, null, null, null, "१", "२", "३", "४", "५", "६", "७", "८", "९", "०", null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "?१२३"};

    private static final String[] TEXTS_nl = {"ó,ö,ô,ò,õ,œ,ø,ō", "á,ä,â,à,æ,ã,å,ā", null, "é,ë,ê,è,ę,ė,ē", "ú,ü,û,ù,ū", "í,ï,ì,î,į,ī,ĳ", null, null, "ñ,ń", null, null, "!text/double_9qm_rqm", "!text/single_9qm_rqm", "ĳ"};

    private static final String[] TEXTS_pl = {"ó,ö,ô,ò,õ,œ,ø,ō", "ą,á,à,â,ä,æ,ã,å,ā", null, "ę,è,é,ê,ë,ė,ē", null, null, "ć,ç,č,©", "ć,ç,č,©", "ń,ñ", null, "ś,ß,š", "!text/double_9qm_rqm", "!text/single_9qm_rqm", null, null, null, "ż,ź,ž", null, null, null, null, "ł"};

    private static final String[] TEXTS_pt = {"ó,õ,ô,ò,ö,œ,ø,ō,º", "á,ã,à,â,ä,å,æ,ª", null, "é,ê,è,ę,ė,ē,ë", "ú,ü,ù,û,ū", "í,î,ì,ï,į,ī", "ç,č,ć,©", "ç,č,ć,©"};

    private static final String[] TEXTS_rm = {"ò,ó,ö,ô,õ,œ,ø", null, null, null, null, null, "©", "©", null, null, null, null, null, null, null, "™", null, "™", "®", "®"};

    private static final String[] TEXTS_ro = {null, "â,ã,ă,à,á,ä,æ,å,ā", null, null, null, "î,ï,ì,í,į,ī", null, null, null, null, "ș,ß,ś,š", "!text/double_9qm_rqm", "!text/single_9qm_rqm", null, null, "ț,™", null, "ț,™"};

    private static final String[] TEXTS_ru = {null, null, "АБВ", null, null, null, null, null, null, "₽", null, "!text/double_9qm_lqm", "!text/single_9qm_lqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "щ", "ы", "э", "и", "ё", "ъ"};

    private static final String[] TEXTS_si_LK = {null, null, "අ,ආ", null, null, null, null, null, null, "රු"};

    private static final String[] TEXTS_sk = {"ô,ó,ö,ò,õ,œ,ő,ø", "á,ä,ā,à,â,ã,å,æ,ą", null, "é,ě,ē,ė,è,ê,ë,ę", "ú,ů,ü,ū,ų,ù,û,ű", "í,ī,į,ì,î,ï,ı", "č,ç,ć,©", "č,ç,ć,©", "ň,ņ,ñ,ń", null, "š,ß,ś,ş", "!text/double_9qm_lqm", "!text/single_9qm_lqm", "ý,ÿ", "ď", "ť,ţ,™", "ž,ż,ź", "ť,ţ,™", "ŕ,ř,ŗ,®", "ŕ,ř,ŗ,®", null, "ľ,ĺ,ļ,ł", "ģ,ğ", null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ķ"};

    private static final String[] TEXTS_sl = {null, null, null, null, null, null, "č,ć,©", "č,ć,©", null, null, "š", "!text/double_9qm_lqm", "!text/single_9qm_lqm", null, "đ", null, "ž", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm"};

    private static final String[] TEXTS_sq = {null, null, null, "ë", null, null, "ç,©", "ç,©"};

    private static final String[] TEXTS_sr = {"ó,ô,ö,ò,œ,ø,ō,õ", "à,á,â,ä,æ,ã,å,ā", null, "é,è,ê,ë,ē", "ú,û,ü,ù,ū", "í,î,ï,ī,ì", "ç,ć,č,©", "ç,ć,č,©", "ñ", null, "ß,š", null, null, null, "đ", null, "ž"};

    private static final String[] TEXTS_su = {"ò,ó,ô,ö,œ,ø,õ", "â,å,à,á,ä,æ,ã", null, "è,é,ê,ë", "ù,ú,û,ü", "ì,í,î,ï"};

    private static final String[] TEXTS_sv = {"ó,ò,ô,õ,ō,ø,œ", "á,à,â,ą,ã,æ", null, "é,è,ê,ë,ę", "ü,ú,ù,û,ū", "í,ì,î,ï", "ç,ć,č,©", "ç,ć,č,©", "ń,ñ,ň", null, "ś,š,ş,ß", null, null, "ý,ÿ", "ð,ď", "ť,þ,™", "ź,ž,ż", "ť,þ,™", "ř,®", "ř,®", null, "ł", null, null, null, null, null, null, null, null, null, null, null, null, "!text/single_raqm_laqm", "!text/double_raqm_laqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "å", "ö", "ä", "ø,œ", "ä,å,æ,á,à,â,ą,ã", "ö,ø,œ,ó,ò,ô,õ,ō", null, null, null, null, null, null, "æ"};

    private static final String[] TEXTS_sw = {"ô,ö,ò,ó,œ,ø,ō,õ", "à,á,â,ä,æ,ã,å,ā", null, "è,é,ê,ë,ē", "û,ü,ù,ú,ū", "î,ï,í,ī,ì", "ç", "ç", "ñ", null, "ß", null, null, null, null, null, null, null, null, null, null, null, "g'"};

    private static final String[] TEXTS_ta_IN = {null, null, "தமிழ்", null, null, null, null, null, null, "௹"};

    private static final String[] TEXTS_ta_LK = {null, null, "தமிழ்", null, null, null, null, null, null, "රු"};

    private static final String[] TEXTS_ta_SG = {null, null, "தமிழ்"};

    private static final String[] TEXTS_te_IN = {null, null, "అఆఇ", null, null, null, null, null, null, "₹"};

    private static final String[] TEXTS_th = {null, null, "กขค", null, null, null, null, null, null, "฿", null, null, null, null, null, null, null, null, null, null, null, null, null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", null, null, null, "¹,½,⅓,¼,⅛", "²,⅔", "³,¾,⅜", "⁴", "⅝", "", "⅞", "", "", "ⁿ,∅", "๑", "๒", "๓", "๔", "๕", "๖", "๗", "๘", "๙", "๐"};

    private static final String[] TEXTS_tl = {"ó,ò,ö,ô,õ,ø,œ,ō,º", "á,à,ä,â,ã,å,ą,æ,ā,ª", null, "é,è,ë,ê,ę,ė,ē", "ú,ü,ù,û,ū", "í,ï,ì,î,į,ī", "ç,ć,č,©", "ç,ć,č,©", "ñ,ń"};

    private static final String[] TEXTS_tr = {"ö,ô,œ,ò,ó,õ,ø,ō", "â", null, null, "ü,û,ù,ú,ū", "i,î,ï,ì,í,į,ī", "ç,ć,č,©", "ç,ć,č,©", null, "₺", "ş,ß,ś,š", null, null, null, null, null, null, null, null, null, null, null, "ğ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ı"};

    private static final String[] TEXTS_uk = {null, null, "АБВ", null, null, null, null, null, null, "₴", null, "!text/double_9qm_lqm", "!text/single_9qm_lqm", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "щ", "і", "є", "и", null, "ъ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ї", null, "ґ"};

    private static final String[] TEXTS_uz = {"oʻ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "gʻ"};

    private static final String[] TEXTS_vi = {"ò,ó,ỏ,õ,ọ,ô,ồ,ố,ổ,ỗ,ộ,ơ,ờ,ớ,ở,ỡ,ợ", "à,á,ả,ã,ạ,ă,ằ,ắ,ẳ,ẵ,ặ,â,ầ,ấ,ẩ,ẫ,ậ", null, "è,é,ẻ,ẽ,ẹ,ê,ề,ế,ể,ễ,ệ", "ù,ú,ủ,ũ,ụ,ư,ừ,ứ,ử,ữ,ự", "ì,í,ỉ,ĩ,ị", null, null, null, "₫", "́", null, null, "ỳ,ý,ỷ,ỹ,ỵ", "đ", null, null, null, "̉,®", "̉,®", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "̣", "̃", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "̀"};

    private static final String[] TEXTS_zh_CN_pinyin = {null, null, "拼音", null, null, null, null, null, null, "￥", null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zh_CN_stroke = {null, null, "笔画", null, null, null, null, null, null, "￥", null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zh_HK_cangjie = {null, null, "倉頡", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zh_HK_stroke = {null, null, "筆劃", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zh_TW_pinyin = {null, null, "拼音", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zh_TW_stroke = {null, null, "筆劃", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zh_TW_zhuyin = {null, null, "注音", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "，", null, null, null, null, null, null, null, null, null, null, null, null, "。"};

    private static final String[] TEXTS_zu = {"ó,ô,ö,ò,œ,ø,ō,õ", "à,á,â,ä,æ,ã,å,ā", null, "é,è,ê,ë,ē", "ú,û,ü,ù,ū", "í,î,ï,ī,ì", "ç,©", "ç,©", "ñ", null, "ß"};

    private static final String[] TEXTS_zz = {"ò,ó,ô,õ,ö,ø,ō,ŏ,ő,œ,º", "à,á,â,ã,ä,å,æ,ā,ă,ą,ª", null, "è,é,ê,ë,ē,ĕ,ė,ę,ě", "ù,ú,û,ü,ũ,ū,ŭ,ů,ű,ų", "ì,í,î,ï,ĩ,ī,ĭ,į,ı,ĳ", "ç,ć,ĉ,ċ,č,©", "ç,ć,ĉ,ċ,č,©", "ñ,ń,ņ,ň,ŉ,ŋ", null, "ß,ś,ŝ,ş,š,ſ", null, null, "ý,ŷ,ÿ,ĳ", "ď,đ,ð", "þ,ţ,ť,ŧ,™", "ź,ż,ž", "þ,ţ,ť,ŧ,™", "ŕ,ŗ,ř,®", "ŕ,ŗ,ř,®", null, "ĺ,ļ,ľ,ŀ,ł", "ĝ,ğ,ġ,ģ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ķ,ĸ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ŵ", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "ĥ", "ĵ"};

    private static final Object[] LOCALES_AND_TEXTS = {"DEFAULT", TEXTS_DEFAULT, "af", TEXTS_af, "ar", TEXTS_ar, "az_AZ", TEXTS_az_AZ, "be", TEXTS_be, "bg", TEXTS_bg, "bn_IN", TEXTS_bn_IN, "bs", TEXTS_bs, "ca", TEXTS_ca, "cs", TEXTS_cs, "cy", TEXTS_cy, "da", TEXTS_da, "de", TEXTS_de, "el", TEXTS_el, "en", TEXTS_en, "eo", TEXTS_eo, "es", TEXTS_es, "et_EE", TEXTS_et_EE, "eu_ES", TEXTS_eu_ES, "fa", TEXTS_fa, "fi", TEXTS_fi, "fil", TEXTS_fil, "fr", TEXTS_fr, "ga", TEXTS_ga, "gl_ES", TEXTS_gl_ES, "hi", TEXTS_hi, "hr", TEXTS_hr, "hu", TEXTS_hu, "hy_AM", TEXTS_hy_AM, "is", TEXTS_is, "it", TEXTS_it, "iw", TEXTS_iw, "ja", TEXTS_ja, "jv", TEXTS_jv, "ka_GE", TEXTS_ka_GE, "kk", TEXTS_kk, "km_KH", TEXTS_km_KH, "kn_IN", TEXTS_kn_IN, "ko", TEXTS_ko, "ky", TEXTS_ky, "lo_LA", TEXTS_lo_LA, "lt", TEXTS_lt, "lv", TEXTS_lv, "mk", TEXTS_mk, "ml_IN", TEXTS_ml_IN, "mn_MN", TEXTS_mn_MN, "mr_IN", TEXTS_mr_IN, "my_MM", TEXTS_my_MM, "nb", TEXTS_nb, "ne_NP", TEXTS_ne_NP, "nl", TEXTS_nl, "pl", TEXTS_pl, "pt", TEXTS_pt, "rm", TEXTS_rm, "ro", TEXTS_ro, "ru", TEXTS_ru, "si_LK", TEXTS_si_LK, "sk", TEXTS_sk, "sl", TEXTS_sl, "sq", TEXTS_sq, "sr", TEXTS_sr, "su", TEXTS_su, "sv", TEXTS_sv, "sw", TEXTS_sw, "ta_IN", TEXTS_ta_IN, "ta_LK", TEXTS_ta_LK, "ta_SG", TEXTS_ta_SG, "te_IN", TEXTS_te_IN, "th", TEXTS_th, "tl", TEXTS_tl, "tr", TEXTS_tr, "uk", TEXTS_uk, "uz", TEXTS_uz, "vi", TEXTS_vi, "zh_CN_pinyin", TEXTS_zh_CN_pinyin, "zh_CN_stroke", TEXTS_zh_CN_stroke, "zh_HK_cangjie", TEXTS_zh_HK_cangjie, "zh_HK_stroke", TEXTS_zh_HK_stroke, "zh_TW_pinyin", TEXTS_zh_TW_pinyin, "zh_TW_stroke", TEXTS_zh_TW_stroke, "zh_TW_zhuyin", TEXTS_zh_TW_zhuyin, "zu", TEXTS_zu, "zz", TEXTS_zz};

    static {
        int i = 0;
        int i2 = 0;
        while (true) {
            String[] strArr = NAMES;
            if (i2 >= strArr.length) {
                break;
            }
            sNameToIndexesMap.put(strArr[i2], Integer.valueOf(i2));
            i2++;
        }
        while (true) {
            Object[] objArr = LOCALES_AND_TEXTS;
            if (i >= objArr.length) {
                break;
            }
            String str = (String) objArr[i];
            String[] strArr2 = (String[]) objArr[i + 1];
            sLocaleToTextsTableMap.put(str, strArr2);
            sTextsTableToLocaleMap.put(strArr2, str);
            i += 2;
        }
    }

    public static String getTextInternal(String str, String[] strArr) {
        Integer num = sNameToIndexesMap.get(str);
        if (num == null) {
            throw new RuntimeException("Unknown text name=" + str + " locale=" + sTextsTableToLocaleMap.get(strArr));
        }
        int iIntValue = num.intValue();
        String str2 = iIntValue < strArr.length ? strArr[iIntValue] : null;
        if (str2 != null) {
            return str2;
        }
        if (iIntValue >= 0) {
            String[] strArr2 = TEXTS_DEFAULT;
            if (iIntValue < strArr2.length) {
                return strArr2[iIntValue];
            }
        }
        throw new RuntimeException("Illegal index=" + iIntValue + " for name=" + str + " locale=" + sTextsTableToLocaleMap.get(strArr));
    }

    public static String[] getTextsTable(Locale locale) {
        String string = locale.toString();
        if (sLocaleToTextsTableMap.containsKey(string)) {
            return sLocaleToTextsTableMap.get(string);
        }
        String language = locale.getLanguage();
        if (sLocaleToTextsTableMap.containsKey(language)) {
            return sLocaleToTextsTableMap.get(language);
        }
        return TEXTS_DEFAULT;
    }
}
