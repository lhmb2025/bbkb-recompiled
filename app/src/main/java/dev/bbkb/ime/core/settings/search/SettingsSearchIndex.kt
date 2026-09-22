package dev.bbkb.ime.core.settings.search

import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.settings.backup.SettingsBackup

/**
 * The device facts the index needs in order to decide whether a setting's row exists at all.
 *
 * Snapshotted out of [DeviceProfile] instead of being read through it, so [DeviceRequirement] is
 * a pure function of a value a test can construct. `DeviceProfile.current()` is a process-wide
 * singleton with no injection point, so without this a test could only ever exercise whichever
 * device shape the JVM happens to have detected — which is the touch-only one.
 */
data class SearchDeviceCapabilities(
    val hasPhysicalKeyboard: Boolean,
    val hasMultifunctionKey: Boolean,
) {
    companion object {
        fun current(): SearchDeviceCapabilities {
            val profile = DeviceProfile.current()
            return SearchDeviceCapabilities(
                hasPhysicalKeyboard = profile?.hasPhysicalKeyboard() ?: false,
                hasMultifunctionKey =
                    profile?.getDeviceMapping()?.getMultifunctionKeyMapping() != null,
            )
        }
    }
}

/**
 * Which devices can actually show a setting's row.
 *
 * One APK serves both the physical-keyboard KEY2 family and touchscreen-only phones, and the leaf
 * screens hide what the device cannot do rather than showing it disabled. Search is a view onto
 * those screens, so an entry whose row is hidden must be hidden from results too — otherwise the
 * user searches, gets a hit, taps it, and lands on a screen that does not contain the thing they
 * searched for.
 *
 * Each value must mirror the `DeviceProfile` condition guarding the row in its screen.
 * `SettingsScreenRenderTest.searchAnchorsClaimedByTheIndexAreReachableOnThisScreen` asserts the
 * two agree — in both directions — for the device shape the tests run as.
 */
enum class DeviceRequirement {
    /** Ungated: the row renders on every device. */
    ANY,

    /** Row sits inside `if (hasPhysicalKeyboard)`. */
    PHYSICAL_KEYBOARD,

    /** Row is the `else` of that gate — touchscreen-only devices. */
    TOUCH_ONLY,

    /** Row sits behind a device mapping that declares a MULTIFUNCTION key (the KEY2 mic key). */
    MULTIFUNCTION_KEY;

    fun isMetBy(capabilities: SearchDeviceCapabilities): Boolean = when (this) {
        ANY -> true
        PHYSICAL_KEYBOARD -> capabilities.hasPhysicalKeyboard
        TOUCH_ONLY -> !capabilities.hasPhysicalKeyboard
        MULTIFUNCTION_KEY -> capabilities.hasMultifunctionKey
    }
}

/**
 * A single searchable setting. [anchor] (when non-null) is the row's
 * [settingsSearchAnchor] id, used to scroll-to / highlight it after navigation. [requires] is the
 * device gate the row itself sits behind, if any.
 */
data class SearchableSetting(
    val titleRes: Int,
    val keywords: String,
    val route: String,
    val categoryTitleRes: Int,
    val anchor: String? = null,
    val requires: DeviceRequirement = DeviceRequirement.ANY,
)

/**
 * Flat index of every searchable setting. Hand-maintained alongside the leaf screens —
 * each [anchor] must match a [settingsSearchAnchor] call in the corresponding screen.
 *
 * Query [entriesFor], not [entries]: the raw list contains rows that only exist on some devices.
 */
object SettingsSearchIndex {

    private val LANGUAGES = R.string.settings_languages_title
    private val TYPING = R.string.settings_preferences_title
    private val APPEARANCE = R.string.settings_appearance_layout_title
    private val SUGGESTION = R.string.settings_suggestion_correction_title
    private val ADVANCED = R.string.settings_advanced_title

