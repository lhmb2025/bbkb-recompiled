package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.ConfigurationCompat
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.internal.KeySpecParser
import dev.bbkb.ime.keyboard.internal.MoreKeySpec
import java.util.Locale

/**
 * Builds emoji pages from [EmojibaseDataProvider]. Category, search and recents pages share one
 * builder and differ only in: category pages are not padded; recents is one page of at most
 * [RECENTS_MAX_KEYS]; none uses category id 0, which would make it write the recents preference.
 */
class EmojiKeyboardFactory(
    context: Context,
    private val sharedPreferences: SharedPreferences,
    keyboardBuilder: KeyboardBuilder
) {

    companion object {
        private const val TEMPLATE_KEY_CODE = 48
        private const val TEMPLATE_LAYOUT_ID = 12
        /** 10 x 4: recents use the full height (no search bar above them). */
        private const val RECENTS_MAX_KEYS = 40
        private const val SEARCH_RESULTS_CATEGORY_ID = -1
        private const val RECENTS_DISPLAY_CATEGORY_ID = -2
    }

    private val appContext: Context = context.applicationContext

    // Read on every use, not kept: a field would keep a released dataset alive next to its
    // re-parsed successor.
    private val emojiProvider: EmojibaseDataProvider
        get() = EmojibaseDataProvider.getSharedLoaded(appContext)

    private val locale: Locale =
        ConfigurationCompat.getLocales(context.resources.configuration).get(0) ?: Locale.getDefault()

    private val maxKeysPerPage: Int =
        context.resources.getInteger(R.integer.config_emoji_keyboard_max_page_key_count)

    // Holds the sample emoji key the pages take their dimensions from.
    private val templateKeyboard: Keyboard = keyboardBuilder.getKeyboard(TEMPLATE_LAYOUT_ID)

    private val templateKey: Key = templateKeyboard.getKeyByCode(TEMPLATE_KEY_CODE)

    /** The pages of [category], up to maxKeysPerPage emojis each, not padded. */
    fun createEmojiKeyboards(category: EmojiCategory): List<EmojiKeyboard> =
        emojiProvider.getEmojisByCategory(category).map { it.emoji }
            .chunked(maxKeysPerPage)
            .map { buildPage(it, maxKeysPerPage, category.id, pad = false) }

    /** Search results, maxKeysPerPage to a page, each page padded with blank keys. */
    fun createSearchResultsKeyboards(results: List<EmojiData>): List<EmojiKeyboard> =
        results.map { it.emoji }
            .chunked(maxKeysPerPage)
            .map { buildPage(it, maxKeysPerPage, SEARCH_RESULTS_CATEGORY_ID, pad = true) }

    /** One blank page for a query with no matches, so the tab behind does not show through. */
    fun createEmptyResultsPage(): List<EmojiKeyboard> =
        listOf(buildPage(emptyList(), maxKeysPerPage, SEARCH_RESULTS_CATEGORY_ID, pad = true))

    /** Recents for the overlay: never more than one padded page. */
    fun createRecentsKeyboards(emojis: List<String>): List<EmojiKeyboard> {
        // The stored preference may be corrupt: an entry the parser rejects (e.g. "|x") would
        // throw from createEmojiKey, so skip it.
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

    fun getPageCount(category: EmojiCategory): Int {
        val emojis = emojiProvider.getEmojisByCategory(category)
        return (emojis.size + maxKeysPerPage - 1) / maxKeysPerPage
    }

    fun getLocale(): Locale {
        return locale
    }

    private fun buildPage(emojis: List<String>, maxKeys: Int, categoryId: Int, pad: Boolean): EmojiKeyboard =
        EmojiKeyboard(sharedPreferences, templateKeyboard, maxKeys, categoryId, locale).apply {
            emojis.forEach { addKey(createEmojiKey(it)) }
            if (pad) fillEmptySpaces()
        }

    /**
     * [key] rebuilt with the remembered tone if it is a key for base [base] and its output changed,
     * else null. Rebuilt from the popup's first entry, the emoji it was built from.
     */
    fun retoneKey(key: Key, base: String): Key? {
        val built = key.moreKeys?.firstOrNull()?.label ?: return null
        if (built.isEmpty() || EmojiToneMemory.normalize(built) != base) return null
        val rebuilt = createEmojiKey(built)
        return if (rebuilt.keySpecOutputText == key.keySpecOutputText) null else rebuilt
    }

    /**
     * A key for [emoji] showing its remembered tone ([EmojiToneMemory]). The spec skips the parser
     * (it can fail on emoji) and uses CODE_OUTPUT_TEXT (-4) so a multi-code-point sequence is
     * inserted atomically. More keys (the popup): the base emoji, then each skin-tone variant.
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
