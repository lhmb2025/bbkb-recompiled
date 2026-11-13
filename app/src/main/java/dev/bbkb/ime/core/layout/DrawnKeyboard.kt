package dev.bbkb.ime.core.layout

import java.security.MessageDigest

/**
 * The letters of a drawn on-screen keyboard, the input to the engine's on-screen KDB ([OnScreenKdbs]).
 * [rows]: rows above the space bar, top to bottom, no digit row, function keys excluded.
 * [Position.codes]: primary code point, then the Shift layer's letter when it differs (Arabic,
 * Korean), then the long-press letters. [Position.center]: key centre as a fraction of the width,
 * which tells apart two shipped files of the same row shape.
 */
class DrawnKeyboard(
    /** The layout set the keyboard was built from (`qwerty`, `east_slavic`, `colemak`), or null. */
    val layoutSet: String?,
    val rows: List<List<Position>>,
    /** The positions in the space bar's row (comma, period, a Hindi danda): tells apart files whose letter rows match. */
    val spaceRowPositions: Int = 0,
) {
    class Position(val codes: IntArray, val center: Float)

    /** Positions per row: the row shape. */
    val shape: List<Int> get() = rows.map { it.size }

    /** Identifies letters and shape only (same letters on the same rows build the same KDB); centres rounded to 1/100. */
    val fingerprint: String by lazy {
        val text = buildString {
            append(layoutSet ?: "").append('|').append(spaceRowPositions).append('|')
            for (row in rows) {
                for (p in row) {
                    append(Math.round(p.center * 100)).append(':')
                    p.codes.forEach { append(Integer.toHexString(it)).append(',') }
                    append(' ')
                }
                append('/')
            }
        }
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }
    }
}
