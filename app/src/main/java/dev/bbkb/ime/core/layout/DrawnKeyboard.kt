package dev.bbkb.ime.core.layout

import java.security.MessageDigest

/**
 * The letters of an on-screen keyboard as it is drawn, read off the built keyboard
 * (`dev.bbkb.ime.keyboard.DrawnKeyboards`): what the engine's on-screen KDB is built from
 * ([OnScreenKdbs]).
 *
 * [rows] are the keyboard's rows above the space bar, top to bottom, without a row of digits;
 * each position is a key that is not a function key (Shift, Delete), left to right. A position's
 * [Position.codes] are the code points it stands for, primary first: the key's own, its Shift
 * layer's letter where that is another letter (an Arabic or Korean key), then the letters of its
 * long-press list. [Position.center] is the key's centre as a fraction of the keyboard's width,
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

    /** The number of positions in each row: the keyboard's row shape. */
    val shape: List<Int> get() = rows.map { it.size }

    /**
     * Identifies the letters and the shape, nothing else: two keyboards with the same letters on the
     * same rows build the same KDB. Centres are rounded to the 1/100 of the width that tells shapes
     * apart.
     */
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
