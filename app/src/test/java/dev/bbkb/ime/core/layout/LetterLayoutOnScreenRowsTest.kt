package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.keyboard.internal.KeySpecParser
import dev.bbkb.ime.keyboard.internal.KeyboardTextsSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.Locale

/**
 * The on-screen letter rows and the documents they are generated from agree: the round trip of
 * `xt9kdb-scaffold/tools/letter_layouts_gen.py`'s second direction, read back with a reader of the
 * test's own and the app's text tables.
 *
 * For every shipped document for the on-screen keyboard (Colemak, Dvorak, and QWERTY, AZERTY and
 * QWERTZ, whose rows are the app's own), the keys its `rows_<id>.xml` puts on each letter row —
 * the wrapper's own keys first, then the `rowkeys_<id>N.xml` — are, position by position, the
 * document's keys at the positions its rows template names; for a generated row also with the
 * document's hold lists once the `!text/` references are resolved in the English table (the table
 * the documents' lists come from), where the rows kept by hand hold the keyboard language's own
 * lists. An edit to a generated row, or to a document without rerunning the generator, fails here.
 * The rows template also has to match the engine's on-screen KDB of that name, which is what the
 * engine decodes the drawn keys against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutOnScreenRowsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun mainDir(): File {
        val dir = sequenceOf("src/main", "app/src/main", "../app/src/main")
            .map { File(it).canonicalFile }
            .firstOrNull { File(it, "res/xml").isDirectory }
        assertNotNull("app/src/main not found from ${File(".").canonicalPath}", dir)
        return dir!!
    }

    private fun xml(name: String): File = File(mainDir(), "res/xml/$name.xml")

    private val latin = "http://schemas.android.com/apk/res-auto"

    /** A `<Key>` as the row files spell it: its key spec and hold list, unresolved. */
    private data class RowKey(val keySpec: String, val moreKeys: String?)

    private fun parser(file: File): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            .apply { setInput(file.reader(Charsets.UTF_8)) }

    /**
     * The keys [file] contributes, in order, through `<include>`s; a `<switch>` contributes its
     * `<default>`, the unshifted alphabet. Keys without a key spec (Shift, Delete) are not letter
     * positions and are left out. Returned by row when [rows] is set: each `<Row>`'s keys.
     */
    private fun keysOf(file: File): List<RowKey> {
        val out = ArrayList<RowKey>()
        val p = parser(file)
        var skipDepth = 0
        val switches = ArrayList<Boolean>() // per open <switch>: whether a branch was taken
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    if (skipDepth > 0) { skipDepth++; continue }
                    when (p.name) {
                        "switch" -> switches += false
                        "case" -> skipDepth = 1 // a shifted or per-mode variant; the default is the alphabet
                        "default" -> if (switches.last()) skipDepth = 1 else switches[switches.size - 1] = true
                        "include" -> out += keysOf(xml(p.getAttributeValue(latin, "keyboardLayout").removePrefix("@xml/")))
                        "Key" -> p.getAttributeValue(latin, "keySpec")?.let { out += RowKey(it, p.getAttributeValue(latin, "moreKeys")) }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (skipDepth > 0) { skipDepth--; continue }
                    if (p.name == "switch") switches.removeAt(switches.size - 1)
                }
            }
        }
        return out
    }

    /** The letter keys of each `<Row>` of `rows_<id>.xml`, the wrapper's and the included ones. */
    private fun rowsOf(id: String): List<List<RowKey>> {
        val rows = ArrayList<List<RowKey>>()
        val p = parser(xml("rows_$id"))
        var current: ArrayList<RowKey>? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "Row" -> current = ArrayList()
                    "include" -> current?.addAll(keysOf(xml(p.getAttributeValue(latin, "keyboardLayout").removePrefix("@xml/"))))
                    "Key" -> p.getAttributeValue(latin, "keySpec")?.let { current?.add(RowKey(it, p.getAttributeValue(latin, "moreKeys"))) }
                }
            } else if (p.eventType == XmlPullParser.END_TAG && p.name == "Row") {
                rows += current!!
                current = null
            }
        }
        return rows
    }

    private val templates = mapOf(
        "qwerty" to listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").map { row -> row.map { "KEYCODE_$it" } },
        "spanish" to listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").map { row -> row.map { "KEYCODE_$it" } }
            .let { listOf(it[0], it[1] + "KEYCODE_SEMICOLON", it[2]) },
    )

    private fun onScreenDocuments(): List<LetterLayout> {
        val registry = LetterLayoutRegistry(context)
        return registry.builtInIds().mapNotNull { registry.builtIn(it) }.filter { LetterLayout.Target.VKB in it.targets }
    }

    /** Whether a document's rows are the app's own: its first row file was not generated. */
    private fun rowsKeptByHand(id: String): Boolean =
        xml("rowkeys_${id}1").readLines(Charsets.UTF_8).getOrNull(1)?.startsWith("<!-- GENERATED") != true

    @Test
    fun theDocumentsForTheOnScreenKeyboard() {
        assertEquals(listOf("azerty", "colemak", "dvorak", "qwerty", "qwertz"), onScreenDocuments().map { it.id }.sorted())
        assertEquals(
            "the three standard arrangements draw the rows the app always had",
            listOf("azerty", "qwerty", "qwertz"),
            onScreenDocuments().map { it.id }.filter(::rowsKeptByHand).sorted(),
        )
    }

    @Test
    fun theDrawnRowsAreTheDocumentsKeys() {
        val texts = KeyboardTextsSet().apply { setLocale(Locale.ENGLISH, context) }
        fun list(raw: String?): List<String> =
            KeySpecParser.splitKeySpecs(texts.getResolvedText(raw))?.toList().orEmpty()
                .filterNot { it == "%" || Regex("^!(?:[A-Za-z]+!|icon/|code/|text/)").containsMatchIn(it) }
        for (layout in onScreenDocuments()) {
            val template = templates[layout.rowsTemplate]
            assertNotNull("${layout.id}: rows template ${layout.rowsTemplate}", template)
            val rows = rowsOf(layout.id)
            assertEquals("${layout.id}: three letter rows", 3, rows.size)
            for ((row, codes) in template!!.withIndex()) {
                val drawn = rows[row]
                assertEquals("${layout.id} row ${row + 1}: one key per template position", codes.size, drawn.size)
                for ((position, code) in codes.withIndex()) {
                    val key = layout.keys[KeyEvent.keyCodeFromString(code)]
                    assertNotNull("${layout.id} names $code", key)
                    val where = "${layout.id} row ${row + 1} $code"
                    assertEquals("$where keySpec", LetterLayout.string(key!!.keySpec), texts.getResolvedText(drawn[position].keySpec))
                    if (!rowsKeptByHand(layout.id)) assertEquals("$where moreKeys", key.moreKeys.toList(), list(drawn[position].moreKeys))
                }
            }
        }
    }

    @Test
    fun theGeneratedRowsSayWhereTheyComeFrom_andTheWrapperDoesNot() {
        for (layout in onScreenDocuments()) {
            for (n in 1..3) {
                if (rowsKeptByHand(layout.id)) break
                val lines = xml("rowkeys_${layout.id}$n").readLines(Charsets.UTF_8)
                assertTrue("rowkeys_${layout.id}$n opens with the declaration", lines[0].startsWith("<?xml"))
                assertTrue("rowkeys_${layout.id}$n is generated", lines[1].startsWith("<!-- GENERATED by xt9kdb-scaffold/tools/letter_layouts_gen.py"))
            }
            assertFalse("rows_${layout.id} is kept by hand", xml("rows_${layout.id}").readText().contains("<!-- GENERATED"))
            // The source document itself is kept by hand too.
            val document = context.assets.open("letter_layouts/${layout.id}.xml").use { it.readBytes().toString(Charsets.UTF_8) }
            assertFalse("${layout.id}.xml is a source document", document.contains("<!-- GENERATED"))
        }
    }

    @Test
    fun theRowsTemplateIsTheShapeOfTheEnginesKdbOfThatName() {
        for (layout in onScreenDocuments()) {
            val file = File(mainDir(), "assets/kdb/${LetterLayoutKdb.templateFile(layout.rowsTemplate!!)}")
            assertTrue("${file.name} exists", file.isFile)
            val perRow = Regex("<area>(.*?)</area>", RegexOption.DOT_MATCHES_ALL).findAll(file.readText())
                .map { area -> Regex("""keyType="regional"""").findAll(area.groupValues[1]).count() }
                .take(3).toList()
            assertEquals(layout.id, templates.getValue(layout.rowsTemplate!!).map { it.size }, perRow)
            assertEquals("the drawn rows have the template's shape", perRow, rowsOf(layout.id).map { it.size })
        }
    }

    @Test
    fun theLayoutSetsBuildTheSameKeypadKeyboardsAsQwerty() {
        // Chosen for a language, a layout's set stands in for the language's own, so the keypad's
        // keyboards (and the input menu bar) must not change with it.
        val element = Regex("""elementKeyboard="([^"]+)"\s+latin:elementName="([^"]+)"""")
        fun elements(set: String) = element.findAll(xml("keyboard_layout_set_$set").readText())
            .associate { it.groupValues[2] to it.groupValues[1] }
        val qwerty = elements("qwerty")
        for (layout in onScreenDocuments()) {
            val own = elements(layout.id)
            assertEquals("@xml/kbd_${layout.id}", own["alphabet"])
            assertEquals(layout.id, qwerty - "alphabet", own - "alphabet")
        }
    }
}
