package dev.bbkb.ime.core.textinput.composing

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.keyevent.InputEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A converter language (here Korean) shows the engine's composed text, so the tracker waits for
 * it in a bounded engine query (Wave 3 Phase A). When the engine thread is busy past the bound
 * (behind a language load, in production), the unconverted text stays and the feed still reaches
 * the engine afterwards. Runs on the REAL engine looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class ComposingTextTrackerConverterTimeoutTest {

    private lateinit var sdk: NuanceSDK
    private lateinit var tracker: ComposingTextTracker

    @Before
    fun setUp() {
        EngineThreads.setInlineForTests(false)
        ComposingTextTracker.setConverterQueryTimeoutForTests(100L)
        sdk = mock(NuanceSDK::class.java)
        `when`(sdk.buildHangul()).thenReturn("한")
        tracker = ComposingTextTracker(sdk)
        tracker.setConverterByName("HangulConverter")
    }

    @After
    fun tearDown() {
        ComposingTextTracker.setConverterQueryTimeoutForTests(0L)
        shadowOf(EngineThreads.engineLooper()).idle()
    }

    private fun typeA() = tracker.processInputEvent(InputEvent.createTouchEvent('a'.code, 0, 0, false))

    @Test
    fun anIdleEngineThreadAnswersWithTheComposedText() {
        typeA()
        assertEquals("한", tracker.composingText)
    }

    @Test
    fun aTimedOutConverterQueryKeepsTheUnconvertedText_andTheFeedStillRuns() {
        val release = CountDownLatch(1)
        val parked = CountDownLatch(1)
        EngineThreads.engineHandler().post { parked.countDown(); release.await(5, TimeUnit.SECONDS) }
        parked.await(5, TimeUnit.SECONDS)
        try {
            typeA()   // waits 100 ms for the busy engine thread, then falls back
            assertEquals("a", tracker.composingText)
        } finally {
            release.countDown()
        }
        shadowOf(EngineThreads.engineLooper()).idle()

        // The op was not cancelled: the feed reached the engine once the thread was free, and
        // its late answer did not overwrite the text.
        verify(sdk).processKeyBySymbol('a')
        assertEquals("a", tracker.composingText)
    }
}
