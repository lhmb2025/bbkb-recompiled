package dev.bbkb.ime.core.settings.data

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider
import dev.bbkb.ime.core.shared.Logger

/**
 * Repository for managing Custom Symbol Page data.
 * Maintains exact backward compatibility with legacy storage formats.
 */
class CustomSymbolRepository(private val context: Context) {

    private val prefs: SharedPreferences = PrefsManager.getPrefs(context)

    companion object {
        /** Editable keys on the on-screen custom page (10+9+7). */
        const val VKB_SLOTS = 26
        /**
         * Editable keys on the physical custom page (10+9+8, `rows_symbols_custom_pkb.xml`). A
         * store saved by the old 28-slot editor loads trimmed to this; the keyboard never had a
         * 28th key to draw the extra entry on.
         */
        const val PKB_SLOTS = 27
        private const val DELIMITER = "\u0378"
        private const val KEY_VKB_LAYOUT = "pref_vkb_symbol_page_layout"
        private const val KEY_PKB_LAYOUT = "pref_pkb_symbol_page_layout"
        private const val KEY_CUSTOM_LIST = "pref_additional_symbol_list"
        private const val NULL_MARKER = "\u0000"
        private const val TAG = "CustomSymbolRepository"

        /**
         * Static utility method for loading symbol lists from SharedPreferences.
         * Used by KeyboardBuilder to load custom symbol layouts.
         * Migrated from CustomSymbolPageFragment.loadSymbolList()
         */
        @JvmStatic
        fun loadSymbolList(key: String, prefs: SharedPreferences?): List<String>? {
            if (prefs == null) return null
            val stored = prefs.getString(key, null) ?: return null
            return stored.split(DELIMITER)
        }

        /**
         * On-screen page: key position (reading order, rows of 10, 9 and 7) -> index in the
         * stored list. The stored order is the original app's key-sorting order; it is kept so
         * saved pages and exported layouts stay valid. The editor and the keyboard both go
         * through this one table.
         */
        private val VKB_STORAGE_INDEX_FOR_POSITION = intArrayOf(
            6, 7, 8, 9, 10, 11, 12, 13, 14, 5,
            25, 21, 20, 19, 22, 23, 24, 18, 17,
            1, 3, 4, 15, 0, 16, 2,
        )

        /** A stored on-screen page in key-position order; a short or absent list is returned as it is. */
        @JvmStatic
        fun vkbStorageToVisualOrder(stored: List<String>?): List<String>? {
            if (stored == null || stored.size < VKB_SLOTS) return stored
            return List(VKB_SLOTS) { position -> stored[VKB_STORAGE_INDEX_FOR_POSITION[position]] }
        }
    }

    // ============================================================================
    // Layout Management
    // ============================================================================

    fun loadLayout(isPkb: Boolean): List<String> {
        val key = if (isPkb) KEY_PKB_LAYOUT else KEY_VKB_LAYOUT
        val stored = prefs.getString(key, null)
        
        val maxKeys = if (isPkb) PKB_SLOTS else VKB_SLOTS

        if (stored != null) {
            val rawLayout = stored.split(DELIMITER)
            
            val layout = if (isPkb) rawLayout else vkbStorageToVisualOrder(rawLayout) ?: rawLayout
            
            return when {
                layout.size > maxKeys -> layout.take(maxKeys)
                layout.size < maxKeys -> layout + List(maxKeys - layout.size) { "\u0000" }
                else -> layout
            }
        }
        
        // Fallback to defaults if nothing stored
        return loadDefaultLayout(isPkb, 1) // Default to Page 1
    }

    fun saveLayout(isPkb: Boolean, layout: List<String>) {
        val key = if (isPkb) KEY_PKB_LAYOUT else KEY_VKB_LAYOUT
        
        val processedLayout = if (isPkb) {
            layout
        } else {
            // 28 entries, as the original stored it; the two the page has no key for stay blank.
            MutableList(28) { NULL_MARKER }.also { storage ->
                VKB_STORAGE_INDEX_FOR_POSITION.forEachIndexed { position, storageIndex ->
                    if (position < layout.size) storage[storageIndex] = layout[position]
                }
            }
        }
        
        val serialized = processedLayout.joinToString(DELIMITER) { sanitize(it) }
        
        prefs.edit().putString(key, serialized).apply()

        // CRITICAL, and not redundant with SettingsManager: this is the ONLY caller of
        // onKeyboardThemeChanged() in the tree. SettingsManager's OnSharedPreferenceChangeListener
        // does fire for these keys — it always did, since getDefaultSharedPreferences and
        // PrefsManager hand back the same process-wide instance for the same file — but all it
        // does is rebuild SettingsValues and notify its own listeners. Nothing on that path clears
        // KeyboardBuilder's sKeyboardCache/sKeysCache, so without this call an edited symbol page
        // keeps serving the cached keyboard. §5.8 flagged a possible double rebuild here; there
        // is none, because there is no other rebuild.
        KeyboardBuilder.onKeyboardThemeChanged()
        Logger.debug(TAG, "Saved layout to $key and cleared KeyboardBuilder cache")
    }

