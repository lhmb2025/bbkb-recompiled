package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The physical keys type the active keyboard's alphabet, read from the engine's own hardware
 * layouts in `assets/kdb`. These tests read the shipped files straight off disk, so a KDB edit
 * that moves a letter or drops a doubled keycap fails here, on the JVM, before any device sees it.
 *
 * Expected letters come from the KEY2 keycap photos (Arabic, Russian and Korean units,
 * 2026-09-30) and from `pkbd_arabic.xml`, where BlackBerry recorded the firmware's Shift layer.
 */
class HardwareScriptLayoutTest {

    private fun kdbDir(): File {
        val dir = sequenceOf("src/main/assets/kdb", "app/src/main/assets/kdb", "../app/src/main/assets/kdb")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertTrue("assets/kdb not found from ${File(".").canonicalPath}", dir != null)
        return dir!!
    }

    private fun layout(name: String): HardwareScriptLayout? =
        File(kdbDir(), "${name}_pkb.xml").inputStream().use { HardwareScriptLayout.parse(it) }

    private fun letters(layout: HardwareScriptLayout, keys: String): String =
        keys.map { key ->
            val cell = layout.cell(KeyEvent.KEYCODE_A + (key - 'A'))
            assertNotNull("no letter on the $key key", cell)
            String(Character.toChars(cell!!.letter))
        }.joinToString("")

    private fun unshifted(cell: HardwareScriptLayout.Cell) = HardwareScriptLayouts.letterOf(cell, false) { null }
    private fun shifted(cell: HardwareScriptLayout.Cell, table: (String) -> String? = { null }) =
        HardwareScriptLayouts.letterOf(cell, true, table)

    @Test
    fun arabicKeycapsRowByRow() {
        val arabic = layout("arabic")!!
        assertEquals("صثقفغعهخحج", letters(arabic, "QWERTYUIOP"))
        assertEquals("سيبلاتنمك", letters(arabic, "ASDFGHJKL"))
        assertEquals("ذدزرةوظ", letters(arabic, "ZXCVBNM"))
        // The KEY2's `$` key carries ط.
        assertEquals("ط", String(Character.toChars(arabic.extraKey!!.letter)))
        assertEquals(26, arabic.letterKeyCount)
    }

    @Test
    fun arabicDoubledKeysGiveTheirSecondLetterOnShift() {
        val arabic = layout("arabic")!!
        val q = arabic.cell(KeyEvent.KEYCODE_Q)!!
        val a = arabic.cell(KeyEvent.KEYCODE_A)!!
        assertEquals('ص'.code, unshifted(q))
        assertEquals('ض'.code, shifted(q))
        assertEquals('س'.code, unshifted(a))
        assertEquals('ش'.code, shifted(a))
        // Keys whose Shift legend lives only in pkbd_arabic.xml (ة→ء, ط→ى) take it from the table.
        val b = arabic.cell(KeyEvent.KEYCODE_B)!!
        val table = mapOf("ة" to "ء", "ط" to "ى")
        assertEquals('ء'.code, shifted(b) { table[it] })
        assertEquals('ى'.code, shifted(arabic.extraKey!!) { table[it] })
        // Without a table entry a single-letter key stays itself under Shift.
        assertEquals('ث'.code, shifted(arabic.cell(KeyEvent.KEYCODE_W)!!))
    }

    @Test
    fun russianKeycapsRowByRow() {
        val russian = layout("east_slavic")!!
        assertEquals("йцукенгшщх", letters(russian, "QWERTYUIOP"))
        assertEquals("фывапролд", letters(russian, "ASDFGHJKL"))
        assertEquals("ячсмитьб", letters(russian, "ZXCVBNM") + String(Character.toChars(russian.extraKey!!.letter)))
        // Cased script: Shift is upper case, the doubled legend is the double-tap alternate.
        val q = russian.cell(KeyEvent.KEYCODE_Q)!!
        assertEquals('Й'.code, shifted(q))
        assertArrayEquals(intArrayOf('ё'.code), q.alternates)
        assertArrayEquals(intArrayOf('ю'.code), russian.extraKey!!.alternates)
    }

    @Test
    fun koreanIsTheTwoSetLayoutInCompatibilityJamo() {
        val korean = layout("korean")!!
        assertEquals("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", letters(korean, "QWERTYUIOP"))
        assertEquals("ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", letters(korean, "ASDFGHJKL"))
        assertEquals("ㅋㅌㅊㅍㅠㅜㅡ", letters(korean, "ZXCVBNM"))
        // The `$` key stays a currency key: its cell holds no letter.
        assertEquals('$'.code, korean.extraKey!!.letter)
        // Doubled consonants come from the pkbd_hangul table on Shift.
        assertEquals('ㅃ'.code, shifted(korean.cell(KeyEvent.KEYCODE_Q)!!) { if (it == "ㅂ") "ㅃ" else null })
    }

    @Test
    fun hebrewGreekUkrainianBelarusianParse() {
        val hebrew = layout("hebrew")!!
        assertEquals("'-קראטוןםפ", letters(hebrew, "QWERTYUIOP"))
        assertEquals('ף'.code, shifted(hebrew.cell(KeyEvent.KEYCODE_P)!!))
        val greek = layout("greek")!!
        assertEquals("ςερτυθιοπ", letters(greek, "WERTYUIOP"))
        assertEquals('Ε'.code, shifted(greek.cell(KeyEvent.KEYCODE_E)!!))
        assertEquals("йцукенгшзх", letters(layout("ukranian")!!, "QWERTYUIOP"))
        assertEquals("йцукенгшзх", letters(layout("belarusian")!!, "QWERTYUIOP"))
    }

    @Test
    fun latinAndGeneratedQwertyLayoutsAreNotScriptLayouts() {
        for (name in listOf("qwerty", "azerty", "qwertz", "farsi", "hindi", "turkish", "thai", "ethiopic", "tibetan")) {
            assertNull("$name should type on the Latin keys", layout(name))
        }
    }

    @Test
    fun everyShippedScriptLayoutFillsAllTwentySixLetterKeys() {
        for (name in listOf("arabic", "east_slavic", "ukranian", "belarusian", "hebrew", "greek", "korean")) {
            val layout = layout(name)
            assertNotNull("$name should be a script layout", layout)
            assertEquals("$name letter keys", 26, layout!!.letterKeyCount)
        }
    }

    /** The Language screen offers the setting only when an enabled keyboard is one of these. */
    @Test
    fun onlyKeyboardsWithTheirOwnAlphabetOfferTheSetting() {
        for (locale in listOf("ar", "ru", "uk", "be", "iw", "he", "iw_IL", "el", "ko")) {
            assertTrue("$locale should offer it", HardwareScriptLayouts.supportsLocale(locale))
        }
        for (locale in listOf("en_US", "fr", "in", "fa", "hi", "th", "bg", "zz", "", null)) {
            assertTrue("$locale should not offer it", !HardwareScriptLayouts.supportsLocale(locale))
        }
    }

    @Test
    fun conjoiningJamoMapToCompatibilityJamo() {
        assertEquals('ㄱ'.code, HardwareScriptLayout.toCompatibilityJamo(0x1100))
        assertEquals('ㅎ'.code, HardwareScriptLayout.toCompatibilityJamo(0x1112))
        assertEquals('ㅏ'.code, HardwareScriptLayout.toCompatibilityJamo(0x1161))
        assertEquals('ㅣ'.code, HardwareScriptLayout.toCompatibilityJamo(0x1175))
        assertEquals('a'.code, HardwareScriptLayout.toCompatibilityJamo('a'.code))
    }
}
