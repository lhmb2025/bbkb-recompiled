package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The remembered skin tone per emoji, keyed by the base form. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EmojiToneMemoryTest {

    private lateinit var prefs: SharedPreferences

    private val thumbs = "👍"            // 👍
    private val thumbsQualified = "👍️" // 👍️ as emojibase stores it
    private val thumbsMedium = "👍🏽" // 👍🏽
    private val thumbsDark = "👍🏿"   // 👍🏿
    private val heart = "❤️"              // ❤️

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("tone-memory-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun nothingRememberedAnswersNull() {
        assertNull(EmojiToneMemory.preferred(prefs, thumbs))
        assertNull(EmojiToneMemory.preferred(prefs, heart))
    }

    @Test
    fun aTonedPickIsRememberedForItsBaseInEitherForm() {
        assertTrue(EmojiToneMemory.onEmojiPicked(prefs, thumbsMedium))
        assertEquals(thumbsMedium, EmojiToneMemory.preferred(prefs, thumbs))
        assertEquals("the dataset's fully-qualified base finds it too", thumbsMedium, EmojiToneMemory.preferred(prefs, thumbsQualified))
        assertEquals("asking with a toned form answers the remembered tone", thumbsMedium, EmojiToneMemory.preferred(prefs, thumbsDark))
    }

    @Test
    fun aLaterToneReplacesTheEarlierOne() {
        EmojiToneMemory.onEmojiPicked(prefs, thumbsMedium)
        assertTrue(EmojiToneMemory.onEmojiPicked(prefs, thumbsDark))
        assertEquals(thumbsDark, EmojiToneMemory.preferred(prefs, thumbs))
        assertFalse("the same tone again changes nothing", EmojiToneMemory.onEmojiPicked(prefs, thumbsDark))
    }

    @Test
    fun pickingThePlainEmojiForgetsTheTone() {
        EmojiToneMemory.onEmojiPicked(prefs, thumbsMedium)
        assertTrue(EmojiToneMemory.onEmojiPicked(prefs, thumbsQualified))
        assertNull(EmojiToneMemory.preferred(prefs, thumbs))
        assertFalse("nothing to forget", EmojiToneMemory.onEmojiPicked(prefs, thumbs))
    }

    @Test
    fun anEmojiWithoutTonesChangesNothing() {
        assertFalse(EmojiToneMemory.onEmojiPicked(prefs, heart))
        assertFalse(prefs.contains("emoji_tone_memory"))
    }

    @Test
    fun normalizeStripsTonesAndVariationSelectorsOnly() {
        assertEquals(thumbs, EmojiToneMemory.normalize(thumbsQualified))
        assertEquals(thumbs, EmojiToneMemory.normalize(thumbsMedium))
        assertEquals("❤", EmojiToneMemory.normalize(heart))
        // A two-person sequence keeps its zero-width joiners.
        val couple = "🧑🏽‍🤝‍🧑🏿"
        assertEquals("🧑‍🤝‍🧑", EmojiToneMemory.normalize(couple))
        assertTrue(EmojiToneMemory.hasSkinTone(couple))
        assertFalse(EmojiToneMemory.hasSkinTone(heart))
    }

    @Test
    fun aCorruptedValueReadsAsEmptyAndIsOverwrittenByTheNextPick() {
        prefs.edit().putString("emoji_tone_memory", "not json").commit()
        assertNull(EmojiToneMemory.preferred(prefs, thumbs))
        assertTrue(EmojiToneMemory.onEmojiPicked(prefs, thumbsMedium))
        assertEquals(thumbsMedium, EmojiToneMemory.preferred(prefs, thumbs))
    }

    @Test
    fun clearForgetsEverything() {
        EmojiToneMemory.onEmojiPicked(prefs, thumbsMedium)
        EmojiToneMemory.clear(prefs)
        assertNull(EmojiToneMemory.preferred(prefs, thumbs))
    }
}
