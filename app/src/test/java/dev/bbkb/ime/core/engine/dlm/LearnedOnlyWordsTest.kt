package dev.bbkb.ime.core.engine.dlm

import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * [LearnedOnlyWords]: which records count as learned-only, and when the engine's model is read
 * again (a changed word count, or the refresh interval; one cache per engine).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class LearnedOnlyWordsTest {

    @Before
    fun setUp() = LearnedOnlyWords.clearForTest()

    @After
    fun tearDown() = LearnedOnlyWords.clearForTest()

    private fun record(text: String, quality: Int) =
        LearnedModelStream.Word(text, 1, 0x0b09, 0, quality, 0, 0)

    @Test
    fun learnedAndTypedOnceWordsAreLearnedOnly_keptAndBoostedOnesAreNot() {
        val words = LearnedOnlyWords.learnedOnlyWords(
            listOf(
                record("explicit", 15),
                record("boostedHigh", 3),
                record("boostedLow", 2),
                record("learnedOne", 1),
                record("learnedZero", 0),
                record("typedOnce", -1),
                record("expiring", -11),
                record("deleted", -15),
            )
        )
        assertEquals(setOf("learnedOne", "learnedZero", "typedOnce", "expiring"), words)
    }

    @Test
    fun exactSpellingsOnly() {
        val words = LearnedOnlyWords.learnedOnlyWords(listOf(record("BlackBerry", 0)))
        assertTrue(words.contains("BlackBerry"))
        assertFalse(words.contains("blackberry"))
    }

    /** A minimal version-5 stream the parser accepts: no categories, no next-word pairs. */
    private fun stream(vararg words: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) u8((v ushr s).toInt()) }
        u8(5); u32(42L)
        u16(0)
        u16(words.size)
        for ((text, quality) in words) {
            u16(1); u16(0); u16(0x0b09); u8(text.length); u8(quality); u8(0)
            text.forEach { u16(it.code) }
        }
        u32(0)
        u16(0); u16(0)
        val payload = out.toByteArray()
        val sum = LearnedModelStream.checksum(payload, payload.size)
        return payload + byteArrayOf((sum ushr 24).toByte(), (sum ushr 16).toByte(), (sum ushr 8).toByte(), sum.toByte())
    }

    private fun engine(count: Int, vararg words: Pair<String, Int>): NuanceSDK {
        val sdk = mock(NuanceSDK::class.java)
        `when`(sdk.isDLMAttached).thenReturn(true)
        `when`(sdk.isDLMTransferAvailable).thenReturn(true)
        `when`(sdk.getDLMWordCount(false)).thenReturn(count)
        `when`(sdk.exportDLM()).thenReturn(stream(*words))
        return sdk
    }

    @Test
    fun readsTheModelOnceWhileTheWordCountStands() {
        val sdk = engine(2, "beezone" to -1, "the" to 3)

        assertTrue(LearnedOnlyWords.contains(sdk, "beezone"))
        assertFalse(LearnedOnlyWords.contains(sdk, "the"))
        assertFalse(LearnedOnlyWords.contains(sdk, "nowhere"))

        verify(sdk, times(1)).exportDLM()
    }

    @Test
    fun readsAgainWhenTheWordCountChanges() {
        val sdk = engine(1, "beezone" to -1)
        assertTrue(LearnedOnlyWords.contains(sdk, "beezone"))

        `when`(sdk.getDLMWordCount(false)).thenReturn(2)
        `when`(sdk.exportDLM()).thenReturn(stream("beezone" to -1, "quux" to 0))

        assertTrue(LearnedOnlyWords.contains(sdk, "quux"))
        verify(sdk, times(2)).exportDLM()
    }

    @Test
    fun eachEngineHasItsOwnWords() {
        val keyboard = engine(1, "beezone" to -1)
        val spellChecker = engine(1, "quux" to 0)

        assertTrue(LearnedOnlyWords.contains(keyboard, "beezone"))
        assertFalse(LearnedOnlyWords.contains(keyboard, "quux"))
        assertTrue(LearnedOnlyWords.contains(spellChecker, "quux"))
        assertFalse(LearnedOnlyWords.contains(spellChecker, "beezone"))
    }

    @Test
    fun nothingIsLearnedOnlyWhenTheModelCannotBeRead() {
        val sdk = mock(NuanceSDK::class.java)
        `when`(sdk.isDLMAttached).thenReturn(true)
        `when`(sdk.isDLMTransferAvailable).thenReturn(false)

        assertNull(LearnedOnlyWords.of(sdk))
        assertFalse(LearnedOnlyWords.contains(sdk, "beezone"))
        assertNull(LearnedOnlyWords.of(null))
    }

    @Test
    fun aStreamThatDoesNotParseKeepsTheLastRead() {
        val sdk = engine(1, "beezone" to -1)
        assertTrue(LearnedOnlyWords.contains(sdk, "beezone"))

        `when`(sdk.getDLMWordCount(false)).thenReturn(2)
        `when`(sdk.exportDLM()).thenReturn(byteArrayOf(1, 2, 3))

        assertTrue(LearnedOnlyWords.contains(sdk, "beezone"))
    }
}
