package dev.bbkb.ime.core.settings

/**
 * A settings destination: one route string and its title. Deep links and the `"screen"` intent
 * extra carry route strings, so [fromRoute] keeps resolving every legacy spelling, each to a page
 * the NavHost registers (pinned by `SettingsRouteTest`). [TouchFeedback]/[KeyPressFeedback] and
 * [CustomSymbolPagePkb]/[CustomSymbolPageVkb] are deliberate pairs sharing one screen.
 */
sealed class SettingsRoute(val route: String, val title: String) {
    object Main : SettingsRoute("main", "Settings")

    object Preferences : SettingsRoute("preferences", "Preferences")
    object AppearanceLayout : SettingsRoute("appearance_layout", "Appearance and layout")
    object Advanced : SettingsRoute("advanced", "Advanced")

    object OnScreenKeyboard : SettingsRoute("on_screen_keyboard", "On-Screen Keyboard")
    object PhysicalKeyboard : SettingsRoute("physical_keyboard", "Physical Keyboard")
    object VoiceInputMain : SettingsRoute("voice_input_main", "Voice Input")
    object TouchFeedback : SettingsRoute("touch_feedback", "Touch Feedback")

    object SuggestionCorrection : SettingsRoute("suggestion_correction", "Suggestion and Correction")
    object Suggestion : SettingsRoute("suggestion", "Suggestions")
    object Correction : SettingsRoute("correction", "Correction")
    object Learning : SettingsRoute("learning", "Learning")

    object DeviceCompatibility : SettingsRoute("device_compatibility", "Device Compatibility")

    object LanguageSwitching : SettingsRoute("language_switching", "Language Switching")
    object QuickPhrases : SettingsRoute("quick_phrases", "Quick Phrases")
    object VoiceInput : SettingsRoute("voice_input", "Voice Input Settings")
    object AuxiliaryInput : SettingsRoute("auxiliary_input", "Auxiliary input")
    object VoiceLanguageSelection : SettingsRoute("voice_language_selection", "Voice Input Language")
    object LanguagePacks : SettingsRoute("language_packs", "Language Packs")
    object LanguagesHub : SettingsRoute("languages_hub", "Language")
    object Debug : SettingsRoute("debug", "Debug Settings")
    object AdvancedGestureParameters : SettingsRoute("advanced_gesture_parameters", "Advanced Gesture Parameters")
    object GestureLab : SettingsRoute("gesture_lab", "Gesture Lab")
    object ShakeTuning : SettingsRoute("shake_tuning", "Shake gesture tuning")
    object CkbGestures : SettingsRoute("ckb_gestures", "CKB Gestures")
    object AnimationParameters : SettingsRoute("animation_parameters", "Animation Parameters")
    object PersonalLearnedWords : SettingsRoute("personal_learned_words", "Learned words")
    object UserDictionary : SettingsRoute("user_dictionary", "User Dictionary")
    object TextShortcuts : SettingsRoute("text_shortcuts", "Text Shortcuts")
    object CustomMacros : SettingsRoute("custom_macros", "Custom Macros")
    object SymbolCustomization : SettingsRoute("symbol_customization", "Customize symbols")
    object CustomSymbolPagePkb : SettingsRoute("custom_symbol_page_pkb", "Custom Symbol Page (PKB)")
    object CustomSymbolPageVkb : SettingsRoute("custom_symbol_page_vkb", "Custom Symbol Page (VKB)")
    object CustomizeSlideBoard : SettingsRoute("customize_slideboard", "Customize SlideBoard")
    object SlideboardSettings : SettingsRoute("slideboard_settings", "Touch Screen Keyboard")
    object CustomizeMenu : SettingsRoute("customize_menu", "Customize Menu")
    object About : SettingsRoute("about", "About")
    object Updates : SettingsRoute("updates", "Updates")
    object Licenses : SettingsRoute("licenses", "Open-source licenses")
    object KeyboardHelper : SettingsRoute("keyboard_helper", "BBKB Helper")
    object TouchSurfaceHelper : SettingsRoute("touch_surface_helper", "Touch surface helper")
    object DeviceConfiguration : SettingsRoute("device_configuration", "Device Configuration")
    object DeviceProfileBuilder : SettingsRoute("device_profile_builder", "Build a device profile")
    object PhysicalLayouts : SettingsRoute("physical_layouts", "Custom keypad layouts")
    object BackupRestore : SettingsRoute("backup_restore", "Backup and restore")

