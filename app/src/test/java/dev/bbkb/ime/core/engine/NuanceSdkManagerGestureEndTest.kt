package dev.bbkb.ime.core.engine

import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.Xt9KdbVariant
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ImeApplication
import dev.bbkb.ime.core.suggestion.SuggestionEngine
import dev.bbkb.ime.core.suggestion.SuggestionRequestQueue
import dev.bbkb.ime.core.textinput.InputLogic
import dev.bbkb.ime.keyboard.internal.EngineTouch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `touchEnd` runs on the engine thread, yet the main thread sees "a swipe word may be coming" from
 * the moment it posts the op (with suggestions off, a swipe commits only through the request that
 * check lets through), and the request posted after the op runs after it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class NuanceSdkManagerGestureEndTest {

    private val appField = ImeApplication::class.java.getDeclaredField("sInstance").apply { isAccessible = true }
    private var savedApp: Any? = null
    private lateinit var savedFactory: () -> NuanceSDK?

    /** Mockito's any() returns null, which Kotlin rejects for the non-null callback parameter. */
    private fun <T> anyCallback(): T = Mockito.any()

    private fun counter(name: String): AtomicInteger =
        NuanceSDKManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null) as AtomicInteger

    /** The seqs and the posted count are process-wide; every test starts and ends at zero. */
    private fun resetGestureState() {
        counter("gestureDepositSeq").set(0)
        counter("gestureConsumedSeq").set(0)
        counter("pendingGestureEnds").set(0)
    }

    @Before
    fun setUp() {
        savedApp = appField.get(null)
        appField.set(null, mock(ImeApplication::class.java))
        savedFactory = NuanceSDKManager.primaryFactory
        EngineThreads.setInlineForTests(true)
        NuanceSDKManager.resetForTests()
        resetGestureState()
        EngineTouch.resetForTests()
    }

    @After
    fun tearDown() {
        EngineThreads.setInlineForTests(true)
        NuanceSDKManager.primaryFactory = savedFactory
        NuanceSDKManager.resetForTests()
        EngineThreads.setInlineForTests(false)
        appField.set(null, savedApp)
        resetGestureState()
    }

    @Test
    fun pendingFromThePostUntilTheOpResolvesWithNoDeposit() {
        assertFalse(NuanceSDKManager.isGesturePending())
        NuanceSDKManager.noteGestureEndPosted()
        assertTrue("posted and not yet run: a word may be coming", NuanceSDKManager.isGesturePending())
        assertFalse("the bridge's form sees only deposits", NuanceSDKManager.isGestureDepositPending())
        NuanceSDKManager.noteGestureEndResolved()
        assertFalse("rejected gesture: nothing is pending", NuanceSDKManager.isGesturePending())
    }

    @Test
    fun aDepositKeepsItPendingUntilItsSeqIsConsumed() {
        Mockito.mockStatic(Xt9KdbVariant::class.java).use { native ->
            native.`when`<Int> { Xt9KdbVariant.rankSeq() }.thenReturn(7)
            native.`when`<Int> { Xt9KdbVariant.recognizerUnavailable() }.thenReturn(-1)
            NuanceSDKManager.noteGestureEndPosted()
            NuanceSDKManager.noteGestureDeposit()
            NuanceSDKManager.noteGestureEndResolved()
        }
        assertTrue(NuanceSDKManager.isGesturePending())
        assertTrue(NuanceSDKManager.isGestureDepositPending())
        assertTrue(NuanceSDKManager.consumeGestureSeq(7))
        assertFalse(NuanceSDKManager.isGesturePending())
    }

    @Test
    fun aTouchEndPostedBeforeARequestRunsBeforeIt() {
        val sdk = mock(NuanceSDK::class.java)
        doReturn(false).`when`(sdk).touchEnd(anyLong(), anyFloat(), anyFloat(), anyLong())
        NuanceSDKManager.primaryFactory = { sdk }
        NuanceSDKManager.getInstance()          // inline: published here
        EngineThreads.setInlineForTests(false)  // from now on the real engine looper

        val ime = mock(BlackBerryIME::class.java)
        val queue = SuggestionRequestQueue(ime, mock(InputLogic::class.java))
        val callback = mock(SuggestionEngine.SuggestionCallback::class.java)
        val release = CountDownLatch(1)
        val parked = CountDownLatch(1)
        try {
            // Park the engine thread so both are queued before either runs.
            EngineThreads.engineHandler().post { parked.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(parked.await(5, TimeUnit.SECONDS))
            try {
                // A lift-off is posted only for the contact that owns the swipe session.
                EngineTouch.start("test", 0, 10, 20, 10L)
                EngineTouch.end("test", 0, 10, 20, 30L)
                queue.sendToWorkerThread(42, true, callback)
                assertTrue("main sees the swipe as pending as soon as it posts the op",
                        NuanceSDKManager.isGesturePending())
            } finally {
                release.countDown()
            }
            shadowOf(EngineThreads.engineLooper()).idle()

            val order = inOrder(sdk, ime)
            order.verify(sdk).touchEnd(eq(0L), eq(10f), eq(20f), eq(30L))
            order.verify(ime).runSuggestionRequest(eq(42), anyCallback())
            assertFalse("the op ran and the engine rejected the gesture", NuanceSDKManager.isGesturePending())
        } finally {
            queue.shutdown()
        }
    }
}
