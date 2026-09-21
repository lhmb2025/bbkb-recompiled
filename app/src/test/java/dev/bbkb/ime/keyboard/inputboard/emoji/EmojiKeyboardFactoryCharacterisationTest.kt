package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.SharedPreferences
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.PAGE_KEYS
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.field
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterisation of the page builders in `EmojiKeyboardFactory` and of emoji search ranking in
 * `EmojibaseDataProvider` / `EmojiData`, before the three builders become one and match+rank fold.
 *
 * The three builders differ in exactly three things, all pinned here:
 *  - category pages are NOT padded with blank keys; search-result and recents pages are;
 *  - recents is a single page of at most 40, search results chunk into pages of 40;
 *  - none of the three is the Recents keyboard (category id 0), so none writes the recents preference.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojiKeyboardFactoryCharacterisationTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var factory: EmojiKeyboardFactory

    private val smileys = (0 until 41).map { EmojiTestEnv.emoji("s$it", 0, "smile $it") }

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seed(
            linkedMapOf(
                EmojiCategory.SMILEYS_EMOTION to smileys,
                EmojiCategory.ANIMALS_NATURE to (0 until 3).map { EmojiTestEnv.emoji("a$it", 3, "cat $it") },
            )
        )
        val shared = EmojibaseDataProvider.getSharedLoaded(EmojiTestEnv.app)
        field(EmojibaseDataProvider::class.java, "skinsByEmoji").set(shared, mapOf("s0" to listOf("s0-1", "s0-2")))
        prefs = EmojiTestEnv.prefs()
        prefs.edit().clear().commit()
        factory = EmojiKeyboardFactory(
            BoardKeyboardFactory.themedContext(EmojiTestEnv.app), prefs, EmojiTestEnv.emojiBuilder()
        )
    }

    @After
    fun tearDown() = EmojiTestEnv.uninstall()

    private fun outputs(k: EmojiKeyboard): List<String?> = k.keys.map { it.keySpecOutputText }

    private fun emojiOutputs(k: EmojiKeyboard): List<String?> =
        k.keys.filter { it.code != -21 }.map { it.keySpecOutputText }

    private fun moreKeyLabels(key: Key): List<String?> = key.moreKeys.map { it.label }

    // ------------------------------------------------------------------ category pages

    @Test
    fun categoryPagesChunkByFortyAndAreNotPadded() {
        val pages = factory.createEmojiKeyboards(EmojiCategory.SMILEYS_EMOTION)
        assertEquals(2, pages.size)
        assertEquals((0 until 40).map { "s$it" }, outputs(pages[0]))
        assertEquals(listOf("s40"), outputs(pages[1]))
    }

    @Test
    fun emojiKeysAreOutputTextKeysLabelledWithTheEmoji() {
        val key = factory.createEmojiKeyboards(EmojiCategory.SMILEYS_EMOTION)[0].keys[1]
        assertEquals(-4, key.code)
        assertEquals("s1", key.label)
        assertEquals("s1", key.keySpecOutputText)
    }

    @Test
    fun skinToneVariantsFollowTheBaseEmojiInMoreKeys() {
        val keys = factory.createEmojiKeyboards(EmojiCategory.SMILEYS_EMOTION)[0].keys
        assertEquals(listOf("s0", "s0-1", "s0-2"), moreKeyLabels(keys[0]))
        assertEquals(listOf("s1"), moreKeyLabels(keys[1]))
    }

    @Test
    fun anEmptyCategoryHasNoPages() {
        assertEquals(emptyList<EmojiKeyboard>(), factory.createEmojiKeyboards(EmojiCategory.PEOPLE_BODY))
        assertEquals(0, factory.getPageCount(EmojiCategory.PEOPLE_BODY))
        assertEquals(2, factory.getPageCount(EmojiCategory.SMILEYS_EMOTION))
        assertEquals(1, factory.getPageCount(EmojiCategory.ANIMALS_NATURE))
    }

    // ------------------------------------------------------------------ search and recents pages

    @Test
    fun searchResultPagesChunkByFortyAndArePadded() {
        val pages = factory.createSearchResultsKeyboards(smileys)
        assertEquals(2, pages.size)
        assertEquals((0 until 40).map { "s$it" }, outputs(pages[0]))
        assertEquals(PAGE_KEYS, pages[1].keys.size)
        assertEquals(listOf("s40"), emojiOutputs(pages[1]))
        assertTrue(pages[1].keys.drop(1).all { it.code == -21 && it.keySpecOutputText == "" })
        assertEquals(emptyList<EmojiKeyboard>(), factory.createSearchResultsKeyboards(emptyList()))
    }

    @Test
    fun recentsIsOnePaddedPageOfAtMostForty() {
        val many = factory.createRecentsKeyboards((0 until 45).map { "r$it" })
        assertEquals(1, many.size)
        assertEquals((0 until 40).map { "r$it" }, outputs(many[0]))

        val one = factory.createRecentsKeyboards(listOf("r0"))
        assertEquals(1, one.size)
        assertEquals(PAGE_KEYS, one[0].keys.size)
        assertEquals(listOf("r0"), emojiOutputs(one[0]))

        assertEquals(emptyList<EmojiKeyboard>(), factory.createRecentsKeyboards(emptyList()))
    }

    /** Recents come from a stored value that may be corrupted: an entry the key-spec parser rejects is skipped. */
    @Test
    fun recentsSkipsAnEntryTheKeySpecParserRejects() {
        val pages = factory.createRecentsKeyboards(listOf("|x", "r0"))
        assertEquals(listOf("r0"), emojiOutputs(pages.single()))
        assertEquals(emptyList<EmojiKeyboard>(), factory.createRecentsKeyboards(listOf("|x")))
    }

    @Test
    fun recentsAndSearchKeysCarrySkinTonesToo() {
        assertEquals(listOf("s0", "s0-1", "s0-2"), moreKeyLabels(factory.createRecentsKeyboards(listOf("s0"))[0].keys[0]))
        assertEquals(listOf("s0", "s0-1", "s0-2"), moreKeyLabels(factory.createSearchResultsKeyboards(smileys)[0].keys[0]))
    }

    @Test
    fun noBuiltPageIsTheRecentsKeyboard() {
        factory.createEmojiKeyboards(EmojiCategory.SMILEYS_EMOTION)[0].addEmojiToRecents("x1")
        factory.createSearchResultsKeyboards(smileys)[0].addEmojiToRecents("x2")
        factory.createRecentsKeyboards(listOf("r0"))[0].addEmojiToRecents("x3")
        assertFalse(prefs.contains(RECENTS_KEY))
    }

    // ------------------------------------------------------------------ search ranking

    private fun provider(vararg data: EmojiData): EmojibaseDataProvider {
        val p = EmojibaseDataProvider(EmojiTestEnv.app)
        field(EmojibaseDataProvider::class.java, "emojiByCategory")
            .set(p, mutableMapOf(EmojiCategory.SMILEYS_EMOTION to data.toMutableList()))
        return p
    }

    private fun data(e: String, description: String, tags: List<String>? = null, aliases: List<String>? = null) =
        EmojiData(
            emoji = e, codepoints = "0", description = description, tags = tags, aliases = aliases,
            group = 0, subgroup = 0, emojiVersion = 1.0, order = 0
        )

    private val ranked = provider(
        data("e1", "grinning face", tags = listOf("smile", "happy"), aliases = listOf("grinning")),
        data("e2", "smile cat", tags = listOf("cat")),
        data("e3", "sun", tags = listOf("weather"), aliases = listOf("sunny_smile")),
        data("e4", "happy dog"),
        data("e5", "Smiley", tags = listOf("SMILES")),
    )

    private fun search(query: String) = ranked.searchEmojis(query).map { it.emoji }

    @Test
    fun searchRanksDescriptionThenTagThenAliasMatches() {
        // e2 desc-startsWith 50; e5 desc-startsWith 50 + tag-startsWith 20; e1 tag-exact 40; e3 alias-contains 5
        assertEquals(listOf("e5", "e2", "e1", "e3"), search("smile"))
    }

    @Test
    fun searchIsCaseInsensitiveAndTrimsTheQuery() {
        assertEquals(listOf("e4", "e1"), search("HAPPY"))
        // e3: desc-exact 100 + alias-contains 5
        assertEquals(listOf("e3"), search("  sun "))
    }

    @Test
    fun blankOrUnmatchedQueriesFindNothing() {
        assertEquals(emptyList<String>(), search("   "))
        assertEquals(emptyList<String>(), search("zzz"))
    }

    @Test
    fun equalScoresKeepDatasetOrderAndResultsCapAtEighty() {
        val many = provider(*(0 until 90).map { data("m$it", "moon $it") }.toTypedArray())
        assertEquals((0 until 80).map { "m$it" }, many.searchEmojis("moon").map { it.emoji })
    }
}
