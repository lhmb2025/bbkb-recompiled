package dev.bbkb.ime.keyboard.inputboard.emoji

/**
 * Emoji categories after the Unicode EmojiGroup list. [id] is the tab id `EmojiCategoryManager`
 * uses (0 is Recents, not listed here), persisted as `last_shown_emoji_category_id`. Tab icons come
 * from the `EmojiPalettesView` styleable.
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
        @JvmStatic

        fun fromId(id: Int): EmojiCategory? {
            return values().firstOrNull { it.id == id }
        }

    }
}
