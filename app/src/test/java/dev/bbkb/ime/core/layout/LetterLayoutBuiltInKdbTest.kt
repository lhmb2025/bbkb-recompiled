package dev.bbkb.ime.core.layout

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The engine's keypad KDBs built from a device's geometry file and the built-in letter layouts,
 * against the per-language files the app used to ship, which `xt9kdb-scaffold/reference/kdb_pkb/`
 * keeps for this comparison: which keys changed, and how.
 *
 * Which shipped file a keyboard loaded was measured on the emulator on 2026-10-15 (every keyboard
 * switched through, the `XT9KDB SetKdbNum` log read): most Latin keyboards share English's file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutBuiltInKdbTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Keyboard (language, layout set) -> the KDB file the engine loaded for it, measured. */
    private val measured: List<Triple<String, String?, String>> = listOf(
        "en" to null, "af" to "qwerty", "az" to "qwerty", "bs" to "qwerty", "cy" to "qwerty", "ga" to "qwerty",
        "in" to "qwerty", "is" to "qwerty", "it" to null, "jv" to "qwerty", "lt" to "qwerty", "lv" to "qwerty",
        "ms" to "qwerty", "nl" to null, "pl" to null, "pt" to "qwerty", "ro" to "qwerty", "sk" to "qwerty",
        "sl" to "qwerty", "sq" to "qwerty", "sr" to "qwerty", "su" to "qwerty", "uz" to "qwerty", "vi" to "qwerty",
        "zh" to "pinyin", "zh" to "cangjie",
    ).map { (l, s) -> Triple(l, s, "qwerty") } + listOf(
        Triple("cs", null, "qwertz"), Triple("de", null, "qwertz"), Triple("hr", null, "qwertz"), Triple("hu", null, "qwertz"),
        Triple("es", null, "spanish"), Triple("eu", "spanish", "spanish"), Triple("fil", "spanish", "spanish"), Triple("gl", "spanish", "spanish"),
        Triple("fi", null, "finnish"), Triple("sv", null, "finnish"),
        Triple("fr", null, "azerty"),
        Triple("ru", "east_slavic", "east_slavic"), Triple("ru", "translit", "east_slavic"),
        Triple("ar", "arabic", "arabic"), Triple("be", "east_slavic", "belarusian"), Triple("be", "translit", "belarusian"),
        Triple("ca", "spanish", "catalan"), Triple("da", null, "danish"), Triple("el", "greek", "greek"),
        Triple("iw", "hebrew", "hebrew"), Triple("ja", "romaji", "japanese"), Triple("ko", "hangul", "korean"),
        Triple("nb", null, "norwegian"), Triple("tr", null, "turkish"), Triple("uk", "east_slavic", "ukranian"),
        Triple("uk", "translit", "ukranian"), Triple("et", "nordic", "estonian"),
    )

    private fun dir(vararg candidates: String): File {
        val dir = candidates.asSequence().flatMap { sequenceOf(it, "../$it", "app/$it") }
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("${candidates.toList()} not found", dir)
        return dir!!
    }

    private fun kdbDir() = dir("src/main/assets/kdb")

    private fun referenceDir() = dir("xt9kdb-scaffold/reference/kdb_pkb", "../xt9kdb-scaffold/reference/kdb_pkb")

    private fun reference(file: String) = File(referenceDir(), "${file}_pkb.xml").readText(Charsets.UTF_8)

    /** KEYCODE_A..Z and the key past M: the names a keypad KDB's positions take. */
    private fun keyName(keyCode: Int): String = when (keyCode) {
        in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z -> "KEYCODE_" + ('A' + (keyCode - android.view.KeyEvent.KEYCODE_A))
        android.view.KeyEvent.KEYCODE_4 -> "KEYCODE_4"
        else -> keyCode.toString()
    }

    private fun show(codes: IntArray?): String =
        codes?.joinToString(" ") { String(Character.toChars(it)) } ?: "-"

    private val keyElement = Regex("<key\\b[^>]*>")
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    /** Every `<key>` element outside comments, white space collapsed, letters and type left out: the geometry. */
    private fun geometry(xml: String): List<String> =
        keyElement.findAll(comment.replace(xml, "")).map { key ->
            key.value.replace(Regex("\\s+(keyLabel|keyCodes|keyType)=\"[^\"]*\""), "").replace(Regex("\\s+"), " ")
                .replace(Regex("\\s*/?>$"), ">")
        }.toList()

    private fun keyTypes(xml: String): List<String?> =
        keyElement.findAll(comment.replace(xml, "")).map { Regex("keyType=\"([^\"]*)\"").find(it.value)?.groupValues?.get(1) }.toList()

    /**
     * Every key whose engine letters the built KDB changes against the shipped file the keyboard
     * loaded. Only additions, each a letter the key types: the Shift letter of an Arabic, Hebrew or
     * Korean key, and Turkish's dotted i on the I key, which types i (the shipped file had only the
     * dotless ı there).
     */
    private val expectedDifferences = setOf(
        "ar/arabic KEYCODE_4 [ط] -> [ط ى]",
        "ar/arabic KEYCODE_B [ة] -> [ة ء]",
        "iw/hebrew KEYCODE_B [נ] -> [נ ן]",
        "iw/hebrew KEYCODE_F [כ] -> [כ ך]",
        "iw/hebrew KEYCODE_N [מ] -> [מ ם]",
        "ko/hangul KEYCODE_E [ᄃ] -> [ᄃ ᄄ]",
        "ko/hangul KEYCODE_O [ᅢ] -> [ᅢ ᅤ]",
        "ko/hangul KEYCODE_P [ᅦ] -> [ᅦ ᅨ]",
        "ko/hangul KEYCODE_Q [ᄇ] -> [ᄇ ᄈ]",
        "ko/hangul KEYCODE_R [ᄀ] -> [ᄀ ᄁ]",
        "ko/hangul KEYCODE_T [ᄉ] -> [ᄉ ᄊ]",
        "ko/hangul KEYCODE_W [ᄌ] -> [ᄌ ᄍ]",
        "tr/- KEYCODE_I [ı] -> [i ı]",
    )

    /**
     * What the engine loads for each measured keyboard while its built-in layout applies (the
     * device's file for the id, rewritten with the keyboard's own document), against the shipped
     * file: the same ids, key boxes and key types, the same letters but for the pinned additions.
     * For both of the KEY2's geometries' even twin and the generic keypad.
     */
    @Test
    fun eachKeyboardsBuiltKdbHoldsTheShippedLetters_exceptThePinnedAdditions() {
        val registry = LetterLayoutRegistry(context)
        for (device in listOf(KeypadKdbs.GENERIC, "athena")) {
            val template = File(kdbDir(), KeypadKdbs.templateFile(device)).readText(Charsets.UTF_8)
            val built = KeypadKdbs.forDevice(template, KeypadKdbs.shippedLetters(registry))
            val missing = ArrayList<String>()
            val differences = HashSet<String>()
            for ((language, set, file) in measured) {
                val doc = registry.builtIn(language, "qwerty", null, set)
                if (doc == null) { missing += "$language/$set"; continue }
                val shipped = reference(file)
                val ids = LetterLayoutKdb.engineIds(shipped)!!
                val base = built[ids.first]
                assertNotNull("$device: no built file for id ${ids.first} ($file)", base)
                val loaded = LetterLayoutKdb.rewrite(base!!, doc, Template.PKB)
                assertEquals("$device/$file ids", ids, LetterLayoutKdb.engineIds(loaded))
                assertEquals("$device/$file key boxes", geometry(shipped), geometry(loaded))
                assertEquals("$device/$file key types", keyTypes(shipped), keyTypes(loaded))
                val before = LetterLayoutKdb.codesByKeyCode(shipped)
                val after = LetterLayoutKdb.codesByKeyCode(loaded)
                (before.keys + after.keys).filter { !before[it].contentEquals(after[it]) }.forEach {
                    differences += "$language/${set ?: "-"} ${keyName(it)} [${show(before[it])}] -> [${show(after[it])}]"
                }
            }
            assertEquals("$device: keyboards with no built-in document", emptyList<String>(), missing)
            assertEquals(device, expectedDifferences, differences)
        }
    }

    /** Every id the app no longer ships a keypad file for is built, under the id its file had. */
    @Test
    fun everyRemovedFileIsBuilt_underItsOwnIds() {
        val removed = referenceDir().listFiles()!!.filter { it.name.endsWith("_pkb.xml") }
            .associate { LetterLayoutKdb.engineIds(it.readText(Charsets.UTF_8))!!.first to it.name }
        assertEquals(removed.keys.sorted(), KeypadKdbs.SHIPPED.map { it.primaryId }.sorted())
        val shippedNow = kdbDir().listFiles()!!.filter { it.name.endsWith(".xml") && it.name.contains("_pkb") }
        for (file in shippedNow) {
            val ids = LetterLayoutKdb.engineIds(file.readText(Charsets.UTF_8))
            if (KeypadKdbs.isTemplate(file.name)) continue
            assertEquals("${file.name} still ships, so its id must not be built", false, ids!!.first in removed.keys)
        }
    }

    /** The KEY2's file has the generic keypad's key boxes; only the header differs. */
    @Test
    fun theKey2sKeypadIsTheGenericEvenGeometry() {
        val generic = File(kdbDir(), KeypadKdbs.templateFile(KeypadKdbs.GENERIC)).readText(Charsets.UTF_8)
        val athena = File(kdbDir(), KeypadKdbs.templateFile("athena")).readText(Charsets.UTF_8)
        assertEquals(geometry(generic), geometry(athena))
        assertEquals(9 to 6, LetterLayoutKdb.engineIds(generic))
        assertEquals(0 to 0, LetterLayoutKdb.engineIds(athena))
        val test = File(kdbDir(), KeypadKdbs.templateFile("athena_test")).readText(Charsets.UTF_8)
        assertEquals(0 to 0, LetterLayoutKdb.engineIds(test))
        assertEquals(geometry(generic).size, geometry(test).size)
    }
}
