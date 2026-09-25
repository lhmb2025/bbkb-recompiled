package dev.bbkb.ime.core.ime

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler
import dev.bbkb.ime.core.settings.util.SettingsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The multifunction key's "act as Ctrl" remap, now that it lives in [ControlModeController]
 * (Phase 1f) together with its `multifunctionCtrlDown` latch — the last piece of Ctrl state that
 * used to sit in `BlackBerryIME`.
 *
 * Uses the shipped KEY2 (athena) config, whose multifunction key is keyCode 119 / scanCode 110,
 * with its default action switched to Ctrl. The behaviour pinned is the one the method had in
 * `BlackBerryIME`: rewrite to `KEYCODE_CTRL_LEFT` unless Alt is active at press time, and keep the
 * key-up consistent with the key-down whatever Alt did in between.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MultifunctionCtrlRemapTest {

    private companion object {
        const val MF_KEYCODE = 119
        const val MF_SCANCODE = 110
    }

    private class FakeHost : ControlModeController.Host {
        override var isVkbControlModeEnabled = false
        override var controlModeSetting = 2
        override var isAltActiveForCharacter = false
        var altQueries = 0
        override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) {}
        override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) {}
        override fun showControlModeUi() {}
        override fun hideControlModeUi() {}
    }

    /** Counts the Alt reads, so the test can pin that ordinary keys never pay for one. */
    private class CountingHost(private val inner: FakeHost) : ControlModeController.Host by inner {
        override val isAltActiveForCharacter: Boolean
            get() { inner.altQueries++; return inner.isAltActiveForCharacter }
    }

    private lateinit var host: FakeHost
    private lateinit var controller: ControlModeController

    @Before
    fun setUp() {
        // No settings values: getConfiguredAction then falls back to the mapping's default-action.
        ReflectionHelpers.setField(SettingsManager.getInstance(), "settingsValues", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = DeviceInputMappingParser.parseConfigFromXmlResource(context, R.xml.device_config_athena)
        assertNotNull("device_config_athena.xml failed to parse", config)
        ScancodeMappingResolver.getInstance().initialize(config!!.mappings[0])
        val mapping = ScancodeMappingResolver.getInstance().resolve(MF_SCANCODE, MF_KEYCODE)
        assertNotNull("the athena multifunction key is no longer keyCode 119 / scanCode 110", mapping)
        mapping!!.defaultAction = MultifunctionKeyHandler.ACTION_CTRL

        host = FakeHost()
        controller = ControlModeController(CountingHost(host))
    }

    @After
    fun tearDown() {
        ScancodeMappingResolver.getInstance().reset()
        ReflectionHelpers.setField(SettingsManager.getInstance(), "settingsValues", null)
    }

    private fun event(action: Int, keyCode: Int = MF_KEYCODE, scanCode: Int = MF_SCANCODE, repeat: Int = 0) =
        KeyEvent(0L, 0L, action, keyCode, repeat, 0, 0, scanCode, 0, InputDevice.SOURCE_KEYBOARD)

    @Test
    fun withAltOffTheMultifunctionKeyBecomesLeftCtrlOnDownAndUp() {
        val down = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))
        val up = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_UP))

        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, down.keyCode)
        assertTrue("carries the Ctrl meta", down.metaState and KeyEvent.META_CTRL_ON != 0)
        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, up.keyCode)
    }

    @Test
    fun withAltActiveAtPressTimeTheKeyKeepsItsOwnCode() {
        host.isAltActiveForCharacter = true

        val down = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))
        val up = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_UP))

        assertEquals("Alt+multifunction types the mapping's Alt character", MF_KEYCODE, down.keyCode)
        assertEquals(MF_KEYCODE, up.keyCode)
    }

    /** The latch: the key-up follows the key-down, even if Alt changed in between. */
    @Test
    fun theKeyUpFollowsTheKeyDownWhateverAltDidMidHold() {
        controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))
        host.isAltActiveForCharacter = true

        val up = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_UP))

        assertEquals("still Ctrl on release", KeyEvent.KEYCODE_CTRL_LEFT, up.keyCode)
    }

    @Test
    fun theLatchIsReleasedByTheKeyUp() {
        controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))
        controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_UP))
        host.isAltActiveForCharacter = true

        val nextDown = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))

        assertEquals("the next press decides afresh", MF_KEYCODE, nextDown.keyCode)
    }

    /** Auto-repeat downs keep the latch rather than re-deciding it. */
    @Test
    fun anAutoRepeatDownDoesNotReDecide() {
        controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))
        host.isAltActiveForCharacter = true

        val repeat = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN, repeat = 1))

        assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, repeat.keyCode)
    }

    /** resetAll() drops the latch: it is Ctrl state now, and resetAll drops all Ctrl state. */
    @Test
    fun resetAllDropsTheLatch() {
        controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_DOWN))

        controller.resetAll()
        val up = controller.remapMultifunctionCtrlKey(event(KeyEvent.ACTION_UP))

        assertEquals("the up is no longer rewritten", MF_KEYCODE, up.keyCode)
    }

    /**
     * An ordinary key returns the very same event and never asks the Alt question — the snapshot
     * the host builds to answer it is paid for only by the multifunction key.
     */
    @Test
    fun anOrdinaryKeyIsUntouchedAndNeverAsksAboutAlt() {
        val a = event(KeyEvent.ACTION_DOWN, keyCode = KeyEvent.KEYCODE_A, scanCode = 30)

        assertSame(a, controller.remapMultifunctionCtrlKey(a))
        assertEquals(0, host.altQueries)
    }

    /** With a non-Ctrl action configured the key passes through, latch untouched. */
    @Test
    fun aMultifunctionKeyConfiguredForSomethingElseIsUntouched() {
        ScancodeMappingResolver.getInstance().resolve(MF_SCANCODE, MF_KEYCODE)!!.defaultAction =
            MultifunctionKeyHandler.ACTION_EMOJI_BOARD

        val down = event(KeyEvent.ACTION_DOWN)
        assertSame(down, controller.remapMultifunctionCtrlKey(down))
        assertFalse("not Ctrl", controller.isCtrlActive)
    }
}
