package com.blackberry.nuanceshim.languagepack

import dev.bbkb.ime.core.shared.StartupTiming
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * `LanguagePackManager` parses the pack catalogue on first read (prewarmed on a worker) to keep it
 * off the IME's cold-start main thread. A lazily loaded manager must answer exactly as an eager
 * one, whichever of the prewarm and the first reader arrives first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LanguagePackRegistryLazyLoadTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** Locales spanning the shapes the registry resolves: exact, language-only, and absent. */
    private val probes = listOf(
        Locale.US, Locale.UK, Locale.FRANCE, Locale.GERMANY, Locale("af"), Locale("zz")
    )

    @Before
    fun setUp() {
        StartupTiming.resetForTest()
        clearStatusCache()
    }

    /**
     * These tests must query the stock manifest, so they warm `LanguagePackInstaller`'s
     * process-wide status cache; Robolectric shares it with `SettingsScreenRenderTest`. Leave it cold.
     */
    @After
    fun tearDown() {
        clearStatusCache()
    }

    private fun clearStatusCache() {
        LanguagePackInstaller::class.java.getDeclaredField("sByLanguageTag")
            .apply { isAccessible = true }
            .let { (it.get(null) as MutableMap<*, *>).clear() }
    }

    @Test
    fun constructingTheManagerNoLongerParsesTheCatalogue() {
        LanguagePackManager(context)
        assertEquals(0, StartupTiming.occurrences("languagePacks.registryParse"))
    }

    @Test
    fun aPrewarmedManagerAndAColdOneGiveTheSameAnswers() {
        val prewarmed = LanguagePackManager(context).apply { prewarmRegistry() }
        val cold = LanguagePackManager(context)

        assertEquals(1, StartupTiming.occurrences("languagePacks.registryParse"))

        for (locale in probes) {
            assertEquals(
                "isSupported disagreed for $locale",
                prewarmed.isSupported(locale), cold.isSupported(locale)
            )
            assertEquals(
                "isInstalled disagreed for $locale",
                prewarmed.isInstalled(locale), cold.isInstalled(locale)
            )
            assertEquals(
                "getStatus disagreed for $locale",
                prewarmed.getStatus(locale)?.localeIdentifier,
                cold.getStatus(locale)?.localeIdentifier
            )
        }
    }

    @Test
    fun theStockManifestStillResolvesAfterALazyLoad() {
        val manager = LanguagePackManager(context)

        // First read triggers the parse; same assertions LanguagePackManagerTest makes.
        assertNotNull(manager.getStatus(Locale.US))
        assertEquals("en_US", manager.getStatus(Locale.US)!!.localeIdentifier)
        assertEquals("en_UK", manager.getStatus(Locale.UK)!!.localeIdentifier)
    }

    @Test
    fun anExplicitReloadStillReplacesTheCatalogue() {
        val manager = LanguagePackManager(context)
        val before = manager.getStatus(Locale.US)?.localeIdentifier

        manager.reloadRegistry()

        assertEquals(before, manager.getStatus(Locale.US)?.localeIdentifier)
        assertNotNull(manager.getStatus(Locale.US))
    }
}
