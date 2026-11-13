package dev.bbkb.ime.core.settings.ui

/** What the host draws, in order: a category label, a page-level block, or a group of rows. */
sealed interface RowBlock {
    class Label(val row: Category) : RowBlock
    class Page(val row: Custom) : RowBlock
    class Group(val rows: List<SettingsRow>) : RowBlock
}

/**
 * Splits visible rows into blocks: each run of item rows is a group; a [Category] or a page-level
 * [Custom] (not [Custom.inGroup]) ends the run. Empty runs produce no group.
 */
fun groupRows(rows: List<SettingsRow>): List<RowBlock> {
    val blocks = mutableListOf<RowBlock>()
    val run = mutableListOf<SettingsRow>()
    fun flush() {
        if (run.isNotEmpty()) {
            blocks += RowBlock.Group(run.toList())
            run.clear()
        }
    }
    for (row in rows) {
        when {
            row is Category -> {
                flush()
                blocks += RowBlock.Label(row)
            }
            row is Custom && !row.inGroup -> {
                flush()
                blocks += RowBlock.Page(row)
            }
            else -> run += row
        }
    }
    flush()
    return blocks
}
