package dev.bbkb.ime.core.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.SettingsTransitions
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.bbkb.ime.core.settings.screens.AdvancedGestureParametersScreen
import dev.bbkb.ime.core.settings.screens.AdvancedSettingsScreen
import dev.bbkb.ime.core.settings.screens.AnimationParametersScreen
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.screens.AppearanceLayoutScreen
import dev.bbkb.ime.core.settings.screens.AutoCorrectionScreen
import dev.bbkb.ime.core.settings.screens.CorrectionLearningScreen
import dev.bbkb.ime.core.settings.screens.CreditsScreen
import dev.bbkb.ime.core.settings.screens.OpenSourceLicensesScreen
import dev.bbkb.ime.core.settings.screens.CustomizationScreen
import dev.bbkb.ime.core.settings.screens.DebugSettingsScreen
import dev.bbkb.ime.core.settings.screens.GestureLabScreen
import dev.bbkb.ime.core.settings.screens.CkbGesturesScreen
import dev.bbkb.ime.core.settings.screens.DeviceCompatibilityScreen
import dev.bbkb.ime.core.settings.screens.DictionariesLearningScreen
import dev.bbkb.ime.core.settings.screens.KeyPressFeedbackScreen
import dev.bbkb.ime.core.settings.screens.LanguagePacksScreen
import dev.bbkb.ime.core.settings.screens.LanguageSwitchingScreen
import dev.bbkb.ime.core.settings.screens.LanguagesHubScreen
import dev.bbkb.ime.core.settings.screens.MainSettingsScreen
import dev.bbkb.ime.core.settings.screens.KeyboardHelperScreen
import dev.bbkb.ime.core.settings.screens.TouchSurfaceHelperScreen
import dev.bbkb.ime.core.settings.screens.DeviceConfigurationScreen
import dev.bbkb.ime.core.settings.screens.DeviceProfileBuilderScreen
import dev.bbkb.ime.core.settings.screens.PhysicalKeyboardScreen
import dev.bbkb.ime.core.settings.screens.BackupRestoreScreen
import dev.bbkb.ime.core.settings.screens.PhysicalLayoutsScreen
import dev.bbkb.ime.core.settings.screens.PredictionsSuggestionsScreen
import dev.bbkb.ime.core.settings.screens.QuickPhrasesScreen
import dev.bbkb.ime.core.settings.screens.CustomizeMenuScreen
import dev.bbkb.ime.core.settings.screens.SlideboardLayoutScreen
import dev.bbkb.ime.core.settings.screens.SymbolCustomizationScreen
import dev.bbkb.ime.core.settings.screens.AuxiliaryInputScreen
import dev.bbkb.ime.core.settings.screens.ShakeTuningScreen
import dev.bbkb.ime.core.settings.screens.CustomSymbolPageScreen
import dev.bbkb.ime.core.settings.screens.CustomizeSlideBoardScreen
import dev.bbkb.ime.core.settings.screens.CustomMacrosScreen
import dev.bbkb.ime.core.settings.screens.TouchScreenKeyboardScreen
import dev.bbkb.ime.core.settings.screens.TextShortcutsScreen
import dev.bbkb.ime.core.settings.screens.UpdatesScreen
import dev.bbkb.ime.core.settings.screens.UserDictionaryScreen
import dev.bbkb.ime.core.settings.screens.VoiceInputSettingsScreen
import dev.bbkb.ime.core.settings.screens.VoiceLanguageSelectionScreen
import dev.bbkb.ime.core.settings.screens.WordListEditorScreen
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.shared.StartupTiming

