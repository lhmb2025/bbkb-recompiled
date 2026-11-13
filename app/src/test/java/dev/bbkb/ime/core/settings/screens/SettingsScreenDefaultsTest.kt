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
 * Pins that a settings screen opens on the value the runtime reader (`SettingsManager` /
 * `SettingsValues`) uses for an unset key, not a default of the screen's own.
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
        // The gesture activation delay lives on Advanced gesture thresholds and must open on the
        // engine's default.
        render { AdvancedGestureParametersScreen({}) }
        assertEquals(
            "Value: ${context.resources.getInteger(R.integer.config_default_VKB_swipe_gesture_suppression_timeout)}ms",
            sliderValueOf(context.getString(R.string.settings_gesture_vkb_suppression_title)),
        )
    }

    /**
     * The CKB slider must open on the engine's `R.integer` default; [CkbGesturesScreen] writes the
     * preference by hand rather than through a `Slide` spec.
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
        // "" is what the runtime reads for an unset key: leave the layout's own currency key
        // alone, so "$" would be wrong for every non-dollar layout. Unset shows the ¤ icon.
        render { AppearanceLayoutScreen({}, {}) }
        val texts = rendered()
        assertTrue(texts.toString(), "¤" in texts)
        assertTrue(texts.toString(), "$" !in texts)
    }

    @Test
    fun aChosenCurrencyIsStillShown() {
        prefs.edit().putString("pref_currency_key", "€").commit()
        render { AppearanceLayoutScreen({}, {}) }
        assertTrue("€" in rendered())
    }

    @Test
    fun theSlideboardSummaryShowsTheQuickPhrasesTheKeyboardInserts() {
        render { SlideboardLayoutScreen({}, {}, {}) }
        val expected = context.getString(
            R.string.settings_vkb_quick_phrases_summary,
            context.getString(R.string.pref_quick_phrase_1_default),
            context.getString(R.string.pref_quick_phrase_2_default),
            context.getString(R.string.pref_quick_phrase_3_default),
        )
        assertTrue(rendered().toString(), expected in rendered())
    }

    @Test
    fun aKeypressVolumeStoredAsTheOriginalFloatFractionRendersItsLevel() {
        // Old installs may hold a float (0..1) here; reading it with getInt threw.
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
