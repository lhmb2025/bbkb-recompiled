package dev.bbkb.ime.core.device.touch.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing the device: name and regex matching, node ordering, and decoding what the native
 * probe reports about a node (flags and ABS ranges) on both sides of the AIDL boundary.
 */
class EvdevDeviceMatcherTest {

    private fun device(path: String, name: String) = TouchDeviceInfo(path, name)

    @Test
    fun anExactNameMatchesOnlyItself() {
        val m = TouchDeviceMatcher.exact("touch_keypad")
        assertTrue(m.matches("touch_keypad"))
        assertFalse(m.matches("Touch_Keypad"))
        assertFalse(m.matches("touch_keypad2"))
        assertFalse(m.matches("my touch_keypad"))
        assertFalse(m.isRegex)
    }

    @Test
    fun aRegexSearchesTheNameUnlessAnchored() {
        val loose = TouchDeviceMatcher.regex("pad")
        assertTrue(loose.matches("mtk-pad"))
        assertTrue(loose.matches("touch_keypad"))
        assertFalse(loose.matches("touchPad"))

        val anchored = TouchDeviceMatcher.regex("^touchPad$")
        assertTrue(anchored.matches("touchPad"))
        assertFalse(anchored.matches("touchPad2"))

        val either = TouchDeviceMatcher.regex("^(touchPad|mtk-pad)$")
        assertTrue(either.matches("mtk-pad"))
        assertTrue(either.isRegex)
    }

    @Test
    fun aCaseInsensitiveKotlinRegexKeepsItsFlagAcrossTheWire() {
        val m = TouchDeviceMatcher.regex(Regex("^touchpad$", RegexOption.IGNORE_CASE))
        assertEquals("(?i)^touchpad$", m.pattern)
        assertTrue(m.matches("touchPad"))
        // What EvdevUserService rebuilds from (pattern, isRegex) behaves the same.
        val rebuilt = TouchDeviceMatcher.parse(m.pattern, m.isRegex)!!
        assertTrue(rebuilt.matches("TOUCHPAD"))
        assertEquals(m, rebuilt)
    }