/** Settings activity: a Compose NavHost over every settings screen. */
class ComposeSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTiming.anchor("settings.onCreate")
        val total = StartupTiming.begin()
        super.onCreate(savedInstanceState)

        // Settings is often the cold start (LAUNCHER filter). The first pref read blocks on the file
        // load, so finish it on a worker while Compose sets up.
        var t = StartupTiming.begin()
        PrefsManager.init(applicationContext)
        Thread({
            try {
                PrefsManager.getPrefs().contains(PrefsManager.Keys.KEYBOARD_THEME_MODE)
            } catch (_: Throwable) {
                // Best-effort warm-up.
            }
        }, "SettingsPrefsWarm").apply { isDaemon = true }.start()
        StartupTiming.end("settings.onCreate.prefsWarmSpawn", t)

        // Screens read DeviceProfile only after it loads; startInitialize moves the ~250 ms scan
        // and config parse onto a worker and every reader joins it.
        t = StartupTiming.begin()
        DeviceProfile.startInitialize(applicationContext, null)
        StartupTiming.end("settings.onCreate.deviceProfileStart", t)

        // Edge to edge on every version; BlackBerryTheme sets the bar icon colours.
        enableEdgeToEdge()

        // Validated so an unknown "screen" extra cannot become an unregistered start destination.
        val initialScreen = SettingsRoute.fromRoute(intent.getStringExtra("screen"))?.route
            ?: SettingsRoute.Main.route

        t = StartupTiming.begin()
        setContent {
            // Fires inside the first composition, before the first frame.
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

@Composable
fun SettingsNavHost(
    initialRoute: String = "main",
    onExit: () -> Unit
) {
    val navController = rememberNavController()
    val highlightController = remember { SettingsHighlightController() }
    // Destination registrations plus SettingsRoute class-init: the real work in the first composition.
    remember { StartupTiming.mark("settings.navHostComposition"); Unit }

    val back: () -> Unit = { if (!navController.popBackStack()) onExit() }
    fun go(route: SettingsRoute): () -> Unit = { navController.navigate(route.route) }

    // The system Settings app's page transition, not the library's cross-fade.
    val transitions = SettingsTransitions

    CompositionLocalProvider(LocalSettingsHighlight provides highlightController) {
    NavHost(
        navController = navController,
        startDestination = initialRoute,
        // Behind the sliding pages, so the uncovered edge shows the page colour, not the window.
        modifier = Modifier.background(LocalSettingsColors.current.page),
        enterTransition = transitions.enter,
        exitTransition = transitions.exit,
        popEnterTransition = transitions.popEnter,
        popExitTransition = transitions.popExit,
    ) {
        composable(SettingsRoute.Main.route) {
            MainSettingsScreen(
                onNavigateToScreen = { route ->
                    navController.navigate(route)
                },
                onNavigateBack = onExit
            )
        }

        composable(SettingsRoute.Advanced.route) {
            AdvancedSettingsScreen(
                onNavigateBack = back,
                onNavigateToDeviceCompatibility = go(SettingsRoute.DeviceCompatibility),
                onNavigateToDebug = go(SettingsRoute.Debug),
                onNavigateToBackupRestore = go(SettingsRoute.BackupRestore)
            )
        }

        composable(SettingsRoute.BackupRestore.route) {
            BackupRestoreScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.LanguagesHub.route) {
            LanguagesHubScreen(
                onNavigateToLanguageSwitching = go(SettingsRoute.LanguageSwitching),
                onNavigateToDictionaryFiles = go(SettingsRoute.LanguagePacks),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.SuggestionCorrection.route) {
            CorrectionLearningScreen(
                onNavigateToAutoCorrection = go(SettingsRoute.Correction),
                onNavigateToPredictions = go(SettingsRoute.Suggestion),
                onNavigateToDictionaries = go(SettingsRoute.Learning),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.Preferences.route) {
            CustomizationScreen(
                onNavigateToTouchScreenKeyboard = go(SettingsRoute.OnScreenKeyboard),
                onNavigateToPhysicalKeyboard = go(SettingsRoute.PhysicalKeyboard),
                onNavigateToVoiceInput = go(SettingsRoute.VoiceInput),
                onNavigateToAuxiliaryInput = go(SettingsRoute.AuxiliaryInput),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.AppearanceLayout.route) {
            AppearanceLayoutScreen(
                onNavigateToSymbolCustomization = go(SettingsRoute.SymbolCustomization),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.OnScreenKeyboard.route) {
            TouchScreenKeyboardScreen(
                onNavigateBack = back,
                onNavigateToKeyPressFeedback = go(SettingsRoute.TouchFeedback),
                onNavigateToSlideboard = go(SettingsRoute.SlideboardSettings),
            )
        }

        composable(SettingsRoute.AuxiliaryInput.route) {
            AuxiliaryInputScreen(
                onNavigateToCustomizeMenu = go(SettingsRoute.CustomizeMenu),
                onNavigateBack = back,
            )
        }

        composable(SettingsRoute.TouchFeedback.route) {
            KeyPressFeedbackScreen(
                onNavigateBack = back
            )
        }

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

        composable(SettingsRoute.PhysicalKeyboard.route) {
            PhysicalKeyboardScreen(
                onNavigateToCkbGestures = go(SettingsRoute.CkbGestures),
                onNavigateToTouchSurfaceHelper = go(SettingsRoute.TouchSurfaceHelper),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.PhysicalLayouts.route) {
            PhysicalLayoutsScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.DeviceConfiguration.route) {
            DeviceConfigurationScreen(
                onNavigateToProfileBuilder = go(SettingsRoute.DeviceProfileBuilder),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.DeviceProfileBuilder.route) {
            DeviceProfileBuilderScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.DeviceCompatibility.route) {
            DeviceCompatibilityScreen(
                onNavigateToDeviceConfiguration = go(SettingsRoute.DeviceConfiguration),
                onNavigateToKeyboardHelper = go(SettingsRoute.KeyboardHelper),
                onNavigateToTouchSurfaceHelper = go(SettingsRoute.TouchSurfaceHelper),
                onNavigateToPhysicalLayouts = go(SettingsRoute.PhysicalLayouts),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.KeyboardHelper.route) {
            KeyboardHelperScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.TouchSurfaceHelper.route) {
            TouchSurfaceHelperScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.VoiceInput.route) {
            VoiceInputSettingsScreen(
                onNavigateToLanguageSelection = go(SettingsRoute.VoiceLanguageSelection),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.VoiceInputMain.route) {
            VoiceInputSettingsScreen(
                onNavigateToLanguageSelection = go(SettingsRoute.VoiceLanguageSelection),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.Suggestion.route) {
            PredictionsSuggestionsScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.Correction.route) {
            AutoCorrectionScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.Learning.route) {
            DictionariesLearningScreen(
                onNavigateToUserDictionary = go(SettingsRoute.UserDictionary),
                onNavigateToTextShortcuts = go(SettingsRoute.TextShortcuts),
                onNavigateToLearnedWords = go(SettingsRoute.PersonalLearnedWords),
                onNavigateBack = back
            )
        }

        // Legacy routes (AutoCorrection, PredictionsSuggestions, DictionariesLearning) share these route strings.

        composable(SettingsRoute.UserDictionary.route) {
            UserDictionaryScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.TextShortcuts.route) {
            TextShortcutsScreen(
                onNavigateToCustomMacros = go(SettingsRoute.CustomMacros),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.CustomMacros.route) {
            CustomMacrosScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.LanguageSwitching.route) {
            LanguageSwitchingScreen(
                onNavigateToPhysicalKeyboard = go(SettingsRoute.PhysicalKeyboard),
                onNavigateToCkbGestures = go(SettingsRoute.CkbGestures),
                onNavigateBack = back
            )
        }
        
        composable(SettingsRoute.QuickPhrases.route) {
            QuickPhrasesScreen(
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

        // Back-compat deep-link route for the TouchFeedback page; fromRoute must keep accepting it.
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
                onNavigateToShakeTuning = go(SettingsRoute.ShakeTuning),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.ShakeTuning.route) {
            ShakeTuningScreen(onNavigateBack = back)
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

        composable(SettingsRoute.About.route) {
            CreditsScreen(
                onNavigateToUpdates = go(SettingsRoute.Updates),
                onNavigateToLicenses = go(SettingsRoute.Licenses),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.Licenses.route) {
            OpenSourceLicensesScreen(onNavigateBack = back)
        }

        // Also opened by the daily check's notification ("screen" = "updates").
        composable(SettingsRoute.Updates.route) {
            UpdatesScreen(
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.SymbolCustomization.route) {
            SymbolCustomizationScreen(
                onNavigateToCustomSymbolPagePKB = go(SettingsRoute.CustomSymbolPagePkb),
                onNavigateToCustomSymbolPageVKB = go(SettingsRoute.CustomSymbolPageVkb),
                onNavigateToCustomizeNumberPad = go(SettingsRoute.CustomizeSlideBoard),
                onNavigateBack = back
            )
        }

        composable(SettingsRoute.CustomSymbolPagePkb.route) {
            CustomSymbolPageScreen(
                isPkb = true,
                onBack = back
            )
        }

        composable(SettingsRoute.CustomSymbolPageVkb.route) {
            CustomSymbolPageScreen(
                isPkb = false,
                onBack = back
            )
        }

        composable(SettingsRoute.CustomizeSlideBoard.route) {
            CustomizeSlideBoardScreen(
                onBack = back
            )
        }
    }
    }
}
