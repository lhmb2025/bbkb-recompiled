package dev.bbkb.ime.core.device.touch

import android.view.MotionEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Shizuku events enter the IME's generic-motion entry, so the KEY2 rule applies: a pad event is
 * recognised as one, and answered false while the input view is hidden (the grab is released, so
 * the ROM has the pad).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuTouchImeEntryTest {

    @After
    fun tearDown() {
        SyntheticTouchSources.clear()
        DeviceProfile.initialize(null)
    }

    @Test
    fun aStreamedPadEvent_isDroppedWhileTheInputViewIsHidden() {
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
                true, false, false, "qwerty", "4row"))
        SyntheticTouchSources.register(TouchKeypadInfo.measured(ShizukuTouchSource.DEVICE_ID, 1440f, 720f))
        val ime = Robolectric.buildService(BlackBerryIME::class.java).get()
        val down = ShizukuTouchSource.motionEvent(MotionEvent.ACTION_DOWN, 1_000L, 1_000L, 200f, 100f)

        assertTrue("it is a keypad event", DeviceProfile.current().isFromTouchKeypad(down))
        assertFalse(ime.isInputViewShown)
        assertFalse("dropped, as every keypad event is while the input view is hidden",
            ime.onGenericMotionEvent(down))
        down.recycle()
    }
}
