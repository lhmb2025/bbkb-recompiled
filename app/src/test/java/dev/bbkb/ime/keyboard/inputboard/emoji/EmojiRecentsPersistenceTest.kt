package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.SharedPreferences
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.PAGE_KEYS
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.RECENTS_KEY
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.jsonArray
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv.padded
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Characterisation of the emoji recents preference — `emoji_recent_keys`, user data that survives
 * upgrades — pinned byte for byte, before anything consolidates its two readers.
 *
 * ## The format, as it actually is
 * A JSON array written by a default-configured `Gson` from `EmojiKeyboard`'s whole key grid,
 * newest first. The grid is always padded to its 40-key page with blank filler keys, and a blank
 * key's output text is `""`, so the stored array is almost always exactly 40 entries with a tail of
 * `""`. Readers skip empty strings and anything that is not a string.
 *
 * ## Two readers that are not the same reader
 *  - `EmojiKeyboard.loadRecentsFromPreferences` (the Recents page) builds keys, caps at the grid's
 *    40 via `addKey`, pads with blanks, reads malformed JSON as no recents, and skips a string the
 *    key spec parser rejects (see [keyboardLoaderSkipsAnEntryTheKeySpecParserRejects]).
 *  - `EmojiCategoryManager.getRecentEmojis()` (the search/recents overlay) returns at most 30, and
 *    catches everything — the same rejected entry comes back as a string (the overlay's page
 *    builder, `EmojiKeyboardFactory.createRecentsKeyboards`, then skips it).
 * A shared helper has to keep both policies; this file is what says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class EmojiRecentsPersistenceTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var template: Keyboard

    @Before
    fun setUp() {
        EmojiTestEnv.install()
        EmojiTestEnv.seed(emptyMap())
        prefs = EmojiTestEnv.prefs()
        prefs.edit().clear().commit()
        template = EmojiTestEnv.templateKeyboard()
    }

    @After
    fun tearDown() = EmojiTestEnv.uninstall()

    private fun store(json: String) {
        prefs.edit().putString(RECENTS_KEY, json).commit()
    }

    private fun stored(): String? = prefs.getString(RECENTS_KEY, null)

    private fun recentsKeyboard(): EmojiKeyboard =
        EmojiKeyboard(prefs, template, PAGE_KEYS, 0, Locale.US).also { it.loadRecentsFromPreferences() }

    private fun outputs(keyboard: EmojiKeyboard): List<String?> = keyboard.keys.map { it.keySpecOutputText }

    private fun emojiOutputs(keyboard: EmojiKeyboard): List<String?> =
        keyboard.keys.filter { it.code != -21 }.map { it.keySpecOutputText }

    private fun categoryManager(): EmojiCategoryManager {
        val ctx = BoardKeyboardFactory.themedContext(EmojiTestEnv.app)
        val attrs = ctx.obtainStyledAttributes(
            null, R.styleable.EmojiPalettesView, R.attr.emojiPalettesViewStyle, R.style.EmojiPalettesView
        )
        try {
            return EmojiCategoryManager(prefs, ctx, EmojiTestEnv.emojiBuilder(), attrs)
        } finally {
            attrs.recycle()
        }
    }

    // ------------------------------------------------------------------ EmojiKeyboard: read

    @Test
    fun loadKeepsNonEmptyStringsInOrderAndSkipsEverythingElse() {
        store("[\"😀\",\"\",7,\"😁\",null,true]")
        val k = recentsKeyboard()
        assertEquals(listOf("😀", "😁"), emojiOutputs(k))
        assertEquals(PAGE_KEYS, k.keys.size)
        assertEquals(List(PAGE_KEYS - 2) { "" }, outputs(k).drop(2))
        assertTrue(k.keys.drop(2).all { it.code == -21 })
    }

    @Test
    fun loadOfAnAbsentPreferenceIsAFullPageOfBlanks() {
        val k = recentsKeyboard()
        assertEquals(PAGE_KEYS, k.keys.size)
        assertTrue(k.keys.all { it.code == -21 })
    }

    /** Malformed JSON loads as no recents: a full page of blanks, and the stored value is left alone. */
    @Test
    fun loadOfMalformedJsonIsAFullPageOfBlanks() {
        for (bad in listOf("[", "{\"a\":1}", "\"😀\"")) {
            store(bad)
            val k = recentsKeyboard()
            assertEquals(bad, PAGE_KEYS, k.keys.size)
            assertTrue(bad, k.keys.all { it.code == -21 })
            assertEquals(bad, stored())
        }
    }

    @Test
    fun loadCapsAtTheGridSizeKeepingTheFirstEntries() {
        store(jsonArray((0 until 45).map { "e$it" }))
        val k = recentsKeyboard()
        assertEquals((0 until PAGE_KEYS).map { "e$it" }, outputs(k))
    }

    @Test
    fun loadDoesNotRewriteThePreference() {
        val raw = "[ \"😀\" , 7 ]"
        store(raw)
        recentsKeyboard()
        assertEquals(raw, stored())
    }

    /** An entry the key-spec parser rejects is skipped on its own; the entries around it still load. */
    @Test
    fun keyboardLoaderSkipsAnEntryTheKeySpecParserRejects() {
        store("[\"😀\",\"|x\",\"😁\"]")
        val k = recentsKeyboard()
        assertEquals(listOf("😀", "😁"), emojiOutputs(k))
        assertEquals(PAGE_KEYS, k.keys.size)
    }

    // ------------------------------------------------------------------ EmojiKeyboard: write

    @Test
    fun addWritesTheWholeGridNewestFirstPaddedWithEmptyStrings() {
        store("[\"😀\",\"😁\"]")
        recentsKeyboard().addEmojiToRecents("😂")
        assertEquals(padded("😂", "😀", "😁"), stored())
    }

    @Test
    fun addOfAnExistingEmojiMovesItToTheFrontWithoutDuplicating() {
        store("[\"😀\",\"😁\",\"😂\"]")
        recentsKeyboard().addEmojiToRecents("😁")
        assertEquals(padded("😁", "😀", "😂"), stored())
    }

    @Test
    fun addToAFullGridDropsTheOldest() {
        store(jsonArray((0 until PAGE_KEYS).map { "e$it" }))
        recentsKeyboard().addEmojiToRecents("new")
        assertEquals(jsonArray(listOf("new") + (0 until PAGE_KEYS - 1).map { "e$it" }), stored())
    }

    @Test
    fun writeUsesGsonDefaultHtmlEscaping() {
        recentsKeyboard().addEmojiToRecents("a=b'<>&")
        assertEquals(padded("a\\u003db\\u0027\\u003c\\u003e\\u0026"), stored())
    }

    @Test
    fun addOnANonRecentsKeyboardDoesNotWrite() {
        EmojiKeyboard(prefs, template, PAGE_KEYS, 5, Locale.US).addEmojiToRecents("😀")
        assertFalse(prefs.contains(RECENTS_KEY))
    }

    @Test
    fun addOfNullOrEmptyIsANoOp() {
        store("[\"😀\"]")
        val k = recentsKeyboard()
        k.addEmojiToRecents(null)
        k.addEmojiToRecents("")
        assertEquals("[\"😀\"]", stored())
        assertEquals(listOf("😀"), emojiOutputs(k))
    }

    @Test
    fun enqueueDefersAndProcessingAppliesInOrderThenWrites() {
        val k = recentsKeyboard()
        k.enqueueEmoji("😀")
        k.enqueueEmoji("😁")
        assertFalse(prefs.contains(RECENTS_KEY))
        k.processEmojiQueue()
        assertEquals(padded("😁", "😀"), stored())
    }

    /** Processing an empty queue changes nothing, so nothing is written. */
    @Test
    fun processingAnEmptyQueueDoesNotWrite() {
        recentsKeyboard().processEmojiQueue()
        assertFalse(prefs.contains(RECENTS_KEY))
    }

    /** A queued tap is a use like any tap: it writes the grid even when the order is unchanged. */
    @Test
    fun processingAQueuedTapWritesEvenWhenTheOrderIsUnchanged() {
        store("[\"😀\",\"😁\"]")
        val k = recentsKeyboard()
        k.enqueueEmoji("😀")
        k.processEmojiQueue()
        assertEquals(padded("😀", "😁"), stored())
    }

    @Test
    fun legacyValueRoundTripsThroughAddAndReload() {
        store("[\"😀\",3,\"\",\"😁\"]")
        recentsKeyboard().addEmojiToRecents("😀")
        val written = padded("😀", "😁")
        assertEquals(written, stored())
        val reloaded = recentsKeyboard()
        assertEquals(listOf("😀", "😁"), emojiOutputs(reloaded))
        reloaded.processEmojiQueue()
        assertEquals(written, stored())
    }

    // ------------------------------------------------------------------ EmojiCategoryManager: read

    @Test
    fun categoryManagerReturnsTheFirstThirtyNonEmptyStrings() {
        val entries = (0 until 35).flatMap { listOf("\"r$it\"", "\"\"", "$it") }
        store(entries.joinToString(",", "[", "]"))
        val ecm = categoryManager()
        assertEquals((0 until 30).map { "r$it" }, ecm.recentEmojis)
    }

    @Test
    fun categoryManagerReadsMalformedOrAbsentAsEmpty() {
        val ecm = categoryManager()
        assertEquals(emptyList<String>(), ecm.recentEmojis)
        for (bad in listOf("[", "{\"a\":1}", "\"😀\"")) {
            store(bad)
            assertEquals(bad, emptyList<String>(), ecm.recentEmojis)
        }
    }

    @Test
    fun categoryManagerReturnsAnEntryTheKeyboardLoaderRejects() {
        val ecm = categoryManager()
        store("[\"|x\",\"😀\"]")
        assertEquals(listOf("|x", "😀"), ecm.recentEmojis)
    }

    @Test
    fun categoryManagerReadsThePreferenceNotTheQueuedGrid() {
        store("[\"😀\"]")
        val ecm = categoryManager()
        ecm.getOrCreateKeyboard(0, 0).enqueueEmoji("😁")
        assertEquals(listOf("😀"), ecm.recentEmojis)
    }

    @Test
    fun categoryManagerSkipsTheGridsBlankPadding() {
        store(padded("😀", "😁"))
        assertEquals(listOf("😀", "😁"), categoryManager().recentEmojis)
    }
}