    // ============================================================================
    // Custom Symbol List Management
    // ============================================================================

    fun loadCustomSymbols(): List<String> {
        val stored = prefs.getString(KEY_CUSTOM_LIST, null) ?: return emptyList()
        return stored.split(DELIMITER)
            .filter { it.isNotEmpty() && it != "null" && it != NULL_MARKER }
    }

    fun addCustomSymbol(symbol: String) {
        val current = loadCustomSymbols().toMutableList()
        if (!current.contains(symbol)) {
            current.add(0, symbol) // Add to front
            saveCustomSymbols(current)
        }
    }

    fun removeCustomSymbol(symbol: String) {
        val current = loadCustomSymbols().toMutableList()
        if (current.remove(symbol)) {
            saveCustomSymbols(current)
        }
    }

    /**
     * Replaces the whole custom palette with [symbols], in order, dropping blanks and repeats —
     * what importing a layouts file does. Goes through the same write as add/remove, so the
     * keyboard cache is cleared the same way.
     */
    fun replaceCustomSymbols(symbols: List<String>) {
        saveCustomSymbols(symbols.filter { it.isNotEmpty() && it != NULL_MARKER }.distinct())
    }

    private fun saveCustomSymbols(list: List<String>) {
        val serialized = list.joinToString(DELIMITER) { sanitize(it) }
        prefs.edit().putString(KEY_CUSTOM_LIST, serialized).apply()
        // See saveLayout: the only keyboard-cache invalidation there is.
        KeyboardBuilder.onKeyboardThemeChanged()
    }

    // ============================================================================
    // Defaults & Resources
    // ============================================================================

    fun loadDefaultLayout(isPkb: Boolean, page: Int): List<String> {
        val resId = when {
            isPkb && page == 1 -> R.array.pkb_page_1
            isPkb && page == 2 -> R.array.pkb_page_2
            !isPkb && page == 1 -> R.array.vkb_page_1
            !isPkb && page == 2 -> R.array.vkb_page_2
            else -> return emptyList()
        }
        val fullArray = context.resources.getStringArray(resId).toList()
        
        // VKB has only 26 customizable positions (10+9+7), but arrays have 28 items;
        // PKB has 27 (10+9+8) and its arrays hold exactly those.
        val maxKeys = if (isPkb) PKB_SLOTS else VKB_SLOTS
        return fullArray.take(maxKeys)
    }

    fun loadUmlautLayout(isPkb: Boolean): List<String> {
        val resId = if (isPkb) R.array.pkb_umlaut_layout else R.array.vkb_umlaut_layout
        return context.resources.getStringArray(resId).toList()
    }

    fun loadCategorySymbols(categoryIndex: Int): List<String> {
        // Category indices:
        // 0 = Emoji, 1 = Alphanumeric, 2 = Punctuation, 3 = Accents, 
        // 4 = Brackets, 5 = Currency, 6 = Math, 7 = Arrows, 8 = Custom
        when (categoryIndex) {
            0 -> return loadEmojisFromEmojibase()
            1 -> return loadAlphanumeric()
        }

        val arrayId = when (categoryIndex) {
            2 -> R.array.symbols_list_punctuation
            3 -> R.array.symbols_list_accents
            4 -> R.array.symbols_list_brackets
            5 -> R.array.symbols_list_currency
            6 -> R.array.symbols_list_math
            7 -> R.array.symbols_list_arrows
            else -> return emptyList()
        }

        return context.resources.getStringArray(arrayId).toList()
    }
    
    private fun loadAlphanumeric(): List<String> {
        // a-z, A-Z, 0-9
        val chars = mutableListOf<String>()
        // Lowercase a-z
        for (c in 'a'..'z') chars.add(c.toString())
        // Uppercase A-Z
        for (c in 'A'..'Z') chars.add(c.toString())
        // Digits 0-9
        for (c in '0'..'9') chars.add(c.toString())
        return chars
    }

    private fun loadEmojisFromEmojibase(): List<String> {
        // The shared, already-parsed dataset: a private provider here parsed a second copy of
        // the whole dataset every time the custom symbol page was built.
        val allEmojis = EmojibaseDataProvider.getSharedLoaded(context).getAllEmojis()
        
        Logger.debug(TAG, "Loaded ${allEmojis.size} emojis from Emojibase for Compose custom symbol page")
        
        return allEmojis.mapNotNull { emojiData ->
            val emoji = emojiData.emoji
            if (emoji.isNullOrEmpty()) null else emoji
        }
    }
    
    // ============================================================================
    // Helpers
    // ============================================================================

    private fun sanitize(value: String?): String {
        return if (value.isNullOrEmpty() || value.equals("null", ignoreCase = true)) {
            NULL_MARKER
        } else {
            value
        }
    }
}
