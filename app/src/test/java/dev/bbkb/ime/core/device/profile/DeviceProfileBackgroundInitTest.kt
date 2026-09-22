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
 * `DeviceProfile.initialize` moved off the IME's `onCreate` main thread (~250 ms: a device scan,
 * the active-config XML parse and a blocking pref read). The only thing that makes that safe is
 * that every reader joins the load before it can observe the profile — otherwise the first
 * reader takes `current()`'s auto-detect fallback, which on a KEY2 is the *wrong shape* (VKB),
 * publishes it, and the real profile lands on top of it some milliseconds later.
 *
 * These pin the join. The observable is the occurrence counter on
 * `deviceProfile.initializeForDevice`: if the reader raced the load, the load would not yet have
 * run when the reader returned.
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
        // The profile and its captured application context are process statics that outlive this
        // class under Robolectric's shared sandbox classloader; leave the no-context profile the
        // other DeviceProfile suites expect to find.
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
     * `KeyEventProcessor` treats `appContext() == null` as "initialize has never run" and fires a
     * targeted `initializeForDevice` when it is. A first hardware key arriving while the
     * background load was still in flight would have met exactly that, and paid for a second full
     * profile build mid-keystroke — so `appContext()` has to join too.
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

    /**
     * Semantics preserved: the synchronous `initialize()` this replaced rebuilt the profile every
     * time it was called (the settings activity relies on that — it re-initializes on every
     * launch so an imported config takes effect). Two sequential starts are two builds.
     */
    @Test
    fun aSecondStartAfterTheFirstCompletedRebuildsJustAsInitializeDid() {
        DeviceProfile.startInitialize(null, null)
        DeviceProfile.current()
        DeviceProfile.startInitialize(null, null)
        DeviceProfile.current()

        assertEquals(2, StartupTiming.occurrences(initPhase))
    }
}
