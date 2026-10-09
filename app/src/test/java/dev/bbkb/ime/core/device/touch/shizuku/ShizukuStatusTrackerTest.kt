package dev.bbkb.ime.core.device.touch.shizuku

import android.content.ServiceConnection
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState.NOT_GRANTED
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState.NOT_INSTALLED
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState.NOT_RUNNING
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState.READY
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState.UNSUPPORTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UNSUPPORTED..READY ladder against a fake Shizuku, including the transitions Shizuku's
 * callbacks drive: binder received, binder dead, permission answered.
 */
class ShizukuStatusTrackerTest {

    /** Shizuku as the tests want it to be; the callback methods mimic what Shizuku does first. */
    private class FakeShizuku(override var sdkInt: Int = 34) : ShizukuFacade {
        var installed = true
        var alive = false
        var preV11 = false
        var granted = false
        var deniedForever = false
        var throwOnPermissionCheck = false
        val requested = ArrayList<Int>()
        var registered: ShizukuFacade.Listener? = null
        var setListenerCalls = 0
        var otherCalls = 0

        fun binderReceived() {
            alive = true
            registered?.onBinderReceived()
        }

        fun binderDead() {
            alive = false
            registered?.onBinderDead()
        }

        fun answer(requestCode: Int, allow: Boolean) {
            granted = allow
            registered?.onPermissionResult(requestCode, allow)
        }

        override fun isManagerInstalled(): Boolean { otherCalls++; return installed }
        override fun pingBinder(): Boolean { otherCalls++; return alive }
        override fun isPreV11(): Boolean { otherCalls++; check(alive); return preV11 }
        override fun isPermissionGranted(): Boolean {
            otherCalls++
            if (throwOnPermissionCheck || !alive) throw IllegalStateException("binder haven't been received")
            return granted
        }
        override fun isPermissionDeniedForever(): Boolean { otherCalls++; check(alive); return deniedForever }
        override fun requestPermission(requestCode: Int) { otherCalls++; check(alive); requested.add(requestCode) }
        override fun setListener(listener: ShizukuFacade.Listener?) {
            setListenerCalls++
            registered = listener
        }
        override fun bindUserService(connection: ServiceConnection) { otherCalls++ }
        override fun unbindUserService(connection: ServiceConnection, remove: Boolean) { otherCalls++ }
    }

    private val shizuku = FakeShizuku()
    private val changes = ArrayList<ShizukuTouchStatus>()
    private val tracker = ShizukuStatusTracker(shizuku) { changes.add(it) }

    private fun states() = changes.map { it.state }

    @Test
    fun belowApi24ItIsUnsupportedAndShizukuIsNeverTouched() {
        val old = FakeShizuku(sdkInt = 23).apply { alive = true; granted = true }
        val t = ShizukuStatusTracker(old) { changes.add(it) }
        t.attach()

        assertEquals(ShizukuTouchStatus(UNSUPPORTED, ShizukuTouchErrors.API_TOO_OLD), t.status)
        assertFalse(t.requestPermission(1))
        t.detach()
        assertEquals(0, old.setListenerCalls)
        assertEquals(0, old.otherCalls)
    }

    @Test
    fun noBinderAndNoManagerAppIsNotInstalled() {
        shizuku.installed = false
        tracker.attach()
        assertEquals(NOT_INSTALLED, tracker.status.state)
    }

    @Test
    fun noBinderWithTheManagerAppIsNotRunning() {
        tracker.attach()
        assertEquals(ShizukuTouchStatus(NOT_RUNNING), tracker.status)
        // Unchanged from the initial NOT_RUNNING, so nothing was reported.
        assertTrue(changes.isEmpty())
    }

    @Test
    fun aLiveBinderWithoutTheManagerAppCountsAsRunning() {
        // Sui (the Magisk flavour of Shizuku) provides the binder with no manager app installed.
        shizuku.installed = false
        shizuku.alive = true
        tracker.attach()
        assertEquals(NOT_GRANTED, tracker.status.state)
    }

    @Test
    fun aPreV11ServerIsUnsupported() {
        shizuku.alive = true
        shizuku.preV11 = true
        tracker.attach()
        assertEquals(ShizukuTouchStatus(UNSUPPORTED, ShizukuTouchErrors.SERVER_TOO_OLD), tracker.status)
    }

    @Test
    fun runningAndGrantedIsReady() {
        shizuku.alive = true
        shizuku.granted = true
        tracker.attach()
        assertEquals(ShizukuTouchStatus(READY), tracker.status)
        assertTrue(tracker.status.isAvailable)
        assertFalse(tracker.requestPermission(7))
        assertTrue(shizuku.requested.isEmpty())
    }

