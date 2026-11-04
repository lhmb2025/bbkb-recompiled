package dev.bbkb.ime.core.engine

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.ImeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audit P-6 / F-7: the primary engine is started on a worker from onCreate and joined by its first
 * user; a failed start is not retried on every call.
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
        NuanceSDKManager.disposePrimary()
    }

    @After
    fun tearDown() {
        NuanceSDKManager.primaryFactory = savedFactory
        NuanceSDKManager.disposePrimary()
        NuanceSDKManager.setPrimaryAutoCommitHandler(null)
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
        val engine = mock(NuanceSDK::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        NuanceSDKManager.primaryFactory = {
            created.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            engine
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            NuanceSDKManager.startPrimaryAsync(executor)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Thread { Thread.sleep(100); release.countDown() }.start()
            assertSame(engine, NuanceSDKManager.getInstance())
            assertEquals("the joining caller must not start a second engine", 1, created.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aShutDownExecutorLeavesTheStartToTheFirstUser() {
        val engine = mock(NuanceSDK::class.java)
        NuanceSDKManager.primaryFactory = { created.incrementAndGet(); engine }
        val executor = Executors.newSingleThreadExecutor().apply { shutdown() }
        NuanceSDKManager.startPrimaryAsync(executor)   // rejected, must not throw
        assertSame(engine, NuanceSDKManager.getInstance())
        assertEquals(1, created.get())
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
