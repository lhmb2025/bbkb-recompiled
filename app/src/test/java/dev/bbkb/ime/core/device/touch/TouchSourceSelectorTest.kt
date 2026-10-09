package dev.bbkb.ime.core.device.touch

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig.SourcePreference
import dev.bbkb.ime.core.device.touch.TouchSourceSelector.Choice
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.Reason
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TouchSourceSelector]: automatic choice between the native source and the privileged reader,
 * the profile's `source` pin, and the statuses the Touch surface helper shows. Pure JVM.
 */
class TouchSourceSelectorTest {

    private fun pad(minSdk: Int? = 36, source: SourcePreference = SourcePreference.AUTO) =
        TouchKeypadConfig().apply {
            nativeMinSdk = minSdk ?: TouchKeypadConfig.NATIVE_MIN_SDK_UNSET
            this.source = source
        }

    /** What the reader itself says (here: Shizuku not running); the selection passes it through. */
    private val reader = TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_NOT_RUNNING)

    @Test
    fun noDeclaredPad_selectsNothing() {
        val s = TouchSourceSelector.select(null, 36, true)
        assertEquals(Choice.NONE, s.choice)
        assertEquals(Reason.NO_TOUCH_KEYPAD_DECLARED, s.reason)
        assertNull(s.preference)
        assertFalse(s.nativeRouteApplies)
        assertEquals(State.NOT_APPLICABLE, s.nativeStatus().state)
        assertEquals(State.NOT_APPLICABLE, s.shizukuStatus(reader).state)
    }

    @Test
    fun auto_onTheNativeSdk_selectsNative() {
        val s = TouchSourceSelector.select(pad(), 36, true)
        assertEquals(Choice.NATIVE, s.choice)
        assertEquals(SourcePreference.AUTO, s.preference)
        assertTrue(s.nativeRouteApplies)
        assertEquals(TouchSourceStatus.of(State.IDLE, Reason.OK), s.nativeStatus())
        assertEquals(State.NOT_APPLICABLE, s.shizukuStatus(reader).state)
    }

    @Test
    fun auto_onTheNativeSdk_withThePadNotEnumerated_isNativeWaitingForTheOemSwitch() {
        // Titan 2 on Android 16 with Scroll assistant off: still the native route, not Shizuku.
        val s = TouchSourceSelector.select(pad(), 36, false)
        assertEquals(Choice.NATIVE, s.choice)
        assertEquals(TouchSourceStatus.of(State.UNAVAILABLE, Reason.PAD_NOT_ENUMERATED), s.nativeStatus())
    }

    @Test
    fun auto_belowTheNativeSdk_selectsShizuku_andReportsTheReadersRealState() {
        val s = TouchSourceSelector.select(pad(), 35, true)
        assertEquals(Choice.SHIZUKU, s.choice)
        assertEquals(Reason.OS_DOES_NOT_DELIVER_PAD, s.reason)
        assertFalse(s.nativeRouteApplies)
        assertEquals(reader, s.shizukuStatus(reader))
        assertEquals(TouchSourceStatus.of(State.NOT_APPLICABLE, Reason.OS_DOES_NOT_DELIVER_PAD), s.nativeStatus())
    }

    @Test
    fun noNativeMinSdk_deliveryFollowsEnumeration() {
        assertEquals(Choice.NATIVE, TouchSourceSelector.select(pad(minSdk = null), 29, true).choice)
        val notEnumerated = TouchSourceSelector.select(pad(minSdk = null), 29, false)
        assertEquals(Choice.SHIZUKU, notEnumerated.choice)
        assertEquals(Reason.PAD_NOT_ENUMERATED, notEnumerated.reason)
        assertTrue("both routes are offered until the pad shows up", notEnumerated.nativeRouteApplies)
    }

    @Test
    fun nativePin_neverSelectsShizuku() {
        assertEquals(Choice.NATIVE, TouchSourceSelector.select(pad(source = SourcePreference.NATIVE), 36, false).choice)
        val below = TouchSourceSelector.select(pad(source = SourcePreference.NATIVE), 35, false)
        assertEquals(Choice.NONE, below.choice)
        assertEquals(Reason.OS_DOES_NOT_DELIVER_PAD, below.reason)
        assertFalse(below.nativeRouteApplies)
        assertEquals(State.NOT_APPLICABLE, below.shizukuStatus(reader).state)
        val chosenNative = TouchSourceSelector.select(pad(source = SourcePreference.NATIVE), 36, false)
        assertEquals(Reason.PROFILE_PINS_NATIVE, chosenNative.shizukuStatus(reader).reason)
    }

    @Test
    fun shizukuPin_alwaysSelectsShizuku_andTheNativeSourceStaysOff() {
        val s = TouchSourceSelector.select(pad(source = SourcePreference.SHIZUKU), 36, true)
        assertEquals(Choice.SHIZUKU, s.choice)
        assertEquals(Reason.PROFILE_PINS_SHIZUKU, s.reason)
        assertEquals(TouchSourceStatus.of(State.NOT_APPLICABLE, Reason.PROFILE_PINS_SHIZUKU), s.nativeStatus())
        assertEquals(reader, s.shizukuStatus(reader))
        assertFalse("a pinned reader offers no built-in route", s.nativeRouteApplies)
    }
}
