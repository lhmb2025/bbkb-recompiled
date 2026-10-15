package dev.bbkb.ime.core.layout

/**
 * The engine's on-screen KDBs, built from the keyboard actually drawn.
 *
 * The app used to ship one on-screen KDB per language (`assets/kdb/<name>_vkb.xml`); they came in
 * a handful of row shapes, and their letters were a copy of what the language's keyboard draws,
 * kept by hand and sometimes wrong (Belgian Dutch and German draw AZERTY but had QWERTY and QWERTZ
 * letters). Now one file of each shape ships, and the engine's KDB is built from it and the drawn
 * keyboard's letters ([DrawnKeyboard]): [choose] picks the file whose rows have the drawn row
 * shape and, between files of one shape (QWERTY and Georgian, Thai and Lao), whose keys sit nearest
 * the drawn ones; [build] writes the letters on it and stamps it with every engine id the
 * on-screen keyboard may ask for ([IDS]), so whichever language the engine loads it finds the
 * drawn letters, as the physical keypad's files do ([KeypadKdbs]).
 *
 * The Chinese stroke, zhuyin and Cangjie keyboards keep their shipped files: their keys stand for
 * strokes and radicals the engine reads its own way ([KEPT_LAYOUT_SETS]).
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

    /**
     * Every (primaryId, secondaryId) the engine may ask an on-screen KDB for, from the per-language
     * files the app shipped: the built KDB is registered under each.
     */
    @JvmField
    val IDS: List<Pair<Int, Int>> = listOf(
        1, 2, 3, 6, 7, 8, 9, 10, 11, 12, 13, 18, 20, 25, 30, 31, 34, 35, 37, 41, 43, 44, 47, 57, 68,
        75, 76, 87, 90, 96, 99, 106, 107, 109, 115, 116, 132, 133,
    ).map { it to SCREEN_SECONDARY_ID } + listOf(17 to 5)

    /** Whether [name], a file under `assets/kdb/`, is an on-screen row-shape template. */
    @JvmStatic
    fun isTemplate(name: String): Boolean = name.endsWith(LetterLayoutKdb.Template.VKB.fileSuffix) && name !in NOT_TEMPLATES

    /**
     * The name of the template in [templates] (file name -> contents) for [drawn]: one whose rows
     * above its last have [drawn]'s shape, nearest by key centres and then by its last row's
     * positions ([DrawnKeyboard.spaceRowPositions]). Null when none has the shape.
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

    /**
     * The on-screen KDB for every id in [IDS]: [template] with [drawn]'s letters, each stamped with
     * one id. Null when the drawn rows do not fit the template.
     */
    @JvmStatic
    fun build(template: String, drawn: DrawnKeyboard): List<String>? {
        val lettered = LetterLayoutKdb.forDrawnKeyboard(template, drawn.rows.map { row -> row.map { it.codes } }) ?: return null
        return IDS.mapNotNull { (primaryId, secondaryId) -> LetterLayoutKdb.withEngineIds(lettered, primaryId, secondaryId) }
    }
}
