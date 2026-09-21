package dev.bbkb.ime.personaldictionary

import dev.bbkb.ime.personaldictionary.model.LoadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup allowlists must name the files the personal-dictionary store actually writes.
 *
 * Where the store writes: `DictionaryManager.doInitBasl` hands `context.getFilesDir()` to
 * `PersonalDictionaryManager`, `PersonalDictionaryUtil.setupPduDir` appends
 * [PersonalDictionaryUtil.PERSONAL_DICTIONARY_UTIL_DIR], and `doSave` writes one file per
 * [LoadConfig] there. `domain="file"` is `getFilesDir()`, so each include must be
 * `<pdu dir>/<LoadConfig filename>`.
 *
 * Both XML files are read from source, so no Android runtime is involved: an allowlist that names
 * a path nothing writes is silently a no-op on device, which is exactly how this broke.
 */
class BackupRulesTest {

    private fun resource(name: String): File {
        val rel = "src/main/res/xml/$name"
        val file = File(rel).takeIf { it.isFile } ?: File("app/$rel")
        assertTrue("$name not found from ${File(".").canonicalPath}", file.isFile)
        return file
    }

    /** Every `<include>` under [section] (or the whole document when null), as (domain, path). */
    private fun includes(file: File, section: String?): List<Pair<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root: Element = if (section == null) {
            doc.documentElement
        } else {
            doc.getElementsByTagName(section).item(0) as Element? ?: error("no <$section> in ${file.name}")
        }
        val nodes = root.getElementsByTagName("include")
        return (0 until nodes.length).map {
            val e = nodes.item(it) as Element
            e.getAttribute("domain") to e.getAttribute("path")
        }
    }

    /** The relative-to-filesDir paths of every file the store writes. */
    private val storeFiles: Set<String> =
        LoadConfig.entries.map {
            PersonalDictionaryUtil.PERSONAL_DICTIONARY_UTIL_DIR.trimStart('/') + "/" + it.filename
        }.toSet()

    private val allowlists: Map<String, List<Pair<String, String>>> by lazy {
        mapOf(
            "backupscheme.xml" to includes(resource("backupscheme.xml"), null),
            "data_extraction_rules.xml <cloud-backup>" to
                includes(resource("data_extraction_rules.xml"), "cloud-backup"),
            "data_extraction_rules.xml <device-transfer>" to
                includes(resource("data_extraction_rules.xml"), "device-transfer"),
        )
    }

    @Test
    fun theStoreWritesThreeFilesUnderTheBaslPduDirectory() {
        assertEquals(
            setOf(
                "dev.bbkb.ime.basl.pdu/substitutions",
                "dev.bbkb.ime.basl.pdu/deleted_substitutions",
                "dev.bbkb.ime.basl.pdu/personal_words",
            ),
            storeFiles
        )
    }

    /** Every file include is a store file, and every store file is included, in all three lists. */
    @Test
    fun theFileIncludesAreExactlyTheFilesTheStoreWrites() {
        allowlists.forEach { (where, list) ->
            val filePaths = list.filter { it.first == "file" }.map { it.second }
            assertEquals(where, storeFiles, filePaths.toSet())
            assertEquals("$where has a duplicate include", filePaths.size, filePaths.toSet().size)
        }
    }
}
