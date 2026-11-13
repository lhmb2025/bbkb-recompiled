package dev.bbkb.ime.core.settings.data

/**
 * The store the dictionary editor screens use; [DictionaryRepository] is the only production
 * implementation. A seam so tests can run the screens without the Nuance SDK.
 */
interface DictionaryEntryStore {

    /** Brings the underlying dictionary up, recovering from corrupted data if it has to. */
    suspend fun initialize(): DictionaryRepository.InitResult

    /** Every locale that can hold entries, with `""` meaning "all languages" when there are several. */
    suspend fun getLocales(): List<String>

    /** Every entry for a locale, personal words and shortcuts together. */
    suspend fun getAllEntries(locale: String): List<DictionaryEntry>

    suspend fun addEntry(entry: DictionaryEntry): Result<Unit>

    suspend fun updateEntry(oldEntry: DictionaryEntry, newEntry: DictionaryEntry): Result<Unit>

    suspend fun deleteEntry(entry: DictionaryEntry): Result<Unit>
}

/**
 * An add or an edit refused because another entry already has that name: [name] is the entry in
 * the way, a user dictionary word when [isWord], else a shortcut (built-in ones included).
 */
class EntryExistsException(val name: String, val isWord: Boolean) :
    Exception("${if (isWord) "Word" else "Shortcut"} already exists: $name")
