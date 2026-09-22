package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import android.graphics.Paint
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/**
 * Emoji data provider using Emojibase JSON datasets.
 * Provides comprehensive emoji data with proper Unicode 16.0 category support.
 *
 * Key features:
 * - Uses Emojibase datasets for emoji metadata with Unicode 16.0
 * - Built-in skin tone variants via `skins` array
 * - Clean category grouping with numeric IDs (0-8)
 * - Simplified filtering - just check for `tone` field
 */
class EmojibaseDataProvider(private val context: Context) {

    companion object {
        private const val EMOJI_DATA_FILE = "emojibase/emojibase-en-full.json"
        private const val MAX_SEARCH_RESULTS = 80  // ~2 pages worth

        // Shared, already-loaded instance for callers outside the emoji board (e.g. the
        // custom symbol page in KeyboardBuilder) so repeated keyboard rebuilds don't
        // re-parse the emojibase JSON. Loaded once on first use.
        @Volatile
        private var sharedLoadedInstance: EmojibaseDataProvider? = null

        @JvmStatic
        fun getSharedLoaded(context: Context): EmojibaseDataProvider {
            return sharedLoadedInstance ?: synchronized(this) {
                sharedLoadedInstance ?: EmojibaseDataProvider(context.applicationContext).also {
                    it.loadEmojiData()
                    sharedLoadedInstance = it
                }
            }
        }

        /**
         * IB-26: drop the shared parsed dataset (735 KB of JSON inflated into ~3700 EmojiData
         * objects plus the per-category and skin-tone indexes). Nothing else evicts it, and an
         * IME process is deliberately long-lived, so it otherwise stays resident whether or not
         * the emoji board is ever opened again. [getSharedLoaded] re-parses on the next use.
         *
         * Callers already holding a provider keep their own copy; only the static goes.
         */
        @JvmStatic
        fun releaseShared() {
            synchronized(this) {
                sharedLoadedInstance = null
            }
        }
    }

    private var emojiList: List<EmojiData>? = null
    private var emojiByCategory: MutableMap<EmojiCategory, MutableList<EmojiData>>? = null

    /**
     * Skin-tone variants indexed by base emoji, under both the fully-qualified form
     * and the VS16-stripped form. Built once in [loadEmojiData] so
     * [getSkinToneVariants] is O(1): it used to linear-scan the whole ~3700-entry
     * dataset (twice, on a miss) once per emoji key created, i.e. millions of string
     * comparisons per emoji-board build.
     */
    private var skinsByEmoji: Map<String, List<String>> = emptyMap()
    private val paint = Paint()
    private val gson = Gson()

    /**
     * Load and categorize emoji data from Emojibase JSON.
     */
    fun loadEmojiData() {

        try {
            // Load JSON from assets
            val inputStream = context.assets.open(EMOJI_DATA_FILE)
            val reader = InputStreamReader(inputStream)

            // Parse JSON array directly into EmojiData (now compatible!)
            val type = object : TypeToken<List<EmojiData>>() {}.type
            val allEmojis: List<EmojiData> = gson.fromJson(reader, type)
            reader.close()

            // Filter to base emojis only (no variants)
            val baseEmojis = allEmojis.filter { isBaseEmoji(it) }

            // Filter device-supported emojis
            val supportedEmojis = baseEmojis.filter { isEmojiSupported(it.emoji) }

            // Group by category
            val categoryMap = supportedEmojis.groupBy { it.category }
                .mapValues { it.value.toMutableList() }
                .toMutableMap()

            emojiByCategory = categoryMap

            // Index skin-tone variants for O(1) lookup (see skinsByEmoji).
            val skins = HashMap<String, List<String>>(allEmojis.size * 2)
            for (e in allEmojis) {
                val variants = e.skins?.map { it.emoji } ?: continue
                if (variants.isEmpty()) continue
                // Index under the stripped form too, but never let it displace an
                // entry whose own (fully-qualified) form is that exact string.
                val stripped = stripVariationSelectors(e.emoji)
                if (stripped != null && !skins.containsKey(stripped)) {
                    skins[stripped] = variants
                }
                skins[e.emoji] = variants
            }
            skinsByEmoji = skins

        } catch (e: Exception) {
            // Failed to load Emojibase data
        }
    }

