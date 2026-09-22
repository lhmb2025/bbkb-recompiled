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
 * Behaviour of the two navigation rows in the On-Screen Keyboard screen's Behavior list.
 *
 * "Customize unified input menu" configures the order of a menu that "Enable unified input menu"
 * — the toggle directly above it — can switch off entirely. With the menu off there is nothing
 * for that screen to configure, so the row is disabled rather than opening it; and because the
 * toggle's value lives in the host's state map, switching it back on must re-enable the row
 * without leaving and re-entering the screen.
 *
 * "Slideboard settings" is here rather than under Personalization: everything behind it is
 * on-screen-keyboard behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class OnScreenKeyboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        // PrefsManager's instance is shared with the other screen tests in this sandbox, and the
        // cases below both read and write it.
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    @After
    fun tearDown() {
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    private var customizeMenuOpened = 0
    private var slideboardOpened = 0

    private fun render() {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    Content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun Content() {
        TouchScreenKeyboardScreen(
            onNavigateBack = {},
            onNavigateToCustomizeMenu = { customizeMenuOpened++ },
            onNavigateToSlideboard = { slideboardOpened++ },
        )
    }

    private fun string(id: Int): String = context.getString(id)

    private fun click(id: Int) {
        composeRule.onNodeWithText(string(id)).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    /** The menu is on by default, so the row it configures opens as it always did. */
    @Test
    fun `customize menu opens while the unified input menu is on`() {
        render()
        click(R.string.settings_customize_menu_title)
        assertEquals(1, customizeMenuOpened)
    }

    @Test
    fun `customize menu does not open while the unified input menu is off`() {
        PrefsManager.getPrefs(context).edit().putBoolean("pref_uim_enabled", false).commit()
        render()
        click(R.string.settings_customize_menu_title)
        assertEquals(0, customizeMenuOpened)
    }

    /**
     * The re-enable has to happen live: the toggle writes through the host's state map, which is
     * what the row's `enabled` gate reads.
     */
    @Test
    fun `turning the unified input menu back on re-enables the row`() {
        PrefsManager.getPrefs(context).edit().putBoolean("pref_uim_enabled", false).commit()
        render()

        click(R.string.settings_uim_enable_title)
        click(R.string.settings_customize_menu_title)

        assertEquals(1, customizeMenuOpened)
    }

    @Test
    fun `slideboard settings opens from this screen`() {
        render()
        click(R.string.settings_slideboard_settings_title)
        assertEquals(1, slideboardOpened)
    }
}
