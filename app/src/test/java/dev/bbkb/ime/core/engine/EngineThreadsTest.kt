package dev.bbkb.ime.core.engine

import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The two engine-capable threads: FIFO across every Handler on the engine looper, inline
 * re-entry, bounded queries, the loader thread's identity, and the debug thread assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EngineThreadsTest {

    @Before
    fun setUp() {
        EngineThreads.setInlineForTests(false)
    }

    @After
    fun tearDown() {
        EngineThreads.setInlineForTests(false)
        shadowOf(EngineThreads.engineLooper()).idle()
    }

    private fun idleEngine() = shadowOf(EngineThreads.engineLooper()).idle()

    @Test
    fun postsRunInPostOrderAcrossHandlersOnTheEngineLooper() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val other = Handler(EngineThreads.engineLooper()) { msg -> order += "msg${msg.what}"; true }
        EngineThreads.post { order += "post1" }
        other.sendEmptyMessage(1)
        EngineThreads.post { order += "post2" }
        other.sendEmptyMessage(2)
        EngineThreads.post { order += "post3" }
        idleEngine()
        assertEquals(listOf("post1", "msg1", "post2", "msg2", "post3"), order.toList())
    }

    @Test
    fun opsRunOnTheEngineThread() {
        val name = AtomicReference<String>()
        val flagged = AtomicBoolean()
        EngineThreads.post {
            name.set(Thread.currentThread().name)
            flagged.set(EngineThreads.onEngineThread() && EngineThreads.onEngineCapableThread())
        }
        idleEngine()
        assertEquals("EngineThread", name.get())
        assertTrue(flagged.get())
        assertFalse("the test thread is not the engine thread", EngineThreads.onEngineThread())
    }

    @Test
    fun aPostFromTheEngineThreadRunsInline() {
        val order = Collections.synchronizedList(mutableListOf<String>())
        EngineThreads.post {
            order += "outer-start"
            EngineThreads.post { order += "inner" }
            order += "outer-end"
        }
        idleEngine()
        assertEquals(listOf("outer-start", "inner", "outer-end"), order.toList())
    }

    @Test
    fun queryReturnsTheOpsValue() {
        val result = runOffMain { EngineThreads.query(Callable { 6 * 7 }, 2_000L, -1) }
        assertEquals(42, result)
    }

    @Test
    fun queryReturnsTheFallbackWhenTheOpOutlivesTheTimeout() {
        val release = CountDownLatch(1)
        val ran = CountDownLatch(1)
        val result = runOffMain {
            EngineThreads.query(Callable { release.await(5, TimeUnit.SECONDS); ran.countDown(); 1 }, 50L, -1)
        }
        assertEquals(-1, result)
        // The timed-out op is not cancelled: it still runs once unblocked.
        release.countDown()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun queryOnTheEngineThreadRunsInline() {
        val result = AtomicReference<Int>()
        EngineThreads.post {
            result.set(EngineThreads.query(Callable { if (EngineThreads.onEngineThread()) 1 else 0 }, 10L, -1))
        }
        idleEngine()
        assertEquals(1, result.get())
    }

    @Test
    fun loadRunsOnTheLoaderThread() {
        val (name, onLoader) = EngineThreads.load(Callable {
            Thread.currentThread().name to EngineThreads.onLoaderThread()
        }).get(5, TimeUnit.SECONDS)
        assertEquals("EngineLoader", name)
        assertTrue(onLoader)
        assertFalse(EngineThreads.onLoaderThread())
    }

    @Test
    fun aLoadFromTheLoaderThreadRunsInline() {
        val inner = EngineThreads.load(Callable {
            val nested = EngineThreads.load(Callable { Thread.currentThread().name })
            // Already complete: queueing it behind ourselves would deadlock this get().
            nested.isDone to nested.get()
        }).get(5, TimeUnit.SECONDS)
        assertTrue(inner.first)
        assertEquals("EngineLoader", inner.second)
    }

    @Test
    fun aThrowingLoaderTaskDoesNotReplaceTheLoaderThread() {
        val before = EngineThreads.load(Callable { Thread.currentThread() }).get(5, TimeUnit.SECONDS)
        EngineThreads.loader().execute { throw IllegalStateException("boom") }
        val after = EngineThreads.load(Callable { Thread.currentThread() }).get(5, TimeUnit.SECONDS)
        assertTrue("the loader thread must never exit", before === after)
        EngineThreads.loader().shutdown()   // ignored
        assertFalse(EngineThreads.loader().isShutdown)
    }

    @Test
    fun theAssertionThrowsOnTheTestThreadAndPassesOnTheEngineThreads() {
        try {
            EngineThreads.assertEngineCapable("probe")
            fail("the test (main) thread is not engine-capable")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("probe called on"))
            assertTrue(e.message!!.contains("EngineThread or EngineLoader only"))
        }
        val onEngine = AtomicReference<Throwable?>()
        EngineThreads.post {
            try { EngineThreads.assertEngineCapable("probe") } catch (t: Throwable) { onEngine.set(t) }
        }
        idleEngine()
        assertNull(onEngine.get())
        val onLoader = EngineThreads.load(Callable {
            try { EngineThreads.assertEngineCapable("probe"); null } catch (t: Throwable) { t }
        }).get(5, TimeUnit.SECONDS)
        assertNull(onLoader)
    }

    @Test
    fun inlineModeRunsEverythingOnTheCallerAndSilencesTheAssertions() {
        EngineThreads.setInlineForTests(true)
        val ran = mutableListOf<String>()
        EngineThreads.post { ran += "post" }
        EngineThreads.postAtFront { ran += "front" }
        assertEquals(3, EngineThreads.query(Callable { 3 }, 1L, -1))
        assertTrue(EngineThreads.load(Runnable { ran += "load" }).isDone)
        assertEquals(listOf("post", "front", "load"), ran)
        EngineThreads.assertEngineCapable("probe")
        EngineThreads.assertNotMain("probe")
    }

    @Test
    fun assertNotMainThrowsOnlyOnTheMainThread() {
        assertTrue(Looper.getMainLooper().isCurrentThread)
        try {
            EngineThreads.assertNotMain("probe")
            fail("the test thread is the main thread")
        } catch (expected: IllegalStateException) {
        }
        val off = EngineThreads.load(Callable {
            try { EngineThreads.assertNotMain("probe"); null } catch (t: Throwable) { t }
        }).get(5, TimeUnit.SECONDS)
        assertNull(off)
    }

    /**
     * Runs [block] on a plain thread (query from the main thread would be legal, but the
     * engine looper must be free to run while the caller waits) and returns its result.
     */
    private fun <T> runOffMain(block: () -> T): T {
        val out = AtomicReference<T>()
        val t = Thread { out.set(block()) }
        t.start()
        t.join(10_000)
        assertFalse("the query did not return", t.isAlive)
        return out.get()
    }
}
