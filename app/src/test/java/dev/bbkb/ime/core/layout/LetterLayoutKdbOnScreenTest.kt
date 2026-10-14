package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.LetterLayoutKdb.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The engine's on-screen KDBs rewritten from a letter layout ([LetterLayoutKdb] with
 * [Template.VKB]), on the shipped `qwerty_vkb.xml` and `spanish_vkb.xml`: the positions of the
 * on-screen rows, the letters a rewritten key gets, that nothing but those letters changes, and the
 * KDB built for a language from a rows template's geometry ([LetterLayoutKdb.forOnScreenKeyboard]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutKdbOnScreenTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun kdbDir(): File {
        val dir = sequenceOf("src/main/assets/kdb", "app/src/main/assets/kdb", "../app/src/main/assets/kdb")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("assets/kdb not found from ${File(".").canonicalPath}", dir)
        return dir!!
    }

    private fun vkb(name: String): String = File(kdbDir(), "${name}_vkb.xml").readText(Charsets.UTF_8)

    private fun layout(keys: String, id: String = "test", rows: String = "qwerty") = LetterLayoutXml.parse(
        """<letter-layout version="1" id="$id" name="Test" targets="pkb,vkb">
            <bind/>
            <rows template="$rows"/>
            $keys
        </letter-layout>"""
    )

    private val registry by lazy { LetterLayoutRegistry(context) }
    private val colemak get() = registry.builtIn("colemak")!!
    private val dvorak get() = registry.builtIn("dvorak")!!

    private fun letters(xml: String): String {
        val byCode = LetterLayoutKdb.lettersByKeyCode(xml, Template.VKB)
        return listOf("QWERTYUIOP", "ASDFGHJKL;", "ZXCVBNM").joinToString("|") { row ->
            row.map { c ->
                val code = if (c == ';') KeyEvent.KEYCODE_SEMICOLON else KeyEvent.KEYCODE_A + (c - 'A')
                byCode[code]?.let { String(Character.toChars(it)) } ?: "·"
            }.joinToString("")
        }
    }

    private val geometry = Regex("""\b(keyLeft|keyTop|keyWidth|keyHeight)="[^"]*"""")

    /** Every key element's geometry attributes, in file order. */
    private fun geometryOf(xml: String): List<String> =
        Regex("<key\\b[^>]*>").findAll(xml).map { key -> geometry.findAll(key.value).joinToString(" ") { it.value } }.toList()

    @Test
    fun theOnScreenRowsArePlacedLeftToRight() {
        assertEquals("qwertyuiop|asdfghjkl·|zxcvbnm", letters(vkb("qwerty")))
        // Spanish has ten keys in the middle row: the tenth is the semicolon position.
        assertEquals("qwertyuiop|asdfghjklñ|zxcvbnm", letters(vkb("spanish")))
        // The bottom row (comma, space, period) and the function keys have no positions.
        assertNull(LetterLayoutKdb.codesByKeyCode(vkb("qwerty"), Template.VKB)[KeyEvent.KEYCODE_COMMA])
    }

    @Test
    fun colemakOnTheSpanishGeometry_changesOnlyTheLetters() {
        val before = vkb("spanish")
        val after = LetterLayoutKdb.rewrite(before, colemak, Template.VKB)
        assertEquals("qwfpgjluy;|arstdhneio|zxcvbkm", letters(after))
        assertEquals("geometry untouched", geometryOf(before), geometryOf(after))
        assertEquals("the header untouched", before.substringBefore("<area>"), after.substringBefore("<area>"))
        assertEquals("the bottom row untouched", before.substringAfterLast("<area>"), after.substringAfterLast("<area>"))
        // The semicolon on P is a symbol: written as a code, and out of tap correction like the
        // files' other symbols; o where ñ was is a letter in the label form.
        assertTrue(after.contains("""<key keyType="nonRegional" keyCodes="0x003B" keyWidth="108dp" keyHeight="140dp" keyTop="9dp" keyLeft="972dp" />"""))
        assertTrue(after.contains("""<key keyType="regional" keyLabel="O" keyWidth="108dp" keyHeight="140dp" keyTop="158dp" keyLeft="972dp" />"""))
        // A key the layout leaves as it is stays byte for byte.
        assertTrue(after.contains("""<key keyType="regional" keyLabel="Q" keyWidth="108dp" keyHeight="140dp" keyTop="9dp" keyLeft="0dp" />"""))
    }

    @Test
    fun dvorakPutsItsPunctuationOnTheFirstThreeKeys() {
        val after = LetterLayoutKdb.rewrite(vkb("spanish"), dvorak, Template.VKB)
        assertEquals("',.pyfgcrl|aoeuidhtns|jkxbmwv", letters(after))
        val codes = LetterLayoutKdb.codesByKeyCode(after, Template.VKB)
        // The apostrophe carries its Shift legend as an alternate, as a script key carries its own.
        assertEquals(listOf('\''.code, '"'.code), codes[KeyEvent.KEYCODE_Q]!!.toList())
        assertTrue(after.contains("""<key keyType="nonRegional" keyCodes="0x0027, 0x0022" keyWidth="108dp" keyHeight="140dp" keyTop="9dp" keyLeft="0dp" />"""))
    }

    @Test
    fun aQwertyTemplateLayoutFillsTheQwertyRows() {
        val swap = layout("""<key code="KEYCODE_Q" keySpec="w"/><key code="KEYCODE_W" keySpec="q"/><key code="KEYCODE_SEMICOLON" keySpec="ö"/>""")
        val after = LetterLayoutKdb.rewrite(vkb("qwerty"), swap, Template.VKB)
        // The qwerty rows have no tenth middle key, so the semicolon position names nothing there.
        assertEquals("wqertyuiop|asdfghjkl·|zxcvbnm", letters(after))
        assertEquals(geometryOf(vkb("qwerty")), geometryOf(after))
        val qwerty = vkb("qwerty")
        val unchanged = layout("""<key code="KEYCODE_Q" keySpec="q"/>""")
        assertSame("nothing to change: the same text back", qwerty, LetterLayoutKdb.rewrite(qwerty, unchanged, Template.VKB))
    }

    @Test
    fun theKdbForALanguageHasItsIdsAndTheTemplatesRows() {
        val english = LetterLayoutKdb.forOnScreenKeyboard(vkb("spanish"), vkb("qwerty"), colemak)!!
        assertEquals(9 to 1, LetterLayoutKdb.engineIds(english))
        assertEquals("qwfpgjluy;|arstdhneio|zxcvbkm", letters(english))
        assertEquals("the spanish geometry: ten keys in the middle row", geometryOf(vkb("spanish")), geometryOf(english))
        // Japanese romaji's KDB has a secondary id of its own; the copy keeps it.
        val japanese = LetterLayoutKdb.forOnScreenKeyboard(vkb("spanish"), vkb("japanese"), colemak)!!
        assertEquals(17 to 5, LetterLayoutKdb.engineIds(japanese))
        // Everything outside the ids is the template's, rewritten.
        assertEquals(
            LetterLayoutKdb.rewrite(vkb("spanish"), colemak, Template.VKB).replace("primaryId=\"10\"", "primaryId=\"9\""),
            english,
        )
    }

    @Test
    fun theEngineIdsAreReadAndReplacedInTheKeyboardElementOnly() {
        assertEquals(9 to 1, LetterLayoutKdb.engineIds(vkb("qwerty")))
        assertEquals(10 to 1, LetterLayoutKdb.engineIds(vkb("spanish")))
        val stamped = LetterLayoutKdb.withEngineIds(vkb("qwerty"), 7, 1)!!
        assertEquals(7 to 1, LetterLayoutKdb.engineIds(stamped))
        assertEquals("only the id changed", vkb("qwerty").replaceFirst("primaryId=\"9\"", "primaryId=\"7\""), stamped)
        val commented = "<!-- <keyboard primaryId=\"1\" secondaryId=\"2\"> -->\n<keyboard primaryId=\"3\" secondaryId=\"4\">"
        assertEquals(3 to 4, LetterLayoutKdb.engineIds(commented))
        assertNull(LetterLayoutKdb.engineIds("<keyboard primaryId=\"3\">"))
        assertNull(LetterLayoutKdb.withEngineIds("<area/>", 1, 1))
    }

    @Test
    fun oneVariantNameForBothKeyboards() {
        assertEquals("layout:colemak", LetterLayoutKdb.variantName(colemak, Template.PKB))
        assertEquals("layout:colemak", LetterLayoutKdb.variantName(colemak, Template.VKB))
        assertEquals("spanish_vkb.xml", LetterLayoutKdb.templateFile("spanish"))
        assertEquals("_vkb.xml", Template.VKB.fileSuffix)
    }
}
