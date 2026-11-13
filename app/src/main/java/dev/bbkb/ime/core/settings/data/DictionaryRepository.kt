package dev.bbkb.ime.core.settings.data

import android.content.Context
import android.util.Log
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil
import dev.bbkb.ime.personaldictionary.model.LoadConfig
import dev.bbkb.ime.personaldictionary.util.LocaleUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R

/** Personal dictionary words and text shortcuts, both as [DictionaryEntry]. */
class DictionaryRepository @androidx.annotation.VisibleForTesting internal constructor(
    private val context: Context,
    private val load: (Context, DictionaryManager) -> Unit,
) : DictionaryEntryStore {

    constructor(context: Context) : this(context, { ctx, manager ->
        manager.initialiseOrSwitchLanguages(ctx, LocaleUtil.getAvailableLocales(ctx))
    })

    /**
     * Looked up on every use, never cached: the keyboard service in this process replaces the
     * manager when it starts and shuts it down when it stops, often while a dictionary screen is open.
     */
    private val personalDictManager: DictionaryManager
        get() = DictionaryManager.getInstance()

    /** Loads the manager if needed: after the service stops, nobody else loads its replacement. */
    private suspend fun ensureLoaded(): Boolean = personalDictManager.isLoaded() || initialize().isSuccess

    /**
     * The entry already holding [key], as the failure to report; null when free or [ownKey] (an
     * edit keeping its name). Words and shortcuts share one case-insensitive key space.
     */
    private fun entryInTheWay(key: String, ownKey: String? = null): EntryExistsException? {
        if (ownKey != null && key.equals(ownKey, ignoreCase = true)) return null
        val existing = personalDictManager.getWordSubstitutions()?.get(key) ?: return null
        val isWord = existing.key == existing.word
        return EntryExistsException(if (isWord) existing.word else existing.key, isWord)
    }

    /** Loads the manager if needed, recovering once from corrupted data. */
    override suspend fun initialize(): InitResult = withContext(Dispatchers.IO) {
        try {
            // isLoaded() flips only after the BASL load completes; a non-null substitutions map
            // appears earlier and is not readiness.
            val isLoaded = personalDictManager.isLoaded()
            if (!isLoaded) {
                if (BuildConfig.DEBUG) Log.d("DictionaryRepository", "Initializing DictionaryManager...")

                var dataWasCorrupted = false
                try {
                    load(context, personalDictManager)
                } catch (e: ClassCastException) {
                    if (BuildConfig.DEBUG) {
                    Log.e(
                        "DictionaryRepository",
                        "Data corruption detected (ClassCastException). Clearing corrupted data...",
                        e
                    )
                    }
                    dataWasCorrupted = true
                    clearCorruptedData()
                    load(context, personalDictManager)
                }

                // delay(), not Thread.sleep(): the poll must stop when the caller leaves the screen.
                var attempts = 0
                while (attempts < 50) {
                    delay(100)
                    if (personalDictManager.isLoaded()) {
                        if (BuildConfig.DEBUG) Log.d("DictionaryRepository", "Initialized successfully")
                        return@withContext if (dataWasCorrupted) {
                            InitResult.SuccessAfterRecovery
                        } else {
                            InitResult.Success
                        }
                    }
                    attempts++
                }
                if (BuildConfig.DEBUG) Log.w("DictionaryRepository", "Initialization timeout")
                InitResult.Failure(context.getString(R.string.settings_dict_init_timeout))
            } else {
                InitResult.Success
            }
        } catch (e: ClassCastException) {
            if (BuildConfig.DEBUG) {
            Log.e(
                "DictionaryRepository",
                "ClassCastException during initialization - attempting recovery",
                e
            )
            }
            clearCorruptedData()
            InitResult.Failure(context.getString(R.string.settings_dict_reset_after_corruption))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error initializing", e)
            InitResult.Failure(context.getString(R.string.settings_dict_init_failed, e.message))
        }
    }

    sealed class InitResult {
        object Success : InitResult()
        object SuccessAfterRecovery : InitResult()
        data class Failure(val message: String) : InitResult()

        val isSuccess: Boolean
            get() = this is Success || this is SuccessAfterRecovery
    }

    private fun clearCorruptedData() = clearCorruptedStore(context)

    companion object {
    @androidx.annotation.VisibleForTesting
    internal fun clearCorruptedStore(context: Context) {
        try {
            if (BuildConfig.DEBUG) Log.w("DictionaryRepository", "Clearing corrupted dictionary data...")

            // The store is filesDir + PERSONAL_DICTIONARY_UTIL_DIR holding the extension-less
            // LoadConfig names. This deletes user data: only those files, never the directory.
            val dataDir = File(
                context.filesDir,
                PersonalDictionaryUtil.PERSONAL_DICTIONARY_UTIL_DIR.trimStart('/')
            )

            LoadConfig.entries.map { it.filename }.forEach { filename ->
                val file = File(dataDir, filename)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (BuildConfig.DEBUG) Log.w("DictionaryRepository", "Deleted $filename: $deleted")
                }
            }

            if (BuildConfig.DEBUG) Log.w("DictionaryRepository", "Corrupted data cleared. Dictionary will start fresh.")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error clearing corrupted data", e)
        }
    }
    }

    /** Words and shortcuts for [locale] ("" or "all" for every locale), sorted by display key. */
    override suspend fun getAllEntries(locale: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val actualLocale = if (locale.isEmpty() || locale == "all") "" else locale

        val entries = mutableListOf<DictionaryEntry>()

        try {
            // An unloaded manager lists nothing, so the screen would read as empty.
            ensureLoaded()
            val substitutions = personalDictManager.getWordsForLocale(actualLocale, true) ?: emptyList()
            entries.addAll(substitutions.mapNotNull { item ->
                try {
                    val word = personalDictManager.getWord(item) ?: return@mapNotNull null
                    val shortcut = personalDictManager.getSubstitutionKey(item)
                    val baslWord = personalDictManager.asDictionaryWord(item)
                    val fixedCase = !baslWord.isAutoCapsEnabled

                    DictionaryEntry(
                        word = word,
                        shortcut = shortcut,
                        locale = actualLocale,
                        fixedCase = fixedCase
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error converting substitution", e)
                    null
                }
            })

            val personalWords = personalDictManager.getWordsForLocale(actualLocale, false) ?: emptyList()
            entries.addAll(personalWords.mapNotNull { item ->
                try {
                    val word = personalDictManager.getWord(item) ?: return@mapNotNull null
                    val baslWord = personalDictManager.asDictionaryWord(item)
                    val fixedCase = !baslWord.isAutoCapsEnabled

                    DictionaryEntry(
                        word = word,
                        shortcut = null,
                        locale = actualLocale,
                        fixedCase = fixedCase
                    )
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error converting personal word", e)
                    null
                }
            })

        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error loading entries", e)
        }

        entries.sortedBy { it.displayKey.lowercase() }
    }

    /** Locales with entries, led by "" (all languages) when there are several. */
    override suspend fun getLocales(): List<String> = withContext(Dispatchers.IO) {
        try {
            val treeSet = LocaleUtil.getAvailableLocaleStrings(context)
            val list = mutableListOf<String>()

            if (treeSet.size > 1) {
                list.add("")
            }

            list.addAll(treeSet)

            if (list.isEmpty()) {
                list.add(Locale.getDefault().toString())
            }

            list
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error loading locales", e)
            listOf(Locale.getDefault().toString())
        }
    }

    override suspend fun addEntry(entry: DictionaryEntry): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ensureLoaded()) return@withContext Result.failure(Exception("Dictionary not loaded"))
            entryInTheWay(entry.shortcut ?: entry.word)?.let { return@withContext Result.failure(it) }
            val locale = if (entry.locale.isEmpty()) PersonalDictionaryConstants.LOCALE_ALL else entry.locale

            val success = if (entry.isSubstitution) {
                personalDictManager.addWordSubstitution(
                    entry.word,
                    entry.shortcut!!,
                    locale,
                    entry.fixedCase
                )
            } else {
                // A personal word is stored as a shortcut for itself.
                personalDictManager.addWordSubstitution(
                    entry.word,
                    entry.word,
                    locale,
                    entry.fixedCase
                )
            }

            if (success) {
                personalDictManager.save()
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to add entry"))
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error adding entry", e)
            Result.failure(e)
        }
    }

    override suspend fun updateEntry(
        oldEntry: DictionaryEntry,
        newEntry: DictionaryEntry
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ensureLoaded()) return@withContext Result.failure(Exception("Dictionary not loaded"))
            entryInTheWay(newEntry.shortcut ?: newEntry.word, oldEntry.shortcut ?: oldEntry.word)
                ?.let { return@withContext Result.failure(it) }
            val locale =
                if (newEntry.locale.isEmpty()) PersonalDictionaryConstants.LOCALE_ALL else newEntry.locale

            val success = if (newEntry.isSubstitution) {
                personalDictManager.updateOrAddWordSubstitution(
                    oldEntry.word,
                    oldEntry.shortcut ?: oldEntry.word,
                    newEntry.word,
                    newEntry.shortcut!!,
                    locale,
                    newEntry.fixedCase
                )
            } else {
                personalDictManager.updateOrAddWordSubstitution(
                    oldEntry.word,
                    oldEntry.word,
                    newEntry.word,
                    newEntry.word,
                    locale,
                    newEntry.fixedCase
                )
            }

            if (success) {
                personalDictManager.save()
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update entry"))
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error updating entry", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteEntry(entry: DictionaryEntry): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // removeWordSubstitution silently does nothing on an unloaded manager.
            if (!ensureLoaded()) return@withContext Result.failure(Exception("Dictionary not loaded"))
            val shortcut = if (entry.isSubstitution) entry.shortcut!! else entry.word
            personalDictManager.removeWordSubstitution(shortcut, entry.word)
            personalDictManager.save()
            Result.success(Unit)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error deleting entry", e)
            Result.failure(e)
        }
    }

    fun isLoaded(): Boolean {
        return personalDictManager.getWordSubstitutions() != null || personalDictManager.getPersonalDictionary() != null
    }
}