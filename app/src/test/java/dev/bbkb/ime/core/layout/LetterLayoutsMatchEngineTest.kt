package dev.bbkb.ime.core.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File

/**
 * The built-in letter layouts (`assets/letter_layouts/<id>.xml`, written by
 * `xt9kdb-scaffold/tools/letter_layouts_gen.py`) type exactly the letters the engine decodes
 * against.
 *
 * The engine reads its key letters from `assets/kdb/<name>_pkb.xml`; the physical keys of the
 * seven languages with an alphabet of their own read theirs from the generated documents
 * ([PhysicalKeyResolver]), and `LetterLayoutEngineSync` compares a custom layout with the same
 * documents to tell whether it changes the engine's letters. If someone edits a generated file by
 * hand, or a KDB moves a letter without the generator being rerun, the keys and the engine
 * disagree: a tapped word is decoded against letters the user never typed. This test re-reads
 * both and fails on any difference.
 *
 * Both sides are read here with readers of the test's own, so the check does not lean on the code
 * it guards. The KDB position rules are the ones the old `HardwareScriptLayout.parse` used, and
 * the generator and [LetterLayoutKdb] use now: three rows on a 108dp grid in QWERTY order, row 3
 * starting with Alt, the key past M (row 3, column 8) is `KEYCODE_4` when it carries a letter,
 * function keys skipped, conjoining jamo read as compatibility jamo.
 *
 * Robolectric only for its `XmlPullParser`: the unit-test android.jar's is a stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LetterLayoutsMatchEngineTest {

    @Test
    fun everyDocumentFollowsTheFormat() {
        val docs = documents()
        assertTrue("no documents found in ${layoutsDir()}", docs.size >= 50)
        for ((file, doc) in docs) {
            assertEquals("$file: the file name is the id", file.removeSuffix(".xml"), doc.id)
            assertTrue("$file: id", Regex("[a-z0-9][a-z0-9._-]{0,63}").matches(doc.id))
            assertTrue("$file: name", doc.name.length in 1..64)
            assertTrue("$file: at most 80 keys", doc.keys.size <= 80)
            assertEquals("$file: one key per code", doc.keys.size, doc.keys.map { it.code }.toSet().size)
            assertTrue("$file: layoutSet ${doc.layoutSet}", doc.layoutSet == "any" || Regex("[a-z0-9_]{1,64}").matches(doc.layoutSet))
            for (key in doc.keys) {
                assertEquals("$file ${key.code} keySpec", 1, key.keySpec.codePointCount(0, key.keySpec.length))
                key.characterMapKeys?.let {
                    assertEquals("$file ${key.code} characterMapKeys", 1, it.codePointCount(0, it.length))
                }
                for ((field, list) in listOf("multitapKeys" to key.multitapKeys, "moreKeys" to key.moreKeys)) {
                    assertTrue("$file ${key.code} $field length", list.size <= 32)
                    for (entry in list) {
                        assertTrue("$file ${key.code} $field entry '$entry'", entry.codePointCount(0, entry.length) in 1..8)
                    }
                }
            }
        }
    }

    @Test
    fun scriptDocumentsTypeTheEngineLetters() {
        val docs = documents().values
        var checked = 0
        for ((language, kdbName) in SCRIPT_KDB) {
            val bound = docs.filter { language in it.languages }
            assertTrue("no document bound to $language", bound.isNotEmpty())
            // Each one carries one layout set's tables (plain or translit) and says which.
            for (doc in bound) assertTrue("${doc.id}: bound to a named layout set", doc.layoutSet != "any")
            assertEquals("$language: one document per layout set", bound.size, bound.map { it.layoutSet }.toSet().size)
            for (path in kdbFiles(kdbName)) {
                val engine = readKdb(path)
                for (doc in bound) {
                    val keys = doc.keys.associateBy { it.code }
                    assertEquals(
                        "${doc.id}: keys the document names vs letters ${path.name} carries",
                        engine.keys.sorted(), keys.keys.sorted(),
                    )
                    for ((code, letters) in engine) {
                        val key = keys.getValue(code)
                        assertEquals(
                            "${doc.id} $code against ${path.parentFile?.name}/${path.name}",
                            String(Character.toChars(letters.first())), key.keySpec,
                        )
                        checked++
                    }
                }
            }
        }
        // Ten documents (seven languages and three translit keyboards), each against a root and
        // an athena copy: about 530 keys. A small number would mean the loops ran empty.
        assertTrue("only $checked keys checked", checked > 400)
    }

    @Test
    fun latinDocumentsTypeTheKeyMapLetters() {
        for (doc in documents().values) {
            if (doc.languages.any { it in SCRIPT_KDB } || doc.languages.isEmpty()) continue
            assertEquals("${doc.id}: 26 letter keys", 26, doc.keys.size)
            // One Latin table serves QWERTY, QWERTZ, AZERTY and the rest alike; only a language
            // that also has a keyboard with no physical-key table (Chinese stroke and Zhuyin) has
            // one document per layout set, named after it.
            val expectedSet = if (doc.id == doc.languages.single()) "any" else doc.id.substringAfter('-')
            assertEquals("${doc.id}: layout set", expectedSet, doc.layoutSet)
            for (key in doc.keys) {
                val letter = key.code.removePrefix("KEYCODE_")
                assertEquals("${doc.id} ${key.code}", letter.lowercase(), key.keySpec)
            }
        }
    }

    // ── the readers ──────────────────────────────────────────────────────────────────────────────

    companion object {
        /**
         * The languages whose keys type their own alphabet, and the engine layout their letters
         * come from: the generator's `SCRIPT_KDB` (the old `HardwareScriptLayouts.FILES`).
         */
        val SCRIPT_KDB = linkedMapOf(
            "ar" to "arabic", "ru" to "east_slavic", "uk" to "ukranian", "be" to "belarusian",
            "iw" to "hebrew", "el" to "greek", "ko" to "korean",
        )

        private const val ROWS = "QWERTYUIOP|ASDFGHJKL|ZXCVBNM"

        class DocKey(
            val code: String,
            val scanCode: Int?,
            val keySpec: String,
            val characterMapKeys: String?,
            val alt: String?,
            val multitapKeys: List<String>,
            val moreKeys: List<String>,
        )

        class Doc(
            val id: String,
            val name: String,
            val targets: List<String>,
            val languages: List<String>,
            val keypad: String,
            val keypadType: String,
            val layoutSet: String,
            val rowsTemplate: String?,
            val keys: List<DocKey>,
        )

        fun moduleFile(vararg candidates: String): File {
            val found = candidates.asSequence().map { File(it).canonicalFile }.firstOrNull { it.exists() }
            assertNotNull("none of ${candidates.toList()} found from ${File(".").canonicalPath}", found)
            return found!!
        }

        fun layoutsDir(): File = moduleFile(
            "src/main/assets/letter_layouts", "app/src/main/assets/letter_layouts",
            "../app/src/main/assets/letter_layouts",
        )

        private fun kdbDir(): File =
            moduleFile("src/main/assets/kdb", "app/src/main/assets/kdb", "../app/src/main/assets/kdb")

        /** The root file and, when there is one, the athena (KEY2) copy. */
        fun kdbFiles(name: String): List<File> =
            listOf(File(kdbDir(), "${name}_pkb.xml"), File(kdbDir(), "athena/${name}_pkb.xml"))
                .filter { it.isFile }

        /** Every generated document by file name, in file-name order. */
        fun documents(): Map<String, Doc> =
            layoutsDir().listFiles { f -> f.name.endsWith(".xml") }!!
                .sortedBy { it.name }
                .associate { it.name to readDocument(it) }

        private fun parser(file: File): XmlPullParser =
            XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(file.reader(Charsets.UTF_8))
            }

        /** A §11 list: comma separated, `\,` a comma and `\\` a backslash. */
        fun splitList(value: String?): List<String> {
            if (value.isNullOrEmpty()) return emptyList()
            val out = ArrayList<String>()
            val current = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '\\' && i + 1 < value.length -> { current.append(value[i + 1]); i++ }
                    c == ',' -> { out.add(current.toString()); current.setLength(0) }
                    else -> current.append(c)
                }
                i++
            }
            out.add(current.toString())
            return out
        }

        fun readDocument(file: File): Doc {
            val p = parser(file)
            var id = ""
            var name = ""
            var targets = listOf("pkb")
            var languages = emptyList<String>()
            var keypad = "any"
            var keypadType = "any"
            var layoutSet = "any"
            var rows: String? = null
            val keys = ArrayList<DocKey>()
            var sawRoot = false
            var sawBind = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                val attrs = (0 until p.attributeCount).associate { p.getAttributeName(it) to p.getAttributeValue(it) }
                fun only(vararg allowed: String) {
                    val unknown = attrs.keys - allowed.toSet()
                    if (unknown.isNotEmpty()) fail("${file.name}: <${p.name}> has unknown attributes $unknown")
                }
                fun required(attribute: String): String = attrs[attribute]
                    ?: throw AssertionError("${file.name}: <${p.name}> ${attrs["code"].orEmpty()} has no $attribute")
                when (p.name) {
                    "letter-layout" -> {
                        only("version", "id", "name", "targets")
                        assertEquals("${file.name}: version", "1", attrs["version"])
                        id = required("id")
                        name = required("name")
                        attrs["targets"]?.let { targets = it.split(',') }
                        sawRoot = true
                    }
                    "bind" -> {
                        only("languages", "keypad", "keypadType", "layoutSet")
                        assertTrue("${file.name}: one <bind>", !sawBind)
                        sawBind = true
                        languages = attrs["languages"].orEmpty().split(',').filter { it.isNotEmpty() }
                        keypad = attrs["keypad"] ?: "any"
                        keypadType = attrs["keypadType"] ?: "any"
                        layoutSet = attrs["layoutSet"] ?: "any"
                    }
                    "rows" -> {
                        only("template")
                        rows = attrs["template"]
                    }
                    "key" -> {
                        only("code", "scanCode", "keySpec", "characterMapKeys", "alt", "multitapKeys", "moreKeys")
                        keys.add(DocKey(
                            code = required("code"),
                            scanCode = attrs["scanCode"]?.toInt(),
                            keySpec = required("keySpec"),
                            characterMapKeys = attrs["characterMapKeys"],
                            alt = attrs["alt"],
                            multitapKeys = splitList(attrs["multitapKeys"]),
                            moreKeys = splitList(attrs["moreKeys"]),
                        ))
                    }
                    else -> fail("${file.name}: unknown element <${p.name}>")
                }
            }
            assertTrue("${file.name}: no <letter-layout>", sawRoot)
            assertTrue("${file.name}: no <bind>", sawBind)
            return Doc(id, name, targets, languages, keypad, keypadType, layoutSet, rows, keys)
        }

        private val CHOSEONG_TO_COMPATIBILITY = intArrayOf(
            0x3131, 0x3132, 0x3134, 0x3137, 0x3138, 0x3139, 0x3141, 0x3142, 0x3143, 0x3145,
            0x3146, 0x3147, 0x3148, 0x3149, 0x314A, 0x314B, 0x314C, 0x314D, 0x314E,
        )

        private fun compatibilityJamo(codePoint: Int): Int = when (codePoint) {
            in 0x1100..0x1112 -> CHOSEONG_TO_COMPATIBILITY[codePoint - 0x1100]
            in 0x1161..0x1175 -> 0x314F + (codePoint - 0x1161)
            else -> codePoint
        }

        private fun dp(value: String?): Int = value?.removeSuffix("dp")?.trim()?.toIntOrNull() ?: 0

        /**
         * A KDB's letters by key code name: the codes of each non-function key with `keyCodes`,
         * on the QWERTY position its `keyLeft` falls in. The key past M counts only when its first
         * code is a letter (on the Greek and Korean layouts it is `$`, the currency key).
         */
        fun readKdb(file: File): Map<String, List<Int>> {
            val rows = ROWS.split('|')
            val out = LinkedHashMap<String, List<Int>>()
            val p = parser(file)
            var row = -1
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "area" -> row++
                    "key" -> {
                        if (row !in rows.indices) continue
                        if (p.getAttributeValue(null, "keyType") == "function") continue
                        val codes = p.getAttributeValue(null, "keyCodes")
                            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                            ?.map { compatibilityJamo(it.removePrefix("0x").removePrefix("0X").toInt(16)) }
                            ?.takeIf { it.isNotEmpty() } ?: continue
                        val width = dp(p.getAttributeValue(null, "keyWidth")).takeIf { it > 0 } ?: 108
                        val left = p.getAttributeValue(null, "keyLeft") ?: continue
                        val column = (dp(left) + width / 2) / width
                        val index = if (row == 2) column - 1 else column
                        when {
                            index in rows[row].indices -> out["KEYCODE_${rows[row][index]}"] = codes
                            row == 2 && column == 8 && Character.isLetter(codes[0]) -> out["KEYCODE_4"] = codes
                        }
                    }
                }
            }
            return out
        }
    }
}
