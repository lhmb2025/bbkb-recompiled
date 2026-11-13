package dev.bbkb.ime.core.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins what the manifest parser accepts (the app installs APKs on its word): real published shapes
 * plus malformed ones. Robolectric because `org.json` is an empty stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DistributionManifestParserTest {

    private val full = """
        {
          "schema": 1,
          "generated": "2026-09-22T00:00:00Z",
          "app": {
            "debug":   {"versionCode": 1430, "versionName": "5.0.0-beta.18-debug",
                        "url": "https://example.test/bbkb-debug.apk",
                        "sha256": "AABBCC", "size": 12345678, "minSdk": 23,
                        "notes": "short release notes"},
            "release": {"versionCode": 1431, "versionName": "5.0.0-beta.18",
                        "url": "https://example.test/bbkb.apk",
                        "sha256": "ddeeff", "size": 12345679, "minSdk": 24}
          },
          "packs": {
            "version": "1902.01",
            "baseUrl": "https://example.test/releases/download/packs-1902.01/",
            "items": [
              {"locale": "af", "name": "Afrikaans",
               "file": "Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb",
               "sha256": "0011", "size": 4400000},
              {"locale": "de_CH", "name": "German (Switzerland)",
               "file": "Blackberry_1305_r1-20_DEusUNCH_xt9_ALM3.ldb",
               "sha256": "2233", "size": 3843297, "group": "de"},
              {"locale": "es_419", "name": "Spanish (Latin America)",
               "file": "es419.ldb", "sha256": "4455", "size": 100}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun parsesEveryFieldOfASchema1Manifest() {
        val manifest = DistributionManifestParser.parse(full)

        assertEquals(1, manifest.schema)
        assertEquals("2026-09-22T00:00:00Z", manifest.generated)
        assertEquals(setOf("debug", "release"), manifest.apps.keys)

        val release = manifest.appFor("release")!!
        assertEquals(1431, release.versionCode)
        assertEquals("5.0.0-beta.18", release.versionName)
        assertEquals("https://example.test/bbkb.apk", release.url)
        assertEquals("ddeeff", release.sha256)
        assertEquals(12345679L, release.size)
        assertEquals(24, release.minSdk)
        assertNull("notes is optional", release.notes)

        val debug = manifest.appFor("debug")!!
        assertEquals("short release notes", debug.notes)
        assertEquals("a hex digest is normalised to lowercase", "aabbcc", debug.sha256)

        val packs = manifest.packs!!
        assertEquals("1902.01", packs.version)
        assertEquals(3, packs.items.size)
    }

    @Test
    fun parserOutputCarriesNoProvenance() {
        // Only ManifestSource knows where bytes came from; a bare parse must not imply freshness.
        val manifest = DistributionManifestParser.parse(full)
        assertFalse(manifest.fromCache)
        assertEquals(0L, manifest.fetchedAt)
    }

    @Test
    fun roundTripsUnchanged() {
        // A round trip must be a no-op, so no field is quietly dropped.
        val once = DistributionManifestParser.parse(full)
        val twice = DistributionManifestParser.parse(reserialise(once))
        assertEquals(once, twice)
    }

    @Test
    fun unknownFieldsAreIgnoredAtEveryLevel() {
        val withExtras = """
            {
              "schema": 1,
              "generated": "2026-09-22T00:00:00Z",
              "futureTopLevel": {"anything": [1, 2, 3]},
              "app": {
                "release": {"versionCode": 1431, "versionName": "n", "url": "https://e.test/a.apk",
                            "sha256": "ab", "size": 1, "minSdk": 23,
                            "signedBy": "somebody", "channelWeight": 0.5}
              },
              "packs": {
                "version": "1902.01", "baseUrl": "https://e.test/p/", "futureKey": true,
                "items": [
                  {"locale": "af", "name": "Afrikaans", "file": "af.ldb", "sha256": "cd",
                   "size": 2, "compression": "zstd", "engine": {"alm": 3}}
                ]
              }
            }
        """.trimIndent()

        val manifest = DistributionManifestParser.parse(withExtras)
        assertEquals(1431, manifest.appFor("release")!!.versionCode)
        assertEquals("Afrikaans", manifest.packFor("af")!!.name)
    }

    @Test
    fun aSchemaAboveOneIsRejectedWithTheSchemaItSaw() {
        val failure = runCatching {
            DistributionManifestParser.parse(full.replace("\"schema\": 1", "\"schema\": 2"))
        }.exceptionOrNull()

        assertTrue("got $failure", failure is UnsupportedSchemaException)
        assertEquals(2, (failure as UnsupportedSchemaException).schema)
        assertTrue(
            "the message must say the app is too old, not that the file is broken",
            failure.message!!.contains("too old")
        )
    }

    @Test
    fun aSchemaBelowOneIsRejectedToo() {
        // Negative control for the above: the check is "not 1", not "greater than 1".
        val failure = runCatching {
            DistributionManifestParser.parse(full.replace("\"schema\": 1", "\"schema\": 0"))
        }.exceptionOrNull()
        assertTrue("got $failure", failure is UnsupportedSchemaException)
    }

    @Test
    fun aMissingOrNonIntegerSchemaIsAFormatError() {
        for (bad in listOf("""{"app": {}}""", """{"schema": "1"}""", """{"schema": 1.5}""")) {
            val failure = runCatching { DistributionManifestParser.parse(bad) }.exceptionOrNull()
            assertTrue("$bad -> $failure", failure is ManifestFormatException)
        }
    }

    @Test
    fun trailingContentAfterTheObjectIsAFormatError() {
        // org.json's tokener stops after one value, so the parser must reject trailing content itself.
        for (bad in listOf("""{"schema": 1} x""", """{"schema": 1}{"schema": 1}""")) {
            val failure = runCatching { DistributionManifestParser.parse(bad) }.exceptionOrNull()
            assertTrue("$bad -> $failure", failure is ManifestFormatException)
        }
        assertEquals(1, DistributionManifestParser.parse("  {\"schema\": 1}  \n").schema)
    }

    @Test
    fun numbersAndStringsAreNotCoercedIntoEachOther() {
        // A float, a quoted number or an out-of-range number where an integer belongs drops the
        // entry; a number where a string belongs does the same. JSON null reads as absent.
        fun release(fields: String) = DistributionManifestParser.parse(
            """{"schema": 1, "app": {"release": {$fields}}}"""
        ).appFor("release")
        val rest = """"url": "https://e.test/a.apk", "sha256": "ab""""
        assertNull(release(""""versionCode": 1430.0, "versionName": "n", $rest, "size": 1"""))
        assertNull(release(""""versionCode": "1430", "versionName": "n", $rest, "size": 1"""))
        assertNull(release(""""versionCode": 3000000000, "versionName": "n", $rest, "size": 1"""))
        assertNull(release(""""versionCode": 1430, "versionName": 5, $rest, "size": 1"""))
        assertNull(release(""""versionCode": 1430, "versionName": "n", $rest, "size": 1.5"""))
        assertNull(release(""""versionCode": 1430, "versionName": null, $rest, "size": 1"""))
        val big = release(""""versionCode": 1430, "versionName": "n", $rest, "size": 3000000000,
            "minSdk": null, "notes": null""")!!
        assertEquals("a size beyond Int is a Long, not a reject", 3_000_000_000L, big.size)
        assertEquals(0, big.minSdk)
        assertNull(big.notes)
        assertEquals(
            "\"app\": null is no channels, not a structural error",
            emptyMap<String, AppBuild>(),
            DistributionManifestParser.parse("""{"schema": 1, "app": null, "packs": null}""").apps
        )
    }

    @Test
    fun malformedJsonIsAFormatErrorNotACrash() {
        for (bad in listOf("", "   ", "not json at all", "{\"schema\": 1", "[1,2,3]", "null")) {
            val failure = runCatching { DistributionManifestParser.parse(bad) }.exceptionOrNull()
            assertTrue("$bad -> $failure", failure is ManifestFormatException)
        }
    }

    @Test
    fun tryParseReportsTheSameFailuresAsAResult() {
        assertTrue(DistributionManifestParser.tryParse(full).isSuccess)
        val failure = DistributionManifestParser
            .tryParse(full.replace("\"schema\": 1", "\"schema\": 9"))
        assertTrue(failure.exceptionOrNull() is UnsupportedSchemaException)
    }

    @Test
    fun aMissingBuildTypeIsNullNotAnError() {
        // The published manifest carries `release` only; a debug-channel lookup comes back empty.
        val releaseOnly = """
            {"schema": 1,
             "app": {"release": {"versionCode": 1431, "versionName": "n",
                                 "url": "https://e.test/a.apk", "sha256": "ab", "size": 1,
                                 "minSdk": 23}}}
        """.trimIndent()

        val manifest = DistributionManifestParser.parse(releaseOnly)
        assertNull(manifest.appFor("debug"))
        assertTrue(manifest.appFor("release") != null)
        assertNull("an unknown build type behaves the same way", manifest.appFor("staging"))
    }

    @Test
    fun anAbsentAppObjectLeavesNoChannels() {
        val manifest = DistributionManifestParser.parse("""{"schema": 1}""")
        assertEquals(emptyMap<String, AppBuild>(), manifest.apps)
        assertNull(manifest.appFor("release"))
        assertNull(manifest.packs)
    }

    @Test
    fun aBrokenChannelIsDroppedWithoutTakingTheOthersDown() {
        // A half-written `release` entry must not hide the debug channel.
        val oneBroken = """
            {"schema": 1,
             "app": {
               "release": {"versionName": "no version code here", "url": "https://e.test/a.apk"},
               "debug":   {"versionCode": 1430, "versionName": "d", "url": "https://e.test/d.apk",
                           "sha256": "ab", "size": 1, "minSdk": 23}
             }}
        """.trimIndent()

        val manifest = DistributionManifestParser.parse(oneBroken)
        assertNull(manifest.appFor("release"))
        assertEquals(1430, manifest.appFor("debug")!!.versionCode)
    }

    @Test
    fun anAppValueThatIsNotAnObjectIsAStructuralError() {
        val failure = runCatching {
            DistributionManifestParser.parse("""{"schema": 1, "app": [1, 2]}""")
        }.exceptionOrNull()
        assertTrue("got $failure", failure is ManifestFormatException)
    }

    @Test
    fun minSdkDefaultsToZeroWhenOmitted() {
        val noMinSdk = """
            {"schema": 1, "app": {"release": {"versionCode": 2, "versionName": "n",
             "url": "https://e.test/a.apk", "sha256": "ab", "size": 1}}}
        """.trimIndent()
        val build = DistributionManifestParser.parse(noMinSdk).appFor("release")!!
        assertEquals(0, build.minSdk)
        assertTrue(build.isInstallableOn(23))
    }

    @Test
    fun isNewerThanIsStrictlyGreater() {
        val build = AppBuild(1430, "n", "https://e.test/a.apk", "ab", 1L, 23, null)
        assertTrue(build.isNewerThan(1429))
        assertTrue(build.isNewerThan(0))
        assertFalse("the same build must never prompt", build.isNewerThan(1430))
        assertFalse("a newer local build must never be downgraded", build.isNewerThan(1431))
    }

    @Test
    fun isInstallableOnComparesAgainstMinSdk() {
        val build = AppBuild(1430, "n", "https://e.test/a.apk", "ab", 1L, 26, null)
        assertFalse(build.isInstallableOn(23))
        assertTrue(build.isInstallableOn(26))
        assertTrue(build.isInstallableOn(36))
    }

    @Test
    fun packForMatchesTheAppsOwnLocaleIdentifiers() {
        val manifest = DistributionManifestParser.parse(full)
        assertEquals("Afrikaans", manifest.packFor("af")!!.name)
        assertEquals("German (Switzerland)", manifest.packFor("de_CH")!!.name)
        assertEquals("Spanish (Latin America)", manifest.packFor("es_419")!!.name)
    }

    @Test
    fun packForDoesNotFallBackOrNormalise() {
        // These are the app's own identifiers, not BCP-47 tags: no de-CH, no case folding, and
        // de_CH must not answer a request for de.
        val manifest = DistributionManifestParser.parse(full)
        assertNull(manifest.packFor("de"))
        assertNull(manifest.packFor("de-CH"))
        assertNull(manifest.packFor("de_ch"))
        assertNull(manifest.packFor("AF"))
    }

    @Test
    fun groupIsPresentOnlyOnAVariantPack() {
        val manifest = DistributionManifestParser.parse(full)
        val swiss = manifest.packFor("de_CH")!!
        assertEquals("de", swiss.group)
        assertTrue(swiss.isVariant)

        val afrikaans = manifest.packFor("af")!!
        assertNull(afrikaans.group)
        assertFalse(afrikaans.isVariant)

        assertEquals(listOf(swiss), manifest.packs!!.variantsOf("de"))
        assertEquals(emptyList<PackEntry>(), manifest.packs!!.variantsOf("af"))
    }

    @Test
    fun packUrlIsBaseUrlPlusFileWithExactlyOneSlash() {
        val entry = PackEntry("af", "Afrikaans", "af.ldb", "ab", 1L)
        val withSlash = Packs("1902.01", "https://e.test/p/", listOf(entry))
        val withoutSlash = Packs("1902.01", "https://e.test/p", listOf(entry))

        assertEquals("https://e.test/p/af.ldb", entry.url(withSlash))
        assertEquals("https://e.test/p/af.ldb", entry.url(withoutSlash))
    }

    @Test
    fun aPackMissingARequiredFieldIsDroppedFromTheCatalogue() {
        val oneBroken = """
            {"schema": 1,
             "packs": {"version": "1", "baseUrl": "https://e.test/p/", "items": [
               {"locale": "af", "name": "Afrikaans", "file": "af.ldb", "sha256": "ab", "size": 1},
               {"locale": "zz", "name": "No file or hash"},
               "a bare string where an object belongs",
               {"locale": "nl", "file": "nl.ldb", "sha256": "cd", "size": 2}
             ]}}
        """.trimIndent()

        val packs = DistributionManifestParser.parse(oneBroken).packs!!
        assertEquals(listOf("af", "nl"), packs.items.map { it.locale })
        assertEquals("a pack with no name falls back to its locale", "nl", packs.forLocale("nl")!!.name)
    }

    @Test
    fun aDuplicateLocaleKeepsTheFirstEntry() {
        val duplicated = """
            {"schema": 1,
             "packs": {"version": "1", "baseUrl": "https://e.test/p/", "items": [
               {"locale": "af", "name": "First", "file": "a.ldb", "sha256": "ab", "size": 1},
               {"locale": "af", "name": "Second", "file": "b.ldb", "sha256": "cd", "size": 2}
             ]}}
        """.trimIndent()

        val packs = DistributionManifestParser.parse(duplicated).packs!!
        assertEquals(1, packs.items.size)
        assertEquals("First", packs.forLocale("af")!!.name)
    }

    @Test
    fun packsItemsThatIsNotAnArrayIsAStructuralError() {
        val failure = runCatching {
            DistributionManifestParser.parse(
                """{"schema": 1, "packs": {"version": "1", "baseUrl": "https://e.test/", "items": {}}}"""
            )
        }.exceptionOrNull()
        assertTrue("got $failure", failure is ManifestFormatException)
    }

    @Test
    fun packsWithoutAVersionOrBaseUrlIsNoCatalogueAtAll() {
        // Every pack URL is built from baseUrl, so a catalogue without one is rejected whole.
        assertNull(
            DistributionManifestParser.parse("""{"schema": 1, "packs": {"items": []}}""").packs
        )
    }

    @Test
    fun anEmptyCatalogueIsValid() {
        val packs = DistributionManifestParser.parse(
            """{"schema": 1, "packs": {"version": "1", "baseUrl": "https://e.test/p/", "items": []}}"""
        ).packs!!
        assertEquals(emptyList<PackEntry>(), packs.items)
        assertNull(packs.forLocale("af"))
    }

    /** Model to JSON for the round-trip test, mirroring the wire format by hand. */
    private fun reserialise(manifest: DistributionManifest): String {
        fun app(build: AppBuild) = buildString {
            append("""{"versionCode": ${build.versionCode},""")
            append(""""versionName": "${build.versionName}",""")
            append(""""url": "${build.url}", "sha256": "${build.sha256}",""")
            append(""""size": ${build.size}, "minSdk": ${build.minSdk}""")
            build.notes?.let { append(""", "notes": "$it"""") }
            append("}")
        }
        fun pack(entry: PackEntry) = buildString {
            append("""{"locale": "${entry.locale}", "name": "${entry.name}",""")
            append(""""file": "${entry.file}", "sha256": "${entry.sha256}",""")
            append(""""size": ${entry.size}""")
            entry.group?.let { append(""", "group": "$it"""") }
            append("}")
        }
        return buildString {
            append("""{"schema": ${manifest.schema}""")
            manifest.generated?.let { append(""", "generated": "$it"""") }
            append(""", "app": {""")
            append(manifest.apps.entries.joinToString(",") { """"${it.key}": ${app(it.value)}""" })
            append("}")
            manifest.packs?.let { packs ->
                append(""", "packs": {"version": "${packs.version}",""")
                append(""""baseUrl": "${packs.baseUrl}", "items": [""")
                append(packs.items.joinToString(",") { pack(it) })
                append("]}")
            }
            append("}")
        }
    }
}