    @Test
    fun notGrantedAsksShizukuForPermission() {
        shizuku.alive = true
        tracker.attach()
        assertEquals(ShizukuTouchStatus(NOT_GRANTED), tracker.status)
        assertFalse(tracker.status.isAvailable)

        assertTrue(tracker.requestPermission(0x5B4B))
        assertEquals(listOf(0x5B4B), shizuku.requested)
    }

    @Test
    fun deniedForeverShowsNoDialog() {
        shizuku.alive = true
        shizuku.deniedForever = true
        tracker.attach()
        assertEquals(ShizukuTouchStatus(NOT_GRANTED, permissionDeniedForever = true), tracker.status)
        assertFalse(tracker.requestPermission(1))
        assertTrue(shizuku.requested.isEmpty())
    }

    @Test
    fun shizukuCallbacksWalkTheWholeLadder() {
        val answers = ArrayList<Pair<Int, Boolean>>()
        tracker.onPermissionResult = { code, granted -> answers.add(code to granted) }
        tracker.attach()
        assertEquals(NOT_RUNNING, tracker.status.state)

        shizuku.binderReceived()
        assertEquals(NOT_GRANTED, tracker.status.state)

        tracker.requestPermission(42)
        shizuku.answer(42, allow = false)
        assertEquals(NOT_GRANTED, tracker.status.state)

        shizuku.answer(42, allow = true)
        assertEquals(READY, tracker.status.state)

        shizuku.binderDead()        // Shizuku restarted (or was stopped)...
        assertEquals(NOT_RUNNING, tracker.status.state)

        shizuku.binderReceived()    // ...and came back with the grant intact.
        assertEquals(READY, tracker.status.state)

        assertEquals(listOf(NOT_GRANTED, READY, NOT_RUNNING, READY), states())
        assertEquals(listOf(42 to false, 42 to true), answers)
    }

    @Test
    fun theManagerAppBeingRemovedWhileStoppedIsSeenOnRefresh() {
        tracker.attach()
        shizuku.installed = false
        // An uninstall sends no Shizuku callback; a settings screen refreshes on resume.
        assertEquals(NOT_INSTALLED, tracker.refresh().state)
        assertEquals(listOf(NOT_INSTALLED), states())
    }

    @Test
    fun refreshReportsOnlyChanges() {
        shizuku.alive = true
        tracker.attach()
        tracker.refresh()
        tracker.refresh()
        assertEquals(listOf(NOT_GRANTED), states())
    }

    @Test
    fun aBinderThatDiesBetweenCallsReadsAsNotGrantedRatherThanCrashing() {
        shizuku.alive = true
        shizuku.throwOnPermissionCheck = true
        tracker.attach()
        assertEquals(NOT_GRANTED, tracker.status.state)
    }

    @Test
    fun attachIsIdempotentAndDetachUnregisters() {
        tracker.attach()
        tracker.attach()
        assertEquals(1, shizuku.setListenerCalls)
        assertTrue(shizuku.registered === tracker)

        tracker.detach()
        tracker.detach()
        assertEquals(2, shizuku.setListenerCalls)
        assertNull(shizuku.registered)
    }

    @Test
    fun theUnsupportedFacadeIsInert() {
        val facade = ShizukuFacade.Unsupported(23)
        val t = ShizukuStatusTracker(facade) {}
        t.attach()
        assertEquals(UNSUPPORTED, t.status.state)
        assertFalse(facade.pingBinder())
    }

    @Test
    fun statusDescribesItselfForLogs() {
        val status = ShizukuTouchStatus(
            ShizukuTouchState.STREAMING, "grab_failed:16",
            TouchDeviceInfo("/dev/input/event3", "touch_keypad", absX = AxisRange(0, 1079), absY = AxisRange(0, 599)),
        )
        assertEquals("STREAMING error=grab_failed:16 device=/dev/input/event3 'touch_keypad' absX=0..1079 absY=0..599",
            status.describe())
        assertTrue(status.isAvailable)
    }

    @Test
    fun retryBackoffDoublesFromAQuarterSecondToThirtySeconds() {
        val backoff = RetryBackoff()
        val delays = List(10) { backoff.nextDelayMs() }
        assertEquals(listOf(250L, 500L, 1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), delays)
        assertEquals(10, backoff.attempts)
        backoff.reset()
        assertEquals(250L, backoff.nextDelayMs())
        repeat(100) { backoff.nextDelayMs() }
        assertEquals(30000L, backoff.nextDelayMs())
    }
}
