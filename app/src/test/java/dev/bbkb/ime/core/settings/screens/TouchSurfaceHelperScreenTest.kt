package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.device.touch.KeypadTouchSources
import dev.bbkb.ime.core.device.touch.ShizukuTouchEngineFacade
import dev.bbkb.ime.core.device.touch.SyntheticTouchSources
import dev.bbkb.ime.core.device.touch.shizuku.AxisRange
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchStatus
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceInfo
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceMatcher
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
import org.robolectric.shadows.ShadowBuild

/**
 * The Touch surface helper and its two link rows, in every state a Titan owner can meet: the
 * built-in route working or waiting for Scroll assistant, and the Shizuku route from "not
 * installed" to "streaming". The profile is a real shipped Titan config; the Shizuku engine is a
 * fake behind [KeypadTouchSources]; the JVM is API 34, so the Titan 2's profile (native from 36)
 * is the Android 15 case, and the same profile with native-min-sdk 34 is the Android 16 case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class TouchSurfaceHelperScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    private class FakeEngine(var status: ShizukuTouchStatus) : ShizukuTouchEngineFacade {
        var refreshes = 0
        var permissionRequests = 0
        val listeners = mutableListOf<ShizukuTouchEngine.StatusListener>()
        override fun start(context: Context, matcher: TouchDeviceMatcher, grab: Boolean,
                           touches: ShizukuTouchEngine.TouchListener,
                           statuses: ShizukuTouchEngine.StatusListener) {}
        override fun setGrab(grab: Boolean) {}
        override fun stop() {}
        override fun status() = status
        override fun refreshStatus(context: Context) { refreshes++ }
        override fun requestPermission(context: Context): Boolean { permissionRequests++; return true }
        override fun addStatusListener(context: Context, listener: ShizukuTouchEngine.StatusListener) {
            listeners += listener
            listener.onStatus(status)
        }
        override fun removeStatusListener(listener: ShizukuTouchEngine.StatusListener) { listeners -= listener }
        fun emit(next: ShizukuTouchStatus) { status = next; listeners.toList().forEach { it.onStatus(next) } }
    }

    private val engine = FakeEngine(ShizukuTouchStatus(ShizukuTouchState.NOT_INSTALLED))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        PrefsManager.getPrefs(context).edit().clear().commit()
        KeyboardDeviceScanner.resetForTest(null)
        KeypadTouchSources.useShizukuEngineForTest(engine)
    }

    @After
    fun tearDown() {
        KeypadTouchSources.useShizukuEngineForTest(null)
        SyntheticTouchSources.clear()
        KeyboardDeviceScanner.resetForTest(null)
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    // ── device shapes ────────────────────────────────────────────────────────

    private fun titan2(nativeMinSdk: Int? = null): DeviceInputMapping =
        DeviceInputMappingParser.parseConfigFromXmlResource(context, R.xml.device_config_titan2)
            .mappings[0].apply { if (nativeMinSdk != null) touchKeypad!!.nativeMinSdk = nativeMinSdk }

    private fun install(mapping: DeviceInputMapping?, padEnumerated: Boolean = false) {
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
                true, false, false, "qwerty", "4row")
                .withTouchKeypad(if (padEnumerated) TouchKeypadInfo.forTest(9, 0f, 1440f, 720f) else null))
        if (mapping != null) DeviceProfileTestSupport.installMapping(mapping)
    }

    /** Android 15: the reader is the route. */
    private fun titan2OnAndroid15(status: ShizukuTouchStatus) {
        engine.status = status
        install(titan2())
    }

    /** Android 16: the built-in route. */
    private fun titan2OnAndroid16(scrollAssistantOn: Boolean) =
        install(titan2(nativeMinSdk = 34), padEnumerated = scrollAssistantOn)

    private fun render(content: @Composable () -> Unit = { TouchSurfaceHelperScreen({}) }) {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun s(id: Int): String = context.getString(id)
    private fun shown(id: Int) = composeRule.onNodeWithText(s(id)).assertExists()
    private fun absent(id: Int) = composeRule.onNodeWithText(s(id)).assertDoesNotExist()
    private fun categoryShown(id: Int) = composeRule.onNodeWithText(s(id).uppercase()).assertExists()
    private fun categoryAbsent(id: Int) = composeRule.onNodeWithText(s(id).uppercase()).assertDoesNotExist()
    /** A row that can be tapped (PreferenceItem only makes an enabled row clickable). */
    private fun row(titleId: Int) = composeRule.onNode(hasClickAction() and hasText(s(titleId)))

    /** A row that is drawn but cannot be tapped: a disabled PreferenceItem has no click action. */
    private fun disabledRow(titleId: Int) {
        shown(titleId)
        composeRule.onNode(hasClickAction() and hasText(s(titleId))).assertDoesNotExist()
    }

    private fun streaming() = ShizukuTouchStatus(ShizukuTouchState.STREAMING,
        device = TouchDeviceInfo("/dev/input/event6", "touchPad", mtX = AxisRange(0, 1440), mtY = AxisRange(0, 720)))

    // ── the built-in route (Android 16) ──────────────────────────────────────

    @Test
    fun nativeWorking_showsTheBuiltInSectionOnly() {
        titan2OnAndroid16(scrollAssistantOn = true)
        render()
        shown(R.string.touch_surface_status_native_working)
        categoryShown(R.string.touch_surface_builtin_category)
        shown(R.string.touch_surface_builtin_intro)
        shown(R.string.touch_surface_open_scroll_assistant)
        shown(R.string.touch_surface_cursor_assistant_title)
        shown(R.string.touch_surface_cursor_assistant_summary)
        categoryAbsent(R.string.touch_surface_shizuku_category)
        absent(R.string.touch_surface_install_shizuku)
        absent(R.string.touch_surface_grant_access)
        assertEquals("Shizuku is never touched on the built-in route", 0, engine.refreshes)
        assertEquals(0, engine.listeners.size)
    }

    @Test
    fun nativeWithScrollAssistantOff_saysToTurnItOn() {
        titan2OnAndroid16(scrollAssistantOn = false)
        render()
        shown(R.string.touch_surface_status_native_off)
        shown(R.string.touch_surface_open_scroll_assistant)
        categoryAbsent(R.string.touch_surface_shizuku_category)
    }

    // ── the Shizuku route (Android 15) ───────────────────────────────────────

    @Test
    fun shizukuNotInstalled() {
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_INSTALLED))
        render()
        shown(R.string.touch_surface_status_shizuku_not_installed)
        categoryAbsent(R.string.touch_surface_builtin_category)
        absent(R.string.touch_surface_open_scroll_assistant)
        categoryShown(R.string.touch_surface_shizuku_category)
        shown(R.string.touch_surface_shizuku_intro)
        shown(R.string.touch_surface_shizuku_state_not_installed)
        row(R.string.touch_surface_install_shizuku).assertExists()
        disabledRow(R.string.touch_surface_open_shizuku)
        disabledRow(R.string.touch_surface_grant_access)
        categoryShown(R.string.touch_surface_shizuku_guide_category)
        shown(R.string.touch_surface_shizuku_guide_steps)
        shown(R.string.touch_surface_shizuku_reboot)
        absent(R.string.touch_surface_shizuku_mediatek)
        assertEquals("re-read on resume", 1, engine.refreshes)
    }

    @Test
    fun shizukuNotRunning_saysSo_andKeepsTheRebootNote() {
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING))
        render()
        shown(R.string.touch_surface_status_shizuku_not_running)
        shown(R.string.touch_surface_shizuku_state_not_running)
        shown(R.string.touch_surface_shizuku_reboot)
        disabledRow(R.string.touch_surface_grant_access)
    }

    @Test
    fun shizukuNotGranted_grantAsksShizuku_andTheAnswerUpdatesTheScreen() {
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_GRANTED))
        render()
        shown(R.string.touch_surface_status_shizuku_not_granted)
        shown(R.string.touch_surface_shizuku_state_not_granted)
        shown(R.string.touch_surface_grant_access_summary)
        row(R.string.touch_surface_grant_access).performClick()
        assertEquals(1, engine.permissionRequests)

        // Shizuku's dialog answers "allow": the status arrives as a change, no resume needed.
        composeRule.runOnIdle { engine.emit(ShizukuTouchStatus(ShizukuTouchState.READY)) }
        composeRule.waitForIdle()
        shown(R.string.touch_surface_status_shizuku_ready)
        shown(R.string.touch_surface_shizuku_state_granted)
        disabledRow(R.string.touch_surface_grant_access)
    }

    @Test
    fun shizukuDeniedForGood_explainsWhereToAllowIt() {
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_GRANTED, permissionDeniedForever = true))
        render()
        shown(R.string.touch_surface_status_shizuku_not_granted)
        shown(R.string.touch_surface_shizuku_state_denied)
        shown(R.string.touch_surface_grant_access_denied)
        disabledRow(R.string.touch_surface_grant_access)
    }

    @Test
    fun shizukuStreaming_isWorking() {
        titan2OnAndroid15(streaming())
        render()
        shown(R.string.touch_surface_status_shizuku_working)
        shown(R.string.touch_surface_shizuku_state_granted)
        disabledRow(R.string.touch_surface_grant_access)
    }

    @Test
    fun onAMediaTekPhone_theVersionNoteShows() {
        ShadowBuild.setHardware("mt6789")
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING))
        render()
        shown(R.string.touch_surface_shizuku_mediatek)
    }

    // ── the two link rows ────────────────────────────────────────────────────

    @Test
    fun deviceCompatibility_showsTheHelperRowWithTheLiveStatus_onlyOnADeclaredPad() {
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING))
        var opened = 0
        render { DeviceCompatibilityScreen({}, {}, {}, onNavigateToTouchSurfaceHelper = { opened++ }) }
        shown(R.string.touch_surface_status_shizuku_not_running)
        row(R.string.touch_surface_helper_title).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun deviceCompatibility_greysOutTheHelperRow_withoutADeclaredPad() {
        install(null) // a KEY2-like shape: no <touch-keypad>
        render { DeviceCompatibilityScreen({}, {}, {}) }
        disabledRow(R.string.touch_surface_helper_title)
        shown(R.string.settings_status_not_required)
        shown(R.string.settings_pkb_keyboard_helper_summary)
        assertEquals(0, engine.refreshes)
        assertEquals(0, engine.listeners.size)
    }

    @Test
    fun physicalKeyboard_showsTheGesturesCategoryAndTheLinkRow_forADeclaredPadWithNoEventsYet() {
        titan2OnAndroid15(ShizukuTouchStatus(ShizukuTouchState.NOT_INSTALLED))
        var opened = 0
        render { PhysicalKeyboardScreen({}, onNavigateToTouchSurfaceHelper = { opened++ }) }
        categoryShown(R.string.settings_category_ckb_gestures)
        shown(R.string.touch_surface_link_title)
        composeRule.onNodeWithText(context.getString(R.string.touch_surface_link_summary,
            s(R.string.touch_surface_status_shizuku_not_installed))).assertExists()
        // The gesture rows themselves still need events that can arrive.
        absent(R.string.ckb_gestures_enable_title)
        row(R.string.touch_surface_link_title).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun physicalKeyboard_hasNoLinkRow_withoutADeclaredPad() {
        install(null)
        render { PhysicalKeyboardScreen({}) }
        absent(R.string.touch_surface_link_title)
        absent(R.string.settings_category_ckb_gestures)
    }

    @Test
    fun physicalKeyboard_whileStreaming_showsTheGestureRowsToo() {
        // A real stream registers the reader's device id, which is what hasTouchKeypad() reads.
        titan2OnAndroid15(streaming())
        SyntheticTouchSources.register(TouchKeypadInfo.measured(0x5B4B0001, 1440f, 720f))
        render { PhysicalKeyboardScreen({}) }
        shown(R.string.touch_surface_link_title)
        shown(R.string.ckb_gestures_enable_title)
    }
}
