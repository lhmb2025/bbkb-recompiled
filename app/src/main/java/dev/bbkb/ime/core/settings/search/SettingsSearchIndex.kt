package dev.bbkb.ime.core.settings.search

import android.app.Activity
import android.os.Build
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.touch.TouchSourceSelector
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.settings.SpellCheckerComposeActivity
import dev.bbkb.ime.core.settings.screens.BackupAnchors
import dev.bbkb.ime.core.settings.screens.ManageDataAnchors
import dev.bbkb.ime.core.settings.screens.TouchSurfaceAnchors

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
    /** The profile describes a touch surface over the keys (`DeviceProfile.declaresTouchKeypad`). */
    val declaresTouchKeypad: Boolean = false,
    /** The touch-source selector gives that surface to the Shizuku reader. */
    val touchKeypadUsesShizuku: Boolean = false,
    /** The BBKB helper is offered on this phone: its profile does not switch it off. */
    val accessibilityHelperAvailable: Boolean = true,
    /** The profile says the keys are capacitive (`DeviceProfile.hasTouchKeypad`): the KEY2 family. */
    val hasTouchKeypad: Boolean = false,
    /** A debug build: the Debug settings row on Advanced is drawn only there. */
    val isDebugBuild: Boolean = false,
    /** Android 12 or later: the "Dynamic color" switch is drawn only there. */
    val supportsDynamicColor: Boolean = true,
) {
    companion object {
        fun current(): SearchDeviceCapabilities {
            val profile = DeviceProfile.current()
            return SearchDeviceCapabilities(
                hasPhysicalKeyboard = profile?.hasPhysicalKeyboard() ?: false,
                hasMultifunctionKey =
                    profile?.getDeviceMapping()?.getMultifunctionKeyMapping() != null,
                declaresTouchKeypad = profile?.declaresTouchKeypad() ?: false,
                accessibilityHelperAvailable =
                    !dev.bbkb.ime.core.device.interceptor.KeyInterceptorComponent.isOffForDevice(profile?.getDeviceMapping()),
                touchKeypadUsesShizuku =
                    profile?.touchSourceSelection?.choice == TouchSourceSelector.Choice.SHIZUKU,
                hasTouchKeypad = profile?.hasTouchKeypad() ?: false,
                isDebugBuild = BuildConfig.DEBUG,
                supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
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
    MULTIFUNCTION_KEY,


    /** Row sits in the Touch surface helper's Shizuku section: the selector picks the reader. */
    TOUCH_KEYPAD_SHIZUKU,

    /** Row sits on the BBKB helper's screen, whose entry row is greyed out where it is not required. */
    ACCESSIBILITY_HELPER_AVAILABLE,

    /** A physical keyboard with capacitive keys: the CKB gesture rows and their screen. */
    TOUCH_KEYPAD,

    /** The profile declares a touch surface over the keys: the Touch surface helper row is enabled. */
    TOUCH_KEYPAD_DECLARED,

    /** Drawn in debug builds only: the Debug settings row and the screens behind it. */
    DEBUG_BUILD,

    /** Drawn on Android 12 and later only: the "Dynamic color" switch. */
    DYNAMIC_COLOR;

    fun isMetBy(capabilities: SearchDeviceCapabilities): Boolean = when (this) {
        ANY -> true
        PHYSICAL_KEYBOARD -> capabilities.hasPhysicalKeyboard
        TOUCH_ONLY -> !capabilities.hasPhysicalKeyboard
        MULTIFUNCTION_KEY -> capabilities.hasMultifunctionKey
        TOUCH_KEYPAD_SHIZUKU -> capabilities.declaresTouchKeypad && capabilities.touchKeypadUsesShizuku
        ACCESSIBILITY_HELPER_AVAILABLE -> capabilities.accessibilityHelperAvailable
        TOUCH_KEYPAD -> capabilities.hasPhysicalKeyboard && capabilities.hasTouchKeypad
        TOUCH_KEYPAD_DECLARED -> capabilities.declaresTouchKeypad
        DEBUG_BUILD -> capabilities.isDebugBuild
        DYNAMIC_COLOR -> capabilities.supportsDynamicColor
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
 * A searchable screen: one of the five categories on the settings root, or a page reached from
 * one. A hit lists the screen itself and opens it, with no row to pulse.
 *
 * [parentPath] is the titles of the screens above it, top-level category first (empty for a
 * category); search shows it as the result's summary ("Input › Physical keyboard"). [requires] is
 * the gate on the way to the screen — the strictest gate of any row along the path — so a page
 * the device never shows the way into is not offered, and nor are the rows on it. [activity] is
 * set for the one page that lives in an activity of its own (Spell checking); [route] is then the
 * hub that opens it.
 */
data class SearchableScreen(
    val titleRes: Int,
    val keywords: String,
    val route: String,
    val parentPath: List<Int>,
    val requires: DeviceRequirement = DeviceRequirement.ANY,
    val activity: Class<out Activity>? = null,
)

/** One line of search results: a screen to open, or a setting to open and pulse. */
sealed interface SettingsSearchResult {
    data class Screen(val screen: SearchableScreen) : SettingsSearchResult
    data class Setting(val setting: SearchableSetting) : SettingsSearchResult
}

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
        // Each keyboard's Layout is a row in the sheet its row on the Language screen opens, so
        // the entry leads to the screen and has no anchor to highlight.
        SearchableSetting(R.string.languages_hub_layout_title, "keyboard layout colemak dvorak qwerty qwertz azerty letters keys arrangement on-screen physical keypad", SettingsRoute.LanguagesHub.route, LANGUAGES),
        SearchableSetting(R.string.settings_lang_quick_switch_title, "quick switch key last two languages quick switch language key", SettingsRoute.LanguageSwitching.route, LANGUAGES, "pref_language_quick_switch_key"),
        SearchableSetting(R.string.settings_lang_spacebar_switch_title, "spacebar swipe gesture language", SettingsRoute.LanguageSwitching.route, LANGUAGES, "pref_spacebar_language_switching"),

        // ── Typing & input: On-screen keyboard ──────────────────────────────────
        SearchableSetting(R.string.settings_vkb_type_by_swiping_title, "swipe glide type trace", SettingsRoute.OnScreenKeyboard.route, TYPING, "type_by_swiping_vkb"),
        SearchableSetting(R.string.settings_vkb_swipe_gestures_title, "swipe gesture", SettingsRoute.OnScreenKeyboard.route, TYPING, "swipe_gesture_vkb"),
        SearchableSetting(R.string.settings_vkb_swipe_down_dismiss_title, "swipe down dismiss hide", SettingsRoute.OnScreenKeyboard.route, TYPING, "swipe_down_vkb"),
        SearchableSetting(R.string.vkb_control_key_title, "ctrl control key", SettingsRoute.OnScreenKeyboard.route, TYPING, "vkb_control_mode_enabled"),
        SearchableSetting(R.string.prefs_key_longpress_delay_title, "long press delay hold timeout alternates accents popup", SettingsRoute.OnScreenKeyboard.route, TYPING, "pref_key_longpress_timeout"),

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
        SearchableSetting(R.string.settings_pkb_multifunction_key_title, "multifunction convenience mic key custom action ctrl emoji clipboard cursor arrow bar input menu show hide", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_multifunction_key_action", DeviceRequirement.MULTIFUNCTION_KEY),
        SearchableSetting(R.string.settings_pkb_hold_action_title, "hold long press key", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_pkb_hold_auto_commit"),
        SearchableSetting(R.string.settings_pkb_alt_sym_shortcut_title, "alt sym shortcut input menu show hide", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_alt_sym_shortcut_action"),
        SearchableSetting(R.string.pref_show_pkb_modifier_status_icon, "modifier status icon shift alt", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_show_pkb_modifier_status_icon"),
        SearchableSetting(R.string.pref_shift_double_tap_lock, "shift double tap caps lock", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_shift_double_tap_lock"),
        SearchableSetting(R.string.pref_alt_double_tap_lock, "alt double tap lock", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_alt_double_tap_lock"),
        SearchableSetting(R.string.pref_ctrl_double_tap_lock, "ctrl control double tap lock", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_ctrl_double_tap_lock"),
        // Only on a phone whose device config lists more than one keypad shape (the KEY2's three editions).
        SearchableSetting(R.string.settings_pkb_keypad_layout_title, "keypad layout shape qwerty qwertz azerty edition detected automatic physical keyboard", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_pkb_keypad_layout", DeviceRequirement.PHYSICAL_KEYBOARD),

        // ── Typing & input: Voice input ─────────────────────────────────────────
        SearchableSetting(R.string.settings_voice_builtin_title, "voice dictation speech", SettingsRoute.VoiceInput.route, TYPING, "voice_input_enabled"),
        SearchableSetting(R.string.settings_voice_recognizer_title, "voice dictation speech recognizer recognition service provider engine app", SettingsRoute.VoiceInput.route, TYPING, "voice_input_recognizer"),
        SearchableSetting(R.string.settings_voice_auto_start_title, "voice auto start listening", SettingsRoute.VoiceInput.route, TYPING, "voice_input_auto_start"),
        SearchableSetting(R.string.settings_voice_stop_on_pause_title, "voice stop listening pause silence continuous keep listening press to stop dictation end", SettingsRoute.VoiceInput.route, TYPING, "voice_input_stop_on_pause"),
        SearchableSetting(R.string.settings_voice_use_keyboard_lang_title, "voice language keyboard", SettingsRoute.VoiceInput.route, TYPING, "voice_input_use_input_language"),
        SearchableSetting(R.string.settings_voice_prefer_offline_title, "voice offline on device", SettingsRoute.VoiceInput.route, TYPING, "voice_input_prefer_offline"),
        SearchableSetting(R.string.settings_voice_show_partial_results_title, "voice dictation partial live words speak", SettingsRoute.VoiceInput.route, TYPING, "voice_input_show_partial_results"),
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
        SearchableSetting(R.string.settings_use_system_colors_title, "dynamic color system colors dynamic material you wallpaper", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_keyboard_use_system_colors", DeviceRequirement.DYNAMIC_COLOR),
        SearchableSetting(R.string.settings_keyboard_height_title, "keyboard size height tall", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_keyboard_height_mode"),

        // Default currency now lives on the Symbol customization screen
        SearchableSetting(R.string.settings_default_currency_title, "currency symbol dollar euro pound", SettingsRoute.AppearanceLayout.route, APPEARANCE, "pref_currency_key"),

        // Unified input menu moved to Input → On-Screen Keyboard
        SearchableSetting(R.string.settings_uim_enable_title, "unified input menu hamburger", SettingsRoute.AuxiliaryInput.route, TYPING, "pref_uim_enabled"),

        // Emoji search (2026-11-03): the on-screen pair on the On-screen keyboard screen, the
        // physical switch on the Physical keyboard screen.
        SearchableSetting(R.string.pref_emoji_search_title, "emoji search dynamic filter composing underlined uncommitted word on-screen", SettingsRoute.OnScreenKeyboard.route, TYPING, "pref_emoji_dynamic_search"),
        SearchableSetting(R.string.pref_emoji_search_replace_text_title, "emoji replace typed text insert", SettingsRoute.OnScreenKeyboard.route, TYPING, "pref_emoji_search_replace_text"),
        SearchableSetting(R.string.pref_emoji_search_title, "emoji search board type physical keyboard filter", SettingsRoute.PhysicalKeyboard.route, TYPING, "pref_pkb_emoji_search"),

        // ── Typing & input: Slideboard (entry row lives on On-Screen Keyboard) ──
        SearchableSetting(R.string.settings_vkb_slideboard_enable_title, "slideboard number pad", SettingsRoute.SlideboardSettings.route, TYPING, "slideboard_active"),
        SearchableSetting(R.string.settings_vkb_slideboard_swap_title, "slideboard swap sides", SettingsRoute.SlideboardSettings.route, TYPING, "slideboard_numeric_location"),

        // ── Appearance & layout: Custom symbol page ─────────────────────────────
        SearchableSetting(R.string.settings_symbol_customization_title, "symbol page custom layout customize symbols", SettingsRoute.SymbolCustomization.route, APPEARANCE, "symbol_customization"),

        // ── Typing & input: Customize menu (UIM toggle order, lives under On-Screen Keyboard) ──
        SearchableSetting(R.string.settings_customize_menu_title, "unified input menu order reorder shortcuts toggles voice emoji cursor clipboard number pad math", SettingsRoute.CustomizeMenu.route, TYPING, "customize_menu"),

        // ── Typing & input: Clipboard (rows live on Auxiliary input) ───
        SearchableSetting(R.string.clipboard_history_title, "clipboard history copy paste clips keep save remember", SettingsRoute.AuxiliaryInput.route, TYPING, "pref_clipboard_history_enabled"),
        SearchableSetting(R.string.clipboard_retention_title, "clipboard retention expire delete keep clips hour day week time", SettingsRoute.AuxiliaryInput.route, TYPING, "pref_clipboard_retention"),
        SearchableSetting(R.string.clipboard_link_previews_title, "clipboard link preview url web page thumbnail title network", SettingsRoute.AuxiliaryInput.route, TYPING, "pref_clipboard_link_previews"),

        // ── Suggestion & correction: Suggestions ────────────────────────────────
        SearchableSetting(R.string.settings_pred_show_predictions_title, "prediction suggestion strip", SettingsRoute.Suggestion.route, SUGGESTION, "show_predictions"),
        SearchableSetting(R.string.settings_pred_on_key_title, "on key prediction", SettingsRoute.Suggestion.route, SUGGESTION, "on_key_predictions"),
        SearchableSetting(R.string.settings_pred_emoji_title, "emoji prediction suggestion", SettingsRoute.Suggestion.route, SUGGESTION, "emoji_predictions"),
        SearchableSetting(R.string.settings_pred_next_word_title, "next word prediction", SettingsRoute.Suggestion.route, SUGGESTION, "next_word_prediction"),
        SearchableSetting(R.string.settings_pred_personalized_title, "personalized suggestions learned words", SettingsRoute.Suggestion.route, SUGGESTION, "pref_key_use_personalized_dicts"),
        SearchableSetting(R.string.settings_pred_inline_autofill_title, "autofill password address saved details", SettingsRoute.Suggestion.route, SUGGESTION, "pref_inline_autofill_enabled"),
        SearchableSetting(R.string.settings_pred_contacts_title, "contacts names suggestion", SettingsRoute.Suggestion.route, SUGGESTION, "pref_key_use_contacts_dict"),
        SearchableSetting(R.string.settings_pred_force_suggestions_title, "force suggestions strip", SettingsRoute.Suggestion.route, SUGGESTION, "pref_force_suggestions"),
        SearchableSetting(R.string.settings_pred_flick_animation_title, "flick commit animation swipe up", SettingsRoute.OnScreenKeyboard.route, TYPING, "pref_flick_commit_animation"),

        // ── Suggestion & correction: Correction ─────────────────────────────────
        SearchableSetting(R.string.settings_autocorrect_enable_title, "auto correction autocorrect enable off on spell fix backspace undo", SettingsRoute.Correction.route, SUGGESTION, "auto_correction_enabled"),
        SearchableSetting(R.string.settings_autocorrect_pkb_title, "autocorrect physical", SettingsRoute.Correction.route, SUGGESTION, "auto_correction_mode_PKB", DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableSetting(R.string.settings_autocorrect_vkb_title, "autocorrect on screen", SettingsRoute.Correction.route, SUGGESTION, "auto_correction_mode_VKB"),
        SearchableSetting(R.string.settings_autocorrect_auto_cap_title, "auto capitalization caps", SettingsRoute.Correction.route, SUGGESTION, "auto_cap"),
        SearchableSetting(R.string.settings_autocorrect_double_space_title, "double space period full stop", SettingsRoute.Correction.route, SUGGESTION, "pref_key_use_double_space_period"),

        // ── Suggestion & correction: Learning ───────────────────────────────────
        SearchableSetting(R.string.settings_dict_dynamic_learning_title, "learning learn words", SettingsRoute.Learning.route, SUGGESTION, "dynamic_learning"),
        SearchableSetting(R.string.settings_dict_add_reverted_words_title, "add typed word dictionary undo correction backspace accept button", SettingsRoute.Learning.route, SUGGESTION, "pref_show_ui_to_accept_typed_word"),

        // ── Suggestion & correction: Spell checker ──────────────────────────────
        // ST-16: the spell-checker settings live in their own Activity
        // (SpellCheckerComposeActivity), not in this NavHost, so this one has no anchor —
        // search lands on the hub row that launches it.
        SearchableSetting(R.string.settings_spellcheck_use_contacts_title, "spell check contacts names", SettingsRoute.SuggestionCorrection.route, SUGGESTION),

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
        SearchableSetting(R.string.pref_key_interceptor_enabled, "special key support accessibility interceptor helper", SettingsRoute.KeyboardHelper.route, TYPING, "pref_key_interceptor_enabled", DeviceRequirement.ACCESSIBILITY_HELPER_AVAILABLE),
        SearchableSetting(R.string.pref_preprocess_all_keys, "process all key events accessibility helper", SettingsRoute.KeyboardHelper.route, TYPING, "pref_preprocess_all_key_events", DeviceRequirement.ACCESSIBILITY_HELPER_AVAILABLE),
        SearchableSetting(R.string.settings_pkb_keyboard_helper_title, "unified key mapping xml experimental helper", SettingsRoute.KeyboardHelper.route, TYPING, "pref_use_unified_key_mapping", DeviceRequirement.ACCESSIBILITY_HELPER_AVAILABLE),

        // ── Appearance & layout: symbol page ordering ──────────────────────────
        // SymbolCustomizationScreen renders exactly one of these two — the symbol page the device can
        // actually reach. Both stay indexed; the gate picks the one this device draws.
        SearchableSetting(R.string.settings_symbol_custom_page_first_title, "custom symbol page first physical", SettingsRoute.SymbolCustomization.route, APPEARANCE, "pkb_custom_page_first", DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableSetting(R.string.settings_symbol_custom_page_first_title, "custom symbol page first on screen", SettingsRoute.SymbolCustomization.route, APPEARANCE, "vkb_custom_page_first"),

        // ── Advanced ────────────────────────────────────────────────────────────
        // Points at the configuration list rather than at the builder itself: the builder is a
        // task, and its entry row (with the detected facts behind it) is what search should land
        // on. No anchor — the row holds no preference. The row is drawn only once the debug
        // screen's switch turns it on, so a release build never shows it.
        SearchableSetting(R.string.device_profile_builder_entry_title, "device profile builder capture keys scancode keycode export import share unknown phone config", SettingsRoute.DeviceConfiguration.route, ADVANCED, requires = DeviceRequirement.DEBUG_BUILD),
        // ── Advanced: Touch surface helper (Unihertz Titans) ────────────────────
        // The row on Device compatibility (drawn on every phone, enabled only on a declared pad,
        // so its entry is ungated) and the Shizuku section of the screen it opens (drawn only on
        // the Shizuku route); both are actions, anchored by the ids TouchSurfaceAnchors declares.
        SearchableSetting(R.string.touch_surface_helper_title, "touch surface touchpad keyboard gestures swipe scroll assistant cursor shizuku titan unihertz", SettingsRoute.DeviceCompatibility.route, ADVANCED, TouchSurfaceAnchors.HELPER),
        SearchableSetting(R.string.touch_surface_shizuku_category, "shizuku touch surface touchpad wireless debugging adb root grant access permission", SettingsRoute.TouchSurfaceHelper.route, ADVANCED, TouchSurfaceAnchors.SHIZUKU, DeviceRequirement.TOUCH_KEYPAD_SHIZUKU),
        // ── Advanced: Manage data ───────────────────────────────────────────────
        // The Backup and restore row (a screen) and Reset (an action) on Advanced, then the
        // action rows on the Backup and restore screen itself: saving a file, restoring one, and
        // the phone-backup switch. All anchored: none holds a preference key to name it by, so
        // the ids are the ones ManageDataAnchors and BackupAnchors declare.
        SearchableSetting(R.string.settings_backup_restore_title, "backup back up restore export import save load settings layouts words file zip copy transfer migrate google account", SettingsRoute.Advanced.route, ADVANCED, ManageDataAnchors.BACKUP_RESTORE),
        SearchableSetting(R.string.settings_reset_title, "reset clear settings layouts words dictionary learned defaults erase wipe data", SettingsRoute.Advanced.route, ADVANCED, ManageDataAnchors.RESET),
        SearchableSetting(R.string.backup_save_title, "backup back up export save settings layouts symbol page slideboard quick phrases custom physical layout dictionary shortcuts learned words file zip", SettingsRoute.BackupRestore.route, ADVANCED, BackupAnchors.SAVE),
        SearchableSetting(R.string.backup_restore_file_title, "restore import load settings layouts letter layout map dictionary words file zip json xml backup transfer migrate", SettingsRoute.BackupRestore.route, ADVANCED, BackupAnchors.RESTORE),
        SearchableSetting(R.string.backup_phone_include_title, "phone backup google account seedvault cloud automatic android back up sync", SettingsRoute.BackupRestore.route, ADVANCED, BackupAnchors.PHONE),
        // ── Advanced: OTA app updates (About -> Updates) ────────────────────────
        SearchableSetting(R.string.settings_update_background_check_title, "update daily background check notify notification", SettingsRoute.Updates.route, ADVANCED, "pref_update_background_check"),
    )

    /**
     * The entries whose rows this device actually renders. This — not [entries] — is what search
     * queries: an entry filtered out here is one whose row the destination screen would not draw,
     * so offering it as a result would navigate the user to a screen that does not contain it.
     */
    fun entriesFor(
        capabilities: SearchDeviceCapabilities = SearchDeviceCapabilities.current()
    ): List<SearchableSetting> = entries.filter {
        it.requires.isMetBy(capabilities) && screenRequirement(it.route).isMetBy(capabilities)
    }

    // ── Screens ──────────────────────────────────────────────────────────────

    private val ON_SCREEN = R.string.settings_keyboard_title
    private val PHYSICAL = R.string.settings_physical_keyboard_title
    private val SLIDEBOARD = R.string.settings_slideboard_settings_title
    private val AUXILIARY = R.string.settings_auxiliary_input_title
    private val VOICE = R.string.settings_voice_input_title
    private val LEARNING = R.string.settings_dictionary_learning_title
    private val SHORTCUTS = R.string.settings_dict_text_shortcuts_title
    private val SYMBOLS = R.string.settings_symbol_customization_title
    private val ABOUT = R.string.settings_about_title
    private val COMPATIBILITY = R.string.settings_device_compatibility_title
    private val DEBUG = R.string.settings_debug_title

    /**
     * Every page of the settings NavHost a user can reach, each titled as the row that opens it.
     * Left out on purpose: the back-compat routes (`voice_input_main`, `key_press_feedback`), the
     * device profile builder (a task behind a debug switch, reached through its row on Device
     * configuration) and the Gesture Lab prototype.
     *
     * Query [screensFor], not this list. `SettingsSearchIndexTest` checks every route is
     * registered in the NavHost and every title resolves.
     */
    val screens: List<SearchableScreen> = listOf(
        // ── The five categories on the settings root ─────────────────────────────
        SearchableScreen(LANGUAGES, "languages keyboards add remove layout predict multi-language", SettingsRoute.LanguagesHub.route, emptyList()),
        SearchableScreen(TYPING, "input typing keyboards", SettingsRoute.Preferences.route, emptyList()),
        SearchableScreen(SUGGESTION, "assistance suggestions predictions corrections autocorrect spelling learning dictionary", SettingsRoute.SuggestionCorrection.route, emptyList()),
        SearchableScreen(APPEARANCE, "personalization personalisation appearance look theme colour color height symbols", SettingsRoute.AppearanceLayout.route, emptyList()),
        SearchableScreen(ADVANCED, "advanced", SettingsRoute.Advanced.route, emptyList()),

        // ── Language ─────────────────────────────────────────────────────────────
        SearchableScreen(R.string.settings_language_switching_title, "switch change language spacebar quick switch", SettingsRoute.LanguageSwitching.route, listOf(LANGUAGES)),
        SearchableScreen(R.string.settings_language_packs_title, "download language pack packs dictionary files", SettingsRoute.LanguagePacks.route, listOf(LANGUAGES)),

        // ── Input ────────────────────────────────────────────────────────────────
        SearchableScreen(ON_SCREEN, "on-screen onscreen on screen virtual touch keyboard vkb", SettingsRoute.OnScreenKeyboard.route, listOf(TYPING)),
        SearchableScreen(SLIDEBOARD, "slideboard slide board number pad", SettingsRoute.SlideboardSettings.route, listOf(TYPING, ON_SCREEN)),
        SearchableScreen(R.string.settings_vkb_customize_numberpad_title, "slideboard number pad numpad customize keys", SettingsRoute.CustomizeSlideBoard.route, listOf(TYPING, ON_SCREEN, SLIDEBOARD)),
        SearchableScreen(R.string.settings_vkb_quick_phrases_title, "quick phrases canned slideboard", SettingsRoute.QuickPhrases.route, listOf(TYPING, ON_SCREEN, SLIDEBOARD)),
        SearchableScreen(R.string.settings_touch_feedback_title, "touch key press keypress feedback haptic vibration sound popup", SettingsRoute.TouchFeedback.route, listOf(TYPING, ON_SCREEN)),
        SearchableScreen(PHYSICAL, "physical hardware keypad keys pkb", SettingsRoute.PhysicalKeyboard.route, listOf(TYPING), DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableScreen(R.string.ckb_gestures_configure_title, "ckb capacitive keypad gestures flick swipe configure", SettingsRoute.CkbGestures.route, listOf(TYPING, PHYSICAL), DeviceRequirement.TOUCH_KEYPAD),
        SearchableScreen(AUXILIARY, "auxiliary input menu clipboard voice emoji", SettingsRoute.AuxiliaryInput.route, listOf(TYPING)),
        SearchableScreen(R.string.settings_customize_menu_title, "unified input menu order reorder customize", SettingsRoute.CustomizeMenu.route, listOf(TYPING, AUXILIARY)),
        SearchableScreen(VOICE, "voice dictation speech mic microphone", SettingsRoute.VoiceInput.route, listOf(TYPING, AUXILIARY)),
        SearchableScreen(R.string.settings_voice_input_lang_title, "voice dictation language", SettingsRoute.VoiceLanguageSelection.route, listOf(TYPING, AUXILIARY, VOICE)),
        SearchableScreen(R.string.settings_shake_title, "shake gestures motion", SettingsRoute.Shake.route, listOf(TYPING)),

        // ── Assistance ───────────────────────────────────────────────────────────
        SearchableScreen(R.string.settings_suggestion_title, "suggestions predictions strip next word", SettingsRoute.Suggestion.route, listOf(SUGGESTION)),
        SearchableScreen(R.string.settings_correction_title, "auto-correction autocorrection autocorrect correction capitalization double space", SettingsRoute.Correction.route, listOf(SUGGESTION)),
        SearchableScreen(LEARNING, "learning learn dictionary dictionaries words", SettingsRoute.Learning.route, listOf(SUGGESTION)),
        SearchableScreen(R.string.settings_dict_user_dictionary_title, "user dictionary personal words", SettingsRoute.UserDictionary.route, listOf(SUGGESTION, LEARNING)),
        SearchableScreen(SHORTCUTS, "text shortcuts substitution macro expand", SettingsRoute.TextShortcuts.route, listOf(SUGGESTION, LEARNING)),
        SearchableScreen(R.string.settings_custom_macros_title, "custom macros macro shortcuts", SettingsRoute.CustomMacros.route, listOf(SUGGESTION, LEARNING, SHORTCUTS)),
        SearchableScreen(R.string.settings_dict_learned_words_title, "learned words learnt delete expiring", SettingsRoute.PersonalLearnedWords.route, listOf(SUGGESTION, LEARNING)),
        // Its own activity, opened from the Assistance hub as the hub row opens it.
        SearchableScreen(R.string.settings_spell_checker_title, "spell checker spellcheck spelling", SettingsRoute.SuggestionCorrection.route, listOf(SUGGESTION), activity = SpellCheckerComposeActivity::class.java),

        // ── Personalization ──────────────────────────────────────────────────────
        SearchableScreen(SYMBOLS, "symbol page custom customize symbols", SettingsRoute.SymbolCustomization.route, listOf(APPEARANCE)),
        SearchableScreen(R.string.settings_symbol_customize_page_title, "custom symbol page on-screen", SettingsRoute.CustomSymbolPageVkb.route, listOf(APPEARANCE, SYMBOLS, R.string.settings_category_on_screen_keyboard)),
        SearchableScreen(R.string.settings_symbol_customize_page_title, "custom symbol page physical", SettingsRoute.CustomSymbolPagePkb.route, listOf(APPEARANCE, SYMBOLS, R.string.settings_category_physical_keyboard), DeviceRequirement.PHYSICAL_KEYBOARD),

        // ── Advanced ─────────────────────────────────────────────────────────────
        SearchableScreen(ABOUT, "about version credits", SettingsRoute.About.route, listOf(ADVANCED)),
        SearchableScreen(R.string.settings_updates_title, "update ota new version apk download install upgrade", SettingsRoute.Updates.route, listOf(ADVANCED, ABOUT)),
        SearchableScreen(COMPATIBILITY, "device compatibility meta state helper", SettingsRoute.DeviceCompatibility.route, listOf(ADVANCED)),
        SearchableScreen(R.string.settings_device_configuration_title, "device configuration config profile import phone", SettingsRoute.DeviceConfiguration.route, listOf(ADVANCED, COMPATIBILITY)),
        SearchableScreen(R.string.settings_pkb_layouts_title, "custom keypad physical layouts built-in builtin letter layout map remap keys keycaps alphabet qwerty qwertz azerty etched thai xml add import template save file export share", SettingsRoute.PhysicalLayouts.route, listOf(ADVANCED, COMPATIBILITY), DeviceRequirement.PHYSICAL_KEYBOARD),
        SearchableScreen(R.string.settings_pkb_keyboard_helper_title, "bbkb helper accessibility special keys interceptor", SettingsRoute.KeyboardHelper.route, listOf(ADVANCED, COMPATIBILITY), DeviceRequirement.ACCESSIBILITY_HELPER_AVAILABLE),
        SearchableScreen(R.string.touch_surface_helper_title, "touch surface touchpad shizuku titan unihertz", SettingsRoute.TouchSurfaceHelper.route, listOf(ADVANCED, COMPATIBILITY), DeviceRequirement.TOUCH_KEYPAD_DECLARED),
        SearchableScreen(R.string.settings_backup_restore_title, "backup back up restore export import save load settings file zip transfer migrate", SettingsRoute.BackupRestore.route, listOf(ADVANCED)),
        SearchableScreen(DEBUG, "debug developer", SettingsRoute.Debug.route, listOf(ADVANCED), DeviceRequirement.DEBUG_BUILD),
        SearchableScreen(R.string.prefs_advanced_gesture_thresholds_title, "debug gesture thresholds", SettingsRoute.AdvancedGestureParameters.route, listOf(ADVANCED, DEBUG), DeviceRequirement.DEBUG_BUILD),
        SearchableScreen(R.string.prefs_shake_tuning_title, "debug shake tuning", SettingsRoute.ShakeTuning.route, listOf(ADVANCED, DEBUG), DeviceRequirement.DEBUG_BUILD),
        SearchableScreen(R.string.prefs_key_preview_animations_title, "debug key preview animations", SettingsRoute.AnimationParameters.route, listOf(ADVANCED, DEBUG), DeviceRequirement.DEBUG_BUILD),
    )

    /**
     * The gate on the way to the screen at [route]: a row on a page the device never shows the way
     * into is not offered either (the Physical keyboard screen's rows on a touchscreen phone).
     */
    fun screenRequirement(route: String): DeviceRequirement =
        screens.firstOrNull { it.route == route && it.activity == null }?.requires ?: DeviceRequirement.ANY

    /** The screens this device shows the way into. Search queries this, not [screens]. */
    fun screensFor(
        capabilities: SearchDeviceCapabilities = SearchDeviceCapabilities.current()
    ): List<SearchableScreen> = screens.filter { it.requires.isMetBy(capabilities) }

    /**
     * The results for [query], in the order search lists them: screens whose title matches, then
     * settings (title or keywords), then screens matched only by their keywords. A setting with
     * the same title as a listed screen (Backup and restore, Customize symbols) is left out: the
     * screen stands for it.
     *
     * [screenTitles] and [settingTitles] are the lower-cased titles of [screens] and [settings], in
     * the same order — resolved by the caller once per language rather than once per keystroke.
     */
    fun search(
        query: String,
        screens: List<SearchableScreen>,
        screenTitles: List<String>,
        settings: List<SearchableSetting>,
        settingTitles: List<String>,
    ): List<SettingsSearchResult> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val byTitle = screens.indices.filter { screenTitles[it].contains(q) }
            // A title that starts with the query first ("lang" → Language before Voice input language).
            .sortedBy { if (screenTitles[it].startsWith(q)) 0 else 1 }
        val byKeyword = screens.indices.filter { it !in byTitle && screens[it].keywords.contains(q) }
        val shownTitles = (byTitle + byKeyword).map { screens[it].titleRes }.toSet()

        val rows = settings.indices
            .filter { settingTitles[it].contains(q) || settings[it].keywords.contains(q) }
            .filter { settings[it].titleRes !in shownTitles }
            .map { SettingsSearchResult.Setting(settings[it]) }

        return byTitle.map { SettingsSearchResult.Screen(screens[it]) } +
            rows +
            byKeyword.map { SettingsSearchResult.Screen(screens[it]) }
    }
}
