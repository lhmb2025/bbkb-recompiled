package dev.bbkb.ime.core.device.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.shared.StartupTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `DeviceProfile.initialize` runs off the main thread, so every reader must join the load first;
 * otherwise it takes `current()`'s auto-detect fallback (VKB on a CKB phone) and publishes it. The
 * observable is the occurrence counter on `deviceProfile.initializeForDevice`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceProfileBackgroundInitTest {

    private val initPhase = "deviceProfile.initializeForDevice"

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceProfile.clearPendingInitForTest()
        StartupTiming.resetForTest()
    }

    @After
    fun tearDown() {
        DeviceProfile.clearPendingInitForTest()
        // The profile and its context are process statics shared across suites under Robolectric;
        // leave the no-context profile the other DeviceProfile suites expect.
        DeviceProfile.initialize(null)
    }

    @Test
    fun currentJoinsTheBackgroundLoadRatherThanPublishingTheFallbackProfile() {
        DeviceProfile.startInitialize(null, null)

        val profile = DeviceProfile.current()

        assertNotNull(profile)
        assertEquals(
            "current() returned before the background load had run, so it can only have"
                    + " returned the auto-detect fallback",
            1, StartupTiming.occurrences(initPhase)
        )
    }

    /**
     * `KeyEventProcessor` treats `appContext() == null` as "never initialized" and starts its own
     * build, so `appContext()` must join too or a first key during the load builds twice.
     */
    @Test
    fun appContextJoinsTheBackgroundLoadSoTheKeyPathSeesAnInitializedProfile() {
        DeviceProfile.startInitialize(context, null)

        val appContext = DeviceProfile.appContext()

        assertNotNull(
            "appContext() came back null mid-load; the key path would have re-initialized",
            appContext
        )
        assertSame(context.applicationContext, appContext)
        assertEquals(1, StartupTiming.occurrences(initPhase))
    }

    /** The profile the joined load published is the one every later reader keeps seeing. */
    @Test
    fun theJoinedProfileIsTheOneSubsequentReadsReturn() {
        DeviceProfile.startInitialize(null, null)

        val first = DeviceProfile.current()
        val second = DeviceProfile.current()

        assertSame(first, second)
        assertEquals("no reader may trigger a second build", 1, StartupTiming.occurrences(initPhase))
    }

    /** Each start rebuilds the profile: settings re-initializes on launch so an imported config applies. */
    @Test
    fun aSecondStartAfterTheFirstCompletedRebuildsJustAsInitializeDid() {
        DeviceProfile.startInitialize(null, null)
        DeviceProfile.current()
        DeviceProfile.startInitialize(null, null)
        DeviceProfile.current()

        assertEquals(2, StartupTiming.occurrences(initPhase))
    }
}
