package dev.bbkb.ime.core.device.touch

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo
import dev.bbkb.ime.core.device.touch.TouchKeypadGeometry.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TouchKeypadGeometry]'s precedence — the pad's InputDevice, then the profile's
 * `<touch-keypad>` ranges, then (Y only, forced CKB) the `<ckb-y-warp>` band, then the KEY2's pad
 * — and the KEY2 identity it must keep: 1080, 525, 144, 610, exactly, wherever the frame is the
 * KEY2's. Pure JVM.
 */
class TouchKeypadGeometryTest {

    private val key2Pad = TouchKeypadInfo.forTest(5, 0.4f, 1080f, 525f)

    private fun declared(x: Int, y: Int) = TouchKeypadConfig().apply { rangeX = x; rangeY = y }

    private fun assertKey2Frame(g: TouchKeypadGeometry) {
        assertEquals(1080, g.frameWidth())
        assertEquals(525, g.frameHeight())
        assertEquals(144, g.strokeKeyWidth())
        assertEquals(610, g.strokeBoardHeight())
        assertEquals(1080f, g.normalizationReference(), 0f)
        assertEquals(1080f / 525f, g.frameAspect(), 1e-6f)
    }

    // ── the KEY2, byte for byte ──────────────────────────────────────────────

    @Test
    fun key2Pad_fromItsInputDevice_isExactlyTheOldConstants() {
        val g = TouchKeypadGeometry.resolve(key2Pad, null, "0:0,450:324", true)
        assertKey2Frame(g)
        assertEquals(Source.INPUT_DEVICE, g.xSource())
        assertEquals(Source.INPUT_DEVICE, g.ySource())
        assertEquals("getTouchKeypadYMax: the scanned range, as before", 525f, g.sensorYMax(), 0f)
        assertEquals("resolution passes through for SettingsValues", 0.4f, g.resolution(), 0f)
    }

    @Test
    fun noPadNoProfile_isTheKey2Frame_andReportsNoYMax() {
        val g = TouchKeypadGeometry.resolve(null, null, null, false)
        assertKey2Frame(g)
        assertEquals(Source.KEY2_DEFAULT, g.xSource())
        assertEquals(Source.KEY2_DEFAULT, g.ySource())
        assertEquals("getTouchKeypadYMax on a device with no pad: 0, as before", 0f, g.sensorYMax(), 0f)
        assertEquals(0f, g.resolution(), 0f)
        assertFalse(g.isMeasured())
    }

    @Test
    fun key2DefaultConstant_isTheKey2Frame() {
        assertKey2Frame(TouchKeypadGeometry.KEY2_DEFAULT)
    }

    /**
     * The W2 emulator posing as a KEY2 (forced CKB, no pad): getTouchKeypadYMax keeps answering
     * the warp's last breakpoint (450), while everything that scales a frame keeps the KEY2 frame —
     * the stroke analyser's 144 / 610 and the recorder's 1080 are what they were.
     */
    @Test
    fun forcedCkbRig_warpFeedsYMaxOnly_frameStaysKey2() {
        val g = TouchKeypadGeometry.resolve(null, null, "0:0,450:324", true)
        assertEquals(Source.LEGACY_Y_WARP, g.ySource())
        assertEquals(450f, g.sensorYMax(), 0f)
        assertEquals(450f, g.height(), 0f)
        assertKey2Frame(g)
    }

    @Test
    fun warpIsIgnoredWithoutForcedCkb_andWhenUnreadable() {
        assertEquals(0f, TouchKeypadGeometry.resolve(null, null, "0:0,450:324", false).sensorYMax(), 0f)
        assertEquals(0f, TouchKeypadGeometry.resolve(null, null, "garbage", true).sensorYMax(), 0f)
        assertEquals(0f, TouchKeypadGeometry.resolve(null, null, null, true).sensorYMax(), 0f)
    }

    // ── precedence ───────────────────────────────────────────────────────────

    @Test
    fun inputDeviceBeatsProfile() {
        val g = TouchKeypadGeometry.resolve(TouchKeypadInfo.forTest(9, 0f, 1400f, 700f),
            declared(1440, 720), null, false)
        assertEquals(1400, g.frameWidth())
        assertEquals(700, g.frameHeight())
        assertEquals(Source.INPUT_DEVICE, g.xSource())
    }

    @Test
    fun profileStandsInForAPadWithoutRanges() {
        // The Titan 2's touchPad found by name, reporting no motion ranges (or no device at all,
        // for a synthesised source): the profile's 1440 x 720.
        for (device in listOf(TouchKeypadInfo.forTest(9, 0f, 0f, 0f), null)) {
            val g = TouchKeypadGeometry.resolve(device, declared(1440, 720), null, false)
            assertEquals(Source.PROFILE, g.xSource())
            assertEquals(Source.PROFILE, g.ySource())
            assertEquals(1440, g.frameWidth())
            assertEquals(720, g.frameHeight())
            assertEquals(720f, g.sensorYMax(), 0f)
            assertEquals(1440f, g.normalizationReference(), 0f)
            assertTrue(g.isMeasured())
        }
    }

    @Test
    fun profileBeatsTheWarp() {
        val g = TouchKeypadGeometry.resolve(null, declared(1440, 720), "0:0,450:324", true)
        assertEquals(Source.PROFILE, g.ySource())
        assertEquals(720, g.frameHeight())
    }

    @Test
    fun axesResolveIndependently() {
        val g = TouchKeypadGeometry.resolve(null, declared(1440, 0), null, false)
        assertEquals(Source.PROFILE, g.xSource())
        assertEquals(Source.KEY2_DEFAULT, g.ySource())
        assertEquals(1440, g.frameWidth())
        assertEquals(525, g.frameHeight())
    }

    @Test
    fun strokeGeometryScalesWithAnotherPad() {
        val titan2 = TouchKeypadGeometry.resolve(null, declared(1440, 720), null, false)
        assertEquals(192, titan2.strokeKeyWidth())          // 144 * 1440 / 1080
        assertEquals(837, titan2.strokeBoardHeight())       // 610 * 720 / 525, rounded
        val pocket = TouchKeypadGeometry.resolve(null, declared(720, 360), null, false)
        assertEquals(96, pocket.strokeKeyWidth())
        assertEquals(418, pocket.strokeBoardHeight())
    }
}
