package dev.bbkb.ime.core.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The grouping rule behind the Android 16-style settings layout, checked without composing. */
class SettingsRowGroupingTest {
    private fun nav(title: Int) = Nav(title = title, onClick = {})
    private fun category(title: Int) = Category(title)
    private fun custom(inGroup: Boolean = false) = Custom(inGroup = inGroup) {}

    private fun shape(blocks: List<RowBlock>): List<String> = blocks.map { block ->
        when (block) {
            is RowBlock.Label -> "L"
            is RowBlock.Page -> "P"
            is RowBlock.Group -> "G${block.rows.size}"
        }
    }

    @Test
    fun leadingRowsFormAnUnlabelledGroup() {
        assertEquals(listOf("G2", "L", "G1"), shape(groupRows(listOf(nav(1), nav(2), category(3), nav(4)))))
    }

    @Test
    fun customBlocksSitAtPageLevelUnlessMarkedInGroup() {
        assertEquals(listOf("G1", "P", "G1"), shape(groupRows(listOf(nav(1), custom(), nav(2)))))
        assertEquals(listOf("G3"), shape(groupRows(listOf(nav(1), custom(inGroup = true), nav(2)))))
    }

    @Test
    fun noEmptyGroups() {
        assertEquals(listOf("L", "L", "G1"), shape(groupRows(listOf(category(1), category(2), nav(3)))))
        assertEquals(listOf("L"), shape(groupRows(listOf(category(1)))))
        assertEquals(emptyList<String>(), shape(groupRows(emptyList())))
    }
}
