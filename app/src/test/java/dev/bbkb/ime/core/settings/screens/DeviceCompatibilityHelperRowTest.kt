package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The BBKB helper row under Advanced > Device compatibility: greyed out and reading "Not required
 * for this phone" where the profile switches the helper off (BlackBerry hardware), a normal row
 * everywhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DeviceCompatibilityHelperRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        PrefsManager.getPrefs(context).edit().clear().commit()
        KeyboardDeviceScanner.resetForTest(null)
    }

    @After
    fun tearDown() {
        KeyboardDeviceScanner.resetForTest(null)
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    private fun s(id: Int) = context.getString(id)

    private fun install(helperOff: Boolean) {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.PKB, true, false, false, "qwerty", "4row"))
        DeviceProfileTestSupport.installMapping(DeviceInputMapping().apply { accessibilityHelperOff = helperOff })
    }

    private fun render(onHelper: () -> Unit) {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    DeviceCompatibilityScreen({}, onHelper, {})
                }
            }
        }
    }

    @Test
    fun greyedOutAndNotRequired_whereTheProfileSwitchesTheHelperOff() {
        install(helperOff = true)
        var opened = 0
        render { opened++ }
        composeRule.onNodeWithText(s(R.string.settings_pkb_keyboard_helper_title)).assertExists()
        composeRule.onNode(hasClickAction() and hasText(s(R.string.settings_pkb_keyboard_helper_title)))
            .assertDoesNotExist()
        composeRule.onNodeWithText(s(R.string.settings_pkb_keyboard_helper_summary)).assertDoesNotExist()
        // Both helper rows can read "Not required for this phone" on such a phone.
        assertEquals(true, composeRule.onAllNodes(hasText(s(R.string.settings_status_not_required)))
            .fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, opened)
    }

    @Test
    fun aNormalRow_everywhereElse() {
        install(helperOff = false)
        var opened = 0
        render { opened++ }
        composeRule.onNodeWithText(s(R.string.settings_pkb_keyboard_helper_summary)).assertExists()
        composeRule.onNode(hasClickAction() and hasText(s(R.string.settings_pkb_keyboard_helper_title)))
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theTitleIsLowerCaseHelper() {
        assertEquals("BBKB helper", s(R.string.settings_pkb_keyboard_helper_title))
    }
}