    /**
     * Check if emoji is a base emoji (not a variant).
     *
     * Filtering logic:
     * - tone != null: Has a skin tone applied, it's a variant
     * - group < 0: Component emoji (e.g., regional indicators)
     * - gender != null: Gender variant (man/woman specific versions)
     * - Contains ZWJ with directional arrows
     * - Regional indicator symbols (alphabet letters 🇦-🇿)
     * - Skin tone modifiers (🏻-🏿)
     * - Hair component emojis (🦰🦱🦲🦳)
     */
    private fun isBaseEmoji(emoji: EmojiData): Boolean {
        // Has a skin tone applied - it's a variant
        if (emoji.tone != null) {
            return false
        }

        // Filter out component emojis (regional indicators, etc.)
        // These have group < 0 or are otherwise not categorized
        if (emoji.group < 0) {
            return false
        }

        // Filter out gender-specific variants (keep only gender-neutral versions)
        if (emoji.gender != null) {
            return false
        }

        val hexcode = emoji.codepoints
        val desc = emoji.description.lowercase()

        // Filter out skin tone modifier components (🏻-🏿)
        // Hexcodes: 1F3FB, 1F3FC, 1F3FD, 1F3FE, 1F3FF
        if (hexcode.startsWith("1F3F") && hexcode.length == 5) {
            return false
        }

        // Filter out hair component emojis (🦰🦱🦲🦳)
        // Hexcodes: 1F9B0 (red hair), 1F9B1 (curly), 1F9B2 (bald), 1F9B3 (white)
        if (hexcode.startsWith("1F9B") && hexcode.length == 5) {
            val lastChar = hexcode.last()
            if (lastChar in '0'..'3') {
                return false
            }
        }

        // Filter out emojis with ZWJ + arrows (head shaking/nodding)
        // Contains: 2194 (left-right arrow) or 2195 (up-down arrow)
        if (hexcode.contains("2194") || hexcode.contains("2195")) {
            return false
        }

        // Filter out directional variants (facing right/left)
        if (desc.contains("facing right") ||
            desc.contains("facing left") ||
            desc.contains("rightward") ||
            desc.contains("leftward")) {
            return false
        }

        // Filter out regional indicators (🇦-🇿)
        if (desc.startsWith("regional indicator")) {
            return false
        }

        // Filter out explicit skin tone/hair descriptions
        if (desc.endsWith("skin tone") ||
            (desc.length < 15 && (desc.contains("hair") || desc == "bald"))) {
            return false
        }

        return true
    }

    /**
     * Check if device can render this emoji.
     * Uses Paint.hasGlyph() on API 23+ to verify glyph support.
     */
    private fun isEmojiSupported(emoji: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // Skip check on older devices
        }

        try {
            // Check if paint can render the first codepoint
            val codePoint = emoji.codePointAt(0)
            return paint.hasGlyph(emoji) || paint.hasGlyph(String(intArrayOf(codePoint), 0, 1))
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get skin tone variants for a base emoji.
     *
     * Returns the variants from the built-in `skins` array.
     * This is much simpler than JEmoji's approach!
     */
    fun getSkinToneVariants(baseEmoji: String): List<String> {
        // The dataset stores fully-qualified forms (with U+FE0F variation
        // selectors), but callers may hold an unqualified form (e.g. a bare
        // U+1F44D stored in the custom symbol page prefs), so the index carries
        // both keys and we fall back to the VS16-insensitive one.
        skinsByEmoji[baseEmoji]?.let { return it }
        val stripped = stripVariationSelectors(baseEmoji) ?: return emptyList()
        return skinsByEmoji[stripped] ?: emptyList()
    }

    private fun stripVariationSelectors(s: String?): String? = s?.replace("\uFE0F", "")

    /**
     * Get emojis for a specific category.
     */
    fun getEmojisByCategory(category: EmojiCategory): List<EmojiData> {
        return emojiByCategory?.get(category) ?: emptyList()
    }

    /**
     * Get all emoji data.
     */
    fun getAllEmojis(): List<EmojiData> {
        if (emojiList == null) {
            emojiList = emojiByCategory?.values?.flatten() ?: emptyList()
        }
        return emojiList ?: emptyList()
    }

    /**
     * Search emojis by query string.
     * Searches in description, tags, and aliases.
     * Returns results sorted by relevance, limited to MAX_SEARCH_RESULTS.
     *
     * A relevance of 0 is exactly "no match" (see [EmojiData.searchRelevance]), so one score per
     * emoji both filters and ranks. The sort is stable: equal scores keep dataset order.
     *
     * Thread-safe: can be called from background thread.
     */
    fun searchEmojis(query: String): List<EmojiData> {
        if (query.isBlank()) return emptyList()

        val trimmedQuery = query.trim()

        return getAllEmojis()
            .map { it to it.searchRelevance(trimmedQuery) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_SEARCH_RESULTS)
            .map { it.first }
    }
}
