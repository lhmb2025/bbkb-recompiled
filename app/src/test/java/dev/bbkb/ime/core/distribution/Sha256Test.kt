package dev.bbkb.ime.core.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * The hash is one of the two things standing between the user and an APK someone else wrote, so
 * it is checked against known-answer vectors rather than against itself.
 */
class Sha256Test {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun matchesTheKnownAnswerForTheEmptyInput() {
        val expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(expected, Sha256.of(ByteArray(0)))
        assertEquals(expected, Sha256.of(ByteArrayInputStream(ByteArray(0))))
        assertEquals(expected, Sha256.of(folder.newFile("empty").also { it.writeBytes(ByteArray(0)) }))
    }

    @Test
    fun matchesTheKnownAnswerForAbc() {
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, Sha256.of("abc".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun fileStreamAndByteArrayAgreeOverSomethingBiggerThanTheBuffer() {
        // The streaming loop's buffer is 64 KiB; a payload several buffers long proves the
        // chunking, which a short input cannot.
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val file = folder.newFile("payload.bin").apply { writeBytes(payload) }

        val fromBytes = Sha256.of(payload)
        assertEquals(fromBytes, Sha256.of(file))
        assertEquals(fromBytes, Sha256.of(ByteArrayInputStream(payload)))
    }

    @Test
    fun isRenderedAsLowercaseHexOfTheRightLength() {
        val hex = Sha256.of("anything".toByteArray())
        assertEquals(64, hex.length)
        assertEquals(hex.lowercase(), hex)
        assertTrue(hex.all { it in "0123456789abcdef" })
    }

    @Test
    fun hexRendersTheHighNibbleFirstAndPadsSingleDigits() {
        assertEquals("000f10ff", Sha256.hex(byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte())))
    }

    @Test
    fun matchesIgnoresCaseAndSurroundingWhitespace() {
        val digest = Sha256.of("abc".toByteArray())
        assertTrue(Sha256.matches(digest, digest))
        assertTrue(Sha256.matches(digest.uppercase(), digest))
        assertTrue(Sha256.matches("  $digest\n", digest))
    }

    @Test
    fun noHashIsNotAnyHash() {
        // The manifest promises a digest; a blank one must never be read as "matches whatever
        // arrived", or an entry with the field left out would install unverified bytes.
        val digest = Sha256.of("abc".toByteArray())
        assertFalse(Sha256.matches(null, digest))
        assertFalse(Sha256.matches("", digest))
        assertFalse(Sha256.matches("   ", digest))
        assertFalse(Sha256.matches(digest, null))
        assertFalse(Sha256.matches(digest, ""))
        assertFalse(Sha256.matches(null, null))
    }

    @Test
    fun differentContentDoesNotMatch() {
        assertFalse(Sha256.matches(Sha256.of("abc".toByteArray()), Sha256.of("abd".toByteArray())))
    }
}
