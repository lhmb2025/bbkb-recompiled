package dev.bbkb.ime.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [KeyboardTextSet.NAMES] repeats three names, exactly as the original APK's table does
 * (`morekeys_c` at 6/7, `morekeys_t` at 15/17, `morekeys_r` at 18/19). The name map is last-writer
 * wins, so only the later slot of each pair is ever read. That is harmless only while every locale
 * table holds the same value in both slots; this test fails the moment a table diverges, which is
 * when the shadowed slot would start silently hiding a real value.
 */
class KeyboardTextSetDuplicateNamesTest {

    private val duplicatePairs = listOf(
        Triple("morekeys_c", 6, 7),
        Triple("morekeys_t", 15, 17),
        Triple("morekeys_r", 18, 19),
    )

    private fun field(name: String): Any? =
        KeyboardTextSet::class.java.getDeclaredField(name).apply { isAccessible = true }.get(null)

    @Suppress("UNCHECKED_CAST")
    private fun localeTables(): List<Pair<String, Array<String?>>> =
        (field("LOCALES_AND_TEXTS") as Array<Any>).toList().chunked(2)
            .map { (locale, table) -> locale as String to table as Array<String?> }

    @Test
    fun theDuplicatedNamesSitAtTheExpectedIndices() {
        @Suppress("UNCHECKED_CAST")
        val names = field("NAMES") as Array<String>
        for ((name, early, late) in duplicatePairs) {
            assertEquals(name, names[early])
            assertEquals(name, names[late])
        }
        assertEquals("only these three names repeat", 3, names.size - names.toSet().size)
    }

    @Test
    fun everyLocaleTableHoldsTheSameValueInBothSlotsOfEachPair() {
        val tables = localeTables()
        assertTrue("expected the full locale table set, got ${tables.size}", tables.size > 80)
        for ((locale, table) in tables) {
            for ((name, early, late) in duplicatePairs) {
                assertEquals(
                    "$locale: $name slot $early vs $late",
                    table.getOrNull(early),
                    table.getOrNull(late),
                )
            }
        }
    }

    @Test
    fun theResolvedValueIsTheOneBothSlotsHold() {
        // Czech differentiates c/t/r from the default table, so it exercises the lookup for real.
        val cs = KeyboardTextSet.getTextsTable(Locale("cs"))
        for ((name, early, _) in duplicatePairs) {
            assertEquals(name, cs[early], KeyboardTextSet.getTextInternal(name, cs))
        }
    }
}
