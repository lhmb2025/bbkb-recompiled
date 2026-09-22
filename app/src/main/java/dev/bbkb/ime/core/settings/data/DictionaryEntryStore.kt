package dev.bbkb.ime.core.settings.data

/**
 * What a dictionary-entry editor needs from its store.
 *
 * This is the surface `TextShortcutsScreen` and `UserDictionaryScreen` actually use, lifted off
 * [DictionaryRepository] so the screens can be composed against something other than the live BASL
 * chain. The production implementation is [DictionaryRepository] and nothing else implements it in
 * `main`; the point of the interface is that `DictionaryRepository` reaches
 * `DictionaryManager.getInstance()`, which reaches the Nuance SDK, which does not exist in a unit
 * test — so without a seam here the two editors can only ever be observed in their loading state.
 *
 * Members are declared exactly as [DictionaryRepository] already declared them. Nothing about the
 * stored form of an entry lives here: that stays entirely inside [DictionaryRepository].
 */
interface DictionaryEntryStore {

    /** Brings the underlying dictionary up, recovering from corrupted data if it has to. */
    suspend fun initialize(): DictionaryRepository.InitResult

    /** Every locale that can hold entries, with `""` meaning "all languages" when there are several. */
    suspend fun getLocales(): List<String>

    /** Every entry for a locale — personal words and substitutions together. */
    suspend fun getAllEntries(locale: String): List<DictionaryEntry>

    suspend fun addEntry(entry: DictionaryEntry): Result<Unit>

    suspend fun updateEntry(oldEntry: DictionaryEntry, newEntry: DictionaryEntry): Result<Unit>

    suspend fun deleteEntry(entry: DictionaryEntry): Result<Unit>
}
