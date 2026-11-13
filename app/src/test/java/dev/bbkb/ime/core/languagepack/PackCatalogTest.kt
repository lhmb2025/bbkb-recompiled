package dev.bbkb.ime.core.languagepack

import dev.bbkb.ime.core.distribution.IntegrityException
import dev.bbkb.ime.core.distribution.Packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PackCatalog] against the real `dist/manifest.json` (regional variants, catalogue-only regions)
 * merged with a described phone. Robolectric because [PackFixtures] parses with `org.json`, an
 * empty stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PackCatalogTest {

    private val packs: Packs = PackFixtures.realPacks()

    @Test
    fun theRealManifestCarriesEveryPublishedPack() {
        assertEquals(PackFixtures.PUBLISHED_ITEM_COUNT, packs.items.size)
        assertEquals(
            "locales must be unique - the parser drops duplicates, and a duplicate would mean " +
                "two packs claiming one directory",
            packs.items.size,
            packs.items.map { it.locale }.toSet().size,
        )
    }

    @Test
    fun theFourTableLocalePacksArePublishedUnderTheirOwnLocale() {
        // Packs whose region is in the catalogue but not the file name; install depends on it.
        for (locale in PackFixtures.TABLE_LOCALE_PACKS) {
            val entry = packs.forLocale(locale)
            assertNotNull("$locale is not in the catalogue", entry)
            assertFalse("$locale must not be a variant", entry!!.isVariant)
        }
    }

    @Test
    fun onlyTheSwissAndBelgianPacksAreVariants() {
        val variants = packs.items.filter { it.isVariant }.associate { it.locale to it.group }
        assertEquals(PackFixtures.VARIANTS, variants)
    }

    @Test
    fun withNothingInstalledEveryPackIsAvailableAndVariantsAreNested() {
        val catalog = PackCatalog.from(packs, InstalledPacks())

        // One row per language: the four variants nest under de/fr/it/nl.
        assertEquals(PackFixtures.PUBLISHED_ITEM_COUNT - 4, catalog.rows.size)
        assertEquals(PackFixtures.PUBLISHED_ITEM_COUNT, catalog.items.size)
        for ((variant, base) in PackFixtures.VARIANTS) {
            val row = catalog.rows.single { it.locale == base }
            assertEquals(
                "$variant should be nested under $base",
                listOf(variant),
                row.variants.map { it.locale },
            )
        }

        assertTrue(catalog.installedRows.isEmpty())
        assertEquals(catalog.rows.size, catalog.availableRows.size)
        val welsh = catalog.item("cy")!!
        assertEquals(PackState.Available(welsh.entry.size), welsh.state)
        assertEquals("1902.01", catalog.version)
    }

    @Test
    fun rowsAreSortedByDisplayName() {
        val catalog = PackCatalog.from(packs, InstalledPacks())
        val names = catalog.rows.map { it.displayName }

        assertEquals(names.sorted(), names)
        assertEquals("Afrikaans", names.first())
    }

    // Fixture phone: shipped en_US, custom jv, active de_CH.

    private val phone = InstalledPacks(
        shipped = setOf("en_US"),
        custom = setOf("jv"),
        variants = mapOf("de" to InstalledPacks.Group(activeTag = "de_CH", tags = setOf("de_CH"))),
    )

    @Test
    fun everyInstalledShapeIsReportedWithItsOrigin() {
        val catalog = PackCatalog.from(packs, phone)

        assertEquals(
            PackState.Installed(PackState.Origin.SHIPPED),
            catalog.item("en_US")!!.state,
        )
        assertEquals(
            PackState.Installed(PackState.Origin.CUSTOM),
            catalog.item("jv")!!.state,
        )
        assertEquals(
            PackState.Installed(PackState.Origin.VARIANT_ACTIVE),
            catalog.item("de_CH")!!.state,
        )
        // The other three variants are not installed, so they are still offers.
        assertTrue(catalog.item("fr_CH")!!.state is PackState.Available)
        assertTrue(catalog.item("nl_BE")!!.state is PackState.Available)
    }

    @Test
    fun anInstalledVariantIsReportedInactiveWhenTheShippedPackIsTheOneLoading() {
        val bothInstalled = phone.copy(
            variants = mapOf(
                "de" to InstalledPacks.Group(
                    // The user switched back to the pack the app shipped with.
                    activeTag = "shipped",
                    tags = setOf("de_CH"),
                )
            )
        )

        val catalog = PackCatalog.from(packs, bothInstalled)

        assertEquals(
            PackState.Installed(PackState.Origin.VARIANT_INACTIVE),
            catalog.item("de_CH")!!.state,
        )
        // Installed but inactive is still installed: nothing to download, nothing to offer.
        assertFalse(catalog.rows.single { it.locale == "de" }.availableVariants.any { it.locale == "de_CH" })
    }

    @Test
    fun aLanguageCanBeInBothSectionsWhenOnlyItsVariantIsMissing() {
        val catalog = PackCatalog.from(packs, phone)
        val german = catalog.rows.single { it.locale == "de" }

        // Only the Swiss variant is installed, so German is in both "Installed" and "Available".
        assertTrue(german.hasAnythingInstalled)
        assertTrue(german.hasAnythingAvailable)
        assertTrue(catalog.installedRows.contains(german))
        assertTrue(catalog.availableRows.contains(german))

        val english = catalog.rows.single { it.locale == "en_US" }
        assertTrue(english.hasAnythingInstalled)
        assertFalse("nothing left to download for a shipped pack with no variants", english.hasAnythingAvailable)
        assertFalse(catalog.availableRows.contains(english))
    }

    @Test
    fun installedAndAvailableCountsAddUpForTheFixturePhone() {
        val catalog = PackCatalog.from(packs, phone)

        // en_US (shipped), jv (custom) and de (because its Swiss variant is installed).
        assertEquals(
            listOf("de", "en_US", "jv"),
            catalog.installedRows.map { it.locale }.sorted(),
        )
        // Every row except the two whose only pack is installed.
        assertEquals(catalog.rows.size - 2, catalog.availableRows.size)
    }

    @Test
    fun downloadStateWinsOverAvailableButNeverOverInstalled() {
        val downloads = mapOf(
            "cy" to PackState.Downloading(1_000L, 2_000L),
            "af" to PackState.Failed(IntegrityException("sha256", "a", "b")),
            // A stale entry for a pack that is on the phone: the disk is the truth.
            "jv" to PackState.Failed(IntegrityException("sha256", "a", "b")),
        )

        val catalog = PackCatalog.from(packs, phone, downloads)

        assertEquals(PackState.Downloading(1_000L, 2_000L), catalog.item("cy")!!.state)
        assertEquals(0.5f, (catalog.item("cy")!!.state as PackState.Downloading).progress, 0.0001f)
        assertTrue(catalog.item("af")!!.state is PackState.Failed)
        assertEquals(
            PackState.Installed(PackState.Origin.CUSTOM),
            catalog.item("jv")!!.state,
        )
    }

    @Test
    fun aDownloadingPackIsNotOfferedAsAvailableButItsRowStaysVisible() {
        val catalog = PackCatalog.from(
            packs,
            InstalledPacks(),
            mapOf("cy" to PackState.Downloading(0L, 200_000L)),
        )
        val welsh = catalog.rows.single { it.locale == "cy" }

        assertTrue(welsh.hasAnythingAvailable)
        assertFalse(welsh.hasAnythingInstalled)
        assertEquals(0f, (welsh.base.state as PackState.Downloading).progress, 0.0001f)
    }

    @Test
    fun provenanceComesFromTheManifest() {
        val manifest = PackFixtures.realManifest().withProvenance(fromCache = true, fetchedAt = 1_700_000_000_000L)

        val catalog = PackCatalog.from(manifest, InstalledPacks())!!

        assertTrue("the screen must be able to say the list came off disk", catalog.fromCache)
        assertEquals(1_700_000_000_000L, catalog.fetchedAt)
    }

    @Test
    fun aManifestWithNoPacksIsNoCatalogueRatherThanAnEmptyOne() {
        val manifest = PackFixtures.realManifest().copy(packs = null)

        assertNull(PackCatalog.from(manifest, InstalledPacks()))
    }

    @Test
    fun aVariantWhoseBaseIsNotPublishedBecomesARowOfItsOwn() {
        // Never published, but dropping such an entry would make a pack silently invisible.
        val orphan = PackFixtures.entry("xx_YY", name = "Orphan", group = "xx", size = 10L)
        val withOrphan = packs.copy(items = packs.items + orphan)

        val catalog = PackCatalog.from(withOrphan, InstalledPacks())

        assertNotNull(catalog.rows.singleOrNull { it.locale == "xx_YY" })
        assertEquals(PackFixtures.PUBLISHED_ITEM_COUNT + 1, catalog.items.size)
    }
}
