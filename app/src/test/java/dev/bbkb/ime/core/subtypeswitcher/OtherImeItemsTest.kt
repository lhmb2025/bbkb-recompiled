package dev.bbkb.ime.core.subtypeswitcher

import android.view.inputmethod.InputMethodInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "Other keyboards" rows of the language menu: every other enabled input method, and only
 * those. Owner decision 2026-09-26: the "Include other keyboards" setting means exactly this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OtherImeItemsTest {

    private val pm get() = RuntimeEnvironment.getApplication().packageManager

    private fun ime(pkg: String, cls: String, label: String) = InputMethodInfo(pkg, cls, label, null)

    private val bbkb = ime("dev.bbkb.ime", "dev.bbkb.ime.core.BlackBerryIME", "BBKB")
    private val gboard = ime("com.google.android.inputmethod.latin", ".LatinIME", "Gboard")
    private val other = ime("com.example.kb", ".Ime", "Example Keyboard")

    @Test
    fun listsTheOtherEnabledInputMethodsAndNotThisOne() {
        val rows = SubtypeSwitcherDialog.otherImeItems(listOf(bbkb, gboard, other), bbkb.id, pm)
        assertEquals(listOf(gboard.id, other.id), rows.map { it.imeId })
        assertTrue(rows.all { it.subtypeIndex == -1 && !it.isHeader })
    }

    @Test
    fun aRowCarriesTheOtherKeyboardsLabel() {
        val rows = SubtypeSwitcherDialog.otherImeItems(listOf(bbkb, gboard), bbkb.id, pm)
        assertEquals("Gboard", rows.single().displayName.toString())
    }

    @Test
    fun nothingElseEnabledMeansNoRows() {
        assertTrue(SubtypeSwitcherDialog.otherImeItems(listOf(bbkb), bbkb.id, pm).isEmpty())
        assertTrue(SubtypeSwitcherDialog.otherImeItems(null, bbkb.id, pm).isEmpty())
    }

    @Test
    fun aNoteItemIsNeitherAKeyboardNorAHeader() {
        val note = SubtypeItem.note("No other input methods are enabled")
        assertTrue(note.isNote)
        assertTrue(!note.isHeader && note.imeId == null && note.subtypeIndex == -1)
    }

    @Test
    fun aHeaderItemIsNotSelectable() {
        val header = SubtypeItem("Other keyboards", null)
        assertTrue(header.isHeader)
        assertEquals(-1, header.subtypeIndex)
    }
}
