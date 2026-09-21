package dev.bbkb.ime.core.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils
import dev.bbkb.ime.core.settings.screens.AdvancedGestureParametersScreen
import dev.bbkb.ime.core.settings.screens.AdvancedSettingsScreen
import dev.bbkb.ime.core.settings.screens.AnimationParametersScreen
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.screens.AppearanceLayoutScreen
import dev.bbkb.ime.core.settings.screens.AutoCorrectionScreen
import dev.bbkb.ime.core.settings.screens.CorrectionLearningScreen
import dev.bbkb.ime.core.settings.screens.CreditsScreen
import dev.bbkb.ime.core.settings.screens.CustomizationScreen
import dev.bbkb.ime.core.settings.screens.DebugSettingsScreen
import dev.bbkb.ime.core.settings.screens.GestureLabScreen
import dev.bbkb.ime.core.settings.screens.CkbGesturesScreen
import dev.bbkb.ime.core.settings.screens.DeviceCompatibilityScreen
import dev.bbkb.ime.core.settings.screens.DictionariesLearningScreen
import dev.bbkb.ime.core.settings.screens.KeyPressFeedbackScreen
import dev.bbkb.ime.core.settings.screens.LanguagePacksScreen
import dev.bbkb.ime.core.settings.screens.LanguageSwitchingScreen
import dev.bbkb.ime.core.settings.screens.LanguagesInputScreen
import dev.bbkb.ime.core.settings.screens.MainSettingsScreen
import dev.bbkb.ime.core.settings.screens.MultiLanguageKeyboardsScreen
import dev.bbkb.ime.core.settings.screens.MultiLanguageWizardScreen
import dev.bbkb.ime.core.settings.screens.KeyboardHelperScreen
import dev.bbkb.ime.core.settings.screens.DeviceConfigurationScreen
import dev.bbkb.ime.core.settings.screens.DeviceProfileBuilderScreen
import dev.bbkb.ime.core.settings.screens.PhysicalKeyboardScreen
import dev.bbkb.ime.core.settings.screens.PredictionsSuggestionsScreen
import dev.bbkb.ime.core.settings.screens.QuickPhrasesScreen
import dev.bbkb.ime.core.settings.screens.ShakeGesturesScreen
import dev.bbkb.ime.core.settings.screens.CustomizeMenuScreen
import dev.bbkb.ime.core.settings.screens.SlideboardLayoutScreen
import dev.bbkb.ime.core.settings.screens.SymbolCustomizationScreen
import dev.bbkb.ime.core.settings.screens.CustomSymbolPageScreen
import dev.bbkb.ime.core.settings.screens.CustomizeSlideBoardScreen
import dev.bbkb.ime.core.settings.screens.CustomMacrosScreen
import dev.bbkb.ime.core.settings.screens.TouchScreenKeyboardScreen
import dev.bbkb.ime.core.settings.screens.TextShortcutsScreen
import dev.bbkb.ime.core.settings.screens.UserDictionaryScreen
import dev.bbkb.ime.core.settings.screens.VoiceInputSettingsScreen
import dev.bbkb.ime.core.settings.screens.VoiceLanguageSelectionScreen
import dev.bbkb.ime.core.settings.screens.WizardMode
import dev.bbkb.ime.core.settings.screens.WordListEditorScreen
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.shared.StartupTiming

/**
 * Modern Compose-based Settings Activity
 * Uses Jetpack Compose Navigation for Material 3 native experience
 */
class ComposeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTiming.anchor("settings.onCreate")
        val total = StartupTiming.begin()
        super.onCreate(savedInstanceState)

        // Settings has a LAUNCHER filter, so it is regularly the process's cold start with no
        // IME service to have warmed anything. getDefaultSharedPreferences() only *starts* the
        // prefs file load; the first read blocks on it (~190 ms measured from the IME side, see
        // BlackBerryIME.onCreate). Force it to finish on a worker so the parse overlaps Compose
        // setup instead of stalling the theme's first pref read during composition.
        var t = StartupTiming.begin()
        PrefsManager.init(applicationContext)
        Thread({
            try {
                PrefsManager.getPrefs().contains(PrefsManager.Keys.KEYBOARD_THEME_MODE)
            } catch (_: Throwable) {
            }
        }, "SettingsPrefsWarm").apply { isDaemon = true }.start()
        StartupTiming.end("settings.onCreate.prefsWarmSpawn", t)

        // DeviceProfile must be initialized with device mappings before any screen reads them --
        // and still is: startInitialize only moves the work (device scan + active-config XML
        // parse + debug-override pref read, ~250 ms) onto a worker, and every DeviceProfile
        // reader joins the load before it can observe the profile. The configuration argument
        // stays null to match the one-argument DeviceProfile.initialize(this) this replaced,
        // which likewise did not apply a Configuration.
        t = StartupTiming.begin()
        DeviceProfile.startInitialize(applicationContext, null)
        StartupTiming.end("settings.onCreate.deviceProfileStart", t)

        // Enable edge-to-edge for modern feel
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Get initial screen from intent, default to main menu. Validated through
        // SettingsRoute.fromRoute so an unknown "screen" extra cannot reach navigation-compose
        // as an unregistered start destination.
        val initialScreen = SettingsRoute.fromRoute(intent.getStringExtra("screen"))?.route
            ?: SettingsRoute.Main.route

        t = StartupTiming.begin()
        setContent {
            // Fires inside the first composition, before the first frame is drawn. Every
            // DeviceProfile / SharedPreferences read a screen makes at composition time is
            // already counted against the phases logged after this line.
            remember { StartupTiming.mark("settings.firstComposition"); Unit }
            BlackBerryTheme {
                SettingsNavHost(
                    initialRoute = initialScreen,
                    onExit = { finish() }
                )
            }
        }
        StartupTiming.end("settings.onCreate.setContent", t)
        StartupTiming.end("settings.onCreate.TOTAL", total)
    }
}

/**
 * Navigation host for all settings screens
 */
