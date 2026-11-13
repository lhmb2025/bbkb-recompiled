package dev.bbkb.ime.core.engine

import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.WordInfo
import dev.bbkb.ime.core.keyevent.InputEvent
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyChar
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FIFO on the engine thread orders the main thread's key feeds against suggestion requests. Runs on
 * the real engine looper; the mock engine records the order the engine thread called it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class NuanceSDKDictionaryBridgeFifoTest {

    private lateinit var sdk: NuanceSDK
    private lateinit var bridge: NuanceSDKDictionaryBridge
    private lateinit var tracker: ComposingTextTracker
    private lateinit var settingsStatic: MockedStatic<SettingsManager>

    @Before
    fun setUp() {
        EngineThreads.setInlineForTests(false)
        val sm = mock(SettingsManager::class.java)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        sdk = mock(NuanceSDK::class.java)
        `when`(sdk.language).thenReturn(arrayOf(Locale.US))
        `when`(sdk.selectionListSize).thenReturn(1)
        `when`(sdk.getSelectionListWord(0)).thenAnswer { WordInfo().apply { word = "help" } }

        // The real constructor loads language packs; allocate the shell and inject the engine.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        bridge = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, NuanceSDKDictionaryBridge::class.java) as NuanceSDKDictionaryBridge
        NuanceSDKDictionaryBridge::class.java.getDeclaredField("nuanceSdk")
            .apply { isAccessible = true }.set(bridge, sdk)

        // A tracker over a fixed engine posts its feeds to that engine, as the IME's does.
        tracker = ComposingTextTracker(sdk)
    }

    @After
    fun tearDown() {
        idleEngine()
        settingsStatic.close()
    }

    private fun idleEngine() = shadowOf(EngineThreads.engineLooper()).idle()

    private fun type(text: String) {
        for (c in text) tracker.processInputEvent(InputEvent.createTouchEvent(c.code, 0, 0, false))
    }

    /** Holds the engine thread until [block] has queued everything, so nothing runs early. */
    private fun withEngineThreadParked(block: () -> Unit) {
        val release = CountDownLatch(1)
        val parked = CountDownLatch(1)
        EngineThreads.engineHandler().post { parked.countDown(); release.await(5, TimeUnit.SECONDS) }
        parked.await(5, TimeUnit.SECONDS)
        try {
            block()
        } finally {
            release.countDown()
        }
    }

    /** What InputLogic does: take the snapshot on the main thread, run the request as an op. */
    private fun postRequest() {
        val snapshot = ComposingSnapshot.of(tracker)
        EngineThreads.post { bridge.generateSuggestions(snapshot, null, null, 0) }
    }

    @Test
    fun aFeedPostedBeforeARequestReachesTheEngineFirst() {
        withEngineThreadParked {
            type("h")       // the feed: queued on the engine thread
            postRequest()   // the request: queued after it
        }
        idleEngine()

        // setContextBuffer is the request's first engine call, buildSelectionList its read.
        val order = inOrder(sdk)
        order.verify(sdk).processKeyBySymbol('h')
        order.verify(sdk).setContextBuffer(anyString())
        order.verify(sdk).buildSelectionList()
    }

    @Test
    fun theRequestNeverReplaysTextTypedAfterItWasPosted() {
        withEngineThreadParked {
            type("he")
            postRequest()   // made for "he"
            type("l")       // typed while the request waits in the queue
        }
        idleEngine()

        // The request replays its own snapshot ("he") before the build and nothing after it, so
        // 'l' reaches the engine once.
        verify(sdk, times(1)).processKeyBySymbol('l')
        val order = inOrder(sdk)
        order.verify(sdk).buildSelectionList()
        order.verify(sdk).processKeyBySymbol('l')
        // Two clears: the first key's, and the request's own before its replay.
        verify(sdk, times(2)).clear()
        // h and e: one feed each from typing, one each from the request's pre-build replay.
        verify(sdk, times(2)).processKeyBySymbol('h')
        verify(sdk, times(2)).processKeyBySymbol('e')
        verify(sdk, times(5)).processKeyBySymbol(anyChar())
        assertEquals("hel", tracker.composingText)
    }
}
