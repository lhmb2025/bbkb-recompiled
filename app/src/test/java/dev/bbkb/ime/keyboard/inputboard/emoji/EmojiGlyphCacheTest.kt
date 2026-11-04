package dev.bbkb.ime.keyboard.inputboard.emoji

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The on-disk "font has a glyph" cache: it may skip work, never change an answer. */
class EmojiGlyphCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val emojis = (0 until 21).map { "e$it" }
    private val drawable = emojis.filterIndexed { i, _ -> i % 3 != 0 }.toSet()

    private fun run(dir: File?, key: String = "k", font: Set<String> = drawable, list: List<String> = emojis): Pair<BooleanArray, List<String>> {
        val asked = ArrayList<String>()
        val flags = EmojiGlyphCache.flags(dir, key, list) { asked.add(it); it in font }
        return flags to asked
    }

    private fun expected(font: Set<String> = drawable, list: List<String> = emojis) =
        BooleanArray(list.size) { list[it] in font }

    @Test
    fun aMissChecksEveryEmojiAndAHitOnlyTheUnsupportedOnes() {
        val dir = tmp.newFolder()
        val (first, askedFirst) = run(dir)
        assertArrayEquals(expected(), first)
        assertEquals(emojis, askedFirst)
        assertTrue(File(dir, EmojiGlyphCache.FILE_NAME).isFile)

        val (second, askedSecond) = run(dir)
        assertArrayEquals(expected(), second)
        assertEquals(emojis.filter { it !in drawable }, askedSecond)
    }

    @Test
    fun aFontThatGainsGlyphsIsSeenAtOnce() {
        val dir = tmp.newFolder()
        run(dir)
        val updated = drawable + "e0"
        val (flags, _) = run(dir, font = updated)
        assertArrayEquals(expected(updated), flags)
        // ...and the cache now remembers it.
        val (_, asked) = run(dir, font = updated)
        assertEquals(emojis.filter { it !in updated }, asked)
    }

    @Test
    fun aDifferentKeyOrEmojiListRecomputes() {
        val dir = tmp.newFolder()
        run(dir)
        assertEquals(emojis, run(dir, key = "other build").second)
        val reordered = emojis.reversed()
        val (flags, asked) = run(dir, key = "other build", list = reordered)
        assertEquals(reordered, asked)
        assertArrayEquals(expected(list = reordered), flags)
    }

    @Test
    fun aCorruptOrTruncatedFileRecomputes() {
        val dir = tmp.newFolder()
        run(dir)
        val file = File(dir, EmojiGlyphCache.FILE_NAME)
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 1))
        assertEquals(emojis, run(dir).second)
        file.writeBytes(file.readBytes() + 0)
        assertEquals(emojis, run(dir).second)
        file.writeText("garbage")
        assertEquals(emojis, run(dir).second)
        assertArrayEquals(expected(), run(dir).first)
    }

    @Test
    fun noDirectoryOrAnUnwritableOneStillAnswers() {
        assertArrayEquals(expected(), run(null).first)
        val notADir = tmp.newFile()
        assertArrayEquals(expected(), run(notADir).first)
        assertNull(EmojiGlyphCache.read(File(notADir, "x"), "k", 3))
    }
}
