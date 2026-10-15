package dev.bbkb.ime.keyboard

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.layout.DrawnKeyboard
import dev.bbkb.ime.core.layout.LetterLayoutKdb
import dev.bbkb.ime.core.layout.OnScreenKdbs
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Every shipped keyboard's on-screen keyboard, built headless from the real XML and read
 * ([DrawnKeyboards]), against the on-screen KDB the engine loaded for it when the app shipped one
 * per language (kept in `xt9kdb-scaffold/reference/kdb_vkb/`, or still shipped as a template).
 *
 * Which file a keyboard loaded is its engine id's, measured on the emulator on 2026-10-15 (the
 * Phase 2 table of the one-KDB-per-device plan): most Latin keyboards ask for English's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawnKeyboardsTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardBuilder.clearKeyboardCache()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
    }

    /** Language -> the engine id its on-screen keyboard asks for; anything else asks for English's (9). */
    private val idOf = mapOf(
        "ar" to 1, "bg" to 2, "ca" to 3, "da" to 6, "el" to 8, "iw" to 13, "ja" to 17, "ko" to 18, "nb" to 20,
        "th" to 30, "tr" to 31, "uk" to 34, "be" to 35, "et" to 37, "fa" to 41, "lo" to 43, "km" to 44, "mk" to 47,
        "hi" to 57, "bn" to 75, "hy" to 90, "ka" to 96, "kn" to 99, "ml" to 106, "mn" to 107, "mr" to 109,
        "ta" to 132, "te" to 133, "cs" to 7, "de" to 7, "hr" to 7, "hu" to 7, "es" to 10, "eu" to 10, "fil" to 10,
        "gl" to 10, "fi" to 11, "sv" to 11, "fr" to 12, "ru" to 25, "ky" to 25,
    )

    /**
     * The keyboards whose drawn primary letters differ from the file they loaded: the mismatches
     * building from the drawn keyboard fixes. Belgian Dutch and German draw AZERTY but loaded the
     * QWERTY and QWERTZ files; Canadian French draws QWERTY but loaded AZERTY's. Greek's first key
     * types a semicolon (U+003B) where the file had the Greek question mark (U+037E) that looks
     * the same.
     */
    private val expectedMismatches = setOf("fr_CA", "nl_BE", "de_BE", "el")

    private fun subtypes(): List<InputMethodSubtype> {
        val out = ArrayList<InputMethodSubtype>()
        val parser = app.resources.getXml(R.xml.method)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "subtype") continue
            var locale = ""
            var extra = ""
            for (i in 0 until parser.attributeCount) {
                when (parser.getAttributeName(i)) {
                    "imeSubtypeLocale" -> locale = parser.getAttributeValue(i)
                    "imeSubtypeExtraValue" -> extra = parser.getAttributeValue(i)
                }
            }
            out += InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeLocale(locale).setSubtypeMode("keyboard").setSubtypeExtraValue(extra).build()
        }
        return out
    }

    private fun drawn(subtype: InputMethodSubtype): DrawnKeyboard {
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val builder = KeyboardBuilder.Builder(BoardKeyboardFactory.themedContext(app), editor)
            .setKeyboardGeometry(1080, 900).setSubtype(subtype).build()
        return DrawnKeyboards.read(builder.getKeyboardForShift(0, false), builder.getKeyboardForShift(1, false))!!
    }

    private fun dir(vararg candidates: String): File =
        candidates.asSequence().map { File(it).canonicalFile }.first { it.isDirectory }

    private val kdbDir by lazy { dir("src/main/assets/kdb", "app/src/main/assets/kdb") }
    private val referenceDir by lazy { dir("../xt9kdb-scaffold/reference/kdb_vkb", "xt9kdb-scaffold/reference/kdb_vkb") }

    private val templates by lazy {
        kdbDir.listFiles()!!.filter { OnScreenKdbs.isTemplate(it.name) }.associate { it.name to it.readText(Charsets.UTF_8) }
    }

    /** The on-screen file with [primaryId] (keyboard type 1, or 5 for Japanese), shipped or kept for reference. */
    private fun loadedFile(primaryId: Int): String =
        (kdbDir.listFiles()!! + referenceDir.listFiles()!!).filter { it.name.endsWith("_vkb.xml") }
            .map { it.readText(Charsets.UTF_8) }
            .first { LetterLayoutKdb.engineIds(it).let { ids -> ids != null && ids.first == primaryId && ids.second in setOf(1, 5) } }

    private val keyElement = Regex("<key\\b[^>]*>")
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    private fun geometry(xml: String): List<String> =
        keyElement.findAll(comment.replace(xml, "")).map { key ->
            key.value.replace(Regex("\\s+(keyLabel|keyCodes|keyType|keyCodesShifted)=\"[^\"]*\""), "").replace(Regex("\\s+"), " ")
                .replace(Regex("\\s*/?>$"), ">")
        }.toList()

    private fun primaries(xml: String): List<List<Int>> {
        val rows = Regex("<area\\b[^>]*>(.*?)</area", RegexOption.DOT_MATCHES_ALL).findAll(comment.replace(xml, "")).toList().dropLast(1)
        return rows.map { area ->
            keyElement.findAll(area.groupValues[1]).map { it.value }
                .filter { !it.contains("keyType=\"function\"") && !it.contains("keyName") }
                .map { key ->
                    Regex("keyLabel=\"([A-Za-z])\"").find(key)?.let { it.groupValues[1].lowercase()[0].code }
                        ?: Regex("keyCodes=\"\\s*(0x)?([0-9A-Fa-f]+)").find(key)?.groupValues?.get(2)?.toInt(16)
                        ?: Regex("keyLabel=\"([^\"]+)\"").find(key)!!.groupValues[1].codePointAt(0)
                }.toList()
        }
    }

    @Test
    fun everyKeyboardIsBuiltOnTheGeometryItLoaded_withTheLettersItDraws() {
        val mismatched = HashSet<String>()
        var checked = 0
        for (subtype in subtypes()) {
            val drawn = drawn(subtype)
            if (drawn.layoutSet in OnScreenKdbs.KEPT_LAYOUT_SETS) continue
            val locale = subtype.locale
            val language = locale.substringBefore('_').let { if (it == "tl") "fil" else it }
            val loaded = loadedFile(idOf[language] ?: 9)
            val template = OnScreenKdbs.choose(templates, drawn)
            assertNotNull("$locale (${drawn.layoutSet} ${drawn.shape}): no template", template)
            assertEquals("$locale: the geometry it loaded", geometry(loaded), geometry(templates.getValue(template!!)))
            val built = OnScreenKdbs.build(templates.getValue(template), drawn)
            assertNotNull("$locale: built", built)
            assertEquals(OnScreenKdbs.IDS.size, built!!.size)
            val drawnPrimaries = drawn.rows.map { row ->
                row.map { LetterLayoutKdb.toConjoining(Character.toLowerCase(it.codes[0])) }
            }
            assertEquals("$locale: the built file has the drawn letters", drawnPrimaries, primaries(built[0]))
            if (primaries(loaded) != drawnPrimaries) mismatched += locale
            checked++
        }
        assertEquals(expectedMismatches, mismatched)
        assertEquals("every keyboard but the Chinese stroke, zhuyin and Cangjie ones", 84, checked)
    }
}
