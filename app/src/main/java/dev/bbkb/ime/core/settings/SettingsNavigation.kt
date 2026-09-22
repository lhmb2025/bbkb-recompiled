package dev.bbkb.ime.core.settings

/**
 * Navigation routes for all settings screens
 * Defines the navigation graph structure for Compose settings
 */
/** Name of the wizard's config path argument. */
const val ARG_WIZARD_CONFIG = "config"

/**
 * A settings destination: one navigation route string and its title.
 *
 * ## Aliases (§5.8 item 6)
 *
 * This class used to declare twelve extra objects that were duplicate *identities* for routes
 * declared elsewhere in the same file — `Keyboard` and `TouchScreenKeyboard` beside
 * [OnScreenKeyboard], `Credits` beside [About], `LanguagesInput`, `Customization`,
 * `CorrectionLearning`, `PredictionCorrection`, `Prediction`, `PredictionsSuggestions`,
 * `DictionaryLearning`, `DictionariesLearning`, `AutoCorrection`. Nothing outside this file
 * referenced any of them, and each one read as a distinct destination while being the same page.
 *
 * They are gone. No behaviour moved with them: the *route strings* are what deep links and the
 * `"screen"` intent extra carry, and [fromRoute] still resolves every string it ever resolved,
 * including the four legacy spellings that never had an object at all (`"languages_input"`,
 * `"customization"`, `"prediction_correction"`, `"correction_learning"`). `SettingsRouteTest`
 * pins that list.
 *
 * ## Legacy strings with no page of their own
 *
 * Six more objects — `Personalization`, `LearnedWords`, `FeedbackHaptics`, `PersonalDictionary`,
 * `WordSubstitutions`, `UnifiedDictionary` — were accepted by [fromRoute] but never had a
 * `composable{}` in the settings NavHost, so an intent naming one crashed the activity on an
 * unregistered start destination. The objects are gone; each string now resolves to the page
 * that replaced it: `"personalization"` → [AppearanceLayout] (the Personalization hub),
 * `"learned_words"` → [PersonalLearnedWords], `"feedback_haptics"` → [TouchFeedback],
 * `"personal_dictionary"` → [UserDictionary], `"word_substitutions"` → [TextShortcuts],
 * `"unified_dictionary"` → [Learning] (the dictionaries hub).
 *
 * Two genuine one-page-two-routes cases remain and are deliberate, not aliases of this kind:
 * [TouchFeedback] and [KeyPressFeedback] both render `KeyPressFeedbackScreen` (the second is a
 * back-compat deep link that nothing in the app navigates to), and [CustomSymbolPagePkb] /
 * [CustomSymbolPageVkb] render `CustomSymbolPageScreen` with different arguments.
 */
sealed class SettingsRoute(val route: String, val title: String) {
    // Main settings menu (root)
    object Main : SettingsRoute("main", "Settings")

    // Primary settings screens (v5 - per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
    object Languages : SettingsRoute("languages", "Languages")
    object Preferences : SettingsRoute("preferences", "Preferences")
    object AppearanceLayout : SettingsRoute("appearance_layout", "Appearance and layout")
    // SuggestionCorrection defined below is the primary for "Suggestion and correction"
    object Advanced : SettingsRoute("advanced", "Advanced")

    // Typing and input subpages
    object OnScreenKeyboard : SettingsRoute("on_screen_keyboard", "On-Screen Keyboard")
    object PhysicalKeyboard : SettingsRoute("physical_keyboard", "Physical Keyboard")
    object VoiceInputMain : SettingsRoute("voice_input_main", "Voice Input")
    object TouchFeedback : SettingsRoute("touch_feedback", "Touch Feedback")

    // Suggestion and correction subpages
    object SuggestionCorrection : SettingsRoute("suggestion_correction", "Suggestion and Correction")
    object Suggestion : SettingsRoute("suggestion", "Suggestions")
    object Correction : SettingsRoute("correction", "Correction")
    object Learning : SettingsRoute("learning", "Learning")

    // Advanced subpages
    object DeviceCompatibility : SettingsRoute("device_compatibility", "Device Compatibility")

