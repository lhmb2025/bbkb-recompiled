package dev.bbkb.ime.personaldictionary.macro

/**
 * A user-defined macro for text shortcuts.
 *
 * @property tag the single character after % (e.g. "p" for %p)
 */
data class CustomMacro(
    val tag: String,
    val name: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** The tag with its % prefix (e.g. "%p"). */
    val fullTag: String get() = "%$tag"
    
    companion object {
        /** Tags of the built-in macros. */
        val RESERVED_TAGS = setOf("D", "T", "d", "t", "n", "w", "y", "b")

        fun isTagValid(tag: String): Boolean {
            return tag.length == 1 && tag[0].isLetterOrDigit() && tag !in RESERVED_TAGS
        }
    }
}
