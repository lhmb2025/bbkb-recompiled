package dev.bbkb.ime.core.settings.data

import androidx.compose.runtime.Immutable

/** A personal word (shortcut null or equal to word) or a text shortcut (shortcut != word). */
@Immutable
data class DictionaryEntry(
    val id: Long? = null,
    val word: String,
    val shortcut: String? = null,
    val locale: String,
    val fixedCase: Boolean = false
) {
    val type: EntryType
        get() = when {
            shortcut == null || shortcut == word -> EntryType.WORD
            else -> EntryType.SUBSTITUTION
        }

    val isPersonalWord: Boolean
        get() = type == EntryType.WORD

    val isSubstitution: Boolean
        get() = type == EntryType.SUBSTITUTION

    /** Sort and grouping key: the shortcut when it differs from the word. */
    val displayKey: String
        get() = shortcut?.takeIf { it != word } ?: word

    enum class EntryType {
        WORD,
        SUBSTITUTION
    }
}