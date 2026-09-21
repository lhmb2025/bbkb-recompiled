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
 * Executable proof for `EB-2` — the suggestion worker's engine read is not atomic against
 * main-thread engine writes (docs/2026-07_pipeline-audit-findings_reference.md).
 *
 * The first fix for this was REVERTED (`340b1901`) because it could not tell "the tracker moved
 * on" (normal at typing speed) from "the engine buffer was rewritten inside my read window" (the
 * actual defect). It discarded the list in both cases, and an empty return makes
 * SuggestionCoordinator merge with the PREVIOUS suggestions — the strip froze on stale duplicates.
 *
 * So this suite deliberately pins BOTH directions. [undisturbedRead_returnsListWithoutRetrying] is
 * the one that would have caught the reverted attempt: it fails if the guard fires on a normal
 * read. [mutationDuringRead_rereadsAndStillReturnsSuggestions] proves the defect is actually
 * caught, and that the response is a re-read rather than the empty list that caused the freeze.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionBridgeRaceTest {

    private lateinit var h: PipelineHarness
    private lateinit var settingsStatic: org.mockito.MockedStatic<SettingsManager>
    private lateinit var sdk: NuanceSDK
    private lateinit var bridge: NuanceSDKDictionaryBridge

    /** Calls into the engine's input buffer that the shim counts (clear/processKeyBySymbol/…). */
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
        // THE regression that killed attempt #1. A normal read — the tracker may well have
        // advanced by delivery time, but nothing rewrote the engine buffer during the read —
        // must return its list untouched and must NOT re-read.
        var builds = 0
        `when`(sdk.buildSelectionList()).thenAnswer { builds++; 0 }

        val out = generate()

        assertEquals("an undisturbed read must not be re-read (this is the EB-2 revert)", 1, builds)
        assertTrue("an undisturbed read must not come back empty", out.isNotEmpty())
    }

    @Test
    fun mutationDuringRead_rereadsAndStillReturnsSuggestions() {
        // The real defect: the main thread rewrites the engine input buffer between our replay
        // and our read of the scored list, so the candidates belong to a symbol stream that
        // never existed.
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
