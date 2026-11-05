package dev.bbkb.ime.keyboard.internal

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.ImeApplication
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.engine.NuanceSDKManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wave 3 Phase B: what actually reaches the engine for a tap, a swipe, a contact the arbiter
 * takes, and two fingers at once. The ops run inline here, so the list is the engine's call
 * order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class EngineTouchOpsTest {

    private val appField = ImeApplication::class.java.getDeclaredField("sInstance").apply { isAccessible = true }
    private var savedApp: Any? = null
    private lateinit var savedFactory: () -> NuanceSDK?
    private val calls = mutableListOf<String>()

    private fun counter(name: String): AtomicInteger =
        NuanceSDKManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as AtomicInteger

    @Before
    fun setUp() {
        savedApp = appField.get(null)
        appField.set(null, mock(ImeApplication::class.java))
        savedFactory = NuanceSDKManager.primaryFactory
        EngineThreads.setInlineForTests(true)
        NuanceSDKManager.resetForTests()
        counter("pendingGestureEnds").set(0)
        EngineTouch.resetForTests()

        val sdk = mock(NuanceSDK::class.java)
        doAnswer { calls += "start(${it.arguments[0]})"; true }
            .`when`(sdk).touchStart(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { calls += "move(${it.arguments[0]})"; true }
            .`when`(sdk).touchMove(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { calls += "end(${it.arguments[0]})"; false }
            .`when`(sdk).touchEnd(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { calls += "cancel(${it.arguments[0]})"; true }
            .`when`(sdk).touchCancel(anyLong())
        NuanceSDKManager.primaryFactory = { sdk }
        NuanceSDKManager.getInstance()
    }

    @After
    fun tearDown() {
        EngineTouch.resetForTests()
        NuanceSDKManager.resetForTests()
        NuanceSDKManager.primaryFactory = savedFactory
        counter("pendingGestureEnds").set(0)
        EngineThreads.setInlineForTests(false)
        appField.set(null, savedApp)
    }

    @Test
    fun aTapReachesTheEngineAsAStartAndOneCancel() {
        EngineTouch.start("t", 0, 10, 10, 1L)
        EngineTouch.move("t", 0, 11, 10, 2L)
        EngineTouch.cancel("t/tap", 0L)             // the tap's own cancel
        EngineTouch.cancel("t/notSwipe", 0L)        // the lift that ends no swipe
        assertEquals(listOf("start(0)", "move(0)", "cancel(0)"), calls)
    }

    @Test
    fun withSwipingOffNothingReachesTheEngine() {
        // No start was posted for the contact.
        EngineTouch.move("t", 0, 11, 10, 2L)
        EngineTouch.move("t", 0, 12, 10, 3L)
        EngineTouch.cancel("t/tap", 0L)
        EngineTouch.end("t", 0, 12, 10, 4L)
        assertEquals(emptyList<String>(), calls)
        assertFalse("and no swipe is announced as pending", NuanceSDKManager.isGesturePending())
    }

    @Test
    fun aConsumedContactIsCancelledOnce() {
        EngineTouch.start("t", 0, 10, 10, 1L)
        EngineTouch.move("t", 0, 20, 10, 2L)
        // The arbiter takes the contact, then every later sample of it asks again.
        repeat(30) { EngineTouch.cancelAll("t/consumed", longArrayOf(0)) }
        assertEquals(listOf("start(0)", "move(0)", "cancel(0)"), calls)
    }

    @Test
    fun aStaleCancelNeverEndsANewerContactsSwipe() {
        EngineTouch.start("t", 0, 10, 10, 1L)       // first finger
        EngineTouch.start("t", 1, 500, 10, 2L)      // second finger starts a swipe
        EngineTouch.move("t", 1, 520, 10, 3L)
        EngineTouch.cancel("t/firstFingerLifts", 0L)
        EngineTouch.end("t/firstFingerLifts", 0, 10, 10, 4L)
        EngineTouch.move("t", 1, 540, 10, 5L)
        EngineTouch.end("t", 1, 560, 10, 6L)
        assertEquals(listOf("start(0)", "start(1)", "move(1)", "move(1)", "end(1)"), calls)
    }

    @Test
    fun aSecondFingerDuringASwipeLeavesItAlone() {
        EngineTouch.start("t", 0, 10, 10, 1L)
        EngineTouch.move("t", 0, 20, 10, 2L)
        // The on-screen keyboard cancels the tracking of any other finger on every one of its
        // events while a swipe is in flight.
        repeat(5) { EngineTouch.cancel("t/otherFinger", 1L) }
        EngineTouch.move("t", 0, 30, 10, 3L)
        EngineTouch.end("t", 0, 40, 10, 4L)
        assertEquals(listOf("start(0)", "move(0)", "move(0)", "end(0)"), calls)
    }

    @Test
    fun onlyAPostedLiftOffCountsAsAPendingSwipe() {
        EngineTouch.start("t", 0, 10, 10, 1L)
        EngineTouch.cancel("t", 0L)
        EngineTouch.end("t", 0, 10, 10, 2L)
        assertEquals("the count is not raised for a lift that is not posted",
                0, counter("pendingGestureEnds").get())
        EngineTouch.start("t", 0, 10, 10, 3L)
        EngineTouch.end("t", 0, 10, 10, 4L)
        assertTrue(calls.contains("end(0)"))
        assertEquals("and comes back down once the op has run", 0, counter("pendingGestureEnds").get())
    }
}
