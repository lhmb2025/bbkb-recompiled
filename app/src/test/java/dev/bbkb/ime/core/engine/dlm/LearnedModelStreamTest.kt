package dev.bbkb.ime.core.engine.dlm

import dev.bbkb.ime.core.engine.dlm.LearnedModelStream.InvalidModelException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * [LearnedModelStream] against streams built the way the engine writes them (the layout in its
 * documentation), including the engine's own tolerances: zero padding after the checksum is
 * accepted, anything else after it is not.
 */
class LearnedModelStreamTest {

    /** Builds a version-5 stream byte by byte. */
    private class Builder {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xff) }
        fun u16(v: Int) = apply { out.write((v ushr 8) and 0xff); out.write(v and 0xff) }
        fun u32(v: Long) = apply { for (s in intArrayOf(24, 16, 8, 0)) out.write(((v ushr s) and 0xff).toInt()) }
        fun symbols(s: String) = apply { s.forEach { u16(it.code) } }
        fun category(id: Int, name: String, label: String) = apply {
            u16(id); repeat(5) { u8(0) }; u32(1); repeat(5) { u8(0) }; u32(2); u8(3)
            u8(name.length); symbols(name); u8(label.length); symbols(label)
        }
        fun word(text: String, count: Int, language: Int = 0x0b09, category: Int = 0, quality: Int = 3, flags: Int = 0) = apply {
            u16(count); u16(category); u16(language); u8(text.length); u8(quality); u8(flags); symbols(text)
        }
        fun nextWord(pairs: List<Pair<Int, Int>>) = apply {
            u8(1); u32(0x12345678L); u16(pairs.size); pairs.forEach { (a, b) -> u16(a); u16(b) }
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun withChecksum(payload: ByteArray, padding: Int = 0): ByteArray {
        val sum = LearnedModelStream.checksum(payload, payload.size)
        return payload + byteArrayOf((sum ushr 24).toByte(), (sum ushr 16).toByte(), (sum ushr 8).toByte(), sum.toByte()) + ByteArray(padding)
    }

    private fun model(vararg words: Pair<String, Int>, quality: Int = 3): ByteArray {
        val b = Builder().u8(5).u32(42L)
        b.u16(1).category(7, "names", "People")
        b.u16(words.size)
        words.forEach { (text, count) -> b.word(text, count, quality = quality) }
        b.u32(2).nextWord(listOf(1 to 1)).nextWord(listOf(0 to 2, 1 to 1))
        b.u16(2).u16(0).u32(10).u32(11)
        return withChecksum(b.bytes())
    }

    @Test
    fun readsWordsWithTheirCountsAndTheRest() {
        val m = LearnedModelStream.parse(model("athena" to 12, "keypad" to 1, "Zürich" to 300))
        assertEquals(5, m.version)
        assertEquals(42L, m.updateCount)
        assertEquals(listOf("names"), m.categories.map { it.name })
        assertEquals("People", m.categories[0].label)
        assertEquals(7, m.categories[0].id)
        assertEquals(listOf("athena", "keypad", "Zürich"), m.words.map { it.text })
        assertEquals(listOf(12, 1, 300), m.words.map { it.count })
        assertEquals(0x0b09, m.words[0].language)
        assertEquals(0, m.words[0].category)
        assertEquals(3, m.words[0].quality)
        assertEquals(2, m.nextWordRecords)
    }

    @Test
    fun qualityIsSigned() {
        val m = LearnedModelStream.parse(model("old" to 1, quality = -15))
        assertEquals(-15, m.words[0].quality)
    }

    @Test
    fun paddingAfterTheChecksumIsDroppedButKeptOutOfTheBytes() {
        val exact = model("one" to 1)
        val padded = withChecksum(exact.copyOf(exact.size - 4), padding = 1024 - exact.size % 1024)
        val m = LearnedModelStream.parse(padded)
        assertArrayEquals(exact, m.bytes)
        assertEquals(listOf("one"), m.words.map { it.text })
    }

    @Test
    fun anythingButZerosAfterTheChecksumIsRefused() {
        val padded = model("one" to 1) + byteArrayOf(0, 0, 1)
        assertInvalid(padded, "after the checksum")
    }

    @Test
    fun aWrongChecksumIsRefused() {
        val bytes = model("one" to 1)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        assertInvalid(bytes, "checksum")
    }

    @Test
    fun aChangedByteIsCaughtByTheChecksum() {
        val bytes = model("one" to 1)
        bytes[8] = (bytes[8] + 1).toByte() // a byte inside the category record
        assertInvalid(bytes, "checksum")
    }

    @Test
    fun otherVersionsAreRefused() {
        val newer = model("one" to 1); newer[0] = 6
        assertInvalid(withChecksum(newer.copyOf(newer.size - 4)), "newer")
        val older = model("one" to 1); older[0] = 4
        assertInvalid(withChecksum(older.copyOf(older.size - 4)), "older")
        assertInvalid(ByteArray(0), "truncated")
        assertInvalid(byteArrayOf(0x50, 0x4b, 3, 4), "not a learned model")
    }

    @Test
    fun truncationIsRefused() {
        val bytes = model("athena" to 12)
        for (cut in listOf(1, 7, 20, bytes.size - 5, bytes.size - 1)) assertInvalid(bytes.copyOf(cut), "truncated")
    }

    @Test
    fun aWordLongerThanTheEngineAllowsIsRefused() {
        val b = Builder().u8(5).u32(0L).u16(0).u16(1)
        b.u16(1); b.u16(0); b.u16(0x0b09); b.u8(65); b.u8(0); b.u8(0); b.symbols("a".repeat(65))
        b.u32(0).u16(0).u16(0)
        assertInvalid(withChecksum(b.bytes()), "65 symbols")
    }

    @Test
    fun countsCanBeRewrittenInPlace() {
        val m = LearnedModelStream.parse(model("athena" to 12, "keypad" to 1))
        val edited = LearnedModelStream.withChanges(m, mapOf(1 to 500))
        val again = LearnedModelStream.parse(edited)
        assertEquals(listOf(12, 500), again.words.map { it.count })
        assertEquals("only the count and the checksum change", m.bytes.size, edited.size)
        val differing = m.bytes.indices.filter { m.bytes[it] != edited[it] }
        assertTrue(differing.toString(), differing.all { it in m.words[1].countOffset..m.words[1].countOffset + 1 || it >= edited.size - 4 })
    }

    @Test
    fun qualityCanBeRewrittenInPlace() {
        val m = LearnedModelStream.parse(model("once" to 1, "twice" to 2, quality = -1))
        val edited = LearnedModelStream.withChanges(m, qualities = mapOf(0 to 0))
        val again = LearnedModelStream.parse(edited)
        assertEquals(listOf(0, -1), again.words.map { it.quality })
        assertEquals(listOf(1, 2), again.words.map { it.count })
        val differing = m.bytes.indices.filter { m.bytes[it] != edited[it] }
        assertTrue(differing.toString(), differing.all { it == m.words[0].countOffset + 7 || it >= edited.size - 4 })
        for (bad in listOf(16, -16)) {
            try { LearnedModelStream.withChanges(m, qualities = mapOf(0 to bad)); fail("$bad accepted") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun countsOutsideTheEnginesRangeAreRefused() {
        val m = LearnedModelStream.parse(model("athena" to 12))
        for (bad in listOf(0, 65536, -1)) {
            try { LearnedModelStream.withChanges(m, mapOf(0 to bad)); fail("$bad accepted") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun theChecksumIsTheEnginesHash() {
        // h = h * 65599 + byte, 32-bit: "ab" -> (97 * 65599 + 98)
        assertEquals(97 * 65599 + 98, LearnedModelStream.checksum(byteArrayOf(97, 98), 2))
        assertEquals(0, LearnedModelStream.checksum(byteArrayOf(1, 2, 3), 0))
    }

    private fun assertInvalid(bytes: ByteArray, expected: String) {
        try {
            LearnedModelStream.parse(bytes)
            fail("accepted a stream that should be refused ($expected)")
        } catch (e: InvalidModelException) {
            assertTrue("'${e.message}' should mention '$expected'", e.message!!.contains(expected))
        }
    }
}
