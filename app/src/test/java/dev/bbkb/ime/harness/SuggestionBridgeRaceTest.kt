package dev.bbkb.ime.harness

import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge
import dev.bbkb.ime.core.settings.util.SettingsManager
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.WordInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins the worker's guard against main-thread engine-buffer writes during a suggestion read,
 * both ways: an undisturbed read is not retried, and a disturbed one is re-read once (never
 * returned empty, which makes SuggestionCoordinator merge with stale suggestions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionBridgeRaceTest {

    private lateinit var h: PipelineHarness
    private lateinit var settingsStatic: org.mockito.MockedStatic<SettingsManager>
    private lateinit var sdk: NuanceSDK
    private lateinit var bridge: NuanceSDKDictionaryBridge

    /** Simulate an input-buffer write: bumps the epoch the shim advances on clear/processKeyBySymbol/… */
    private fun bumpEpoch() {
        val f = NuanceSDK::class.java.getDeclaredField("sInputBufferEpoch")
        f.isAccessible = true
        f.setLong(null, f.getLong(null) + 1L)
    }

    private fun epoch(): Long = NuanceSDK.getInputBufferEpoch()

    @Before
    fun setUp() {
        h = PipelineHarness()
        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        sdk = mock(NuanceSDK::class.java)
        `when`(sdk.language).thenReturn(arrayOf(Locale.US))
        `when`(sdk.selectionListSize).thenReturn(1)
        `when`(sdk.getSelectionListWord(0)).thenAnswer {
            WordInfo().apply { word = "help" }
        }

        // The real constructor calls loadLanguagePacks(); allocate the shell and inject instead.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        bridge = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, NuanceSDKDictionaryBridge::class.java) as NuanceSDKDictionaryBridge
        NuanceSDKDictionaryBridge::class.java.getDeclaredField("nuanceSdk")
            .apply { isAccessible = true }.set(bridge, sdk)

        h.seedComposing("hel")
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        h.close()
    }

    private fun generate() = bridge.generateSuggestions(
        h.inputLogic.mComposingTracker, null, null, 0,
    )

    @Test
    fun undisturbedRead_returnsListWithoutRetrying() {
        // The tracker may have moved on, but nothing rewrote the engine buffer: no re-read.
        var builds = 0
        `when`(sdk.buildSelectionList()).thenAnswer { builds++; 0 }

        val out = generate()

        assertEquals("an undisturbed read must not be re-read (this is the EB-2 revert)", 1, builds)
        assertTrue("an undisturbed read must not come back empty", out.isNotEmpty())
    }

    @Test
    fun mutationDuringRead_rereadsAndStillReturnsSuggestions() {
        // A buffer write between replay and reading the list yields candidates for a symbol
        // stream that never existed.
        var builds = 0
        `when`(sdk.buildSelectionList()).thenAnswer {
            builds++
            if (builds == 1) bumpEpoch()   // a keystroke lands mid-read
            0
        }

        val out = generate()

        assertEquals("a mid-read engine mutation must trigger exactly one re-read", 2, builds)
        assertTrue(
            "the re-read must still produce suggestions — returning empty is what froze the strip",
            out.isNotEmpty(),
        )
    }

    @Test
    fun retryIsBounded_repeatedMutationDoesNotLoop() {
        // If the buffer keeps moving we deliver what we have rather than spinning.
        var builds = 0
        `when`(sdk.buildSelectionList()).thenAnswer { builds++; bumpEpoch(); 0 }

        val out = generate()

        assertEquals("retry must be bounded to one re-read", 2, builds)
        assertTrue(out.isNotEmpty())
    }

    @Test
    fun epochCounterIsObservable() {
        // Guards the tests above: if the epoch stopped moving, the disturbed cases would pass
        // vacuously by never detecting anything.
        val before = epoch()
        bumpEpoch()
        assertEquals(before + 1L, epoch())
    }
}
