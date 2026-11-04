package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** The process-wide dataset: release never blocks, preloads coalesce, a release is followed by a clean re-parse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojibaseSharedLifecycleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Holds tasks until [runAll]: lets a test see what was queued. */
    private class QueueExecutor : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            val now = tasks.toList()
            tasks.clear()
            now.forEach { it.run() }
        }
    }

    @Before
    fun setUp() = EmojibaseDataProvider.releaseShared()

    @After
    fun tearDown() = EmojibaseDataProvider.releaseShared()

    @Test
    fun preloadsCoalesceAndStopOnceLoaded() {
        val executor = QueueExecutor()
        EmojibaseDataProvider.preloadAsync(context, executor)
        EmojibaseDataProvider.preloadAsync(context, executor)
        EmojibaseDataProvider.preloadAsync(context, executor)
        assertEquals(1, executor.tasks.size)
        assertNull(EmojibaseDataProvider.peekShared())

        executor.runAll()
        val loaded = EmojibaseDataProvider.peekShared()
        assertNotNull(loaded)
        EmojibaseDataProvider.preloadAsync(context, executor)
        assertEquals("already loaded: nothing queued", 0, executor.tasks.size)

        EmojibaseDataProvider.releaseShared()
        assertNull(EmojibaseDataProvider.peekShared())
        EmojibaseDataProvider.preloadAsync(context, executor)
        assertEquals(1, executor.tasks.size)
        executor.runAll()
        assertNotSame("a release is followed by a fresh parse", loaded, EmojibaseDataProvider.peekShared())
    }

    @Test
    fun aRejectedPreloadDoesNotBlockTheNextOne() {
        EmojibaseDataProvider.preloadAsync(context) { throw java.util.concurrent.RejectedExecutionException("shut down") }
        val executor = QueueExecutor()
        EmojibaseDataProvider.preloadAsync(context, executor)
        assertEquals(1, executor.tasks.size)
        executor.runAll()
    }

    @Test
    fun theDefaultPreloadLoadsInTheBackground() {
        EmojibaseDataProvider.preloadAsync(context)
        val end = System.currentTimeMillis() + 10_000
        while (EmojibaseDataProvider.peekShared() == null && System.currentTimeMillis() < end) Thread.sleep(10)
        assertNotNull(EmojibaseDataProvider.peekShared())
    }

    @Test
    fun releaseDoesNotWaitForAParseInProgress() {
        val lock = EmojiTestEnv.field(EmojibaseDataProvider::class.java, "loadLock").get(null)!!
        val held = CountDownLatch(1)
        val done = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) {
                held.countDown()
                done.await(10, TimeUnit.SECONDS)
            }
        }
        holder.start()
        try {
            assertTrue(held.await(5, TimeUnit.SECONDS))
            val released = CountDownLatch(1)
            Thread { EmojibaseDataProvider.releaseShared(); released.countDown() }.start()
            assertTrue("releaseShared blocked on the parse lock", released.await(2, TimeUnit.SECONDS))
        } finally {
            done.countDown()
            holder.join()
        }
    }

    @Test
    fun getSharedLoadedReturnsOneInstanceUntilReleased() {
        val a = EmojibaseDataProvider.getSharedLoaded(context)
        assertSame(a, EmojibaseDataProvider.getSharedLoaded(context))
        EmojibaseDataProvider.releaseShared()
        val b = EmojibaseDataProvider.getSharedLoaded(context)
        assertNotSame(a, b)
        assertSame(b, EmojibaseDataProvider.peekShared())
    }
}
