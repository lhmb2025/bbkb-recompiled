package dev.bbkb.ime.core.device.config

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preloaded-config listing costs an `R.xml` reflection sweep (431 fields in this app) plus
 * one XML pull-parse per `device_config_*` match just to read its `name` attribute, and it used
 * to be re-run on every `getAvailableConfigs()` and every auto-select search — on
 * `DeviceProfile.initialize`, which is the IME's cold-start path and every cold Settings launch.
 *
 * Nothing about that listing can change while the process lives: `R.xml`'s fields are
 * compile-time constants and the XMLs are in the APK. These pin both halves — that it is
 * computed once, and that memoising it did not change what it says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PreloadedConfigCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun preloadedOf(manager: CustomDeviceConfigManager) =
        manager.availableConfigs.filter { it.type == CustomDeviceConfigManager.ConfigType.PRELOADED }

    @Test
    fun theShippedConfigsAreStillListed() {
        // Guards the rest of the class: memoising an empty list would make every identity
        // assertion below vacuously true.
        val preloaded = preloadedOf(CustomDeviceConfigManager.getInstance(context))
        assertTrue("no preloaded device configs found at all", preloaded.isNotEmpty())
        assertTrue(preloaded.all { it.id.startsWith("preloaded:device_config_") })
        assertTrue(preloaded.all { it.resourceId != 0 })
    }

    @Test
    fun theScanRunsOncePerProcessHoweverOftenItIsAskedFor() {
        val manager = CustomDeviceConfigManager.getInstance(context)

        val first = preloadedOf(manager)
        repeat(4) { preloadedOf(manager) }
        val last = preloadedOf(manager)

        assertEquals(first.size, last.size)
        first.indices.forEach {
            assertSame(
                "config ${first[it].id} was re-scanned instead of served from the memo",
                first[it], last[it]
            )
        }
    }

    @Test
    fun theListingKeepsItsShapeAndOrder() {
        val manager = CustomDeviceConfigManager.getInstance(context)
        val all = manager.availableConfigs

        // Default first, then preloaded, then custom — and preloaded sorted by display name,
        // case-insensitively.
        assertEquals(CustomDeviceConfigManager.ID_DEFAULT, all.first().id)
        assertEquals(CustomDeviceConfigManager.ConfigType.DEFAULT, all.first().type)

        val preloadedNames = preloadedOf(manager).map { it.name }
        assertEquals(preloadedNames.sortedWith(String.CASE_INSENSITIVE_ORDER), preloadedNames)

        val secondCall = manager.availableConfigs
        assertEquals(all.map { it.id }, secondCall.map { it.id })
    }

    /**
     * The auto-select search now enumerates the connected keyboards *before* paying for the
     * preloaded scan, because with none attached the answer is null whatever the configs say.
     * Robolectric reports no input devices, which is the phone-with-no-physical-keyboard case:
     * the stored id must come back untouched.
     */
    @Test
    fun withNoPhysicalKeyboardAttachedTheStoredIdStands() {
        val manager = CustomDeviceConfigManager.getInstance(context)
        assertEquals(CustomDeviceConfigManager.ID_DEFAULT, manager.activeConfigId)

        val config = manager.activeConfig
        assertTrue("getActiveConfig must never return null", config != null)
    }

    @Test
    fun theDefaultConfigIsNotConfusedForAPreloadedOne() {
        val manager = CustomDeviceConfigManager.getInstance(context)
        assertFalse(preloadedOf(manager).any { it.id == CustomDeviceConfigManager.ID_DEFAULT })
    }
}
