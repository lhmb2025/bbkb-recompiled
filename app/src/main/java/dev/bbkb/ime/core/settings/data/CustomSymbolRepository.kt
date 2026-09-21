package dev.bbkb.ime.core.settings.data

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider
import timber.log.Timber

/**
 * Repository for managing Custom Symbol Page data.
 * Maintains exact backward compatibility with legacy storage formats.
 */
class CustomSymbolRepository(private val context: Context) {

    private val prefs: SharedPreferences = PrefsManager.getPrefs(context)

    companion object {
        private const val DELIMITER = "\u0378"
        private const val KEY_VKB_LAYOUT = "pref_vkb_symbol_page_layout"
        private const val KEY_PKB_LAYOUT = "pref_pkb_symbol_page_layout"
        private const val KEY_CUSTOM_LIST = "pref_additional_symbol_list"
        private const val NULL_MARKER = "\u0000"

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
    }

    // ============================================================================
    // Layout Management
    // ============================================================================

    fun loadLayout(isPkb: Boolean): List<String> {
        val key = if (isPkb) KEY_PKB_LAYOUT else KEY_VKB_LAYOUT
        val stored = prefs.getString(key, null)
        
        val maxKeys = if (isPkb) 28 else 26
        
        if (stored != null) {
            val rawLayout = stored.split(DELIMITER)
            
            // For VKB, reverse the keyboard read order transformation
            val layout = if (!isPkb && rawLayout.size >= 26) {
                // KeyboardBuilder reads storage in this order:
                val keyboardReadOrder = intArrayOf(
                    6, 7, 8, 9, 10, 11, 12, 13, 14, 5,     // Row 1
                    25, 21, 20, 19, 22, 23, 24, 18, 17,    // Row 2
                    1, 3, 4, 15, 0, 16, 2                  // Row 3
                )
                
                // Reverse: for each editor position, read from storage[keyboardReadOrder[i]]
                MutableList(26) { editorPos ->
                    if (editorPos < keyboardReadOrder.size && keyboardReadOrder[editorPos] < rawLayout.size) {
                        rawLayout[keyboardReadOrder[editorPos]]
                    } else {
                        "\u0000"
                    }
                }
            } else {
                rawLayout
            }
            
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
            // KeyboardBuilder iterates keys in this non-sequential order due to internal key sorting:
            // [6,7,8,9,10,11,12,13,14,5, 25,21,20,19,22,23,24,18,17, 1,3,4,15,0,16,2]
            // To display editor[0,1,2,...] at keyboard positions, store editor[i] at readOrder[i]
            val keyboardReadOrder = intArrayOf(
                6, 7, 8, 9, 10, 11, 12, 13, 14, 5,     // Row 1: keyboard reads these storage indices
                25, 21, 20, 19, 22, 23, 24, 18, 17,    // Row 2
                1, 3, 4, 15, 0, 16, 2                  // Row 3
            )
            
            val reordered = MutableList(28) { "\u0000" }
            for (kbdPos in keyboardReadOrder.indices) {
                if (kbdPos < layout.size && keyboardReadOrder[kbdPos] < reordered.size) {
                    // Place editor[kbdPos] at storage[keyboardReadOrder[kbdPos]]
                    reordered[keyboardReadOrder[kbdPos]] = layout[kbdPos]
                }
            }
            reordered
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
        Timber.d("Saved layout to $key and cleared KeyboardBuilder cache")
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
        
        // VKB has only 26 customizable positions (10+9+7), but arrays have 28 items
        // PKB has 28 customizable positions (10+9+9)
        val maxKeys = if (isPkb) 28 else 26
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
        // Use modern Emojibase data provider instead of legacy emoji array
        val emojiProvider = EmojibaseDataProvider(context)
        emojiProvider.loadEmojiData()
        val allEmojis = emojiProvider.getAllEmojis()
        
        Timber.d("Loaded ${allEmojis.size} emojis from Emojibase for Compose custom symbol page")
        
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
