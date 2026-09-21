package com.blackberry.nuanceshim.languagepack

import dev.bbkb.ime.core.ime.UIUpdateHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * Characterises the language-pack status layer: the process-wide status cache (keyed by the
 * REQUESTED language tag, first registry wins), registry fallbacks and defaults, installed-state
 * reads, the unused-locale cleanup's uninstall/deregister path, and the locale monitor's posts.
 *
 * Every test uses its own made-up language codes because the status cache is process-wide.
 * NuanceSDKManager has no engine in unit tests (no ImeApplication), so a call that reaches
 * `NuanceSDKManager.getInstance().deregisterLdb(...)` surfaces as a NullPointerException; tests
 * use that to observe that the deregister call was reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LanguagePackManagerTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun pack(
        language: String, country: String? = null, preinstalled: Boolean = false, default: Boolean = false
    ) = LanguagePackInfo().apply {
        this.language = language
        this.country = country
        name = "Test $language"
        path = "ldb/$language"
        version = 1.0
        isDefault = default
        setPreinstalledFlag(preinstalled)
    }

    private fun registryOf(vararg packs: LanguagePackInfo) =
        LanguagePackRegistry().apply { packs.forEach { addLanguagePack(it) } }

    private fun managerWith(registry: LanguagePackRegistry?): LanguagePackManager {
        val manager = LanguagePackManager(context)
        LanguagePackManager::class.java.getDeclaredField("registry").apply { isAccessible = true }
            .set(manager, registry)
        // The catalogue is parsed lazily on first read now, so the injection has to claim the
        // load has already happened -- otherwise the first lookup would replace the injected
        // registry (including the deliberate null of the degraded mode) with the stock manifest.
        LanguagePackManager::class.java.getDeclaredField("registryLoaded")
            .apply { isAccessible = true }.setBoolean(manager, true)
        return manager
    }

    private fun installDir(id: String, version: String? = "2.5\n", ldb: Boolean = true): File {
        val dir = LanguagePackInstaller.getLanguagePackDirectory(context, id)
        LanguagePackInstaller.deleteRecursively(dir)
        dir.mkdirs()
        if (version != null) File(dir, "version.txt").writeText(version)
        if (ldb) File(dir, "$id.ldb").writeText("x")
        return dir
    }

    // ---- degraded mode ---------------------------------------------------------------------

    @Test
    fun nullRegistryIsTheDegradedNoLanguageDatabaseMode() {
        val manager = managerWith(null)
        val locale = Locale("qa", "AA")
        assertNull(manager.getStatus(locale))
        assertFalse(manager.isInstalled(locale))
        assertFalse(manager.isSupported(locale))
        assertFalse(manager.setLanguages(null, arrayOf(locale)))
    }

    @Test
    fun stockManifestResolvesThePreinstalledEnglishPacks() {
        val manager = LanguagePackManager(context)
        assertTrue(manager.isSupported(Locale.US))
        assertTrue(manager.isInstalled(Locale.US))
        assertEquals("en_US", manager.getStatus(Locale.US)!!.localeIdentifier)
        // en-GB is not in the manifest (it says UK) and has no fallback: the language default wins.
        assertEquals("en", manager.getStatus(Locale.UK)!!.localeIdentifier)
        assertTrue(manager.isSupported(Locale("af")))
        assertFalse(manager.isSupported(Locale("zz")))
    }

    // ---- status cache ----------------------------------------------------------------------

    @Test
    fun statusIsCachedByRequestedTagAndTheFirstRegistryWins() {
        val first = managerWith(registryOf(pack("qb", "ZZ")))
        val second = managerWith(LanguagePackRegistry())
        val locale = Locale("qb", "ZZ")

        val status = first.getStatus(locale)
        assertSame(status, first.getStatus(locale))
        assertSame(status, second.getStatus(locale))
        assertTrue(second.isSupported(locale))

        assertFalse(second.isSupported(Locale("qb", "YY")))
        assertTrue(first.isSupported(Locale("qb", "YY")).not())
    }

    @Test
    fun fallbackResolvesAnotherPackButIsCachedUnderTheRequestedTag() {
        val manager = managerWith(registryOf(pack("en", "UK")))
        val requested = manager.getStatus(Locale("en", "ZA"))!!
        assertEquals("en_UK", requested.localeIdentifier)
        assertEquals(Locale("en", "UK"), requested.locale)
        val direct = manager.getStatus(Locale("en", "UK"))!!
        assertNotSame(requested, direct)
        assertEquals(direct.localeIdentifier, requested.localeIdentifier)
    }

    @Test
    fun languageDefaultPackAnswersForAnUnlistedCountry() {
        val manager = managerWith(registryOf(pack("qc", "AA"), pack("qc", default = true)))
        val status = manager.getStatus(Locale("qc", "BB"))!!
        assertEquals("qc", status.localeIdentifier)
        assertEquals(Locale("qc"), status.locale)
    }

    @Test
    fun unsupportedStatusHasNoIdentifierOrLocale() {
        val status = managerWith(LanguagePackRegistry()).getStatus(Locale("qd", "ZZ"))!!
        assertNull(status.localeIdentifier)
        assertNull(status.locale)
        assertFalse(status.isInstalled)
    }

    // ---- installed state -------------------------------------------------------------------

    @Test
    fun installedStateReadsTheVersionFileAndThenPinsIt() {
        val manager = managerWith(registryOf(pack("qe", "AA")))
        val locale = Locale("qe", "AA")
        LanguagePackInstaller.deleteRecursively(LanguagePackInstaller.getLanguagePackDirectory(context, "qe_AA"))
        assertFalse(manager.isInstalled(locale))

        val dir = installDir("qe_AA")
        assertTrue(manager.isInstalled(locale))

        LanguagePackInstaller.deleteRecursively(dir)
        assertTrue("installed version is cached on the status", manager.isInstalled(locale))
    }

    @Test
    fun unreadableVersionFileIsNotInstalled() {
        val manager = managerWith(registryOf(pack("qf", "AA"), pack("qf", "BB")))
        installDir("qf_AA", version = "abc")
        assertFalse(manager.isInstalled(Locale("qf", "AA")))
        installDir("qf_BB", version = null)
        assertFalse(manager.isInstalled(Locale("qf", "BB")))
    }

    @Test
    fun preinstalledPackIsInstalledWithoutAnyDirectory() {
        val manager = managerWith(registryOf(pack("qg", "AA", preinstalled = true)))
        assertTrue(manager.isInstalled(Locale("qg", "AA")))
    }

    @Test
    fun installedLocalesNeedTwoOfLdbOrVersionFile() {
        installDir("qh_AA")
        installDir("qh_BB", version = null)
        installDir("qh_CC", ldb = false)
        val installed = LanguagePackInstaller.getInstalledLocales(context)
        assertTrue(installed.contains("qh_AA"))
        assertFalse(installed.contains("qh_BB"))
        assertFalse(installed.contains("qh_CC"))
    }

    // ---- languages -------------------------------------------------------------------------

    @Test
    fun setLanguagesRefusesAnyUnsupportedLocaleBeforeTouchingTheEngine() {
        val manager = managerWith(registryOf(pack("qi", "AA")))
        assertFalse(manager.setLanguages(null, arrayOf(Locale("qi", "AA"), Locale("qj", "ZZ"))))
    }

    @Test
    fun setLanguagesWithAllSupportedReachesTheEngine() {
        val manager = managerWith(registryOf(pack("qk", "AA")))
        assertThrows(NullPointerException::class.java) {
            manager.setLanguages(null, arrayOf(Locale("qk", "AA")))
        }
    }

    // ---- cleanup ---------------------------------------------------------------------------

    @Test
    fun monitorPostsOneReadyMessagePerInstalledAdditionalLocale() {
        val instanceField = LanguagePackManager::class.java.getDeclaredField("sInstance").apply { isAccessible = true }
        val saved = instanceField.get(null)
        try {
            instanceField.set(
                null,
                managerWith(
                    registryOf(
                        pack("qr", "AA", preinstalled = true), pack("qs", preinstalled = true),
                        pack("qt", preinstalled = true), pack("qu")
                    )
                )
            )
            val handler = Mockito.mock(UIUpdateHandler::class.java)
            val monitor = LanguagePackLocaleMonitor(context, handler)
            monitor.onLocaleChanged(
                Locale("qr", "AA"), linkedSetOf(Locale("qs"), Locale("qt"), Locale("qu"), Locale("qv"))
            )
            Mockito.verify(handler, Mockito.times(1)).removeAdditionalLocalesReady()
            Mockito.verify(handler, Mockito.times(2)).postAdditionalLocalesReady()

            // Same locale and set again: nothing happens.
            monitor.onLocaleChanged(
                Locale("qr", "AA"), linkedSetOf(Locale("qs"), Locale("qt"), Locale("qu"), Locale("qv"))
            )
            Mockito.verify(handler, Mockito.times(1)).removeAdditionalLocalesReady()

            assertEquals(LanguagePackLocaleMonitor.NOTIFICATION_NONE, monitor.getLanguageNotificationState(Locale("qs")))
            assertEquals(LanguagePackLocaleMonitor.NOTIFICATION_NOT_INSTALLED, monitor.getLanguageNotificationState(Locale("qu")))
            assertEquals(LanguagePackLocaleMonitor.NOTIFICATION_UNSUPPORTED, monitor.getLanguageNotificationState(Locale("qv")))
            assertEquals(LanguagePackLocaleMonitor.NOTIFICATION_NONE, monitor.getLanguageNotificationState(Locale("zz")))
            assertNotNull(monitor)
        } finally {
            instanceField.set(null, saved)
        }
    }

    // ── the unused-locale cleanup is GONE (2026-09-16, owner decision) ──────────
    //
    // Eight scenarios that characterised it were removed with it. These two replace them: one
    // pins the behaviour users care about, the other makes reintroducing the feature by accident
    // a build failure rather than a silent return of data loss.

    @Test
    fun bootCompletedDoesNotTouchInstalledPacks() {
        // The decisive case: a pack for a locale that is NOT an enabled subtype - which is every
        // side-loaded pack for a language method.xml has no subtype for. The old cleanup deleted
        // exactly these, within the hour, every time.
        val dir = installDir("qz")
        val manager = managerWith(null)
        manager.onBootCompleted(context)
        assertTrue("boot must not delete an installed pack", dir.isDirectory)
        assertTrue(File(dir, "qz.ldb").exists())
        assertTrue(File(dir, "version.txt").exists())
    }

    @Test
    fun noAutomaticRemovalApiExistsAnyMore() {
        // A guard against reintroduction: any method whose name suggests periodic locale removal
        // would have to be justified against the class javadoc, which explains why it went.
        // uninstall()/deleteRecursively on LanguagePackInstaller remain - those are user-driven.
        val suspicious = LanguagePackManager::class.java.declaredMethods
            .map { it.name }
            .filter { it.contains("UnusedLocale") || it.contains("removeUnused") || it.contains("markLocalesInUse") }
        assertEquals("automatic locale removal is deliberately absent: $suspicious", 0, suspicious.size)
    }
}
