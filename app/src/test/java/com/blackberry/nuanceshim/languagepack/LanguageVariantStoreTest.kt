package com.blackberry.nuanceshim.languagepack

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
 * Regional variants sharing one engine locale's slot.
 *
 * `de_CH`, `fr_CH`, `it_CH` and `nl_BE` have no entry in the ET9 core's locale table, so they can
 * only load AS their base language. The engine finds a dictionary by reading whatever `*.ldb` sits
 * in `no_backup/nuance/<locale>/` — there is no register call — so "activate a variant" is a file
 * swap, and these scenarios are about that swap being correct and reversible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LanguageVariantStoreTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun slotDir(locale: String) =
        LanguagePackInstaller.getLanguagePackDirectory(context, locale)

    private fun activeLdb(locale: String) = File(slotDir(locale), "$locale.ldb")

    private fun sourceLdb(name: String, contents: String): File {
        val f = File(context.cacheDir, name)
        f.writeText(contents)
        return f
    }

    @Before
    fun clean() {
        LanguageVariantStore.file(context).delete()
        LanguagePackInstaller.deleteRecursively(File(context.noBackupFilesDir, "lang_variants"))
        LanguagePackInstaller.deleteRecursively(File(context.noBackupFilesDir, "nuance"))
    }

    // ── installing a variant ───────────────────────────────────────────────────

    @Test
    fun installingAVariantParksItAndMakesItActive() {
        val src = sourceLdb("de_CH.ldb", "SWISS-GERMAN")
        assertTrue(LanguageVariantStore.installVariant(
            context, "de", "de_CH", "German (Switzerland)", src))

        // active: the engine's slot holds the variant's bytes
        assertEquals("SWISS-GERMAN", activeLdb("de").readText())
        // getInstalledLocales() needs a version.txt beside it or the pack does not count
        assertTrue(File(slotDir("de"), "version.txt").exists())
        // parked: the only durable copy of a side-loaded variant
        assertEquals("SWISS-GERMAN",
            LanguageVariantStore.variantFile(context, "de", "de_CH").readText())

        val g = LanguageVariantStore.groupFor(context, "de")!!
        assertEquals("de_CH", g.activeTag)
        assertEquals(2, g.members.size)
    }

    @Test
    fun theFirstVariantSeedsAWayBackToTheShippedPack() {
        // Without this the user could never return to the pack the APK came with.
        LanguageVariantStore.installVariant(
            context, "de", "de_CH", "German (Switzerland)", sourceLdb("a.ldb", "CH"))
        val g = LanguageVariantStore.groupFor(context, "de")!!
        val shipped = g.members.single { it.isShipped }
        assertEquals(LanguageVariantStore.TAG_SHIPPED, shipped.tag)
        assertEquals(0L, shipped.installedAt)
        // named for a human, not a locale code
        assertTrue(shipped.name.contains("German"))
    }

    @Test
    fun parkedVariantsAreNotMistakenForInstalledLocales() {
        // lang_variants/ is a SIBLING of nuance/. Parking inside nuance/ would make
        // getInstalledLocales() report locales that do not exist.
        LanguageVariantStore.installVariant(
            context, "de", "de_CH", "German (Switzerland)", sourceLdb("a.ldb", "CH"))
        val installed = LanguagePackInstaller.getInstalledLocales(context)
        assertTrue("de must be installed", installed.contains("de"))
        assertFalse("de_CH must NOT be its own locale", installed.contains("de_CH"))
        assertFalse(installed.contains("lang_variants"))
    }

    @Test
    fun theMostRecentlyInstalledVariantWins() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        LanguageVariantStore.installVariant(context, "de", "de_AT", "Austrian", sourceLdb("b.ldb", "AT"))
        assertEquals("de_AT", LanguageVariantStore.groupFor(context, "de")!!.activeTag)
        assertEquals("AT", activeLdb("de").readText())
    }

    // ── toggling ───────────────────────────────────────────────────────────────

    @Test
    fun activatingAnotherVariantSwapsTheFileInTheEnginesSlot() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        LanguageVariantStore.installVariant(context, "de", "de_AT", "Austrian", sourceLdb("b.ldb", "AT"))

        assertTrue(LanguageVariantStore.activate(context, "de", "de_CH"))

        assertEquals("CH", activeLdb("de").readText())
        assertEquals("de_CH", LanguageVariantStore.groupFor(context, "de")!!.activeTag)
        // and the other one is still parked, not lost
        assertEquals("AT", LanguageVariantStore.variantFile(context, "de", "de_AT").readText())
    }

    @Test
    fun activatingShippedRemovesOurDirectorySoTheEngineFallsBackToAssets() {
        // The preinstalled pack lives in the APK. The only way to "select" it is to stop shadowing
        // it — there is no file of ours to put back.
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        assertTrue(slotDir("de").isDirectory)

        assertTrue(LanguageVariantStore.activate(context, "de", LanguageVariantStore.TAG_SHIPPED))

        assertFalse("the slot must be empty for the assets fallback", slotDir("de").exists())
        assertEquals(LanguageVariantStore.TAG_SHIPPED,
            LanguageVariantStore.groupFor(context, "de")!!.activeTag)
        // the variant survives being deselected
        assertEquals("CH", LanguageVariantStore.variantFile(context, "de", "de_CH").readText())
    }

    @Test
    fun switchingBackAndForthIsLossless() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        repeat(3) {
            LanguageVariantStore.activate(context, "de", LanguageVariantStore.TAG_SHIPPED)
            LanguageVariantStore.activate(context, "de", "de_CH")
        }
        assertEquals("CH", activeLdb("de").readText())
        assertEquals(2, LanguageVariantStore.groupFor(context, "de")!!.members.size)
    }

    @Test
    fun activatingSomethingThatIsNotAMemberChangesNothing() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        assertFalse(LanguageVariantStore.activate(context, "de", "de_XX"))
        assertEquals("CH", activeLdb("de").readText())
        assertEquals("de_CH", LanguageVariantStore.groupFor(context, "de")!!.activeTag)
    }

    @Test
    fun aGroupThatDoesNotExistCannotBeActivated() {
        assertFalse(LanguageVariantStore.activate(context, "fr", "fr_CH"))
    }

    // ── removing ───────────────────────────────────────────────────────────────

    @Test
    fun removingTheActiveVariantPromotesTheNextMostRecent() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        LanguageVariantStore.installVariant(context, "de", "de_AT", "Austrian", sourceLdb("b.ldb", "AT"))
        assertEquals("de_AT", LanguageVariantStore.groupFor(context, "de")!!.activeTag)

        assertTrue(LanguageVariantStore.removeVariant(context, "de", "de_AT"))

        assertEquals("de_CH", LanguageVariantStore.groupFor(context, "de")!!.activeTag)
        assertEquals("CH", activeLdb("de").readText())
        assertFalse(LanguageVariantStore.variantFile(context, "de", "de_AT").exists())
    }

    @Test
    fun removingTheLastVariantRestoresTheShippedPackAndDropsTheGroup() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))

        assertTrue(LanguageVariantStore.removeVariant(context, "de", "de_CH"))

        assertNull("a group with only the shipped pack is not a group",
            LanguageVariantStore.groupFor(context, "de"))
        assertFalse("the slot must be empty so assets are used again", slotDir("de").exists())
        assertFalse(LanguageVariantStore.variantDir(context, "de").exists())
    }

    @Test
    fun theShippedMemberCannotBeRemoved() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss", sourceLdb("a.ldb", "CH"))
        assertFalse(LanguageVariantStore.removeVariant(
            context, "de", LanguageVariantStore.TAG_SHIPPED))
        assertNotNull(LanguageVariantStore.groupFor(context, "de"))
    }

    @Test
    fun removingFromAGroupThatDoesNotExistIsHarmless() {
        assertTrue(LanguageVariantStore.removeVariant(context, "fr", "fr_CH"))
    }

    // ── guards ─────────────────────────────────────────────────────────────────

    @Test
    fun theShippedTagIsReservedAndCannotBeInstalledOver() {
        assertFalse(LanguageVariantStore.installVariant(
            context, "de", LanguageVariantStore.TAG_SHIPPED, "x", sourceLdb("a.ldb", "X")))
        assertNull(LanguageVariantStore.groupFor(context, "de"))
    }

    @Test
    fun aTagThatCouldEscapeTheVariantDirectoryIsRefused() {
        // Both the locale and the tag become path segments under no_backup/.
        for (bad in listOf("..", "../x", "a/b", "", "toolong")) {
            assertFalse("accepted tag $bad", LanguageVariantStore.installVariant(
                context, "de", bad, "x", sourceLdb("a.ldb", "X")))
            assertFalse("accepted locale $bad", LanguageVariantStore.installVariant(
                context, bad, "de_CH", "x", sourceLdb("a.ldb", "X")))
        }
        assertFalse(LanguageVariantStore.file(context).exists())
    }

    @Test
    fun aCorruptStoreReadsAsNoGroupsRatherThanThrowing() {
        LanguageVariantStore.file(context).writeText("{\"groups\": [{\"locale\":")
        assertEquals(0, LanguageVariantStore.read(context).size)
    }

    @Test
    fun twoLanguagesGroupIndependently() {
        LanguageVariantStore.installVariant(context, "de", "de_CH", "Swiss German", sourceLdb("a.ldb", "DE-CH"))
        LanguageVariantStore.installVariant(context, "fr", "fr_CH", "Swiss French", sourceLdb("b.ldb", "FR-CH"))
        assertEquals("DE-CH", activeLdb("de").readText())
        assertEquals("FR-CH", activeLdb("fr").readText())
        LanguageVariantStore.activate(context, "de", LanguageVariantStore.TAG_SHIPPED)
        assertFalse(slotDir("de").exists())
        assertEquals("FR-CH", activeLdb("fr").readText())
    }
}
