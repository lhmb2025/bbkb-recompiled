package com.blackberry.nuanceshim.languagepack

import dev.bbkb.ime.core.settings.screens.extractCountryFromFileName
import dev.bbkb.ime.core.settings.screens.extractLanguageFromFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Side-loaded ("+" button) language packs: the country is read from the LDB file name (an en-US
 * pack lives in `nuance/en_US`, not the language-only `nuance/en`), and a writable registry
 * records locales that `assets/ldb/manifest.json` cannot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CustomPackRegistryStoreTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        CustomPackRegistryStore.file(context).delete()
    }

    /** Every one of these names is shipped in `app/src/main/assets/ldb/`. */
    @Test
    fun countryIsReadFromTheShippedFilenames() {
        val cases = mapOf(
            "Blackberry_1305_r1-5_ENubUNUS_xt9_ALM3.ldb" to "US",
            "Blackberry_1305_r1-17_ENubUNUK_xt9_ALM3.ldb" to "UK",
            "Blackberry_1305_r1-12_ESusUNES_xt9_ALM3.zip" to "ES",
        )
        for ((file, expected) in cases) {
            val lang = extractLanguageFromFileName(file)
            assertEquals("language for $file", expected.take(0) + lang, lang)
            assertEquals("country for $file", expected, extractCountryFromFileName(file, lang))
        }
    }

    @Test
    fun aLanguageOnlyPackHasNoCountry() {
        // _ENubUN_ and _DEusUN_ are the language-only forms: the trailing UN is the shared
        // vendor marker, not a region. Reading one as a country would invent a locale.
        for (file in listOf(
            "Blackberry_1305_r1-76_ENubUN_xt9_ALM3.ldb",
            "Blackberry_1305_r1-20_DEusUN_xt9_ALM3.ldb",
            "Blackberry_1305_r1-21_FRusUN_xt9_ALM3.ldb",
            "Blackberry_1305_r1-10_RUusUN_xt9_ALM3.ldb",
        )) {
            val lang = extractLanguageFromFileName(file)
            assertNull("country for $file", extractCountryFromFileName(file, lang))
        }
    }

    @Test
    fun anUnrecognisedSuffixIsNotTreatedAsARegion() {
        // Without the region allowlist any trailing letter pair would become a directory name.
        assertNull(extractCountryFromFileName("Blackberry_1305_r1-9_ENubUNZZ_xt9.ldb", "en"))
    }

    @Test
    fun addThenReadRoundTripsIncludingTheCountry() {
        assertTrue(CustomPackRegistryStore.add(context, "en", "US", "English (US)", "nuance/en_US/en_US.ldb", 1.0))
        val packs = CustomPackRegistryStore.read(context)
        assertEquals(1, packs.size)
        assertEquals("en", packs[0].language)
        assertEquals("US", packs[0].country)
        // uninstall() refuses preinstalled packs, so a side-loaded one must never be marked so.
        assertFalse(packs[0].isPreinstalled)
    }

    @Test
    fun aLanguageOnlyEntryRoundTripsWithANullCountry() {
        assertTrue(CustomPackRegistryStore.add(context, "cy", null, "Welsh", "nuance/cy/cy.ldb", 1.0))
        assertNull(CustomPackRegistryStore.read(context).single().country)
    }

    @Test
    fun addingTheSameLocaleTwiceReplacesRatherThanDuplicates() {
        // A duplicate makes addLanguagePack throw on merge (caught per entry): the older one would win.
        CustomPackRegistryStore.add(context, "en", "US", "First", "nuance/en_US/en_US.ldb", 1.0)
        CustomPackRegistryStore.add(context, "en", "US", "Second", "nuance/en_US/en_US.ldb", 1.0)
        val packs = CustomPackRegistryStore.read(context)
        assertEquals(1, packs.size)
        assertEquals("Second", packs[0].name)
    }

    @Test
    fun removeDropsOnlyTheMatchingLocale() {
        CustomPackRegistryStore.add(context, "en", "US", "English (US)", "a", 1.0)
        CustomPackRegistryStore.add(context, "en", "UK", "English (UK)", "b", 1.0)
        CustomPackRegistryStore.remove(context, "en", "US")
        val left = CustomPackRegistryStore.read(context)
        assertEquals(1, left.size)
        assertEquals("UK", left[0].country)
    }

    @Test
    fun removingSomethingThatIsNotThereSucceedsAndWritesNothing() {
        assertTrue(CustomPackRegistryStore.remove(context, "xx", null))
        assertFalse(CustomPackRegistryStore.file(context).exists())
    }

    @Test
    fun aLocaleThatCouldEscapeTheNuanceDirectoryIsRefused() {
        // The identifier becomes a directory name under noBackupFilesDir/nuance/, whose siblings
        // include the dynamic learning model.
        for (bad in listOf("..", "../x", "e", "toolongcode", "e/n", "")) {
            assertFalse("accepted $bad", CustomPackRegistryStore.add(context, bad, null, "n", "p", 1.0))
        }
        assertFalse(CustomPackRegistryStore.file(context).exists())
    }

    @Test
    fun theUnMFourNineteenRegionIsAccepted() {
        // es-419 ships, so a 2-letter-only region check would reject a supported locale.
        assertTrue(CustomPackRegistryStore.isValidLocaleIdentifier("es_419"))
        assertTrue(CustomPackRegistryStore.add(context, "es", "419", "Spanish (LatAm)", "p", 1.0))
    }

    @Test
    fun aCorruptRegistryReadsAsEmptyRatherThanTakingTheCatalogueDown() {
        CustomPackRegistryStore.file(context).writeText("{\"languages\": [{\"language\":")
        assertEquals(0, CustomPackRegistryStore.read(context).size)
    }

    @Test
    fun aHalfWrittenEntryIsSkippedWithoutLosingTheOthers() {
        // The second entry has no name/path/version, so LanguagePackInfo.isValid() rejects it.
        CustomPackRegistryStore.file(context).writeText(
            """{"languages":[
                 {"language":"cy","name":"Welsh","path":"p","version":1.0,"preinstalled":false},
                 {"language":"zz"}
               ]}"""
        )
        val packs = CustomPackRegistryStore.read(context)
        assertEquals(1, packs.size)
        assertEquals("cy", packs[0].language)
    }

    @Test
    fun noTempFileIsLeftBehind() {
        CustomPackRegistryStore.add(context, "cy", null, "Welsh", "p", 1.0)
        val stray = File(context.filesDir, "nuance_custom_packs.json.new")
        assertFalse(stray.exists())
        assertNotNull(CustomPackRegistryStore.read(context))
    }

    @Test
    fun aWriteACrashLeftHalfDoneIsIgnoredAndReplaced() {
        assertTrue(CustomPackRegistryStore.add(context, "cy", null, "Welsh", "p", 1.0))
        val stale = File(context.filesDir, "nuance_custom_packs.json.new").apply { writeText("{\"languages\": [") }

        assertEquals("the live file is read, not the stale one", listOf("cy"),
            CustomPackRegistryStore.read(context).map { it.language })
        assertTrue(CustomPackRegistryStore.add(context, "gd", null, "Gaelic", "p", 1.0))
        assertEquals(listOf("cy", "gd"), CustomPackRegistryStore.read(context).map { it.language })
        assertFalse("the next write replaced it", stale.exists())
    }
}