    // Other sub-screens
    object LanguageSwitching : SettingsRoute("language_switching", "Language Switching")
    object MultiLanguageKeyboards : SettingsRoute("multi_language_keyboards", "Multi-language Keyboards")
    object MultiLanguageWizardAdd : SettingsRoute("multi_language_wizard_add", "Add Keyboard")
    object MultiLanguageWizardEdit : SettingsRoute("multi_language_wizard_edit/{$ARG_WIZARD_CONFIG}", "Edit Keyboard")
    object QuickPhrases : SettingsRoute("quick_phrases", "Quick Phrases")
    object Shake : SettingsRoute("shake", "Shake Gestures")
    object VoiceInput : SettingsRoute("voice_input", "Voice Input Settings")
    object VoiceLanguageSelection : SettingsRoute("voice_language_selection", "Voice Input Language")
    object LanguagePacks : SettingsRoute("language_packs", "Language Packs")
    object Debug : SettingsRoute("debug", "Debug Settings")
    object AdvancedGestureParameters : SettingsRoute("advanced_gesture_parameters", "Advanced Gesture Parameters")
    object GestureLab : SettingsRoute("gesture_lab", "Gesture Lab")
    object CkbGestures : SettingsRoute("ckb_gestures", "CKB Gestures")
    object AnimationParameters : SettingsRoute("animation_parameters", "Animation Parameters")
    object PersonalLearnedWords : SettingsRoute("personal_learned_words", "Personal Learned Words")
    object UserDictionary : SettingsRoute("user_dictionary", "User Dictionary")
    object TextShortcuts : SettingsRoute("text_shortcuts", "Text Shortcuts")
    object CustomMacros : SettingsRoute("custom_macros", "Custom Macros")
    object SymbolCustomization : SettingsRoute("symbol_customization", "Symbol Customization")
    object CustomSymbolPagePkb : SettingsRoute("custom_symbol_page_pkb", "Custom Symbol Page (PKB)")
    object CustomSymbolPageVkb : SettingsRoute("custom_symbol_page_vkb", "Custom Symbol Page (VKB)")
    object CustomizeSlideBoard : SettingsRoute("customize_slideboard", "Customize SlideBoard")
    object SlideboardSettings : SettingsRoute("slideboard_settings", "Touch Screen Keyboard")
    object CustomizeMenu : SettingsRoute("customize_menu", "Customize Menu")
    object About : SettingsRoute("about", "About")
    object Updates : SettingsRoute("updates", "Updates")
    object KeyboardHelper : SettingsRoute("keyboard_helper", "BBKB Helper")
    object DeviceConfiguration : SettingsRoute("device_configuration", "Device Configuration")
    object DeviceProfileBuilder : SettingsRoute("device_profile_builder", "Build a device profile")

    /** Back-compat deep link onto the same page as [TouchFeedback]; nothing navigates here. */
    object KeyPressFeedback : SettingsRoute("key_press_feedback", "Key Press Feedback")
    
    companion object {
        // Personal dictionary routes with locale parameter
        fun personalDictionaryWords(locale: String) = "personal_dictionary_words/$locale"
        fun wordSubstitutionWords(locale: String) = "word_substitution_words/$locale"
        // Unified dictionary routes
        fun unifiedDictionaryEntries(locale: String) = "unified_dictionary_entries/$locale"

        /**
         * Route to the wizard in EDIT mode, carrying the config itself rather than parking it in
         * a static. Base64url keeps the serialized form (which contains '/' and ':') inside one
         * path segment with no percent-encoding round trip.
         */
        fun multiLanguageWizardEdit(serializedConfig: String): String =
            "multi_language_wizard_edit/" + android.util.Base64.encodeToString(
                serializedConfig.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )

        /** Inverse of [multiLanguageWizardEdit]; returns null if the argument is unusable. */
        fun decodeWizardConfigArg(encoded: String?): String? = try {
            if (encoded.isNullOrEmpty()) null
            else String(
                android.util.Base64.decode(
                    encoded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                ),
                Charsets.UTF_8
            )
        } catch (e: IllegalArgumentException) {
            null
        }
        /**
         * Get all primary settings routes for main menu
         */
        fun getAllPrimaryRoutes(): List<SettingsRoute> = listOf(
            Languages,
            Preferences,
            SuggestionCorrection,  // "Suggestion and correction"
            Advanced
        )
        
        /**
         * Resolve a route string to its destination, or null if it is not a route.
         *
         * This is what validates the `"screen"` intent extra before it reaches navigation-compose
         * as a start destination, so every string it has ever accepted must keep resolving —
         * including the legacy spellings below that have no `SettingsRoute` object of their own —
         * and every result must be a destination the NavHost registers. `SettingsRouteTest`
         * enumerates the accepted set and checks both.
         */
        fun fromRoute(route: String?): SettingsRoute? {
            return when (route) {
                Main.route -> Main
                Languages.route, "languages_input" -> Languages
                SuggestionCorrection.route, "prediction_correction", "correction_learning" -> SuggestionCorrection
                Preferences.route, "customization" -> Preferences
                // Legacy strings whose own page was folded into another; see the KDoc above.
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
                MultiLanguageKeyboards.route -> MultiLanguageKeyboards
                MultiLanguageWizardAdd.route -> MultiLanguageWizardAdd
                QuickPhrases.route -> QuickPhrases
                Shake.route -> Shake
                VoiceInput.route -> VoiceInput
                VoiceLanguageSelection.route -> VoiceLanguageSelection
                LanguagePacks.route -> LanguagePacks
                Debug.route -> Debug
                AdvancedGestureParameters.route -> AdvancedGestureParameters
                GestureLab.route -> GestureLab
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
                About.route -> About
                Updates.route -> Updates
                KeyboardHelper.route -> KeyboardHelper
                DeviceConfiguration.route -> DeviceConfiguration
                DeviceProfileBuilder.route -> DeviceProfileBuilder
                KeyPressFeedback.route -> KeyPressFeedback
                else -> null
            }
        }
    }
}
