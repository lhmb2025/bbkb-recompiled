package dev.bbkb.ime.core.layout

/**
 * The engine's on-screen KDBs, built from the drawn keyboard ([DrawnKeyboard]). One template ships
 * per row shape: [choose] picks the one matching the drawn shape (nearest key centres between
 * same-shape files such as QWERTY/Georgian, Thai/Lao); [build] writes the drawn letters on it and
 * stamps every engine id in [IDS], so whichever language the engine loads finds the drawn letters.
 * Chinese stroke, zhuyin and Cangjie keep their shipped files ([KEPT_LAYOUT_SETS]): their keys are
 * strokes and radicals the engine reads its own way.
 */
object OnScreenKdbs {

    /** The on-screen KDBs' `secondaryId` (`ET9SKIDATQwertyReg`) for the keyboards built here; Japanese romaji asks 5. */
    const val SCREEN_SECONDARY_ID: Int = 1

    /** Smaller than any difference in where a row's keys sit (a key's 1/10 of the width squared is 0.01). */
    private const val SPACE_ROW_TIE_BREAK = 0.0001f

    /** Layout sets whose keyboards keep the shipped on-screen file. */
    @JvmField
    val KEPT_LAYOUT_SETS: Set<String> = setOf("stroke", "zhuyin", "cangjie")

    /** Shipped on-screen files that are not row-shape templates: the Chinese ones above use them as they are. */
    @JvmField
    val NOT_TEMPLATES: Set<String> = setOf("chinese_stroke_vkb.xml", "chinese_zhuyin_vkb.xml")

    /** Every (primaryId, secondaryId) the engine may ask an on-screen KDB for; the built KDB is registered under each. */
    @JvmField
    val IDS: List<Pair<Int, Int>> = listOf(
        1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13, 18, 20, 25, 30, 31, 34, 35, 37, 41, 43, 44, 47, 57, 68,
        75, 76, 87, 90, 96, 99, 106, 107, 109, 115, 116, 132, 133,
    ).map { it to SCREEN_SECONDARY_ID } + listOf(17 to 5)

    /** Whether [name], a file under `assets/kdb/`, is an on-screen row-shape template. */
    @JvmStatic
    fun isTemplate(name: String): Boolean = name.endsWith(LetterLayoutKdb.Template.VKB.fileSuffix) && name !in NOT_TEMPLATES

    /**
     * The template name in [templates] (file name -> contents) whose non-space rows have [drawn]'s
     * shape, nearest by key centres, then by space-row position count. Null when none has the shape.
     */
    @JvmStatic
    fun choose(templates: Map<String, String>, drawn: DrawnKeyboard): String? {
        var best: String? = null
        var bestDistance = Float.MAX_VALUE
        for ((name, xml) in templates.toSortedMap()) {
            val all = LetterLayoutKdb.screenRows(xml)
            val rows = all.dropLast(1)
            if (rows.map { it.size } != drawn.shape) continue
            // A space row with another number of positions only breaks a tie between equal rows.
            var distance = if (all.lastOrNull()?.size == drawn.spaceRowPositions) 0f else SPACE_ROW_TIE_BREAK
            for ((r, row) in rows.withIndex()) {
                for ((i, center) in row.withIndex()) {
                    val d = center - drawn.rows[r][i].center
                    distance += d * d
                }
            }
            if (distance < bestDistance) {
                bestDistance = distance
                best = name
            }
        }
        return best
    }

    /** [template] with [drawn]'s letters, once per id in [IDS]; null when the drawn rows do not fit. */
    @JvmStatic
    fun build(template: String, drawn: DrawnKeyboard): List<String>? {
        val lettered = LetterLayoutKdb.forDrawnKeyboard(template, drawn.rows.map { row -> row.map { it.codes } }) ?: return null
        return IDS.mapNotNull { (primaryId, secondaryId) -> LetterLayoutKdb.withEngineIds(lettered, primaryId, secondaryId) }
    }
}
