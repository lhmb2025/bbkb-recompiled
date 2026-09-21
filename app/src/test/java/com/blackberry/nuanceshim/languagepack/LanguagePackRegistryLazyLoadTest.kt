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
 * `LanguagePackManager` used to parse `ldb/manifest.json` (105 entries) plus the side-loaded pack
 * registry file in its constructor — and its constructor runs from
 * `LanguagePackLocaleMonitor`'s, which runs from `BlackBerryIME.onCreate`. So that parse sat on
 * the IME's cold-start main thread, ahead of the first keyboard frame, for a catalogue nothing
 * reads until the first dictionary load.
 *
 * It is parsed on first read now, and prewarmed on a worker. The contract that has to survive is
 * that a lazily-loaded manager answers exactly as an eagerly-loaded one did, whichever order the
 * prewarm and the first reader arrive in.
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
     * These tests have to query the *stock* manifest — that is the whole comparison — so unlike
     * the rest of the suite they cannot use made-up language codes to stay out of
     * `LanguagePackInstaller`'s process-wide, first-registry-wins status cache. Robolectric
     * shares its sandbox classloader between test classes, so a warm cache left here changes
     * what `LanguagePacksScreen` has finished loading by the time `SettingsScreenRenderTest`
     * takes its snapshot. Leave it as cold as it was found.
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

        // First read triggers the parse; the assertions are the ones LanguagePackManagerTest
        // makes of the eagerly-loaded manager.
        assertNotNull(manager.getStatus(Locale.US))
        assertEquals("en_US", manager.getStatus(Locale.US)!!.localeIdentifier)
        assertEquals("en", manager.getStatus(Locale.UK)!!.localeIdentifier)
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
