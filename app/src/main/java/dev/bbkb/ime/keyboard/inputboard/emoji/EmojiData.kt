package dev.bbkb.ime.keyboard.inputboard.emoji

import com.google.gson.annotations.SerializedName

/**
 * Data class representing an emoji with all its metadata.
 * Directly compatible with Emojibase JSON format.
 *
 * The app builds it from the compact asset ([EmojibaseCompactFormat]), never by reflection. The
 * @SerializedName names stay only for EmojibaseCompactAssetTest, whose reference Gson parse of the
 * emojibase JSON must keep producing exactly what the app used to load.
 */
data class EmojiData(
    @SerializedName("emoji")
    val emoji: String,                   // The actual emoji character(s) e.g., "😀"

    @SerializedName("hexcode")
    val codepoints: String,              // Unicode codepoints e.g., "1F600"

    @SerializedName("label")
    val description: String,             // Human-readable description e.g., "grinning face"

    @SerializedName("tags")
    val tags: List<String>? = null,      // Search keywords e.g., ["happy", "smile", "grin"]

    @SerializedName("shortcodes")
    val aliases: List<String>? = null,   // Short codes e.g., ["grinning"]

    @SerializedName("group")
    val group: Int,                      // Emojibase group (0-8)

    @SerializedName("subgroup")
    val subgroup: Int,                   // Emojibase subgroup

    @SerializedName("version")
    val emojiVersion: Double,            // Emoji version when added

    @SerializedName("order")
    val order: Int,                      // Display order

    @SerializedName("type")
    val type: Int? = null,               // Emoji type

    @SerializedName("gender")
    val gender: Int? = null,             // Gender (if applicable)

    @SerializedName("tone")
    val tone: Int? = null,               // Skin tone if variant (1-5)

    @SerializedName("skins")
    val skins: List<EmojiSkin>? = null   // Skin tone variants
) {
    // Computed property (not serialized)
    val category: EmojiCategory
        get() = mapGroupToCategory(group)

    companion object {
        private fun mapGroupToCategory(group: Int): EmojiCategory {
            return when (group) {
                0 -> EmojiCategory.SMILEYS_EMOTION
                1 -> EmojiCategory.PEOPLE_BODY
                2 -> EmojiCategory.SYMBOLS // Component (skin/hair) - shouldn't appear
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

    /**
     * Skin tone variant data.
     * Note: tone can be Int (single person) or List<Int> (multi-person like handshakes)
     */
    data class EmojiSkin(
        @SerializedName("emoji")
        val emoji: String,

        @SerializedName("hexcode")
        val hexcode: String,

        @SerializedName("label")
        val label: String? = null,

        // Tone can be single Int or array of Ints for multi-person emojis
        // We'll ignore it for now as we just need the emoji string
        @SerializedName("tone")
        val toneRaw: Any? = null
    )

    /**
     * Returns a search relevance score for ranking results.
     * Higher score = more relevant match.
     *
     * Zero means no match: every branch that scores requires the query to be contained in the
     * description, a tag or an alias, and each of those containments scores at least 5. So
     * `searchRelevance(q) > 0` is exactly "the description, a tag or an alias contains q".
     */
    fun searchRelevance(query: String): Int = EmojiSearchScoring.score(
        description.lowercase(),
        tags?.map { it.lowercase() } ?: emptyList(),
        aliases?.map { it.lowercase() } ?: emptyList(),
        query.lowercase()
    )
}
