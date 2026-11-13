package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins that [CkbGesturesScreen]'s DEVELOPER category (sensor-map instrumentation) shows only with
 * [PREF_SHOW_CKB_DEVELOPER_SETTINGS] on, and that nothing else is gated. The screen is a scrolling
 * `Column`, so every row is composed and `assertExists` is enough.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CkbGesturesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        // PrefsManager's instance is shared with the other screen tests in this sandbox, and one
        // case below writes to it.
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    private fun render() {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    CkbGesturesScreen({})
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(id: Int): String = context.getString(id)

    /** On a fresh install: no subhead, no export row, no visualizer. */
    @Test
    fun theDeveloperCategoryIsHiddenUntilTheDebugFlagIsSet() {
        render()

        composeRule.onNodeWithText(string(R.string.ckb_gestures_developer_category)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.ckb_gestures_export_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.ckb_gestures_export_summary)).assertDoesNotExist()
        composeRule.onNodeWithText("Sensor Visualizer (debug)").assertDoesNotExist()
        composeRule.onNodeWithText("Export sensor map").assertDoesNotExist()
    }

    @Test
    fun theDeveloperCategoryIsThereWhenTheDebugFlagIsSet() {
        PrefsManager.getPrefs(context).edit()
            .putBoolean(PREF_SHOW_CKB_DEVELOPER_SETTINGS, true).commit()

        render()

        composeRule.onNodeWithText(string(R.string.ckb_gestures_developer_category)).assertExists()
        composeRule.onNodeWithText(string(R.string.ckb_gestures_export_title)).assertExists()
        composeRule.onNodeWithText("Sensor Visualizer (debug)").assertExists()
        composeRule.onNodeWithText("Export sensor map").assertExists()
    }

    /** The gate hides only the instrumentation: gesture slots and the timing slider render with the flag off. */
    @Test
    fun theSlotsAndTheTimingSliderAreNotDeveloperOnly() {
        render()

        composeRule.onNodeWithText(string(R.string.ckb_gesture_slot_flick_left)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_gesture_category_timing))
            .assertExists()
        composeRule.onNodeWithText(string(R.string.prefs_ckb_gesture_activation_delay_title))
            .assertExists()
    }
}