/**
 * A side-loaded pack for a locale the APK does not ship comes back from [ManifestParser] as a
 * known locale. `LanguagePackInstaller.isSupported()` is exactly "the registry knows this language".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CustomPackRegistryMergeTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        CustomPackRegistryStore.file(context).delete()
    }

    @Test
    fun aSideLoadedLocaleBecomesSupported() {
        // `jw` is one of the few allowlisted codes the manifest does not ship; asserted so the test
        // cannot pass vacuously if a future manifest adds it.
        assertNull(ManifestParser.parseManifest(context).findLanguagePack("jw", null))

        CustomPackRegistryStore.add(context, "jw", null, "Javanese", "nuance/jw/jw.ldb", 1.0)

        val found = ManifestParser.parseManifest(context).findLanguagePack("jw", null)
        assertNotNull("a registered custom pack must be findable, or cleanup deletes it", found)
        assertEquals("Javanese", found!!.name)
        assertFalse(found.isPreinstalled)
    }

    @Test
    fun aSideLoadedVariantIsDistinctFromItsBaseLanguage() {
        // The registry keeps a variant apart from its base language (nuance/jw_ID vs nuance/jw).
        CustomPackRegistryStore.add(context, "jw", "ID", "Javanese (ID)", "nuance/jw_ID/jw_ID.ldb", 1.0)
        val registry = ManifestParser.parseManifest(context)
        assertEquals("Javanese (ID)", registry.findLanguagePack("jw", "ID")!!.name)
        assertNull(registry.findLanguagePack("jw", null))
    }

    @Test
    fun aCustomEntryCannotShadowAShippedPack() {
        // en-US ships: a custom entry claiming it must not redirect the preinstalled pack's path.
        CustomPackRegistryStore.add(context, "en", "US", "Impostor", "nuance/en_US/x.ldb", 1.0)
        val found = ManifestParser.parseManifest(context).findLanguagePack("en", "US")
        assertNotNull(found)
        assertEquals("English (United States)", found!!.name)
    }
}
