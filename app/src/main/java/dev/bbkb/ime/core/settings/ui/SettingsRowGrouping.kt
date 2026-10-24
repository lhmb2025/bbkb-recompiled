package dev.bbkb.ime.core.settings.ui

/** What the host draws, in order: a category label, a page-level block, or a group of rows. */
sealed interface RowBlock {
    class Label(val row: Category) : RowBlock
    class Page(val row: Custom) : RowBlock
    class Group(val rows: List<SettingsRow>) : RowBlock
}

/**
 * Splits a screen's visible rows into the blocks of the grouped layout. Every run of item rows
 * becomes one group; a [Category] ends the run and labels the next group; a [Custom] block ends
 * the run and sits at page level, unless it is marked [Custom.inGroup], in which case it is an
 * item like any other. Rows before the first category form a group with no label; a category
 * followed by nothing visible produces no empty group.
 *
 * Pure, so the rule is testable without composing anything.
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
