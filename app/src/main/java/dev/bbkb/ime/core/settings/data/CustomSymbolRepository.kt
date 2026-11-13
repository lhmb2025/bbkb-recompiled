package dev.bbkb.ime.core.settings.data

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider
import dev.bbkb.ime.core.shared.Logger

/** Custom symbol page layouts and palette, in the established stored formats. */
class CustomSymbolRepository(private val context: Context) {

    private val prefs: SharedPreferences = PrefsManager.getPrefs(context)

    companion object {
        /** Editable keys on the on-screen custom page (10+9+7). */
        const val VKB_SLOTS = 26
        /** Editable keys on the physical custom page (10+9+8); a 28-entry store loads trimmed. */
        const val PKB_SLOTS = 27
        private const val DELIMITER = "\u0378"
        private const val KEY_VKB_LAYOUT = "pref_vkb_symbol_page_layout"
        private const val KEY_PKB_LAYOUT = "pref_pkb_symbol_page_layout"
        private const val KEY_CUSTOM_LIST = "pref_additional_symbol_list"
        private const val NULL_MARKER = "\u0000"
        private const val TAG = "CustomSymbolRepository"

        /** The stored list under [key], split, as KeyboardBuilder reads it. */
        @JvmStatic
        fun loadSymbolList(key: String, prefs: SharedPreferences?): List<String>? {
            if (prefs == null) return null
            val stored = prefs.getString(key, null) ?: return null
            return stored.split(DELIMITER)
        }

        /**
         * On-screen page: key position (reading order, rows of 10, 9 and 7) -> index in the stored
         * list. The stored order is fixed so saved pages and exported layouts stay valid; the
         * editor and the keyboard both use this table.
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

        return loadDefaultLayout(isPkb, 1)
    }

    fun saveLayout(isPkb: Boolean, layout: List<String>) {
        val key = if (isPkb) KEY_PKB_LAYOUT else KEY_VKB_LAYOUT
        
        val processedLayout = if (isPkb) {
            layout
        } else {
            // Stored as 28 entries; the two the page has no key for stay blank.
            MutableList(28) { NULL_MARKER }.also { storage ->
                VKB_STORAGE_INDEX_FOR_POSITION.forEachIndexed { position, storageIndex ->
                    if (position < layout.size) storage[storageIndex] = layout[position]
                }
            }
        }
        
        val serialized = processedLayout.joinToString(DELIMITER) { sanitize(it) }
        
        prefs.edit().putString(key, serialized).apply()

        // Not redundant with SettingsManager's pref listener: nothing else clears KeyboardBuilder's
        // keyboard caches, so without this an edited symbol page keeps serving the cached keyboard.
        KeyboardBuilder.onKeyboardThemeChanged()
        Logger.debug(TAG, "Saved layout to $key and cleared KeyboardBuilder cache")
    }

    fun loadCustomSymbols(): List<String> {
        val stored = prefs.getString(KEY_CUSTOM_LIST, null) ?: return emptyList()
        return stored.split(DELIMITER)
            .filter { it.isNotEmpty() && it != "null" && it != NULL_MARKER }
    }

    fun addCustomSymbol(symbol: String) {
        val current = loadCustomSymbols().toMutableList()
        if (!current.contains(symbol)) {
            current.add(0, symbol)
            saveCustomSymbols(current)
        }
    }

    fun removeCustomSymbol(symbol: String) {
        val current = loadCustomSymbols().toMutableList()
        if (current.remove(symbol)) {
            saveCustomSymbols(current)
        }
    }

    /** Replaces the palette with [symbols] in order, minus blanks and repeats (layouts import). */
    fun replaceCustomSymbols(symbols: List<String>) {
        saveCustomSymbols(symbols.filter { it.isNotEmpty() && it != NULL_MARKER }.distinct())
    }

    private fun saveCustomSymbols(list: List<String>) {
        val serialized = list.joinToString(DELIMITER) { sanitize(it) }
        prefs.edit().putString(KEY_CUSTOM_LIST, serialized).apply()
        // See saveLayout: this is the keyboard-cache invalidation.
        KeyboardBuilder.onKeyboardThemeChanged()
    }

    fun loadDefaultLayout(isPkb: Boolean, page: Int): List<String> {
        val resId = when {
            isPkb && page == 1 -> R.array.pkb_page_1
            isPkb && page == 2 -> R.array.pkb_page_2
            !isPkb && page == 1 -> R.array.vkb_page_1
            !isPkb && page == 2 -> R.array.vkb_page_2
            else -> return emptyList()
        }
        val fullArray = context.resources.getStringArray(resId).toList()

        // VKB arrays hold 28 items for 26 positions; PKB arrays hold exactly 27.
        val maxKeys = if (isPkb) PKB_SLOTS else VKB_SLOTS
        return fullArray.take(maxKeys)
    }

    fun loadUmlautLayout(isPkb: Boolean): List<String> {
        val resId = if (isPkb) R.array.pkb_umlaut_layout else R.array.vkb_umlaut_layout
        return context.resources.getStringArray(resId).toList()
    }

    fun loadCategorySymbols(categoryIndex: Int): List<String> {
        // 0 Emoji, 1 Alphanumeric, 2 Punctuation, 3 Accents, 4 Brackets, 5 Currency, 6 Math,
        // 7 Arrows, 8 Custom
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
        val chars = mutableListOf<String>()
        for (c in 'a'..'z') chars.add(c.toString())
        for (c in 'A'..'Z') chars.add(c.toString())
        for (c in '0'..'9') chars.add(c.toString())
        return chars
    }

    private fun loadEmojisFromEmojibase(): List<String> {
        // The shared parsed dataset; a private provider would parse a second copy.
        val allEmojis = EmojibaseDataProvider.getSharedLoaded(context).getAllEmojis()
        
        Logger.debug(TAG, "Loaded ${allEmojis.size} emojis from Emojibase for Compose custom symbol page")
        
        return allEmojis.mapNotNull { emojiData ->
            val emoji = emojiData.emoji
            if (emoji.isNullOrEmpty()) null else emoji
        }
    }

    private fun sanitize(value: String?): String {
        return if (value.isNullOrEmpty() || value.equals("null", ignoreCase = true)) {
            NULL_MARKER
        } else {
            value
        }
    }
}
