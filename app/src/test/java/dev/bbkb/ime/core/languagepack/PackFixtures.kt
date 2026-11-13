package dev.bbkb.ime.core.languagepack

import android.content.Context
import dev.bbkb.ime.core.distribution.DistributionManifest
import dev.bbkb.ime.core.distribution.DistributionManifestParser
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Shared language-pack fixtures. [realManifest] is the published `dist/manifest.json`, which holds
 * the interesting cases: four `group` variants and four catalogue-only regions (`es_419`, `zh_TW`,
 * `zh_HK`, `en_ZH`).
 */
object PackFixtures {

    /** Items in the published catalogue. A change here is a real change and should be noticed. */
    const val PUBLISHED_ITEM_COUNT = 109

    /** The four packs that load in place of a base language, and the language each replaces. */
    val VARIANTS = mapOf("de_CH" to "de", "fr_CH" to "fr", "it_CH" to "it", "nl_BE" to "nl")

    /** The four packs whose region is in the catalogue, not the file name; install needs the manifest locale. */
    val TABLE_LOCALE_PACKS = listOf("es_419", "zh_TW", "zh_HK", "en_ZH")

    /** `dist/manifest.json`, however the test happened to be launched (cwd is usually `app/`). */
    fun manifestFile(): File {
        val file = sequenceOf("../dist/manifest.json", "dist/manifest.json", "../../dist/manifest.json")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isFile }
        assertTrue("dist/manifest.json not found from ${File(".").canonicalPath}", file != null)
        return file!!
    }

    fun realManifest(): DistributionManifest =
        DistributionManifestParser.parse(manifestFile().readText())

    fun realPacks(): Packs = requireNotNull(realManifest().packs) { "manifest has no packs" }

    /** A one-entry catalogue pointing at [baseUrl], for the download tests. */
    fun packsOf(baseUrl: String, vararg entries: PackEntry, version: String = "1902.01"): Packs =
        Packs(version = version, baseUrl = baseUrl, items = entries.toList())

    fun entry(
        locale: String,
        file: String = "$locale.ldb",
        sha256: String = "",
        size: Long = 0L,
        name: String = locale,
        group: String? = null,
    ): PackEntry = PackEntry(
        locale = locale,
        name = name,
        file = file,
        sha256 = sha256,
        size = size,
        group = group,
    )

    /** `no_backup/nuance/<locale>/`, where an installed pack's files belong. */
    fun packDir(context: Context, locale: String): File =
        File(context.noBackupFilesDir, "nuance/$locale")

    /** `no_backup/lang_variants/<group>/<tag>.ldb`, where an inactive variant is parked. */
    fun parkedVariant(context: Context, group: String, tag: String): File =
        File(context.noBackupFilesDir, "lang_variants/$group/$tag.ldb")

    fun customRegistry(context: Context): File = File(context.filesDir, "nuance_custom_packs.json")

    fun variantRegistry(context: Context): File =
        File(context.filesDir, "nuance_language_variants.json")

    /** A file big enough to pass [PackInstallService.MIN_LDB_BYTES], with recognisable bytes. */
    fun ldbBytes(seed: Int = 7, size: Int = 4_096): ByteArray =
        ByteArray(size) { ((it + seed) % 251).toByte() }

    /** A [PackInstallService.SubtypeRegistrar] fixture; the real one needs a registered IME. */
    class FakeSubtypes(
        private val builtIn: Set<String> = emptySet(),
        private val offerable: Set<String> = emptySet(),
        private val canEnable: Boolean = true,
    ) : PackInstallService.SubtypeRegistrar {
        val offered = mutableListOf<String>()
        val withdrawn = mutableListOf<String>()
        val enabled = mutableListOf<String>()

        override fun enableSubtypeFor(context: Context, locale: String): Boolean {
            enabled += locale
            return canEnable
        }

        override fun hasBuiltInSubtypeFor(language: String): Boolean = language in builtIn

        override fun offerSubtypeFor(context: Context, language: String): Boolean {
            offered += language
            return language in offerable
        }

        override fun withdrawSubtypeFor(context: Context, language: String) {
            withdrawn += language
        }
    }
}
