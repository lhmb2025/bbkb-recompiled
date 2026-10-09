package dev.bbkb.ime.core.ime

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Titan 2 Elite's Fn key sends CTRL_LEFT with no key-up and auto-repeats while held (the
 * `fn-no-key-up` quirk). [ControlModeController] must not read those repeats as "Ctrl held alone"
 * and latch its sticky Ctrl mode, and must not leave Ctrl stuck down for want of a release.
 * Everywhere else — no quirk — the latch behaves exactly as before.
 *
 * Also the config-declared Ctrl-like modifier remap (the Titan 2's Fn on scancode 251).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ControlModeFnQuirkTest {

    private class FakeHost(override val ctrlKeySendsNoKeyUp: Boolean) : ControlModeController.Host {
        override var isVkbControlModeEnabled = false
        override var controlModeSetting = 2
        override var isAltActiveForCharacter = false
        val sent = mutableListOf<String>()
        var showCount = 0
        override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) { sent += "down:$keyCode" }
        override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) { sent += "up:$keyCode" }
        override fun showControlModeUi() { showCount++ }
        override fun hideControlModeUi() {}
    }

    private val ctrl = KeyEvent.KEYCODE_CTRL_LEFT

    @After
    fun tearDown() {
        ScancodeMappingResolver.getInstance().reset()
    }

    // ── without the quirk: the latch as it always was ────────────────────────

    @Test
    fun noQuirk_ctrlHeldAloneUntilItRepeats_latchesTheStickyMode() {
        val host = FakeHost(ctrlKeySendsNoKeyUp = false)
        val c = ControlModeController(host)
        assertTrue(c.handleHardKeyDown(ctrl, 0, false))
        assertTrue(c.handleHardKeyDown(ctrl, 1, false))
        assertTrue(c.handleHardKeyDown(ctrl, 2, false))
        assertTrue(c.handleHardKeyUp(ctrl, 0, false))
        assertTrue("held alone until it repeated: sticky Ctrl", c.isInCtrlMode)
        assertEquals(1, host.showCount)
    }

    @Test
    fun hostDefault_isNoQuirk() {
        val host = object : ControlModeController.Host {
            override val isVkbControlModeEnabled = false
            override val controlModeSetting = 2
            override val isAltActiveForCharacter = false
            override fun sendKeyDownWithMeta(keyCode: Int, metaState: Int) {}
            override fun sendKeyUpWithMeta(keyCode: Int, metaState: Int) {}
            override fun showControlModeUi() {}
            override fun hideControlModeUi() {}
        }
        assertFalse(host.ctrlKeySendsNoKeyUp)
    }

    // ── with the quirk ───────────────────────────────────────────────────────

    @Test
    fun quirk_repeatsNeverLatch_evenIfAReleaseArrives() {
        val host = FakeHost(ctrlKeySendsNoKeyUp = true)
        val c = ControlModeController(host)
        assertTrue(c.handleHardKeyDown(ctrl, 0, false))
        for (r in 1..8) assertTrue(c.handleHardKeyDown(ctrl, r, false))
        assertTrue(c.isCtrlActive)
        assertTrue(c.handleHardKeyUp(ctrl, 0, false))
        assertFalse("no sticky mode from repeats", c.isInCtrlMode)
        assertFalse(c.isCtrlActive)
        assertEquals(0, host.showCount)
    }

    @Test
    fun quirk_ctrlIsOneShot_noKeyUpNeeded() {
        val host = FakeHost(ctrlKeySendsNoKeyUp = true)
        val c = ControlModeController(host)
        // Fn held: CTRL_LEFT down, then repeats; no release ever comes.
        c.handleHardKeyDown(ctrl, 0, false)
        c.handleHardKeyDown(ctrl, 1, false)
        // Ctrl+C
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_C, 0, true))
        assertTrue(c.handleHardKeyUp(KeyEvent.KEYCODE_C, 0, true))
        assertEquals(listOf("down:${KeyEvent.KEYCODE_C}", "up:${KeyEvent.KEYCODE_C}"), host.sent)
        assertFalse("Ctrl is spent; it is not stuck down", c.isCtrlActive)

        // The hold's remaining repeats do not re-arm it...
        c.handleHardKeyDown(ctrl, 2, false)
        c.handleHardKeyDown(ctrl, 3, false)
        assertFalse(c.isCtrlActive)
        // ...so the next letter types as a letter.
        assertFalse(c.handleHardKeyDown(KeyEvent.KEYCODE_A, 0, true))
        assertFalse(c.handleHardKeyUp(KeyEvent.KEYCODE_A, 0, true))
        assertEquals(2, host.sent.size)
        assertFalse(c.isInCtrlMode)
        assertEquals(0, host.showCount)
    }

    @Test
    fun quirk_aFreshPressArmsAgain() {
        val host = FakeHost(ctrlKeySendsNoKeyUp = true)
        val c = ControlModeController(host)
        c.handleHardKeyDown(ctrl, 0, false)
        c.handleHardKeyDown(KeyEvent.KEYCODE_V, 0, true)
        c.handleHardKeyUp(KeyEvent.KEYCODE_V, 0, true)
        c.handleHardKeyDown(ctrl, 0, false)
        assertTrue(c.isCtrlActive)
        c.handleHardKeyDown(KeyEvent.KEYCODE_X, 0, true)
        c.handleHardKeyUp(KeyEvent.KEYCODE_X, 0, true)
        assertEquals(4, host.sent.size)
        assertFalse(c.isCtrlActive)
    }

    @Test
    fun quirk_whenThePressItselfNeverArrives_theFirstRepeatArms() {
        val host = FakeHost(ctrlKeySendsNoKeyUp = true)
        val c = ControlModeController(host)
        assertTrue(c.handleHardKeyDown(ctrl, 1, false))
        assertTrue(c.isCtrlActive)
        assertTrue(c.handleHardKeyDown(KeyEvent.KEYCODE_Z, 0, true))
        assertEquals(listOf("down:${KeyEvent.KEYCODE_Z}"), host.sent)
    }

    @Test
    fun quirk_aPlainKeyEndsASpentHold() {
        val host = FakeHost(ctrlKeySendsNoKeyUp = true)
        val c = ControlModeController(host)
        c.handleHardKeyDown(ctrl, 0, false)
        c.handleHardKeyDown(KeyEvent.KEYCODE_C, 0, true)
        c.handleHardKeyUp(KeyEvent.KEYCODE_C, 0, true)
        c.handleHardKeyDown(KeyEvent.KEYCODE_A, 0, true)   // Fn was let go; typing resumed
        c.handleHardKeyUp(KeyEvent.KEYCODE_A, 0, true)
        // A later hold that only produces repeats arms again.
        c.handleHardKeyDown(ctrl, 1, false)
        assertTrue(c.isCtrlActive)
    }

    // ── the config-declared Ctrl-like modifier ───────────────────────────────

    private fun keyEvent(keyCode: Int, scanCode: Int, action: Int = KeyEvent.ACTION_DOWN) =
        KeyEvent(0L, 0L, action, keyCode, 0, 0, 3, scanCode, 0, InputDevice.SOURCE_KEYBOARD)

    private fun install(resId: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ScancodeMappingResolver.getInstance().initialize(
            DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0])
    }

    @Test
    fun titan2Fn_isRewrittenToCtrl() {
        install(R.xml.device_config_titan2)
        val c = ControlModeController(FakeHost(false))
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val out = c.remapProfileCtrlKey(keyEvent(KeyEvent.KEYCODE_FUNCTION, 251, action))
            assertEquals(KeyEvent.KEYCODE_CTRL_LEFT, out.keyCode)
            assertEquals(251, out.scanCode)
            assertEquals(action, out.action)
            assertTrue(out.isCtrlPressed)
        }
    }

    @Test
    fun anFnAlreadyArrivingAsCtrl_andEveryOtherKey_isLeftAlone() {
        install(R.xml.device_config_titan2_elite)
        val c = ControlModeController(FakeHost(true))
        val already = keyEvent(KeyEvent.KEYCODE_CTRL_LEFT, 251)
        assertSame(already, c.remapProfileCtrlKey(already))
        val alt = keyEvent(KeyEvent.KEYCODE_ALT_LEFT, 56)
        assertSame("a MODIFIER that is not Ctrl-like", alt, c.remapProfileCtrlKey(alt))
        val letter = keyEvent(KeyEvent.KEYCODE_A, 30)
        assertSame(letter, c.remapProfileCtrlKey(letter))
    }

    @Test
    fun key2AndMp01_haveNoCtrlLikeModifier() {
        val c = ControlModeController(FakeHost(false))
        for (resId in intArrayOf(R.xml.device_config_athena, R.xml.device_config_minimal)) {
            install(resId)
            for ((keyCode, scanCode) in listOf(119 to 110, 7 to 11, KeyEvent.KEYCODE_ALT_RIGHT to 249,
                KeyEvent.KEYCODE_UNKNOWN to 251, KeyEvent.KEYCODE_A to 30)) {
                val ev = keyEvent(keyCode, scanCode)
                assertSame(ev, c.remapProfileCtrlKey(ev))
            }
        }
    }
}