    @Test
    fun unusablePatternsAreRejected() {
        assertNull(TouchDeviceMatcher.parse(null, false))
        assertNull(TouchDeviceMatcher.parse("", true))
        assertNull(TouchDeviceMatcher.parse("([unclosed", true))
        // Not a regex, so brackets are just characters.
        assertTrue(TouchDeviceMatcher.parse("([unclosed", false)!!.matches("([unclosed"))
        try {
            TouchDeviceMatcher.regex("*bad")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("*bad"))
        }
    }

    @Test
    fun theFirstMatchInNodeOrderWins() {
        val devices = listOf(
            device("/dev/input/event0", "gpio-keys"),
            device("/dev/input/event4", "touch_keypad"),
            device("/dev/input/event9", "touch_keypad"),
        )
        assertSame(devices[1], TouchDeviceMatcher.exact("touch_keypad").firstMatch(devices))
        assertNull(TouchDeviceMatcher.exact("touchPad").firstMatch(devices))
    }

    @Test
    fun eventNodesSortNumericallyAndEverythingElseIsDropped() {
        val listing = listOf("event10", "mice", "event2", "event", "js0", "eventX", "event0", "event1a", "by-path", "event1")
        assertEquals(listOf("event0", "event1", "event2", "event10"), EvdevNodes.sortedEventNodes(listing))
    }

    @Test
    fun aProbeWithMultiTouchAxesDecodes() {
        val probe = IntArray(EvdevProbeLayout.SIZE)
        probe[EvdevProbeLayout.STATUS] = EvdevProbeLayout.FLAG_ABS_X or EvdevProbeLayout.FLAG_ABS_Y or
            EvdevProbeLayout.FLAG_MT_X or EvdevProbeLayout.FLAG_MT_Y or EvdevProbeLayout.FLAG_BTN_TOUCH or
            EvdevProbeLayout.FLAG_MT_TRACKING_ID or EvdevProbeLayout.FLAG_MT_SLOT
        probe[EvdevProbeLayout.ABS_X_MAX] = 1440
        probe[EvdevProbeLayout.ABS_Y_MAX] = 720
        probe[EvdevProbeLayout.MT_X_MIN] = 1
        probe[EvdevProbeLayout.MT_X_MAX] = 1440
        probe[EvdevProbeLayout.MT_Y_MAX] = 720
        probe[EvdevProbeLayout.MT_SLOT_MAX] = 9

        val info = EvdevProbeLayout.toDeviceInfo("/dev/input/event6", "touchPad", probe)!!

        assertEquals(AxisRange(1, 1440), info.mtX)
        assertEquals(AxisRange(0, 720), info.mtY)
        assertEquals(AxisRange(0, 1440), info.absX)
        assertTrue(info.usesMtAxes)
        assertEquals(AxisRange(1, 1440), info.xRange)
        assertEquals(10, info.mtSlots)
        assertTrue(info.hasBtnTouch && info.hasTrackingId && info.hasPosition)
        assertFalse(info.grabbed)
    }

    @Test
    fun aProbeWithOnlyAbsAxesFallsBackToThem() {
        val probe = IntArray(EvdevProbeLayout.SIZE)
        probe[EvdevProbeLayout.STATUS] = EvdevProbeLayout.FLAG_ABS_X or EvdevProbeLayout.FLAG_ABS_Y or
            EvdevProbeLayout.FLAG_MT_X   // one MT axis is not enough
        probe[EvdevProbeLayout.ABS_X_MAX] = 1079
        probe[EvdevProbeLayout.ABS_Y_MAX] = 599
        probe[EvdevProbeLayout.MT_X_MAX] = 5000

        val info = EvdevProbeLayout.toDeviceInfo("/dev/input/event3", "touch_keypad", probe)!!

        assertFalse(info.usesMtAxes)
        assertEquals(AxisRange(0, 1079), info.xRange)
        assertEquals(AxisRange(0, 599), info.yRange)
        assertEquals(0, info.mtSlots)
    }

    @Test
    fun aFailedProbeIsNoDevice() {
        val failed = IntArray(EvdevProbeLayout.SIZE).also { it[EvdevProbeLayout.STATUS] = -13 }   // EACCES
        assertNull(EvdevProbeLayout.toDeviceInfo("/dev/input/event1", "x", failed))
        assertNull(EvdevProbeLayout.toDeviceInfo("/dev/input/event1", null, IntArray(EvdevProbeLayout.SIZE)))
        assertNull(EvdevProbeLayout.toDeviceInfo("/dev/input/event1", "x", IntArray(3)))
    }

    @Test
    fun aKeyOnlyDeviceHasNoPosition() {
        val probe = IntArray(EvdevProbeLayout.SIZE)   // flags 0: no axes, no BTN_TOUCH
        val info = EvdevProbeLayout.toDeviceInfo("/dev/input/event0", "gpio-keys", probe)!!
        assertFalse(info.hasPosition)
        assertEquals(0, EvdevProbeLayout.flagsOf(info))
    }

    @Test
    fun deviceInfoSurvivesTheAidlParcelableBothWays() {
        val info = TouchDeviceInfo(
            path = "/dev/input/event6", name = "touchPad",
            absX = AxisRange(0, 1440), absY = AxisRange(0, 720),
            mtX = AxisRange(0, 1440), mtY = AxisRange(-5, 720),
            hasBtnTouch = true, hasTrackingId = true, mtSlots = 2, grabbed = true,
        )
        assertEquals(info, info.toParcel().toTouchDeviceInfo())

        val sparse = TouchDeviceInfo("/dev/input/event3", "touch_keypad", absX = AxisRange(0, 1079))
        assertEquals(sparse, sparse.toParcel().toTouchDeviceInfo())
    }

    @Test
    fun describeIsOneReadableLine() {
        val info = TouchDeviceInfo(
            "/dev/input/event6", "touchPad", mtX = AxisRange(0, 1440), mtY = AxisRange(0, 720),
            hasBtnTouch = true, hasTrackingId = true, mtSlots = 1, grabbed = true,
        )
        assertEquals("/dev/input/event6 'touchPad' mtX=0..1440 mtY=0..720 slots=1 trackingId btnTouch grabbed", info.describe())
    }
}
