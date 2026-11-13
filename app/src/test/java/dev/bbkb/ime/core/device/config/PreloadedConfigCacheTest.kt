package dev.bbkb.ime.core.device.config

import android.content.Context
import dev.bbkb.ime.core.settings.PrefsManager
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
 * The preloaded-config listing (an `R.xml` reflection sweep plus one parse per config, on the
 * cold-start path) cannot change while the process lives, so it is computed once. Pins that, and
 * that memoising did not change its contents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PreloadedConfigCacheTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.defaultSharedPreferences(context).edit().clear().commit()
    }

    private fun preloadedOf(manager: CustomDeviceConfigManager) =
        manager.availableConfigs.filter { it.type == CustomDeviceConfigManager.ConfigType.PRELOADED }

    @Test
    fun theShippedConfigsAreStillListed() {
        // Memoising an empty list would make every identity assertion below vacuously true.
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

        // Default first, then preloaded (sorted by display name, case-insensitively), then custom.
        assertEquals(CustomDeviceConfigManager.ID_DEFAULT, all.first().id)
        assertEquals(CustomDeviceConfigManager.ConfigType.DEFAULT, all.first().type)

        val preloadedNames = preloadedOf(manager).map { it.name }
        assertEquals(preloadedNames.sortedWith(String.CASE_INSENSITIVE_ORDER), preloadedNames)

        val secondCall = manager.availableConfigs
        assertEquals(all.map { it.id }, secondCall.map { it.id })
    }

    /**
     * Auto-select enumerates connected keyboards before the preloaded scan, since with none the
     * answer is null. Robolectric reports no input devices, so the stored id comes back untouched.
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
