package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An emoji the phone's font cannot draw is not offered: not on the board, not in search, and not
 * as a skin tone in the long-press popup.
 *
 * The check used to accept an emoji when its first code point had a glyph, so a joined emoji or a
 * flag the font lacked was offered and drawn as its parts (measured: 103 of them on Android 8.0,
 * 29 on Android 14). The fake fonts here are sets of the strings they have a glyph for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiDrawabilityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val grin = "😀"
    private val faceWithoutMouth = "😶"
    private val fog = "🌫"
    private val faceInClouds = "$faceWithoutMouth‍$fog️"
    private val raisedHand = "✋"
    private val wave = "👋"
    private val waveTones = (0x1F3FB..0x1F3FF).map { wave + String(Character.toChars(it)) }

    // ── the rule ──────────────────────────────────────────────────────────────

    @Test
    fun anEmojiTheFontHas_isDrawable() {
        assertTrue(EmojibaseDataProvider.canDraw(grin) { it == grin })
    }

    @Test
    fun aJoinedEmojiTheFontLacks_isNotDrawable_thoughBothItsPartsAre() {
        val font = setOf(faceWithoutMouth, fog)
        assertFalse(EmojibaseDataProvider.canDraw(faceInClouds) { it in font })
    }

    @Test
    fun aFlagTheFontLacks_isNotDrawable_thoughItsLettersAre() {
        val c = "🇨"
        val q = "🇶"
        val font = setOf(c, q)
        assertFalse(EmojibaseDataProvider.canDraw(c + q) { it in font })
    }

    @Test
    fun anEmojiWhoseVariationSelectorTheFontDoesNotList_isDrawable() {
        assertTrue(EmojibaseDataProvider.canDraw("$raisedHand️") { it == raisedHand })
    }

    @Test
    fun aJoinedEmojiIsAskedAboutWithoutItsSelectors_neverByItsParts() {
        val asked = ArrayList<String>()
        val bare = "$faceWithoutMouth‍$fog"
        assertTrue(EmojibaseDataProvider.canDraw(faceInClouds) { asked.add(it); it == bare })
        assertEquals(listOf(faceInClouds, bare), asked)
    }

    @Test
    fun withNoSelectorToDrop_theFontIsAskedOnce() {
        var calls = 0
        assertFalse(EmojibaseDataProvider.canDraw(grin) { calls++; false })
        assertEquals(1, calls)
    }

    // ── the board, search and the popup ───────────────────────────────────────

    private fun entry(emoji: String, description: String, order: Int, skins: List<String>? = null) = EmojiData(
        emoji = emoji,
        codepoints = "",
        description = description,
        group = 0,
        subgroup = 0,
        emojiVersion = 1.0,
        order = order,
        skins = skins?.map { EmojiData.EmojiSkin(emoji = it, hexcode = "") },
    )

    private fun providerWhereFontLacks(vararg missing: String, entries: List<EmojiData>): EmojibaseDataProvider {
        val lacks = missing.toSet()
        return EmojibaseDataProvider(context).apply {
            applyDataset(entries) { asked -> BooleanArray(asked.size) { asked[it] !in lacks } }
        }
    }

    @Test
    fun anEmojiTheFontCannotDraw_isOffTheBoardAndOutOfSearch() {
        val provider = providerWhereFontLacks(
            faceInClouds,
            entries = listOf(entry(grin, "grinning face", 1), entry(faceInClouds, "face in clouds", 2)),
        )

        assertEquals(listOf(grin), provider.getEmojisByCategory(EmojiCategory.SMILEYS_EMOTION).map { it.emoji })
        assertEquals(listOf(grin), provider.getAllEmojis().map { it.emoji })
        assertTrue(provider.searchEmojis("clouds").isEmpty())
        assertNull(provider.topHit("clouds", 1))
        assertEquals(listOf(grin), provider.searchEmojis("grinning").map { it.emoji })
    }

    @Test
    fun aSkinToneTheFontCannotDraw_isLeftOutOfThePopup() {
        val provider = providerWhereFontLacks(
            waveTones[1], waveTones[3],
            entries = listOf(entry(wave, "waving hand", 1, skins = waveTones)),
        )

        assertEquals(listOf(waveTones[0], waveTones[2], waveTones[4]), provider.getSkinToneVariants(wave))
    }

    @Test
    fun whenTheFontCanDrawNoTone_thereIsNoPopupToShow() {
        val provider = providerWhereFontLacks(
            *waveTones.toTypedArray(),
            entries = listOf(entry(wave, "waving hand", 1, skins = waveTones)),
        )

        assertTrue(provider.getSkinToneVariants(wave).isEmpty())
        assertEquals("the emoji itself stays", listOf(wave), provider.getAllEmojis().map { it.emoji })
    }

    @Test
    fun theFontIsAskedOnce_aboutTheBoardEmojiThenEveryTone() {
        val questions = ArrayList<List<String>>()
        EmojibaseDataProvider(context).applyDataset(
            listOf(entry(grin, "grinning face", 1), entry(wave, "waving hand", 2, skins = waveTones)),
        ) { asked -> questions.add(asked); BooleanArray(asked.size) { true } }

        assertEquals(listOf(listOf(grin, wave) + waveTones), questions)
    }
}