    val entries: List<SearchableSetting> = listOf(
        // ── Languages ────────────────────────────────────────────────────────────
        SearchableSetting(R.string.settings_multi_language_keyboards_title, "language layout add keyboard", SettingsRoute.MultiLanguageKeyboards.route, LANGUAGES),
        SearchableSetting(R.string.settings_language_packs_title, "download language pack", SettingsRoute.LanguagePacks.route, LANGUAGES),
        SearchableSetting(R.string.settings_lang_switch_key_title, "globe switch language", SettingsRoute.LanguageSwitching.route, LANGUAGES, "pref_show_language_switch_key"),
        SearchableSetting(R.string.settings_lang_include_other_title, "other input methods ime", SettingsRoute.LanguageSwitching.route, LANGUAGES, "pref_include_other_imes_in_language_switch_list"),
        SearchableSetting(R.string.settings_lang_quick_switch_title, "quick switch language key", SettingsRoute.LanguageSwitching.route, LANGUAGES, "pref_language_quick_switch_key"),
        SearchableSetting(R.string.settings_lang_spacebar_switch_title, "spacebar swipe language", SettingsRoute.LanguageSwitching.route, LANGUAGES, "pref_spacebar_language_switching"),

        // ── Typing & input: On-screen keyboard ──────────────────────────────────
        SearchableSetting(R.string.settings_vkb_type_by_swiping_title, "swipe glide type trace", SettingsRoute.OnScreenKeyboard.route, TYPING, "type_by_swiping_vkb"),
        SearchableSetting(R.string.settings_vkb_swipe_gestures_title, "swipe gesture", SettingsRoute.OnScreenKeyboard.route, TYPING, "swipe_gesture_vkb"),
        SearchableSetting(R.string.settings_vkb_swipe_down_dismiss_title, "swipe down dismiss hide", SettingsRoute.OnScreenKeyboard.route, TYPING, "swipe_down_vkb"),
        SearchableSetting(R.string.vkb_control_key_title, "ctrl control key", SettingsRoute.OnScreenKeyboard.route, TYPING, "vkb_control_mode_enabled"),

        // ── Typing & input: Key press feedback ──────────────────────────────────
        SearchableSetting(R.string.vibrate_on_keypress, "haptic vibration buzz", SettingsRoute.TouchFeedback.route, TYPING, "vibrate_on"),
        SearchableSetting(R.string.prefs_keypress_vibration_duration_settings, "haptic vibration length", SettingsRoute.TouchFeedback.route, TYPING, "pref_vibration_duration_settings"),
        SearchableSetting(R.string.sound_on_keypress, "sound audio click", SettingsRoute.TouchFeedback.route, TYPING, "sound_on"),
        SearchableSetting(R.string.prefs_keypress_sound_volume_settings, "sound volume loudness", SettingsRoute.TouchFeedback.route, TYPING, "pref_keypress_sound_volume"),
        SearchableSetting(R.string.popup_on_keypress, "key preview popup bubble", SettingsRoute.TouchFeedback.route, TYPING, "popup_on"),
        SearchableSetting(R.string.key_preview_popup_dismiss_delay, "popup dismiss delay", SettingsRoute.TouchFeedback.route, TYPING, "pref_key_preview_popup_dismiss_delay"),

        // ── Typing & input: Physical keyboard ───────────────────────────────────
        SearchableSetting(R.string.settings_pkb_ctrl_key_behavior_title, "ctrl control physical", SettingsRoute.PhysicalKeyboard.route, TYPING, "control_mode"),
        SearchableSetting(R.string.settings_pkb_dictation_key_title, "voice dictation mic key", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_voice_input_key"),
        SearchableSetting(R.string.settings_pkb_multifunction_key_title, "multifunction convenience mic key custom action ctrl emoji clipboard cursor arrow bar", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_multifunction_key_action", DeviceRequirement.MULTIFUNCTION_KEY),
        SearchableSetting(R.string.settings_pkb_hold_action_title, "hold long press key", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_pkb_hold_auto_commit"),
        SearchableSetting(R.string.settings_pkb_alt_sym_shortcut_title, "alt sym shortcut", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_alt_sym_shortcut_action"),
        SearchableSetting(R.string.pref_show_pkb_modifier_status_icon, "modifier status icon shift alt", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_show_pkb_modifier_status_icon"),
        SearchableSetting(R.string.pref_shift_double_tap_lock, "shift double tap caps lock", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_shift_double_tap_lock"),
        SearchableSetting(R.string.pref_alt_double_tap_lock, "alt double tap lock", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_alt_double_tap_lock"),

        // ── Typing & input: Voice input ─────────────────────────────────────────
        SearchableSetting(R.string.settings_voice_builtin_title, "voice dictation speech", SettingsRoute.VoiceInput.route, TYPING, "voice_input_enabled"),
        SearchableSetting(R.string.settings_voice_auto_start_title, "voice auto start listening", SettingsRoute.VoiceInput.route, TYPING, "voice_input_auto_start"),
        SearchableSetting(R.string.settings_voice_use_keyboard_lang_title, "voice language keyboard", SettingsRoute.VoiceInput.route, TYPING, "voice_input_use_input_language"),
        SearchableSetting(R.string.settings_voice_prefer_offline_title, "voice offline on device", SettingsRoute.VoiceInput.route, TYPING, "voice_input_prefer_offline"),
        // "Block offensive words" lives on the voice screen: masking the recogniser's results
        // (EXTRA_MASK_OFFENSIVE_WORDS) is all it does. Frozen anchor, moved route and category —
        // a result for "profanity" must now land under Typing & input, on Voice input.
        SearchableSetting(R.string.settings_pred_block_offensive_title, "offensive profanity filter", SettingsRoute.VoiceInput.route, TYPING, "pref_key_block_potentially_offensive"),

        // ── Typing & input: Shake gestures ──────────────────────────────────────
        SearchableSetting(R.string.settings_shake_x_axis_title, "shake gesture motion", SettingsRoute.Shake.route, TYPING, "shake_x_action"),
        SearchableSetting(R.string.settings_shake_y_axis_title, "shake gesture motion", SettingsRoute.Shake.route, TYPING, "shake_y_action"),
        SearchableSetting(R.string.settings_shake_z_axis_title, "shake gesture motion", SettingsRoute.Shake.route, TYPING, "shake_z_action"),
        SearchableSetting(R.string.settings_shake_fallback_title, "shake fallback action", SettingsRoute.Shake.route, TYPING, "shake_fallback_action"),

        // ── Personalization: theme & height (on the hub) ────────────────────────
        SearchableSetting(R.string.settings_keyboard_theme_title, "theme classic modern material bb10 blackberry style", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_keyboard_theme_style"),
        SearchableSetting(R.string.settings_color_scheme_title, "color scheme dark light auto night", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_keyboard_theme_mode"),
        SearchableSetting(R.string.settings_use_system_colors_title, "system colors dynamic material you wallpaper", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_keyboard_use_system_colors"),
        SearchableSetting(R.string.settings_keyboard_height_title, "keyboard size height tall", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_keyboard_height_mode"),

        // Default currency now lives on the Symbol customization screen
        SearchableSetting(R.string.settings_default_currency_title, "currency symbol dollar", SettingsRoute.SymbolCustomization.route, APPEARANCE, "pref_currency_key"),

        // Unified input menu moved to Input → On-Screen Keyboard
        SearchableSetting(R.string.settings_uim_enable_title, "unified input menu", SettingsRoute.OnScreenKeyboard.route, TYPING, "pref_uim_enabled"),

        // Emoji dynamic search moved to Assistance → Suggestions
        SearchableSetting(R.string.pref_emoji_dynamic_search_title, "emoji search", SettingsRoute.Suggestion.route, SUGGESTION, "pref_emoji_dynamic_search"),

        // ── Typing & input: Slideboard (entry row lives on On-Screen Keyboard) ──
        SearchableSetting(R.string.settings_vkb_slideboard_enable_title, "slideboard number pad", SettingsRoute.SlideboardSettings.route, TYPING, "slideboard_active"),
        SearchableSetting(R.string.settings_vkb_slideboard_swap_title, "slideboard swap sides", SettingsRoute.SlideboardSettings.route, TYPING, "slideboard_numeric_location"),
        SearchableSetting(R.string.settings_vkb_quick_phrases_title, "quick phrases canned", SettingsRoute.SlideboardSettings.route, TYPING),

        // ── Appearance & layout: Custom symbol page ─────────────────────────────
        SearchableSetting(R.string.settings_symbol_customization_title, "symbol page custom layout", SettingsRoute.SymbolCustomization.route, APPEARANCE, "symbol_customization"),

        // ── Typing & input: Customize menu (UIM toggle order, lives under On-Screen Keyboard) ──
        SearchableSetting(R.string.settings_customize_menu_title, "unified input menu order reorder shortcuts toggles voice emoji cursor clipboard number pad math", SettingsRoute.CustomizeMenu.route, TYPING, "customize_menu"),

        // ── Suggestion & correction: Suggestions ────────────────────────────────
        SearchableSetting(R.string.settings_pred_show_predictions_title, "prediction suggestion strip", SettingsRoute.Suggestion.route, SUGGESTION, "show_predictions"),
        SearchableSetting(R.string.settings_pred_on_key_title, "on key prediction", SettingsRoute.Suggestion.route, SUGGESTION, "on_key_predictions"),
        SearchableSetting(R.string.settings_pred_emoji_title, "emoji prediction suggestion", SettingsRoute.Suggestion.route, SUGGESTION, "emoji_predictions"),
        SearchableSetting(R.string.settings_pred_next_word_title, "next word prediction", SettingsRoute.Suggestion.route, SUGGESTION, "next_word_prediction"),
        SearchableSetting(R.string.settings_pred_personalized_title, "personalized suggestions", SettingsRoute.Suggestion.route, SUGGESTION, "pref_key_use_personalized_dicts"),
        SearchableSetting(R.string.settings_pred_inline_autofill_title, "autofill password address saved details", SettingsRoute.Suggestion.route, SUGGESTION, "pref_inline_autofill_enabled"),
        SearchableSetting(R.string.settings_pred_contacts_title, "contacts names suggestion", SettingsRoute.Suggestion.route, SUGGESTION, "pref_key_use_contacts_dict"),
        SearchableSetting(R.string.settings_pred_force_suggestions_title, "force suggestions strip", SettingsRoute.Suggestion.route, SUGGESTION, "pref_force_suggestions"),
        SearchableSetting(R.string.settings_pred_flick_animation_title, "flick commit animation", SettingsRoute.Suggestion.route, SUGGESTION, "pref_flick_commit_animation"),

        // ── Suggestion & correction: Correction ─────────────────────────────────
        SearchableSetting(R.string.settings_autocorrect_pkb_title, "autocorrect physical", SettingsRoute.Correction.route, SUGGESTION, "auto_correction_mode_PKB", DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableSetting(R.string.settings_autocorrect_vkb_title, "autocorrect on screen", SettingsRoute.Correction.route, SUGGESTION, "auto_correction_mode_VKB"),
        SearchableSetting(R.string.settings_autocorrect_auto_cap_title, "auto capitalization caps", SettingsRoute.Correction.route, SUGGESTION, "auto_cap"),
        SearchableSetting(R.string.settings_autocorrect_double_space_title, "double space period full stop", SettingsRoute.Correction.route, SUGGESTION, "pref_key_use_double_space_period"),

        // ── Suggestion & correction: Learning ───────────────────────────────────
        SearchableSetting(R.string.settings_dict_dynamic_learning_title, "learning learn words", SettingsRoute.Learning.route, SUGGESTION, "dynamic_learning"),
        SearchableSetting(R.string.settings_dict_user_dictionary_title, "user dictionary personal words", SettingsRoute.Learning.route, SUGGESTION),
        SearchableSetting(R.string.settings_dict_text_shortcuts_title, "text shortcuts substitution macro", SettingsRoute.Learning.route, SUGGESTION),
        SearchableSetting(R.string.settings_dict_learned_words_title, "learned words", SettingsRoute.Learning.route, SUGGESTION),

        // ── Suggestion & correction: Spell checker ──────────────────────────────
        // ST-16: the spell-checker settings live in their own Activity
        // (SpellCheckerComposeActivity), not in this NavHost, so these two have no anchor —
        // search lands on the hub row that launches it.
        SearchableSetting(R.string.settings_spellcheck_use_contacts_title, "spell check contacts names", SettingsRoute.SuggestionCorrection.route, SUGGESTION),
        SearchableSetting(R.string.settings_spellcheck_sensitivity_title, "spell check sensitivity lenient balanced strict", SettingsRoute.SuggestionCorrection.route, SUGGESTION),

        // ── Typing & input: CKB gestures (Physical keyboard → Configure) ────────
        SearchableSetting(R.string.ckb_gesture_slot_flick_left, "ckb gesture flick left physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_flick_left"),
        SearchableSetting(R.string.ckb_gesture_slot_flick_right, "ckb gesture flick right physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_flick_right"),
        SearchableSetting(R.string.ckb_gesture_slot_swipe_left, "ckb gesture swipe left physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_swipe_left"),
        SearchableSetting(R.string.ckb_gesture_slot_swipe_right, "ckb gesture swipe right physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_swipe_right"),
        SearchableSetting(R.string.ckb_gesture_slot_swipe_down, "ckb gesture swipe down physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_swipe_down"),
        SearchableSetting(R.string.ckb_gesture_slot_double_tap, "ckb gesture double tap physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_double_tap"),
        SearchableSetting(R.string.ckb_gesture_slot_hold, "ckb gesture hold long press physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "ckb_gesture_hold"),
        SearchableSetting(R.string.prefs_ckb_gesture_activation_delay_title, "ckb gesture activation delay suppression timeout while typing physical keyboard", SettingsRoute.CkbGestures.route, TYPING, "pref_CKB_gesture_suppression_timeout"),

        // ── Typing & input: Keyboard Helper (accessibility key interception) ────
        SearchableSetting(R.string.pref_key_interceptor_enabled, "special key support accessibility interceptor helper", SettingsRoute.KeyboardHelper.route, TYPING, "pref_key_interceptor_enabled"),
        SearchableSetting(R.string.pref_preprocess_all_keys, "process all key events accessibility helper", SettingsRoute.KeyboardHelper.route, TYPING, "pref_preprocess_all_key_events"),
        SearchableSetting(R.string.settings_pkb_keyboard_helper_title, "unified key mapping xml experimental helper", SettingsRoute.KeyboardHelper.route, TYPING, "pref_use_unified_key_mapping"),

        // ── Appearance & layout: symbol page ordering ──────────────────────────
        // SymbolCustomizationScreen renders exactly one of these two — the symbol page the device can
        // actually reach. Both stay indexed; the gate picks the one this device draws.
        SearchableSetting(R.string.settings_symbol_custom_page_first_title, "custom symbol page first physical", SettingsRoute.SymbolCustomization.route, APPEARANCE, "pkb_custom_page_first", DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableSetting(R.string.settings_symbol_custom_page_first_title, "custom symbol page first on screen", SettingsRoute.SymbolCustomization.route, APPEARANCE, "vkb_custom_page_first", DeviceRequirement.TOUCH_ONLY),

        // ── Advanced ────────────────────────────────────────────────────────────
        SearchableSetting(R.string.settings_about_title, "about version credits", SettingsRoute.Advanced.route, ADVANCED),
        SearchableSetting(R.string.settings_device_compatibility_title, "device compatibility meta state helper", SettingsRoute.Advanced.route, ADVANCED),
        // Points at the configuration list rather than at the builder itself: the builder is a
        // task, and its entry row (with the detected facts behind it) is what search should land
        // on. No anchor — the row holds no preference.
        SearchableSetting(R.string.device_profile_builder_entry_title, "device profile builder capture keys scancode keycode export import share unknown phone config", SettingsRoute.DeviceConfiguration.route, ADVANCED, requires = DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableSetting(R.string.settings_debug_title, "debug developer", SettingsRoute.Advanced.route, ADVANCED),
        SearchableSetting(R.string.settings_clear_settings_title, "reset clear settings data", SettingsRoute.Advanced.route, ADVANCED),
        // ── Advanced: Manage data (settings backup/restore) ─────────────────────
        // Anchored, unlike the four rows above: these two are actions rather than screens, so
        // landing on the Advanced screen with the right row highlighted is the whole navigation.
        // The ids are the ones SettingsBackup declares — the rows hold no preference key to name
        // them by.
        SearchableSetting(R.string.settings_backup_title, "backup back up export save settings file json copy transfer", SettingsRoute.Advanced.route, ADVANCED, SettingsBackup.ANCHOR_BACK_UP),
        SearchableSetting(R.string.settings_restore_title, "restore import load settings file json backup transfer migrate", SettingsRoute.Advanced.route, ADVANCED, SettingsBackup.ANCHOR_RESTORE),
        // ── Advanced: OTA app updates (About -> Updates) ────────────────────────
        SearchableSetting(R.string.settings_updates_title, "update ota new version apk download install upgrade", SettingsRoute.Updates.route, ADVANCED),
        SearchableSetting(R.string.settings_update_background_check_title, "update daily background check notify notification", SettingsRoute.Updates.route, ADVANCED, "pref_update_background_check"),
    )

    /**
     * The entries whose rows this device actually renders. This — not [entries] — is what search
     * queries: an entry filtered out here is one whose row the destination screen would not draw,
     * so offering it as a result would navigate the user to a screen that does not contain it.
     */
    fun entriesFor(
        capabilities: SearchDeviceCapabilities = SearchDeviceCapabilities.current()
    ): List<SearchableSetting> = entries.filter { it.requires.isMetBy(capabilities) }
}
