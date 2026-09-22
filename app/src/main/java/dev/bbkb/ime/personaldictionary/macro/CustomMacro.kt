package dev.bbkb.ime.personaldictionary.macro

/**
 * Represents a user-defined custom macro for use in text shortcuts.
 *
 * @property tag Single character after % (e.g., "p" for %p)
 * @property name Display name (e.g., "Phone Number")
 * @property value Expansion value (e.g., "+1-555-123-4567")
 * @property createdAt Timestamp when the macro was created
 */
data class CustomMacro(
    val tag: String,
    val name: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns the full macro tag with % prefix (e.g., "%p")
     */
    val fullTag: String get() = "%$tag"
    
    companion object {
        /**
         * Reserved tags that cannot be used for custom macros (built-in macros)
         */
        val RESERVED_TAGS = setOf("D", "T", "d", "t", "n", "w", "y", "b")
        
        /**
         * Validates if a tag is available for use as a custom macro
         */
        fun isTagValid(tag: String): Boolean {
            return tag.length == 1 && tag[0].isLetterOrDigit() && tag !in RESERVED_TAGS
        }
    }
}
