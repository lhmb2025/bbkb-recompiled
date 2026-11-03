package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiToneMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The strip's emoji comes from emojibase (owner requirement 2026-11-02): the engine's kind-11
 * entries go, and the top emojibase hit for the composing word is appended under the Emoji
 * suggestions switch, with the remembered skin tone applied. Runs against
 * [EmojiTestEnv.seedStandard]: smileys s0..s40 described "smile N", animals a0..a2 "cat N",
 * food f0..f1 "food N".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojibaseStripSuggestionTest {

    private val context get() = EmojiTestEnv.app

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seedStandard()
        EmojiTestEnv.useSettings(EmojiTestEnv.settings(emojiPredictions = true))
        EmojiToneMemory.clear(PrefsManager.getPrefs(context))
    }

    @After
    fun tearDown() {
        EmojiTestEnv.uninstall()
    }

    private fun word(w: String, kind: Int = 0) =
        SuggestedWords.SuggestedWordInfo(w, 1, kind, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null)

    private fun words(vararg infos: SuggestedWords.SuggestedWordInfo) = ArrayList(infos.toList())

    @Test
    fun theEnginesEmojiEntriesAreDroppedWhereverTheyStand() {
        val list = words(word("hearts"), word("❤", 11), word("heart"), word("🧡", 11))
        EmojibaseStripSuggestion.dropEngineEmoji(list)
        assertEquals(listOf("hearts", "heart"), list.map { it.word })
    }

    @Test
    fun theTopHitIsAppendedAfterTheWords() {
        val list = words(word("smiles"), word("❤", 11), word("smile"))
        EmojibaseStripSuggestion.apply(context, list, "smile", Locale.ENGLISH)
        assertEquals(listOf("smiles", "smile", "s0"), list.map { it.word })
        val hit = list.last()
        assertTrue(hit.isKind(EmojibaseStripSuggestion.KIND_EMOJI))
        assertEquals(Dictionary.DICTIONARY_HARDCODED, hit.sourceDictionary)
        assertNull(hit.nuanceWordInfo)
    }

    @Test
    fun anExactDescriptionBeatsAPrefix() {
        assertEquals("s4", EmojibaseStripSuggestion.topHit(context, "smile 4", Locale.ENGLISH))
        assertEquals("a0", EmojibaseStripSuggestion.topHit(context, "cat", Locale.ENGLISH))
    }

    @Test
    fun aWeakMatchOrNoMatchAddsNothing() {
        assertNull("contains-only is below the threshold", EmojibaseStripSuggestion.topHit(context, "mile", Locale.ENGLISH))
        assertNull(EmojibaseStripSuggestion.topHit(context, "the", Locale.ENGLISH))
        assertNull("one character is too short", EmojibaseStripSuggestion.topHit(context, "s", Locale.ENGLISH))
        assertNull(EmojibaseStripSuggestion.topHit(context, "  ", Locale.ENGLISH))
        assertNull(EmojibaseStripSuggestion.topHit(context, null, Locale.ENGLISH))
    }

    @Test
    fun chineseAndJapaneseGetNoHit() {
        assertNull(EmojibaseStripSuggestion.topHit(context, "smile", Locale.CHINESE))
        assertNull(EmojibaseStripSuggestion.topHit(context, "smile", Locale.JAPANESE))
    }

    @Test
    fun withTheSwitchOffOnlyTheDropHappens() {
        EmojiTestEnv.useSettings(EmojiTestEnv.settings(emojiPredictions = false))
        val list = words(word("smile"), word("❤", 11))
        EmojibaseStripSuggestion.apply(context, list, "smile", Locale.ENGLISH)
        assertEquals(listOf("smile"), list.map { it.word })
    }

    @Test
    fun theRememberedToneIsApplied() {
        // s0 has no tones in the seed; the memory is keyed by base form, so a stored tone wins.
        EmojiToneMemory.onEmojiPicked(PrefsManager.getPrefs(context), "s0🏽")
        assertEquals("s0🏽", EmojibaseStripSuggestion.topHit(context, "smile", Locale.ENGLISH))
    }
}
