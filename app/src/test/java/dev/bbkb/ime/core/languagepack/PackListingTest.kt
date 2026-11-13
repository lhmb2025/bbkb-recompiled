package dev.bbkb.ime.core.languagepack

import dev.bbkb.ime.core.distribution.Packs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins [PackListing] against the real `dist/manifest.json`: one flat list, alphabetical by the
 * name on the row, with the four `group` packs in their own alphabetical place. Robolectric
 * because [PackFixtures] parses with `org.json`, an empty stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PackListingTest {

    private val packs: Packs = PackFixtures.realPacks()

    /** German, French, Italian and Dutch ship with the app; nothing else is installed. */
    private val withShippedBases = InstalledPacks(shipped = setOf("de", "fr", "it", "nl"))

    private fun available(installed: InstalledPacks = InstalledPacks()) =
        PackListing.available(PackCatalog.from(packs, installed).rows, Locale.ENGLISH)

    @Test
    fun everyUninstalledPackIsItsOwnRowIncludingTheVariants() {
        val rows = available()
        assertEquals(PackFixtures.PUBLISHED_ITEM_COUNT, rows.size)
        assertEquals(
            packs.items.map { it.locale }.toSet(),
            rows.map { it.locale }.toSet(),
        )
    }

    @Test
    fun aVariantSaysWhichLanguageItLoadsInPlaceOf() {
        val byLocale = available().associateBy { it.locale }
        for ((variant, base) in PackFixtures.VARIANTS) {
            val row = byLocale.getValue(variant)
            assertEquals(
                "$variant must name the language it replaces",
                packs.forLocale(base)!!.name,
                row.replaces,
            )
        }
    }

    @Test
    fun anOrdinaryLanguageReplacesNothing() {
        val welsh = available().single { it.locale == "cy" }
        assertNull(welsh.replaces)
    }

    @Test
    fun anInstalledBaseLanguageIsNotRepeatedAboveItsVariants() {
        val rows = available(withShippedBases)
        val locales = rows.map { it.locale }

        // The four shipped languages are in the Installed list, so they are not here at all...
        for (base in PackFixtures.VARIANTS.values.toSet()) {
            assertFalse("$base is installed and must not be offered", base in locales)
        }
        // ...but their downloadable regional dictionaries still are, as ordinary rows.
        for (variant in PackFixtures.VARIANTS.keys) {
            assertTrue("$variant must still be offered", variant in locales)
        }
        assertEquals(PackFixtures.PUBLISHED_ITEM_COUNT - 4, rows.size)
    }

    @Test
    fun anInstalledVariantDropsOutOfTheList() {
        val installed = withShippedBases.copy(
            variants = mapOf("de" to InstalledPacks.Group(activeTag = "de_CH", tags = setOf("de_CH"))),
        )
        val locales = available(installed).map { it.locale }
        assertFalse("de_CH is installed", "de_CH" in locales)
        assertTrue("fr_CH is not", "fr_CH" in locales)
    }

    @Test
    fun theCachedCollatorIsHandedOutAsAFreshSecondaryStrengthCopy() {
        val first = PackListing.collator(Locale.GERMAN)
        assertEquals(java.text.Collator.SECONDARY, first.strength)
        first.strength = java.text.Collator.IDENTICAL

        val second = PackListing.collator(Locale.GERMAN)
        assertFalse("one instance per call", first === second)
        assertEquals("a caller's change does not reach the cache", java.text.Collator.SECONDARY, second.strength)
        val fresh = java.text.Collator.getInstance(Locale.GERMAN).apply { strength = java.text.Collator.SECONDARY }
        for ((a, b) in listOf("Äpfel" to "Apfel", "apfel" to "Apfel", "Zulu" to "Åland", "Ärzte" to "Arzt")) {
            assertEquals("$a vs $b", Integer.signum(fresh.compare(a, b)), Integer.signum(second.compare(a, b)))
        }
    }

    @Test
    fun theWholeListIsAlphabeticalByTheNameOnTheRow() {
        val names = available().map { it.displayName }
        val collator = PackListing.collator(Locale.ENGLISH)
        for (i in 1 until names.size) {
            assertTrue(
                "${names[i - 1]} must not sort after ${names[i]}",
                collator.compare(names[i - 1], names[i]) <= 0,
            )
        }
    }

    @Test
    fun aVariantSitsWhereItsOwnNameBelongsEvenWhenItsBaseIsNotInTheList() {
        // Dutch ships, so it is absent here, but "Dutch (Belgium)" still sorts among the D's.
        val names = available(withShippedBases).map { it.displayName }
        val belgian = names.indexOf("Dutch (Belgium)")
        assertTrue("Dutch (Belgium) must be offered", belgian > 0)
        assertFalse("Dutch itself is installed", "Dutch" in names)
        assertEquals("Danish", names[belgian - 1])
        assertEquals("English", names[belgian + 1])
    }

    @Test
    fun sortingIgnoresCaseAndUsesTheLocalesOwnAlphabet() {
        // Code-point order puts "Zebra" before "apple" and "ärzte" after "zulu"; a collator does not.
        val names = listOf("zulu", "Banana", "ärzte", "apple", "Zebra")
        assertEquals(
            listOf("apple", "ärzte", "Banana", "Zebra", "zulu"),
            PackListing.byDisplayName(names, Locale.ENGLISH) { it },
        )
    }

    @Test
    fun sortingIsStableForTwoPacksWithTheSameName() {
        // Ties break on the locale identifier, so the list cannot shuffle between compositions.
        val rows = PackListing.available(
            listOf(
                PackCatalog.Row(item("nn_NO", "Norwegian")),
                PackCatalog.Row(item("nb_NO", "Norwegian")),
            ),
            Locale.ENGLISH,
        )
        assertEquals(listOf("nb_NO", "nn_NO"), rows.map { it.locale })
    }

    private fun item(locale: String, name: String) = PackCatalog.Item(
        entry = PackFixtures.entry(locale = locale, name = name),
        state = PackState.Available(0L),
    )
}
