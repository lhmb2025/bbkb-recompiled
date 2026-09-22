package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.builder.DeviceConfigSummary
import dev.bbkb.ime.core.device.config.builder.HardwareKeyCaptureBus
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.R
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two screens the feature adds to Settings, and the one contract between them and the key
 * pipeline: while the builder is on screen, [HardwareKeyCaptureBus] is live, and whatever any of
 * its three producers offers lands on the step the screen is asking for.
 *
 * The bus is driven directly here rather than through Compose's key input, because that is how the
 * keys that matter actually arrive: the accessibility service consumes a Sym press before any
 * window sees it, so no amount of input injection into this window would reproduce it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DeviceProfileBuilderScreenTest {

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

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * The builder is one long list and a LazyColumn only composes what is on screen, so a row
     * below the fold has to be scrolled to before it exists at all.
     */
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        composeRule.waitForIdle()
    }

    @Test
    fun theBuilderAsksForTheFirstKeyAndOffersOneWayToSave() {
        render { DeviceProfileBuilderScreen(onNavigateBack = {}) }

        composeRule.onNodeWithText(string(R.string.device_profile_builder_title)).assertIsDisplayed()
        // The extended FAB. Its label lives under the button's own merged semantics, so it is
        // found on the unmerged tree; the button itself is the one primary action on the screen.
        composeRule.onNodeWithText(string(R.string.device_profile_save), useUnmergedTree = true)
            .assertExists()
        // The detected facts come first, above the fold.
        composeRule.onNodeWithText(string(R.string.device_profile_detected_model)).assertIsDisplayed()
        scrollTo(string(R.string.device_profile_detected_keypad_layout))
        composeRule.onNodeWithText(
            string(R.string.device_profile_detected_keypad_layout)
        ).assertIsDisplayed()

        scrollTo(string(R.string.device_profile_step_sym))
        composeRule.onNodeWithText(string(R.string.device_profile_step_sym)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.device_profile_capture_waiting)).assertIsDisplayed()
        scrollTo(string(R.string.device_profile_capture_progress, 0, 9))
        composeRule.onNodeWithText(
            string(R.string.device_profile_capture_progress, 0, 9)
        ).assertIsDisplayed()
    }

    @Test
    fun aKeyOfferedToTheCaptureBusIsRecordedOnTheStepBeingAsked() {
        render { DeviceProfileBuilderScreen(onNavigateBack = {}) }
        assertTrue("the screen must be listening while it is on screen", HardwareKeyCaptureBus.isCapturing())

        composeRule.runOnUiThread {
            // What KeyInterceptorService hands over for an MP01 Sym press.
            assertTrue(HardwareKeyCaptureBus.offer(249, KeyEvent.KEYCODE_SYM, 4, 0, true, 1_000L))
            assertTrue(HardwareKeyCaptureBus.offer(249, KeyEvent.KEYCODE_SYM, 4, 0, false, 1_040L))
        }
        composeRule.waitForIdle()

        val recorded =
            string(R.string.device_profile_capture_recorded, 249, KeyEvent.KEYCODE_SYM, "KEYCODE_SYM")
        scrollTo(recorded)
        composeRule.onNodeWithText(recorded).assertIsDisplayed()
        scrollTo(string(R.string.device_profile_capture_progress, 1, 9))
        composeRule.onNodeWithText(
            string(R.string.device_profile_capture_progress, 1, 9)
        ).assertIsDisplayed()
    }

    @Test
    fun skippingTheCurrentStepMovesOnWithoutRecordingAKey() {
        render { DeviceProfileBuilderScreen(onNavigateBack = {}) }

        scrollTo(string(R.string.device_profile_action_skip))
        composeRule.onNodeWithText(string(R.string.device_profile_action_skip)).performClick()
        composeRule.waitForIdle()

        scrollTo(string(R.string.device_profile_capture_skipped))
        composeRule.onNodeWithText(string(R.string.device_profile_capture_skipped)).assertIsDisplayed()
        scrollTo(string(R.string.device_profile_capture_progress, 1, 9))
        composeRule.onNodeWithText(
            string(R.string.device_profile_capture_progress, 1, 9)
        ).assertIsDisplayed()
    }

    @Test
    fun theCaptureBusIsReleasedWhenTheScreenGoesAway() {
        val show = mutableStateOf(true)
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    if (show.value) DeviceProfileBuilderScreen(onNavigateBack = {})
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(HardwareKeyCaptureBus.isCapturing())

        composeRule.runOnUiThread { show.value = false }
        composeRule.waitForIdle()

        assertFalse(
            "a bus left registered would swallow every hardware key in the IME",
            HardwareKeyCaptureBus.isCapturing(),
        )
    }

    /**
     * The builder is unfinished, so the configuration screen does not advertise it on a fresh
     * install: neither the row nor the "THIS DEVICE" subhead above it is composed.
     */
    @Test
    fun theConfigurationScreenHidesTheBuilderUntilTheDebugFlagIsSet() {
        render { DeviceConfigurationScreen({}, {}) }

        composeRule.onNodeWithText(string(R.string.device_profile_builder_entry_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.device_profile_builder_entry_summary)).assertDoesNotExist()
        composeRule.onNodeWithText(
            string(R.string.device_profile_category_this_device).uppercase()
        ).assertDoesNotExist()
    }

    @Test
    fun theConfigurationScreenOffersTheBuilderWhenTheDebugFlagIsSet() {
        PrefsManager.getPrefs(context).edit()
            .putBoolean(PREF_SHOW_DEVICE_PROFILE_BUILDER, true).commit()

        var navigated = false
        render { DeviceConfigurationScreen({ navigated = true }, {}) }

        composeRule.onNodeWithText(string(R.string.device_profile_builder_entry_title)).performClick()
        composeRule.waitForIdle()
        assertTrue(navigated)
    }

    // ── the import confirmation ──────────────────────────────────────────────

    private fun summaryOf(xml: String) =
        DeviceConfigSummary.of(ByteArrayInputStream(xml.toByteArray()))!!

    @Test
    fun theImportDialogShowsWhatTheFileMatchesBeforeItIsActivated() {
        val summary = summaryOf(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <device-input-config version="2.3" name="Minimal Phone (MP01)">
                <device>
                    <match>
                        <device-name exact="aw9523b-key"/>
                        <build-device exact="MP01"/>
                    </match>
                    <device-type>PKB</device-type>
                    <keypad-layout>qwerty</keypad-layout>
                    <input-mappings><scancode-mappings>
                        <key rawScanCode="249" rawKeyCode="63" role="BOARD_SYM"/>
                    </scancode-mappings></input-mappings>
                </device>
            </device-input-config>
            """.trimIndent()
        )
        var activated = false
        render { ImportSummaryDialog(summary, onActivate = { activated = true }, onKeep = {}) }

        composeRule.onNodeWithText("Minimal Phone (MP01)").assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.device_config_import_matches_device, "aw9523b-key")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.device_config_import_matches_build, "MP01")
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.device_config_import_type, "PKB")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.device_config_import_keys, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(
            string(R.string.device_config_import_keypad_layout, "qwerty")
        ).assertIsDisplayed()

        // This is not an MP01, so the dialog must say so rather than silently doing nothing later.
        composeRule.onNodeWithText(string(R.string.device_config_import_mismatch)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.device_config_import_activate)).performClick()
        assertTrue(activated)
    }

    @Test
    fun theImportDialogCallsOutAFileThatMatchesNothing() {
        val summary = summaryOf(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <device-input-config version="2.3" name="Empty">
                <device><match/><device-type>PKB</device-type></device>
            </device-input-config>
            """.trimIndent()
        )
        render { ImportSummaryDialog(summary, onActivate = {}, onKeep = {}) }

        composeRule.onNodeWithText(
            string(R.string.device_config_import_matches_nothing)
        ).assertIsDisplayed()
    }
}
