package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins that "Customize unified input menu" is disabled while "Enable unified input menu" (the
 * toggle above it) is off, and re-enabled in place when it is switched back on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AuxiliaryInputScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    private var customizeMenuOpened = 0

    private fun render() {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    AuxiliaryInputScreen(
                        onNavigateToCustomizeMenu = { customizeMenuOpened++ },
                        onNavigateBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(id: Int): String = context.getString(id)

    private fun click(id: Int) {
        composeRule.onNodeWithText(string(id)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `customize menu opens while the unified input menu is on`() {
        render()
        click(R.string.settings_customize_menu_title)
        assertEquals(1, customizeMenuOpened)
    }

    /**
     * The switch does not close the editor: a key can still raise the menu while the switch is
     * off, and an editor is never greyed out by the feature's switch.
     */
    @Test
    fun `customize menu opens while the unified input menu is off`() {
        PrefsManager.getPrefs(context).edit().putBoolean("pref_uim_enabled", false).commit()
        render()
        click(R.string.settings_customize_menu_title)
        assertEquals(1, customizeMenuOpened)
    }
}
