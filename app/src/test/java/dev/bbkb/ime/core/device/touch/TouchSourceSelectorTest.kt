package dev.bbkb.ime.core.device.touch

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig.SourcePreference
import dev.bbkb.ime.core.device.touch.TouchSourceSelector.Choice
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.Reason
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [TouchSourceSelector]: automatic choice between the native source and the privileged reader,
 * the profile's `source` pin, and the statuses a settings screen will show. Pure JVM.
 */
class TouchSourceSelectorTest {

    private fun pad(minSdk: Int? = 36, source: SourcePreference = SourcePreference.AUTO) =
        TouchKeypadConfig().apply {
            nativeMinSdk = minSdk ?: TouchKeypadConfig.NATIVE_MIN_SDK_UNSET
            this.source = source
        }

    @Test
    fun noDeclaredPad_selectsNothing() {
        val s = TouchSourceSelector.select(null, 36, true)
        assertEquals(Choice.NONE, s.choice)
        assertEquals(Reason.NO_TOUCH_KEYPAD_DECLARED, s.reason)
        assertNull(s.preference)
        assertEquals(State.NOT_APPLICABLE, s.nativeStatus().state)
        assertEquals(State.NOT_APPLICABLE, s.shizukuStatus().state)
    }

    @Test
    fun auto_onTheNativeSdk_selectsNative() {
        val s = TouchSourceSelector.select(pad(), 36, false)
        assertEquals(Choice.NATIVE, s.choice)
        assertEquals(SourcePreference.AUTO, s.preference)
        assertEquals(TouchSourceStatus.of(State.IDLE, Reason.OK), s.nativeStatus())
        assertEquals(State.NOT_APPLICABLE, s.shizukuStatus().state)
    }

    @Test
    fun auto_belowTheNativeSdk_selectsShizuku_whichIsNotYetAvailable() {
        val s = TouchSourceSelector.select(pad(), 35, true)
        assertEquals(Choice.SHIZUKU, s.choice)
        assertEquals(Reason.OS_DOES_NOT_DELIVER_PAD, s.reason)
        assertEquals(TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_NOT_YET_AVAILABLE), s.shizukuStatus())
        assertEquals(TouchSourceStatus.of(State.NOT_APPLICABLE, Reason.OS_DOES_NOT_DELIVER_PAD), s.nativeStatus())
    }

    @Test
    fun noNativeMinSdk_deliveryFollowsEnumeration() {
        assertEquals(Choice.NATIVE, TouchSourceSelector.select(pad(minSdk = null), 29, true).choice)
        val notEnumerated = TouchSourceSelector.select(pad(minSdk = null), 29, false)
        assertEquals(Choice.SHIZUKU, notEnumerated.choice)
        assertEquals(Reason.PAD_NOT_ENUMERATED, notEnumerated.reason)
    }

    @Test
    fun nativePin_neverSelectsShizuku() {
        assertEquals(Choice.NATIVE, TouchSourceSelector.select(pad(source = SourcePreference.NATIVE), 36, false).choice)
        val below = TouchSourceSelector.select(pad(source = SourcePreference.NATIVE), 35, false)
        assertEquals(Choice.NONE, below.choice)
        assertEquals(Reason.OS_DOES_NOT_DELIVER_PAD, below.reason)
        assertEquals(State.NOT_APPLICABLE, below.shizukuStatus().state)
        val chosenNative = TouchSourceSelector.select(pad(source = SourcePreference.NATIVE), 36, false)
        assertEquals(Reason.PROFILE_PINS_NATIVE, chosenNative.shizukuStatus().reason)
    }

    @Test
    fun shizukuPin_alwaysSelectsShizuku_andTheNativeSourceStaysOff() {
        val s = TouchSourceSelector.select(pad(source = SourcePreference.SHIZUKU), 36, true)
        assertEquals(Choice.SHIZUKU, s.choice)
        assertEquals(Reason.PROFILE_PINS_SHIZUKU, s.reason)
        assertEquals(TouchSourceStatus.of(State.NOT_APPLICABLE, Reason.PROFILE_PINS_SHIZUKU), s.nativeStatus())
        assertEquals(Reason.SHIZUKU_NOT_YET_AVAILABLE, s.shizukuStatus().reason)
    }
}
