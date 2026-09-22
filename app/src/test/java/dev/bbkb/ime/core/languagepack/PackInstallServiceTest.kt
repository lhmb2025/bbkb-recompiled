package dev.bbkb.ime.core.languagepack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blackberry.nuanceshim.languagepack.LanguageVariantStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [PackInstallService] — what actually lands on disk, and in the registry.
 *
 * These are file-system assertions on purpose. "Installed" is not a flag anywhere in this app: it
 * is a directory holding a `.ldb` and a `version.txt`, plus an entry in a JSON registry that makes
 * the locale *supported*. Three separate historical bugs came from one of those three being
 * missing, so the test asserts all three rather than the service's return value.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackInstallServiceTest {

    private lateinit var context: Context
    private lateinit var source: File
    private val posted = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        source = File(context.cacheDir, "incoming.ldb")
        source.writeBytes(PackFixtures.ldbBytes())
        posted.clear()
        File(context.noBackupFilesDir, "nuance").deleteRecursively()
        File(context.noBackupFilesDir, "lang_variants").deleteRecursively()
        PackFixtures.customRegistry(context).delete()
        PackFixtures.variantRegistry(context).delete()
    }

    @After
    fun tearDown() {
        source.delete()
    }

    private fun service(
        subtypes: PackInstallService.SubtypeRegistrar = PackFixtures.FakeSubtypes(builtIn = setOf("cy", "es", "zh", "en", "de")),
    ) = PackInstallService(context, subtypes) { action -> posted += action }

    // ── Removing a pack ───────────────────────────────────────────────────────────────────────

    private suspend fun installWelsh(subtypes: PackFixtures.FakeSubtypes) =
        service(subtypes).installFromFile(source, "cy", "Welsh", "1902.01", null).getOrThrow()

    @Test
    fun uninstallRemovesTheFilesTheRegistryEntryAndTheRuntimeSubtype() = runBlocking<Unit> {
        val subtypes = PackFixtures.FakeSubtypes(offerable = setOf("cy"))
        installWelsh(subtypes)
        posted.clear()

        val outcome = service(subtypes).uninstall("cy").getOrThrow()

        assertEquals(UninstallOutcome.REMOVED, outcome)
        assertFalse("pack directory still there", PackFixtures.packDir(context, "cy").exists())
        val registry = PackFixtures.customRegistry(context).readText()
        assertFalse("registry still lists cy: $registry", registry.contains("\"language\":\"cy\""))
        assertEquals(listOf("cy"), subtypes.withdrawn)
        assertEquals(listOf(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED), posted)
        assertFalse(InstalledPacks.read(context, listOf("cy")).isInstalled("cy"))
    }

    @Test
    fun uninstallOfAPackWhoseFilesAreAlreadyGoneStillCleansUpAndIsNotAnError() = runBlocking<Unit> {
        // The screen's rows are a snapshot: by the time the user confirms, the directory can be
        // gone already. That used to surface as "Language pack directory not found" over a pack
        // that was in fact removed.
        val subtypes = PackFixtures.FakeSubtypes(offerable = setOf("cy"))
        installWelsh(subtypes)
        PackFixtures.packDir(context, "cy").deleteRecursively()
        posted.clear()

        val outcome = service(subtypes).uninstall("cy").getOrThrow()

        assertEquals(UninstallOutcome.ALREADY_GONE, outcome)
        val registry = PackFixtures.customRegistry(context).readText()
        assertFalse("registry still lists cy: $registry", registry.contains("\"language\":\"cy\""))
        assertEquals(listOf("cy"), subtypes.withdrawn)
        assertEquals(listOf(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED), posted)
    }

    @Test
    fun uninstallingTwiceSucceedsBothTimes() = runBlocking<Unit> {
        val subtypes = PackFixtures.FakeSubtypes(builtIn = setOf("cy"))
        installWelsh(subtypes)

        assertEquals(UninstallOutcome.REMOVED, service(subtypes).uninstall("cy").getOrThrow())
        assertEquals(UninstallOutcome.ALREADY_GONE, service(subtypes).uninstall("cy").getOrThrow())
        assertFalse(PackFixtures.packDir(context, "cy").exists())
    }

    @Test
    fun uninstallLeavesOtherPacksAlone() = runBlocking<Unit> {
        val subtypes = PackFixtures.FakeSubtypes(builtIn = setOf("cy", "es"))
        installWelsh(subtypes)
        service(subtypes).installFromFile(source, "es", "Spanish", "1902.01", null).getOrThrow()

        service(subtypes).uninstall("cy").getOrThrow()

        assertTrue(PackFixtures.packDir(context, "es").isDirectory)
        assertTrue(PackFixtures.customRegistry(context).readText().contains("\"language\":\"es\""))
    }

    @Test
    fun uninstallRefusesAnUnusableLocale() = runBlocking<Unit> {
        val result = service().uninstall("../nuance")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PackInstallException)
    }

    // ── An ordinary pack ──────────────────────────────────────────────────────────────────────

    @Test
    fun installsABasePackAsFilesPlusVersionPlusRegistryEntry() = runBlocking<Unit> {
        val installed = service().installFromFile(
            file = source,
            locale = "cy",
            displayName = "Welsh",
            version = "1902.01",
            group = null,
        ).getOrThrow()

        val dir = PackFixtures.packDir(context, "cy")
        assertTrue("no pack directory at $dir", dir.isDirectory)
        assertArrayEqualsBytes(PackFixtures.ldbBytes(), File(dir, "cy.ldb").readBytes())
        // Required by getInstalledLocales(): a directory with no version.txt is not a pack.
        assertEquals("1902.01", File(dir, "version.txt").readText())

        val registry = PackFixtures.customRegistry(context).readText()
        assertTrue("registry entry missing: $registry", registry.contains("\"language\":\"cy\""))
        assertTrue("display name missing: $registry", registry.contains("Welsh"))
        assertTrue("path missing: $registry", registry.contains("nuance/cy/cy.ldb"))

        assertEquals("cy", installed.locale)
        assertFalse(installed.isVariant)
        assertTrue("a language method.xml declares needs no runtime subtype", installed.selectable)
        assertFalse(installed.offeredSubtype)

        // The running IME learns about the new file through this, and only through this.
        assertEquals(listOf(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED), posted)

        // And the screen's own view of the disk agrees.
        val onDisk = InstalledPacks.read(context, listOf("cy"))
        assertTrue("InstalledPacks does not see cy: $onDisk", onDisk.isInstalled("cy"))
        assertEquals(setOf("cy"), onDisk.custom)
    }

    @Test
    fun installsUnderTheGivenLocaleAndNeverConsultsTheFileName() = runBlocking<Unit> {
        // The four table-locale packs are the whole reason the locale is a parameter. This file
        // name parses to "es" (its region, "latam", is lower case and lives in the catalogue
        // only), so a filename-derived install would land in nuance/es - on top of Spanish.
        val named = File(context.cacheDir, "Blackberry_1305_r1-11_ESusUNlatam_xt9_ALM3.ldb")
        named.writeBytes(PackFixtures.ldbBytes(seed = 11))

        service().installFromFile(named, "es_419", "Spanish (Latin America)", "1902.01", null)
            .getOrThrow()

        assertTrue(PackFixtures.packDir(context, "es_419").isDirectory)
        assertTrue(File(PackFixtures.packDir(context, "es_419"), "es_419.ldb").isFile)
        assertFalse(
            "installed under the file name's locale instead of the catalogue's",
            PackFixtures.packDir(context, "es").exists()
        )
        val registry = PackFixtures.customRegistry(context).readText()
        assertTrue(registry, registry.contains("\"country\":\"419\""))
        named.delete()
    }

    @Test
    fun offersARuntimeSubtypeOnlyForALanguageMethodXmlOmits() = runBlocking<Unit> {
        val subtypes = PackFixtures.FakeSubtypes(builtIn = setOf("cy"), offerable = setOf("sw"))

        val welsh = service(subtypes)
            .installFromFile(source, "cy", "Welsh", "1.0", null).getOrThrow()
        assertFalse("method.xml has cy; nothing to offer", welsh.offeredSubtype)
        assertTrue(subtypes.offered.isEmpty())

        val swahili = service(subtypes)
            .installFromFile(source, "sw", "Swahili", "1.0", null).getOrThrow()
        assertTrue("method.xml has no sw subtype, so one is offered", swahili.offeredSubtype)
        assertTrue(swahili.selectable)
        assertEquals(listOf("sw"), subtypes.offered)
    }

    @Test
    fun reportsAPackThatInstalledButCannotBeSelected() = runBlocking<Unit> {
        // Five engine-supported languages have no keyboard layout in the tree. The pack installs
        // and sits unused, and the screen has to be able to say so.
        val subtypes = PackFixtures.FakeSubtypes(builtIn = emptySet(), offerable = emptySet())
        val installed = service(subtypes)
            .installFromFile(source, "am", "Amharic", "1.0", null).getOrThrow()

        assertTrue(PackFixtures.packDir(context, "am").isDirectory)
        assertFalse(installed.offeredSubtype)
        assertFalse("no layout for Ethiopic, so nothing can select it", installed.selectable)
    }

    // ── A regional variant ────────────────────────────────────────────────────────────────────

    @Test
    fun installsAVariantParkedUnderItsGroupAndActiveInTheBaseSlot() = runBlocking<Unit> {
        val installed = service().installFromFile(
            file = source,
            locale = "de_CH",
            displayName = "German (Switzerland)",
            version = "1902.01",
            group = "de",
        ).getOrThrow()

        // Parked as a member of the German group...
        val parked = PackFixtures.parkedVariant(context, "de", "de_CH")
        assertTrue("variant not parked at $parked", parked.isFile)
        // ...and copied into the slot the engine reads for German, because that is the only way
        // a pack with no locale-table entry of its own can ever load.
        val slot = PackFixtures.packDir(context, "de")
        assertTrue("German slot not filled", File(slot, "de.ldb").isFile)
        assertTrue("version.txt missing from the German slot", File(slot, "version.txt").isFile)
        assertFalse(
            "a variant must not invent a locale of its own",
            PackFixtures.packDir(context, "de_CH").exists()
        )

        val variants = PackFixtures.variantRegistry(context).readText()
        assertTrue(variants, variants.contains("\"locale\":\"de\""))
        assertTrue(variants, variants.contains("\"active\":\"de_CH\""))
        assertTrue("the way back to the shipped pack must exist", variants.contains("shipped"))

        // No custom-pack entry: the locale that loads is German, which the shipped registry knows.
        assertFalse(PackFixtures.customRegistry(context).isFile)

        assertEquals("de", installed.group)
        assertTrue(installed.isVariant)
        assertEquals(listOf(LanguageVariantStore.ACTION_LANGUAGE_PACK_CHANGED), posted)

        val onDisk = InstalledPacks.read(context, listOf("de", "de_CH"))
        val group = onDisk.variants["de"]
        assertEquals("de_CH", group?.activeTag)
        assertEquals(setOf("de_CH"), group?.tags)
    }

    // ── Refusals leave nothing behind ─────────────────────────────────────────────────────────

    @Test
    fun refusesAFileTooSmallToBeADictionary() = runBlocking<Unit> {
        val tiny = File(context.cacheDir, "tiny.ldb").apply { writeText("not a dictionary") }

        val failure = service().installFromFile(tiny, "cy", "Welsh", "1.0", null)

        assertTrue(failure.exceptionOrNull() is PackInstallException)
        assertFalse(PackFixtures.packDir(context, "cy").exists())
        assertFalse(PackFixtures.customRegistry(context).isFile)
        assertTrue("a refused install must not tell the IME anything changed", posted.isEmpty())
        tiny.delete()
    }

    @Test
    fun refusesALocaleThatCouldEscapeThePackDirectory() = runBlocking<Unit> {
        val failure = service().installFromFile(source, "../../databases", "Bad", "1.0", null)

        assertTrue(failure.exceptionOrNull() is PackInstallException)
        assertFalse(File(context.noBackupFilesDir, "nuance").exists())
        assertTrue(posted.isEmpty())
    }

    @Test
    fun refusesAMissingFile() = runBlocking<Unit> {
        val gone = File(context.cacheDir, "not-there.ldb")

        val failure = service().installFromFile(gone, "cy", "Welsh", "1.0", null)

        assertTrue(failure.exceptionOrNull() is PackInstallException)
        assertFalse(PackFixtures.packDir(context, "cy").exists())
    }

    @Test
    fun anUnusableVersionFallsBackRatherThanWritingAnInvalidRegistryEntry() = runBlocking<Unit> {
        // LanguagePackInfo.isValid() rejects version <= 0 and isInstalled() parses version.txt's
        // trailing digits, so an unparseable version would make the pack read as not installed.
        service().installFromFile(source, "cy", "Welsh", "not a number", null).getOrThrow()

        val written = File(PackFixtures.packDir(context, "cy"), "version.txt").readText()
        assertEquals(PackInstallService.FALLBACK_VERSION.toString(), written)
        assertTrue(written.toDouble() > 0.0)
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals("byte count", expected.size, actual.size)
        assertTrue("installed bytes differ from the source", expected.contentEquals(actual))
    }
}
