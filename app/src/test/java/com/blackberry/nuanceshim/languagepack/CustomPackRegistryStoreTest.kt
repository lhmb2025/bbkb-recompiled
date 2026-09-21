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
 * The side-loaded ("+" button) language-pack path, which could not previously install anything the
 * APK did not already ship.
 *
 * Two independent defects, pinned separately here:
 *
 *  1. **The country was thrown away.** `extractLanguageFromFileName` returned a bare 2-letter code,
 *     so a pack from `*_ENubUNUS_*` was written to `nuance/en` — the directory the language-only
 *     "English" pack owns — while `LanguagePackInstaller.getLocaleIdentifier()` looks for en-US
 *     under `nuance/en_US`. The pack was therefore invisible to the installer AND colliding with a
 *     preinstalled one.
 *  2. **There was nowhere to record it.** `assets/ldb/manifest.json` is an APK asset, so a locale
 *     the app does not ship can never appear in it; `LanguagePackInstaller.isSupported()` is just
 *     "the registry knows this language", and `LanguagePackManager`'s hourly cleanup recursively
 *     deletes the directory of every installed-but-unsupported locale. A side-loaded pack was
 *     deleted by the next cleanup pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CustomPackRegistryStoreTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun clean() {
        CustomPackRegistryStore.file(context).delete()
    }

    // ── defect 1: the region suffix in a real BlackBerry LDB name ───────────────

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
        // Guards the region allowlist: without it, any trailing letter-pair in the vendor block
        // becomes a directory name, and the pack lands somewhere nothing will look for it.
        assertNull(extractCountryFromFileName("Blackberry_1305_r1-9_ENubUNZZ_xt9.ldb", "en"))
    }

    // ── defect 2: the writable registry ─────────────────────────────────────────

    @Test
    fun addThenReadRoundTripsIncludingTheCountry() {
        assertTrue(CustomPackRegistryStore.add(context, "en", "US", "English (US)", "nuance/en_US/en_US.ldb", 1.0))
        val packs = CustomPackRegistryStore.read(context)
        assertEquals(1, packs.size)
        assertEquals("en", packs[0].language)
        assertEquals("US", packs[0].country)
        // Never preinstalled: LanguagePackInstaller.uninstall() refuses to remove a preinstalled
        // pack, so a side-loaded one marked preinstalled could never be deleted by the user.
        assertFalse(packs[0].isPreinstalled)
    }

    @Test
    fun aLanguageOnlyEntryRoundTripsWithANullCountry() {
        assertTrue(CustomPackRegistryStore.add(context, "cy", null, "Welsh", "nuance/cy/cy.ldb", 1.0))
        assertNull(CustomPackRegistryStore.read(context).single().country)
    }

    @Test
    fun addingTheSameLocaleTwiceReplacesRatherThanDuplicates() {
        // A duplicate would make LanguagePackRegistry.addLanguagePack throw on the second merge,
        // and the throw is caught per-entry — so the user would silently get the older one.
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
        // es-419 is in the SHIPPED manifest, so a 2-letter-ISO-only check would reject a locale
        // the app already supports.
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
        val stray = File(context.filesDir, "nuance_custom_packs.json.tmp")
        assertFalse(stray.exists())
        assertNotNull(CustomPackRegistryStore.read(context))
    }
}

/**
 * The end-to-end property the whole change exists for: a side-loaded pack for a locale the APK
 * does not ship must come back from [ManifestParser] as a KNOWN locale.
 *
 * That is not a cosmetic "appears in the list" property. `LanguagePackInstaller.isSupported()` is
 * exactly "the registry knows this language", and `LanguagePackManager`'s hourly cleanup calls
 * `removeUnsupportedLocale` — a recursive directory delete — on every installed locale that is
 * not supported. Before the merge, the answer here was null and the user's pack was deleted
 * within the hour.
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
        // `jw` (Javanese) is the one code in this test file's reach that assets/ldb/manifest.json
        // genuinely does not ship — 94 of the 96 in the installer's own allowlist are shipped, so
        // the absent one has to be chosen deliberately rather than assumed. Asserted here, because
        // if a future manifest adds it this test would otherwise start passing vacuously.
        assertNull(ManifestParser.parseManifest(context).findLanguagePack("jw", null))

        CustomPackRegistryStore.add(context, "jw", null, "Javanese", "nuance/jw/jw.ldb", 1.0)

        val found = ManifestParser.parseManifest(context).findLanguagePack("jw", null)
        assertNotNull("a registered custom pack must be findable, or cleanup deletes it", found)
        assertEquals("Javanese", found!!.name)
        // Must not be preinstalled, or the user could never uninstall it.
        assertFalse(found.isPreinstalled)
    }

    @Test
    fun aSideLoadedVariantIsDistinctFromItsBaseLanguage() {
        // The country half is what defect 1 was throwing away; prove the registry keeps the two
        // apart, so nuance/en_US and nuance/en are different packs rather than one collision.
        CustomPackRegistryStore.add(context, "jw", "ID", "Javanese (ID)", "nuance/jw_ID/jw_ID.ldb", 1.0)
        val registry = ManifestParser.parseManifest(context)
        assertEquals("Javanese (ID)", registry.findLanguagePack("jw", "ID")!!.name)
        // and the bare language must NOT resolve to the variant by accident
        assertNull(registry.findLanguagePack("jw", null))
    }

    @Test
    fun aCustomEntryCannotShadowAShippedPack() {
        // en-US ships. A custom entry claiming it must be ignored, not allowed to redirect the
        // preinstalled pack's path at the engine.
        CustomPackRegistryStore.add(context, "en", "US", "Impostor", "nuance/en_US/x.ldb", 1.0)
        val found = ManifestParser.parseManifest(context).findLanguagePack("en", "US")
        assertNotNull(found)
        assertEquals("English (United States)", found!!.name)
    }
}
