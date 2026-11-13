package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.textinput.InputLogic
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import dev.bbkb.ime.core.engine.EngineThreads
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The worker queue drops a queued request when a newer one arrives, unless it was sent with `keep`
 * (a sync update the main thread waits on, a gesture-commit prediction).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SuggestionRequestQueueCoalescingTest {
    /** Robolectric runs the engine thread for real; park it so all requests queue before any runs. */
    private fun <T> withEngineThreadParked(block: () -> T): T {
        val release = CountDownLatch(1)
        val parked = CountDownLatch(1)
        EngineThreads.engineHandler().post { parked.countDown(); release.await(5, TimeUnit.SECONDS) }
        parked.await(5, TimeUnit.SECONDS)
        try {
            return block()
        } finally {
            release.countDown()
        }
    }

    /** Mockito's any() returns null, which Kotlin rejects for the non-null callback parameter. */
    private fun <T> anyCallback(): T = Mockito.any()

    @Test
    fun aNewerRequestDropsQueuedSupersedingRequestsButNotKeptOnes() {
        val ime = mock(BlackBerryIME::class.java)
        val queue = SuggestionRequestQueue(ime, mock(InputLogic::class.java))
        val callback = mock(SuggestionEngine.SuggestionCallback::class.java)
        try {
            withEngineThreadParked {
                queue.sendToWorkerThread(10, callback)          // superseded by 11
                queue.sendToWorkerThread(11, callback)          // superseded by 12
                queue.sendToWorkerThread(12, true, callback)    // kept: someone waits on its reply
                queue.sendToWorkerThread(13, callback)          // newest superseding one: runs
            }
            shadowOf(EngineThreads.engineLooper()).idle()

            val order = inOrder(ime)
            order.verify(ime).runSuggestionRequest(eq(12), anyCallback())
            order.verify(ime).runSuggestionRequest(eq(13), anyCallback())
            verify(ime, never()).runSuggestionRequest(eq(10), anyCallback())
            verify(ime, never()).runSuggestionRequest(eq(11), anyCallback())
            verify(ime, times(2)).runSuggestionRequest(anyInt(), anyCallback())
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun aKeptRequestNeverDropsAnotherKeptRequest() {
        val ime = mock(BlackBerryIME::class.java)
        val queue = SuggestionRequestQueue(ime, mock(InputLogic::class.java))
        val callback = mock(SuggestionEngine.SuggestionCallback::class.java)
        try {
            withEngineThreadParked {
                queue.sendToWorkerThread(20, true, callback)
                queue.sendToWorkerThread(21, true, callback)
            }
            shadowOf(EngineThreads.engineLooper()).idle()

            val order = inOrder(ime)
            order.verify(ime).runSuggestionRequest(eq(20), anyCallback())
            order.verify(ime).runSuggestionRequest(eq(21), anyCallback())
        } finally {
            queue.shutdown()
        }
    }
}
