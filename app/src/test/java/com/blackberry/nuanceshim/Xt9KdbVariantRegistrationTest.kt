package com.blackberry.nuanceshim

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.KeypadKdbs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * [Xt9KdbVariant]'s registry calls with the owned library replaced by a recording bridge (the
 * library cannot load on the JVM): the device variant [Xt9KdbVariant.apply] builds from the
 * app's assets (its keypad geometry and the built-in letter layouts), and the generated variants a letter layout registers ([Xt9KdbVariant.registerGenerated])
 * and selects ([Xt9KdbVariant.selectVariant]) on top of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Xt9KdbVariantRegistrationTest {

    /** What the native registry was told, in order. */
    private class Recorder : Xt9KdbVariant.NativeBridge {
        val registered: MutableList<Pair<String, Int>> = Collections.synchronizedList(ArrayList())
        val selected: MutableList<String> = Collections.synchronizedList(ArrayList())
        var status = 0

        override fun register(variant: String, xml: ByteArray): Int {
            registered += variant to xml.size
            return status
        }

        override fun setVariant(variant: String) {
            selected += variant
        }

        fun registeredUnder(variant: String) = registered.count { it.first == variant }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val native = Recorder()

    @Before
    fun setUp() {
        Xt9KdbVariant.setNativeBridgeForTest(native)
    }

    @After
    fun tearDown() {
        Xt9KdbVariant.setNativeBridgeForTest(null)
    }

    private fun applied(variant: String?) {
        Xt9KdbVariant.apply(context, variant)
        Xt9KdbVariant.awaitReady(5000)
    }

    @Test
    fun withoutTheLibrary_nothingIsRegisteredOrSelected() {
        Xt9KdbVariant.setNativeBridgeForTest(null)
        assertFalse(Xt9KdbVariant.isAvailable())
        assertFalse(Xt9KdbVariant.registerGenerated("layout:x", byteArrayOf(1)))
        Xt9KdbVariant.selectVariant("layout:x")
        assertNull(Xt9KdbVariant.applied())
        assertTrue(native.registered.isEmpty() && native.selected.isEmpty())
    }

    @Test
    fun applyBuildsTheDevicesKeypadKdbsAndSelectsIt() {
        applied("athena")
        assertEquals("one per engine id the app builds", KeypadKdbs.SHIPPED.size, native.registeredUnder("athena"))
        assertEquals(listOf("athena"), native.selected)
        assertEquals("athena", Xt9KdbVariant.deviceVariant())
        assertEquals("athena", Xt9KdbVariant.applied())
    }

    @Test
    fun aVariantWithNoGeometryFallsBackToTheGenericKeypad() {
        applied("venice")
        assertEquals(KeypadKdbs.GENERIC, Xt9KdbVariant.deviceVariant())
        assertEquals(listOf(KeypadKdbs.GENERIC), native.selected)
        assertEquals(KeypadKdbs.SHIPPED.size, native.registeredUnder(KeypadKdbs.GENERIC))
    }

    @Test
    fun noVariant_isTheGenericKeypad() {
        applied(null)
        assertEquals(KeypadKdbs.GENERIC, Xt9KdbVariant.deviceVariant())
        applied("")
        assertEquals("nothing built again", KeypadKdbs.SHIPPED.size, native.registered.size)
    }

    @Test
    fun aVariantWithNoGeometryAskedForAgain_keepsALayoutSelection() {
        applied("venice")
        Xt9KdbVariant.selectVariant("layout:swap")
        applied("venice")
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals(listOf(KeypadKdbs.GENERIC, "layout:swap"), native.selected)
    }

    @Test
    fun registerGenerated_handsTheBytesOverAndReportsTheOutcome() {
        assertTrue(Xt9KdbVariant.registerGenerated("layout:swap", ByteArray(10)))
        assertEquals(listOf("layout:swap" to 10), native.registered)
        native.status = -1
        assertFalse("the native side refused", Xt9KdbVariant.registerGenerated("layout:swap", ByteArray(10)))
    }

    @Test
    fun registerGenerated_refusesWhatTheNativeSideCannotKey() {
        assertFalse(Xt9KdbVariant.registerGenerated("", ByteArray(10)))
        assertFalse(Xt9KdbVariant.registerGenerated("layout:x", ByteArray(0)))
        // "layout:" plus the longest id a layout may have fits; one byte more than the native
        // field does not, and is refused rather than cut short.
        val longestId = "a".repeat(64)
        assertTrue(Xt9KdbVariant.registerGenerated("layout:$longestId", ByteArray(1)))
        assertFalse(Xt9KdbVariant.registerGenerated("x".repeat(Xt9KdbVariant.MAX_VARIANT_NAME_BYTES + 1), ByteArray(1)))
        assertTrue(Xt9KdbVariant.registerGenerated("x".repeat(Xt9KdbVariant.MAX_VARIANT_NAME_BYTES), ByteArray(1)))
    }

    @Test
    fun selectVariant_switchesOnlyOnAChange_andLeavesTheDeviceVariantAlone() {
        applied("athena")
        Xt9KdbVariant.selectVariant("layout:swap")
        Xt9KdbVariant.selectVariant("layout:swap")
        assertEquals(listOf("athena", "layout:swap"), native.selected)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
        assertEquals("athena", Xt9KdbVariant.deviceVariant())
        Xt9KdbVariant.selectVariant(null)
        assertEquals("", Xt9KdbVariant.applied())
    }

    @Test
    fun applyingTheSameDeviceVariantAgain_keepsALayoutSelection() {
        applied("athena")
        Xt9KdbVariant.selectVariant("layout:swap")
        val before = native.registered.size
        applied("athena")
        assertEquals("nothing registered again", before, native.registered.size)
        assertEquals("layout:swap", Xt9KdbVariant.applied())
    }

    @Test
    fun aNewDeviceVariant_isSelectedAndTheListenerToldOnTheRegistrationThread() {
        val calls = Collections.synchronizedList(ArrayList<String>())
        Xt9KdbVariant.setDeviceVariantListener(Runnable { calls += Thread.currentThread().name + "|" + Xt9KdbVariant.deviceVariant() })
        applied("")
        applied("athena")
        applied("athena")
        assertEquals(listOf("Xt9KdbVariantApply|keypad", "Xt9KdbVariantApply|athena"), calls)
        assertEquals("athena", Xt9KdbVariant.applied())
    }

    @Test
    fun submittedJobsRunInOrderAfterEarlierApplies_andAwaitReadyWaitsForTheLast() {
        val order = Collections.synchronizedList(ArrayList<String>())
        Xt9KdbVariant.apply(context, "athena")
        Xt9KdbVariant.submit { order += "first:" + Xt9KdbVariant.deviceVariant() }
        Xt9KdbVariant.submit {
            Thread.sleep(50)
            order += "second"
        }
        Xt9KdbVariant.awaitReady(5000)
        assertEquals(listOf("first:athena", "second"), order)
    }
}
