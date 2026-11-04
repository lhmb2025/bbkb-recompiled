package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.internal.KeySpecParser
import dev.bbkb.ime.keyboard.internal.MoreKeySpec
import java.util.Locale

/**
 * Factory for creating emoji keyboards using Emojibase emoji data.
 *
 * Creates keyboard-style emoji pages directly from EmojibaseDataProvider
 * without requiring EmojiUtils metadata.
 *
 * The three page kinds (category, search results, recents) share one builder and differ in exactly
 * three things, pinned by `EmojiKeyboardFactoryCharacterisationTest`: category pages are not padded
 * with blank keys while the other two are; recents is a single page of at most [RECENTS_MAX_KEYS];
 * and none of them uses category id 0, which would make it the Recents keyboard that writes the
 * recents preference.
 */
class EmojiKeyboardFactory(
    context: Context,
    private val sharedPreferences: SharedPreferences,
    keyboardBuilder: KeyboardBuilder
) {

    companion object {
        private const val TEMPLATE_KEY_CODE = 48
        private const val TEMPLATE_LAYOUT_ID = 12
        /** 10 emoji per row x 4 rows: recents always use the full height (no search bar above them). */
        private const val RECENTS_MAX_KEYS = 40
        private const val SEARCH_RESULTS_CATEGORY_ID = -1
        private const val RECENTS_DISPLAY_CATEGORY_ID = -2
    }

    private val appContext: Context = context.applicationContext

    // The process-wide provider, read on every use rather than kept in a field. A private instance
    // here re-parsed the emojibase data on the main thread on every input-view creation (audit
    // IB-3), and a field kept a released dataset alive next to its re-parsed successor (audit P-2).
    private val emojiProvider: EmojibaseDataProvider
        get() = EmojibaseDataProvider.getSharedLoaded(appContext)

    // Locale from context resources (LocaleList on API 24+, the deprecated field below that).
    private val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.resources.configuration.locales.get(0)
    } else {
        @Suppress("DEPRECATION")
        context.resources.configuration.locale
    } ?: Locale.getDefault()

    private val maxKeysPerPage: Int =
        context.resources.getInteger(R.integer.config_emoji_keyboard_max_page_key_count)

    // Template keyboard: contains the sample emoji key the pages take their dimensions from.
    private val templateKeyboard: Keyboard = keyboardBuilder.getKeyboard(TEMPLATE_LAYOUT_ID)

    private val templateKey: Key = templateKeyboard.getKeyByCode(TEMPLATE_KEY_CODE)

    /**
     * The pages of [category], up to maxKeysPerPage (40) emojis each; EmojiKeyboard arranges them
     * into its grid from the keyboard width. Category pages are not padded.
     */
    fun createEmojiKeyboards(category: EmojiCategory): List<EmojiKeyboard> =
        emojiProvider.getEmojisByCategory(category).map { it.emoji }
            .chunked(maxKeysPerPage)
            .map { buildPage(it, maxKeysPerPage, category.id, pad = false) }

    /** Search results, maxKeysPerPage to a page, each page padded with blank keys. */
    fun createSearchResultsKeyboards(results: List<EmojiData>): List<EmojiKeyboard> =
        results.map { it.emoji }
            .chunked(maxKeysPerPage)
            .map { buildPage(it, maxKeysPerPage, SEARCH_RESULTS_CATEGORY_ID, pad = true) }

    /**
     * One blank padded page, for a typed query with no matches: the search field stays up and the
     * page shows nothing, so the user sees that the query failed rather than the tab behind it.
     */
    fun createEmptyResultsPage(): List<EmojiKeyboard> =
        listOf(buildPage(emptyList(), maxKeysPerPage, SEARCH_RESULTS_CATEGORY_ID, pad = true))

    /** Recents for the overlay: never more than one padded page. */
    fun createRecentsKeyboards(emojis: List<String>): List<EmojiKeyboard> {
        // Recents come from a stored preference that may be corrupted: an entry the key-spec parser
        // rejects (e.g. "|x") would throw from createEmojiKey and take the board down, so skip it.
        val usable = emojis.filter { parsesAsKeySpec(it) }
        return if (usable.isEmpty()) {
            emptyList()
        } else {
            listOf(buildPage(usable.take(RECENTS_MAX_KEYS), RECENTS_MAX_KEYS, RECENTS_DISPLAY_CATEGORY_ID, pad = true))
        }
    }

    private fun parsesAsKeySpec(emoji: String): Boolean =
        try {
            MoreKeySpec(emoji, false, locale)
            true
        } catch (e: KeySpecParser.KeySpecParserError) {
            false
        }

    /**
     * Get total number of pages for a category.
     * Each page contains up to maxKeysPerPage (40) emojis in a 4×10 grid.
     */
    fun getPageCount(category: EmojiCategory): Int {
        val emojis = emojiProvider.getEmojisByCategory(category)
        return (emojis.size + maxKeysPerPage - 1) / maxKeysPerPage  // Ceiling division
    }

    /**
     * Get locale (for compatibility with old ProximityInfo API).
     */
    fun getLocale(): Locale {
        return locale
    }

    private fun buildPage(emojis: List<String>, maxKeys: Int, categoryId: Int, pad: Boolean): EmojiKeyboard =
        EmojiKeyboard(sharedPreferences, templateKeyboard, maxKeys, categoryId, locale).apply {
            emojis.forEach { addKey(createEmojiKey(it)) }
            if (pad) fillEmptySpaces()
        }

    /**
     * A key for [emoji], cloned from the template key.
     *
     * The key spec is built with the 4-argument constructor, bypassing the key-spec parser (which can
     * fail on emoji strings): code CODE_OUTPUT_TEXT (-4) and the full sequence as output text. A
     * skin-tone or ZWJ sequence spans several code points and must be inserted atomically, or the
     * text field briefly shows the base emoji plus a modifier square.
     *
     * The key shows and inserts the skin tone the user last chose for this emoji
     * ([EmojiToneMemory]), or the base emoji when none was chosen. The more-keys array is the
     * long-press popup: the base emoji first, then every skin-tone variant —
     * EmojiPageKeyboardView.onLongPress builds the MoreKeysKeyboard popup from it.
     */
    private fun createEmojiKey(emoji: String): Key {
        val shown = EmojiToneMemory.preferred(sharedPreferences, emoji) ?: emoji
        return Key(
            templateKey,
            MoreKeySpec(shown, 0, -4, shown),
            (listOf(emoji) + emojiProvider.getSkinToneVariants(emoji))
                .map { MoreKeySpec(it, false, locale) }
                .toTypedArray()
        )
    }
}