    /** Back-compat deep link onto the same page as [TouchFeedback]; nothing navigates here. */
    object KeyPressFeedback : SettingsRoute("key_press_feedback", "Key Press Feedback")
    
    companion object {
        fun personalDictionaryWords(locale: String) = "personal_dictionary_words/$locale"
        fun wordSubstitutionWords(locale: String) = "word_substitution_words/$locale"
        fun unifiedDictionaryEntries(locale: String) = "unified_dictionary_entries/$locale"

        /**
         * Resolves a route string, or null. Validates the `"screen"` extra before it becomes a start
         * destination, so every string ever accepted must keep resolving to a registered page.
         */
        fun fromRoute(route: String?): SettingsRoute? {
            return when (route) {
                Main.route -> Main
                // The old Languages menu and multi-language keyboard pages.
                "languages", "languages_input", "multi_language_keyboards",
                "multi_language_wizard_add" -> LanguagesHub
                SuggestionCorrection.route, "prediction_correction", "correction_learning" -> SuggestionCorrection
                Preferences.route, "customization" -> Preferences
                AppearanceLayout.route, "personalization" -> AppearanceLayout
                Advanced.route -> Advanced
                OnScreenKeyboard.route -> OnScreenKeyboard
                PhysicalKeyboard.route -> PhysicalKeyboard
                VoiceInputMain.route -> VoiceInputMain
                TouchFeedback.route, "feedback_haptics" -> TouchFeedback
                Suggestion.route -> Suggestion
                Correction.route -> Correction
                DeviceCompatibility.route -> DeviceCompatibility
                LanguageSwitching.route -> LanguageSwitching
                QuickPhrases.route -> QuickPhrases
                "shake" -> AuxiliaryInput
                VoiceInput.route -> VoiceInput
                AuxiliaryInput.route -> AuxiliaryInput
                VoiceLanguageSelection.route -> VoiceLanguageSelection
                LanguagePacks.route -> LanguagePacks
                LanguagesHub.route -> LanguagesHub
                Debug.route -> Debug
                AdvancedGestureParameters.route -> AdvancedGestureParameters
                GestureLab.route -> GestureLab
                ShakeTuning.route -> ShakeTuning
                CkbGestures.route -> CkbGestures
                AnimationParameters.route -> AnimationParameters
                PersonalLearnedWords.route, "learned_words" -> PersonalLearnedWords
                UserDictionary.route, "personal_dictionary" -> UserDictionary
                TextShortcuts.route, "word_substitutions" -> TextShortcuts
                Learning.route, "unified_dictionary" -> Learning
                CustomMacros.route -> CustomMacros
                SymbolCustomization.route -> SymbolCustomization
                CustomSymbolPagePkb.route -> CustomSymbolPagePkb
                CustomSymbolPageVkb.route -> CustomSymbolPageVkb
                CustomizeSlideBoard.route -> CustomizeSlideBoard
                SlideboardSettings.route -> SlideboardSettings
                CustomizeMenu.route -> CustomizeMenu
                "clipboard" -> AuxiliaryInput
                About.route -> About
                Licenses.route -> Licenses
                Updates.route -> Updates
                KeyboardHelper.route -> KeyboardHelper
                TouchSurfaceHelper.route -> TouchSurfaceHelper
                DeviceConfiguration.route -> DeviceConfiguration
                DeviceProfileBuilder.route -> DeviceProfileBuilder
                PhysicalLayouts.route, "custom_physical_layouts" -> PhysicalLayouts
                BackupRestore.route -> BackupRestore
                KeyPressFeedback.route -> KeyPressFeedback
                else -> null
            }
        }
    }
}
