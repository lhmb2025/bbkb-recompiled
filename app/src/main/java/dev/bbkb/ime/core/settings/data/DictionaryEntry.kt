package dev.bbkb.ime.core.settings.data

import androidx.compose.runtime.Immutable

/**
 * Unified data model for both personal dictionary words and word substitutions.
 *
 * Personal words have shortcut == null or shortcut == word
 * Word substitutions have shortcut != word
 * 
 * @Immutable annotation tells Compose this class is deeply immutable,
 * reducing unnecessary recompositions when used in lists.
 */
@Immutable
data class DictionaryEntry(
    val id: Long? = null,
    val word: String,
    val shortcut: String? = null,
    val locale: String,
    val fixedCase: Boolean = false
) {
    /**
     * Entry type based on shortcut presence
     */
    val type: EntryType
        get() = when {
            shortcut == null || shortcut == word -> EntryType.WORD
            else -> EntryType.SUBSTITUTION
        }

    /**
     * True if this is a personal dictionary word (no substitution)
     */
    val isPersonalWord: Boolean
        get() = type == EntryType.WORD

    /**
     * True if this is a word substitution (has a different shortcut)
     */
    val isSubstitution: Boolean
        get() = type == EntryType.SUBSTITUTION

    /**
     * Display key for sorting and grouping
     */
    val displayKey: String
        get() = shortcut?.takeIf { it != word } ?: word

    enum class EntryType {
        WORD,           // Personal dictionary word
        SUBSTITUTION    // Word substitution
    }
}