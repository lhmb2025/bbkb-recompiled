package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a fresh install a settings screen must show the value the keyboard is actually using. Each
 * case here was a screen carrying its own copy of a default that disagreed with the runtime
 * reader (`SettingsManager` / `SettingsValues`); the runtime value is what every user has been
 * getting, so it is the one the screen now shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsScreenDefaultsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        prefs = PrefsManager.getPrefs(context)
        prefs.edit().clear().commit()
    }

    /** PrefsManager's instance is shared with the other screen tests in this sandbox. */
    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun rendered(): List<String> =
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    /** Opens the slider dialog of the row titled [title] and returns the value it starts on. */
    private fun sliderValueOf(title: String): String {
        composeRule.onNodeWithText(title).performScrollTo().performClick()
        composeRule.waitForIdle()
        val value = rendered().single { it.startsWith("Value: ") }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        return value
    }

    @Test
    fun emojiPredictionsShowsTheRuntimeDefault() {
        render { PredictionsSuggestionsScreen({}) }
        val title = context.getString(R.string.settings_pred_emoji_title)
        val titleBounds = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        // The switch is the toggleable on the title's row.
        val switch = composeRule.onAllNodes(isToggleable(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .single { it.boundsInRoot.top < titleBounds.bottom && it.boundsInRoot.bottom > titleBounds.top }
        val expected = context.resources.getBoolean(R.bool.config_default_emoji_predictions)
        assertEquals(
            if (expected) ToggleableState.On else ToggleableState.Off,
            switch.config[SemanticsProperties.ToggleableState],
        )
    }

    @Test
    fun debugSlidersShowTheRuntimeDefaults() {
        render { DebugSettingsScreen({}, {}, {}, {}) }
        assertEquals(
            "Value: ${SettingsManager.DEFAULT_KOREAN_DOUBLE_CONSONANT_RESOLUTION_DELAY_MS}ms",
            sliderValueOf(context.getString(R.string.prefs_double_consonant_delay_title)),
        )
        assertEquals(
            "Value: ${context.resources.getInteger(R.integer.config_default_VKB_swipe_gesture_suppression_timeout)}ms",
            sliderValueOf(context.getString(R.string.prefs_vkb_gesture_activation_delay_title)),
        )
    }

    /**
     * The same assertion the CKB row carried while it lived on the debug screen: the slider must
     * open on the `R.integer` default the engine reads, not on a value the screen hardcoded. The
     * row moved to [CkbGesturesScreen] (the one screen a user looks at for CKB gestures), and
     * that screen writes the preference by hand rather than through a `Slide` spec, so the check
     * matters more there, not less.
     */
    @Test
    fun theCkbGestureSuppressionSliderShowsTheRuntimeDefault() {
        render { CkbGesturesScreen({}) }
        assertEquals(
            "Value: ${context.resources.getInteger(R.integer.config_default_CKB_swipe_gesture_suppression_timeout)}ms",
            sliderValueOf(context.getString(R.string.prefs_ckb_gesture_activation_delay_title)),
        )
    }

    @Test
    fun theKoreanDelayConstantIsWhatTheRuntimeReads() {
        assertEquals(350, SettingsManager.DEFAULT_KOREAN_DOUBLE_CONSONANT_RESOLUTION_DELAY_MS)
        assertEquals(
            SettingsManager.DEFAULT_KOREAN_DOUBLE_CONSONANT_RESOLUTION_DELAY_MS,
            SettingsManager.getKoreanDoubleConsonantResolutionDelay(prefs, context.resources),
        )
    }

    @Test
    fun anUnsetCurrencyShowsTheKeyboardsOwnSymbolNotADollar() {
        // "" is what the runtime reads for an unset key, and it means "leave the layout's own
        // currency key alone" — so claiming "$" was wrong for every non-dollar layout.
        render { SymbolCustomizationScreen({}, {}, {}) }
        val texts = rendered()
        assertTrue(texts.toString(), context.getString(R.string.settings_default_currency_summary, "Keyboard default") in texts)
        assertTrue(texts.toString(), context.getString(R.string.settings_default_currency_summary, "$") !in texts)
    }

    @Test
    fun aChosenCurrencyIsStillShown() {
        prefs.edit().putString("pref_currency_key", "€").commit()
        render { SymbolCustomizationScreen({}, {}, {}) }
        assertTrue(context.getString(R.string.settings_default_currency_summary, "€") in rendered())
    }

    @Test
    fun theSlideboardSummaryShowsTheQuickPhrasesTheKeyboardInserts() {
        render { SlideboardLayoutScreen({}, {}, {}) }
        val expected = listOf(
            R.string.pref_quick_phrase_1_default,
            R.string.pref_quick_phrase_2_default,
            R.string.pref_quick_phrase_3_default,
        ).joinToString(", ", postfix = ", ...") { context.getString(it) }
        assertTrue(rendered().toString(), expected in rendered())
    }

    @Test
    fun aKeypressVolumeStoredAsTheOriginalFloatFractionRendersItsLevel() {
        // The original APK's seek bar wrote putFloat(0..1); the Compose screen's getInt threw on it.
        prefs.edit().putFloat("pref_keypress_sound_volume", 0.7f).commit()
        render { KeyPressFeedbackScreen({}) }
        assertTrue(rendered().toString(), "7" in rendered())
    }

    @Test
    fun aKeypressVolumeStoredAsAnIntPercentRendersItsLevel() {
        prefs.edit().putInt("pref_keypress_sound_volume", 40).commit()
        render { KeyPressFeedbackScreen({}) }
        assertTrue(rendered().toString(), "4" in rendered())
    }
}