@Composable
fun SettingsNavHost(
    initialRoute: String = "main",
    onExit: () -> Unit
) {
    val navController = rememberNavController()
    val highlightController = remember { SettingsHighlightController() }
    // 40-odd destination registrations plus the SettingsRoute class-init behind them; the one
    // non-trivial piece of work that is genuinely inside the first composition.
    remember { StartupTiming.mark("settings.navHostComposition"); Unit }

    // Every page's back action is the same: pop, and leave the activity if there is nothing to
    // pop. Every navigation is the same too. Declared once instead of 40-odd times.
    val back: () -> Unit = { if (!navController.popBackStack()) onExit() }
    fun go(route: SettingsRoute): () -> Unit = { navController.navigate(route.route) }

    CompositionLocalProvider(LocalSettingsHighlight provides highlightController) {
    NavHost(
        navController = navController,
        startDestination = initialRoute
    ) {
        // Main Settings Menu (root)
        composable(SettingsRoute.Main.route) {
            MainSettingsScreen(
                onNavigateToScreen = { route ->
                    navController.navigate(route)
                },
                onNavigateBack = onExit
            )
        }
        
        // Advanced Settings
        composable(SettingsRoute.Advanced.route) {
            AdvancedSettingsScreen(
                onNavigateBack = back,
                onNavigateToDeviceCompatibility = go(SettingsRoute.DeviceCompatibility),
                onNavigateToAbout = go(SettingsRoute.About),
                onNavigateToDebug = go(SettingsRoute.Debug)
            )
        }
        
        // V5 Reorganized Primary Screens (per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
        
        // 1. Languages
        composable(SettingsRoute.Languages.route) {
            LanguagesInputScreen(
                onNavigateToLanguageSwitching = go(SettingsRoute.LanguageSwitching),
                onNavigateToMultiLanguageKeyboards = go(SettingsRoute.MultiLanguageKeyboards),
                onNavigateToLanguagePacks = go(SettingsRoute.LanguagePacks),
                onNavigateBack = back
            )
        }
        
        // 3. Suggestion and correction hub
        composable(SettingsRoute.SuggestionCorrection.route) {
            CorrectionLearningScreen(
                onNavigateToAutoCorrection = go(SettingsRoute.Correction),
                onNavigateToPredictions = go(SettingsRoute.Suggestion),
                onNavigateToDictionaries = go(SettingsRoute.Learning),
                onNavigateBack = back
            )
        }

        // 2. Typing and input hub
        composable(SettingsRoute.Preferences.route) {
            CustomizationScreen(
                onNavigateToTouchScreenKeyboard = go(SettingsRoute.OnScreenKeyboard),
                onNavigateToPhysicalKeyboard = go(SettingsRoute.PhysicalKeyboard),
                onNavigateToVoiceInput = go(SettingsRoute.VoiceInput),
                onNavigateToShakeGestures = go(SettingsRoute.Shake),
                onNavigateBack = back
            )
        }

        // 3. Appearance and layout hub
        composable(SettingsRoute.AppearanceLayout.route) {
            AppearanceLayoutScreen(
                onNavigateToSlideboard = go(SettingsRoute.SlideboardSettings),
                onNavigateToSymbolCustomization = go(SettingsRoute.SymbolCustomization),
                onNavigateBack = back
            )
        }

        // On-Screen Keyboard (Gestures / Behavior)
        composable(SettingsRoute.OnScreenKeyboard.route) {
            TouchScreenKeyboardScreen(
                onNavigateBack = back,
                onNavigateToKeyPressFeedback = go(SettingsRoute.TouchFeedback),
                onNavigateToCustomizeMenu = go(SettingsRoute.CustomizeMenu)
            )
        }

        // Touch Feedback — linked page from On-Screen Keyboard → Behavior
        composable(SettingsRoute.TouchFeedback.route) {
            KeyPressFeedbackScreen(
                onNavigateBack = back
            )
        }

        // Slideboard settings — also the direct entry point from the UIM gear icon
        composable(SettingsRoute.CustomizeMenu.route) {
            CustomizeMenuScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.SlideboardSettings.route) {
            SlideboardLayoutScreen(
                onNavigateToCustomizeSlideBoard = go(SettingsRoute.CustomizeSlideBoard),
                onNavigateToQuickPhrases = go(SettingsRoute.QuickPhrases),
                onNavigateBack = back
            )
        }

        // Physical Keyboard
        composable(SettingsRoute.PhysicalKeyboard.route) {
            PhysicalKeyboardScreen(
                onNavigateToCkbGestures = go(SettingsRoute.CkbGestures),
                onNavigateBack = back
            )
        }

        // Device Configuration — still reachable via DeviceCompatibilityScreen
        composable(SettingsRoute.DeviceConfiguration.route) {
            DeviceConfigurationScreen(
                onNavigateToProfileBuilder = go(SettingsRoute.DeviceProfileBuilder),
                onNavigateBack = back
            )
        }

        // Device profile builder — capture this handset's keys and export a config
        composable(SettingsRoute.DeviceProfileBuilder.route) {
            DeviceProfileBuilderScreen(
                onNavigateBack = back
            )
        }

        // Device Compatibility (new screen — config loader, meta state, keyboard helper)
        composable(SettingsRoute.DeviceCompatibility.route) {
            DeviceCompatibilityScreen(
                onNavigateToDeviceConfiguration = go(SettingsRoute.DeviceConfiguration),
                onNavigateToKeyboardHelper = go(SettingsRoute.KeyboardHelper),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.KeyboardHelper.route) {
            KeyboardHelperScreen(
                onNavigateBack = back
            )
        }

        // Voice Input (accessible from Typing and input hub)
        composable(SettingsRoute.VoiceInput.route) {
            VoiceInputSettingsScreen(
                onNavigateToLanguageSelection = go(SettingsRoute.VoiceLanguageSelection),
                onNavigateBack = back
            )
        }

        // Voice Input legacy entry (e.g. deep link from voice_input_main)
        composable(SettingsRoute.VoiceInputMain.route) {
            VoiceInputSettingsScreen(
                onNavigateToLanguageSelection = go(SettingsRoute.VoiceLanguageSelection),
                onNavigateBack = back
            )
        }
        
        // Suggestion and Correction subpages

        // Suggestion (formerly PredictionsSuggestions)
        composable(SettingsRoute.Suggestion.route) {
            PredictionsSuggestionsScreen(
                onNavigateBack = back
            )
        }

        // Correction (formerly AutoCorrection)
        composable(SettingsRoute.Correction.route) {
            AutoCorrectionScreen(
                onNavigateBack = back
            )
        }

        // Learning (formerly DictionariesLearning)
        composable(SettingsRoute.Learning.route) {
            DictionariesLearningScreen(
                onNavigateToUserDictionary = go(SettingsRoute.UserDictionary),
                onNavigateToTextShortcuts = go(SettingsRoute.TextShortcuts),
                onNavigateToLearnedWords = go(SettingsRoute.PersonalLearnedWords),
                onNavigateBack = back
            )
        }

        // Note: AutoCorrection.route == Correction.route == "correction"
        // Note: PredictionsSuggestions.route == Suggestion.route == "suggestion"
        // Note: DictionariesLearning.route == Learning.route == "learning"
        // Legacy aliases share the same route strings, so no separate registrations needed.
        
        // User Dictionary Screen (personal words with language headers)
        composable(SettingsRoute.UserDictionary.route) {
            UserDictionaryScreen(
                onNavigateBack = back
            )
        }
        
        // Text Shortcuts Screen (word substitutions with macro support)
        composable(SettingsRoute.TextShortcuts.route) {
            TextShortcutsScreen(
                onNavigateToCustomMacros = go(SettingsRoute.CustomMacros),
                onNavigateBack = back
            )
        }
        
        // Custom Macros Screen (user-defined macro placeholders)
        composable(SettingsRoute.CustomMacros.route) {
            CustomMacrosScreen(
                onNavigateBack = back
            )
        }
        
        // Other Sub-screens
        
        composable(SettingsRoute.LanguageSwitching.route) {
            LanguageSwitchingScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.MultiLanguageKeyboards.route) {
            MultiLanguageKeyboardsScreen(
                onNavigateToAddKeyboard = go(SettingsRoute.MultiLanguageWizardAdd),
                onNavigateToEditKeyboard = { keyboard ->
                    navController.navigate(
                        SettingsRoute.multiLanguageWizardEdit(MultiLanguageUtils.serializeConfig(keyboard))
                    )
                },
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.MultiLanguageWizardAdd.route) {
            MultiLanguageWizardScreen(
                mode = WizardMode.ADD,
                existingKeyboard = null,
                onSaveSuccess = {
                    navController.popBackStack()
                },
                onNavigateBack = back
            )
        }
        
        composable(
            SettingsRoute.MultiLanguageWizardEdit.route,
            arguments = listOf(navArgument(ARG_WIZARD_CONFIG) { type = NavType.StringType })
        ) { backStackEntry ->
            // Reconstructed from the nav argument, so it survives process death and restore.
            val keyboard = SettingsRoute
                .decodeWizardConfigArg(backStackEntry.arguments?.getString(ARG_WIZARD_CONFIG))
                ?.let { MultiLanguageUtils.parseConfig(it) }
            MultiLanguageWizardScreen(
                mode = WizardMode.EDIT,
                existingKeyboard = keyboard,
                onSaveSuccess = {
                    navController.popBackStack()
                },
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.QuickPhrases.route) {
            QuickPhrasesScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.Shake.route) {
            ShakeGesturesScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.VoiceLanguageSelection.route) {
            VoiceLanguageSelectionScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.LanguagePacks.route) {
            LanguagePacksScreen(
                onNavigateBack = back
            )
        }
        
        // Same page as the TouchFeedback registration above, under its back-compat deep-link
        // route. Nothing in the app navigates here; SettingsRoute.fromRoute must keep accepting
        // "key_press_feedback" or an external link to it lands on the main menu (§5.8 item 6).
        composable(SettingsRoute.KeyPressFeedback.route) {
            KeyPressFeedbackScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.Debug.route) {
            DebugSettingsScreen(
                onNavigateToAdvancedGestureParameters = go(SettingsRoute.AdvancedGestureParameters),
                onNavigateToAnimationParameters = go(SettingsRoute.AnimationParameters),
                onNavigateToGestureLab = go(SettingsRoute.GestureLab),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.CkbGestures.route) {
            CkbGesturesScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.GestureLab.route) {
            GestureLabScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.AdvancedGestureParameters.route) {
            AdvancedGestureParametersScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.AnimationParameters.route) {
            AnimationParametersScreen(
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.PersonalLearnedWords.route) {
            WordListEditorScreen(
                isWorkProfile = false,
                onNavigateBack = back
            )
        }
        
        // About (renamed from Credits per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
        composable(SettingsRoute.About.route) {
            CreditsScreen(
                onNavigateBack = back
            )
        }
        
        // Symbol Customization
        composable(SettingsRoute.SymbolCustomization.route) {
            SymbolCustomizationScreen(
                onNavigateToCustomSymbolPagePKB = go(SettingsRoute.CustomSymbolPagePkb),
                onNavigateToCustomSymbolPageVKB = go(SettingsRoute.CustomSymbolPageVkb),
                onNavigateBack = back
            )
        }
        
        // Custom Symbol Page (PKB)
        composable(SettingsRoute.CustomSymbolPagePkb.route) {
            CustomSymbolPageScreen(
                isPkb = true,
                onBack = back
            )
        }
        
        // Custom Symbol Page (VKB)
        composable(SettingsRoute.CustomSymbolPageVkb.route) {
            CustomSymbolPageScreen(
                isPkb = false,
                onBack = back
            )
        }
        
        // Customize SlideBoard
        composable(SettingsRoute.CustomizeSlideBoard.route) {
            CustomizeSlideBoardScreen(
                onBack = back
            )
        }
    }
    }
}
