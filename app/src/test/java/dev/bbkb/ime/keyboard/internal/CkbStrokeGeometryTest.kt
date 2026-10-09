package dev.bbkb.ime.keyboard.internal

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.touch.TouchKeypadGeometry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The CKB stroke analyser's two numbers, which the original app baked in as 144 / 610 for the
 * KEY2's pad: they now come from the touch keypad's frame, and on the KEY2's frame they must be
 * exactly those literals so swipe typing there is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CkbStrokeGeometryTest {

    @After
    fun restore() {
        GestureEventProcessor.setKeypadGeometry(TouchKeypadGeometry.KEY2_DEFAULT)
    }

    @Test
    fun theDefaultsAreTheOriginalLiterals() {
        assertEquals(144, GestureEventProcessor.strokeKeyWidth())
        assertEquals(610, GestureEventProcessor.strokeBoardHeight())
    }

    @Test
    fun theKey2Frame_keeps144And610() {
        val key2 = TouchKeypadGeometry.resolve(
            dev.bbkb.ime.core.device.detection.TouchKeypadInfo.forTest(5, 0f, 1080f, 525f),
            null, "0:0,450:324", true)
        GestureEventProcessor.setKeypadGeometry(key2)
        assertEquals(144, GestureEventProcessor.strokeKeyWidth())
        assertEquals(610, GestureEventProcessor.strokeBoardHeight())
        GestureEventProcessor.setKeypadGeometry(TouchKeypadGeometry.resolve(null, null, "0:0,450:324", true))
        assertEquals("the forced-CKB rig keeps the KEY2 frame too", 610, GestureEventProcessor.strokeBoardHeight())
    }

    @Test
    fun anotherPad_scalesThem() {
        val titan2 = TouchKeypadGeometry.resolve(null,
            TouchKeypadConfig().apply { rangeX = 1440; rangeY = 720 }, null, false)
        GestureEventProcessor.setKeypadGeometry(titan2)
        assertEquals(192, GestureEventProcessor.strokeKeyWidth())
        assertEquals(837, GestureEventProcessor.strokeBoardHeight())
    }
}
