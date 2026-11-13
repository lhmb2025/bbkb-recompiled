package dev.bbkb.ime.core.engine

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.ImeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The primary engine starts on the loader thread from onCreate and is joined by its first user; a
 * failed start is not retried per call; creation only runs on the loader thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class NuanceSdkManagerStartTest {

    private val appField = ImeApplication::class.java.getDeclaredField("sInstance").apply { isAccessible = true }
    private var savedApp: Any? = null
    private lateinit var savedFactory: () -> NuanceSDK?
    private val created = AtomicInteger()

    @Before
    fun setUp() {
        savedApp = appField.get(null)
        // getInstance() gives up at once without an Application; any instance will do here,
        // the factory below never reads it.
        appField.set(null, mock(ImeApplication::class.java))
        savedFactory = NuanceSDKManager.primaryFactory
        // Inline: dispose (an engine-thread op) and the creation (a loader op) run here, on the
        // test thread. The tests about threads switch it off.
        EngineThreads.setInlineForTests(true)
        NuanceSDKManager.resetForTests()
    }

    @After
    fun tearDown() {
        EngineThreads.setInlineForTests(true)
        NuanceSDKManager.primaryFactory = savedFactory
        NuanceSDKManager.resetForTests()
        NuanceSDKManager.setPrimaryAutoCommitHandler(null)
        EngineThreads.setInlineForTests(false)
        appField.set(null, savedApp)
    }

    @Test
    fun aFailedStartIsNotRetriedOnEveryCall() {
        NuanceSDKManager.primaryFactory = { created.incrementAndGet(); null }
        repeat(5) { assertNull(NuanceSDKManager.getInstance()) }
        assertEquals("a touch burst must not re-run the native init per sample", 1, created.get())
    }

    @Test
    fun theFirstUserJoinsTheBackgroundStart() {
        EngineThreads.setInlineForTests(false)
        val engine = mock(NuanceSDK::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NuanceSDKManager.primaryFactory = {
            created.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            engine
        }
        NuanceSDKManager.startPrimaryAsync()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Thread { Thread.sleep(100); release.countDown() }.start()
        // Off the main thread: a main-thread join is refused (see the test below).
        val joined = AtomicReference<NuanceSDK?>()
        val user = Thread { joined.set(NuanceSDKManager.getInstance()) }
        user.start()
        user.join(10_000)
        assertSame(engine, joined.get())
        assertEquals("the joining caller must not start a second engine", 1, created.get())
    }

    @Test
    fun theEngineIsCreatedOnTheLoaderThreadWhoeverAsks() {
        EngineThreads.setInlineForTests(false)
        val engine = mock(NuanceSDK::class.java)
        val creator = AtomicReference<String>()
        NuanceSDKManager.primaryFactory = { creator.set(Thread.currentThread().name); engine }
        val got = AtomicReference<NuanceSDK?>()
        val user = Thread({ got.set(NuanceSDKManager.getInstance()) }, "SomePoolThread")
        user.start()
        user.join(10_000)
        assertSame(engine, got.get())
        assertEquals("EngineLoader", creator.get())
    }

    @Test
    fun aMainThreadJoinIsRefusedInDebugBuilds() {
        EngineThreads.setInlineForTests(false)
        NuanceSDKManager.primaryFactory = { created.incrementAndGet(); mock(NuanceSDK::class.java) }
        try {
            NuanceSDKManager.getInstance()
            fail("the main thread must not wait for the engine start")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(0, created.get())
    }

    @Test
    fun anAlreadyPublishedEngineIsReturnedOnTheMainThread() {
        val engine = mock(NuanceSDK::class.java)
        NuanceSDKManager.primaryFactory = { engine }
        assertSame(engine, NuanceSDKManager.getInstance())   // inline: created here
        EngineThreads.setInlineForTests(false)
        assertSame(engine, NuanceSDKManager.getInstance())   // main thread, no wait: allowed
    }

    @Test
    fun theAutoCommitHandlerReachesAnEngineCreatedLater() {
        val engine = mock(NuanceSDK::class.java)
        NuanceSDKManager.primaryFactory = { engine }
        val handler = NuanceSDK.AutoCommitCallback { }
        NuanceSDKManager.setPrimaryAutoCommitHandler(handler)
        NuanceSDKManager.getInstance()
        verify(engine).setAutoCommitCallbackHandler(handler)
    }
}
