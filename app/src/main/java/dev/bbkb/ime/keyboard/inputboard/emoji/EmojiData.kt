package dev.bbkb.ime.keyboard.inputboard.emoji

/**
 * One emojibase entry, built from the compact asset ([EmojibaseCompactFormat]). Emojibase JSON
 * names: hexcode is [codepoints], label [description], shortcodes [aliases], version [emojiVersion].
 */
data class EmojiData(
    val emoji: String,
    val codepoints: String,              // e.g. "1F600"
    val description: String,
    val tags: List<String>? = null,
    val aliases: List<String>? = null,
    val group: Int,                      // Emojibase group (0-9)
    val subgroup: Int,
    val emojiVersion: Double,
    val order: Int,
    val type: Int? = null,
    val gender: Int? = null,
    val tone: Int? = null,               // 1-5 on a toned variant
    val skins: List<EmojiSkin>? = null
) {
    val category: EmojiCategory
        get() = mapGroupToCategory(group)

    companion object {
        private fun mapGroupToCategory(group: Int): EmojiCategory {
            return when (group) {
                0 -> EmojiCategory.SMILEYS_EMOTION
                1 -> EmojiCategory.PEOPLE_BODY
                2 -> EmojiCategory.SYMBOLS // Component (skin/hair), not expected
                3 -> EmojiCategory.ANIMALS_NATURE
                4 -> EmojiCategory.FOOD_DRINK
                5 -> EmojiCategory.TRAVEL_PLACES
                6 -> EmojiCategory.ACTIVITIES
                7 -> EmojiCategory.OBJECTS
                8 -> EmojiCategory.SYMBOLS
                9 -> EmojiCategory.FLAGS
                else -> EmojiCategory.SYMBOLS
            }
        }
    }

    /** A skin-tone variant. [toneRaw] is an Int, or a list for multi-person emoji; unused. */
    data class EmojiSkin(
        val emoji: String,
        val hexcode: String,
        val label: String? = null,
        val toneRaw: Any? = null
    )

    /**
     * Search relevance, higher is better. `> 0` exactly when the description, a tag or an alias
     * contains [query] (each containment scores at least 5).
     */

    fun searchRelevance(query: String): Int = EmojiSearchScoring.score(
        description.lowercase(),
        tags?.map { it.lowercase() } ?: emptyList(),
        aliases?.map { it.lowercase() } ?: emptyList(),
        query.lowercase()
    )
}
