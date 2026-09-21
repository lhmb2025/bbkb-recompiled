package dev.bbkb.ime.keyboard.inputboard.emoji

/**
 * Modern emoji categories aligned with Unicode 15.1 EmojiGroup.
 * Now properly split Smileys & Emotion from People & Body!
 *
 * [id] is the tab/category id `EmojiCategoryManager` uses (0 is Recents, which is not a category
 * here) and is persisted as `last_shown_emoji_category_id`. Tab icons come from the
 * `EmojiPalettesView` styleable, not from this enum.
 */
enum class EmojiCategory(val id: Int) {
    SMILEYS_EMOTION(1),
    PEOPLE_BODY(2),
    ANIMALS_NATURE(3),
    FOOD_DRINK(4),
    TRAVEL_PLACES(5),
    ACTIVITIES(6),
    OBJECTS(7),
    SYMBOLS(8),
    FLAGS(9);

    companion object {
        /**
         * Get category by ID (1-based index).
         */
        @JvmStatic
        fun fromId(id: Int): EmojiCategory? {
            return values().firstOrNull { it.id == id }
        }

    }
}
