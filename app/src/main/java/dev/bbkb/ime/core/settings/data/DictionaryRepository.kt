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

/**
 * Repository for managing both personal dictionary and word substitutions
 * through the unified DictionaryEntry model.
 */
class DictionaryRepository(private val context: Context) : DictionaryEntryStore {

    private val personalDictManager = DictionaryManager.getInstance()

    /**
     * Initialize the DictionaryManager if not already initialized
     * Returns: Result with true if successful, false if failed, or error message on corruption
     */
    override suspend fun initialize(): InitResult = withContext(Dispatchers.IO) {
        try {
            val isLoaded =
                personalDictManager.getWordSubstitutions() != null || personalDictManager.getPersonalDictionary() != null
            if (!isLoaded) {
                if (BuildConfig.DEBUG) Log.d("DictionaryRepository", "Initializing DictionaryManager...")

                var dataWasCorrupted = false
                try {
                    personalDictManager.initialiseOrSwitchLanguages(context, LocaleUtil.getAvailableLocales(context))
                } catch (e: ClassCastException) {
                    // Data corruption detected - clear and reinitialize
                    if (BuildConfig.DEBUG) {
                    Log.e(
                        "DictionaryRepository",
                        "Data corruption detected (ClassCastException). Clearing corrupted data...",
                        e
                    )
                    }
                    dataWasCorrupted = true
                    clearCorruptedData()
                    // Try again with clean slate
                    personalDictManager.initialiseOrSwitchLanguages(context, LocaleUtil.getAvailableLocales(context))
                }

                // Wait for initialization
                // delay(), not Thread.sleep(): this runs on an IO-dispatcher thread and is
                // entered from every dictionary screen, so the poll must be suspending and must
                // stop when the caller backs out of the screen.
                var attempts = 0
                while (attempts < 30) {
                    delay(100)
                    if (personalDictManager.getWordSubstitutions() != null || personalDictManager.getPersonalDictionary() != null) {
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
                InitResult.Failure("Initialization timeout")
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
            InitResult.Failure("Data corruption detected. Dictionary has been reset.")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error initializing", e)
            InitResult.Failure("Failed to initialize dictionary: ${e.message}")
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
    /**
     * Clear corrupted dictionary data files
     */
    @androidx.annotation.VisibleForTesting
    internal fun clearCorruptedStore(context: Context) {
        try {
            if (BuildConfig.DEBUG) Log.w("DictionaryRepository", "Clearing corrupted dictionary data...")

            // The store is filesDir + PERSONAL_DICTIONARY_UTIL_DIR (DictionaryManager.doInitBasl
            // passes getFilesDir(); PersonalDictionaryUtil.setupPduDir appends the directory), and
            // its files are the extension-less LoadConfig names. This used to resolve
            // getDir(...) = app_<name> (creating it as a side effect) and look for *.json, so it
            // deleted nothing. It deletes user data: only those named store files, never the
            // directory or anything else in it.
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

    /**
     * Get all entries (both personal words and substitutions) for a locale
     */
    override suspend fun getAllEntries(locale: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val actualLocale = if (locale.isEmpty() || locale == "all") "" else locale

        val entries = mutableListOf<DictionaryEntry>()

        try {
            // Get word substitutions
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

            // Get personal words
            val personalWords = personalDictManager.getWordsForLocale(actualLocale, false) ?: emptyList()
            entries.addAll(personalWords.mapNotNull { item ->
                try {
                    val word = personalDictManager.getWord(item) ?: return@mapNotNull null
                    val baslWord = personalDictManager.asDictionaryWord(item)
                    val fixedCase = !baslWord.isAutoCapsEnabled

                    DictionaryEntry(
                        word = word,
                        shortcut = null, // Personal words have no shortcut
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

        // Sort by display key
        entries.sortedBy { it.displayKey.lowercase() }
    }

    /**
     * Get all available locales that have dictionary entries
     */
    override suspend fun getLocales(): List<String> = withContext(Dispatchers.IO) {
        try {
            val treeSet = LocaleUtil.getAvailableLocaleStrings(context)
            val list = mutableListOf<String>()

            // Add "All languages" if multiple locales exist
            if (treeSet.size > 1) {
                list.add("") // Empty string represents "All languages"
            }

            // Add all locales
            list.addAll(treeSet)

            // If empty, add default locale
            if (list.isEmpty()) {
                list.add(Locale.getDefault().toString())
            }

            list
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error loading locales", e)
            listOf(Locale.getDefault().toString())
        }
    }

    /**
     * Add a new entry (personal word or substitution)
     */
    override suspend fun addEntry(entry: DictionaryEntry): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val locale = if (entry.locale.isEmpty()) PersonalDictionaryConstants.LOCALE_ALL else entry.locale

            val success = if (entry.isSubstitution) {
                // Add word substitution
                personalDictManager.addWordSubstitution(
                    entry.word,
                    entry.shortcut!!,
                    locale,
                    entry.fixedCase
                )
            } else {
                // Add personal word (shortcut = word)
                personalDictManager.addWordSubstitution(
                    entry.word,
                    entry.word,
                    locale,
                    entry.fixedCase
                )
            }

            if (success) {
                personalDictManager.save() // Save
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to add entry"))
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error adding entry", e)
            Result.failure(e)
        }
    }

    /**
     * Update an existing entry
     */
    override suspend fun updateEntry(
        oldEntry: DictionaryEntry,
        newEntry: DictionaryEntry
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val locale =
                if (newEntry.locale.isEmpty()) PersonalDictionaryConstants.LOCALE_ALL else newEntry.locale

            val success = if (newEntry.isSubstitution) {
                // Update word substitution
                personalDictManager.updateOrAddWordSubstitution(
                    oldEntry.word,
                    oldEntry.shortcut ?: oldEntry.word,
                    newEntry.word,
                    newEntry.shortcut!!,
                    locale,
                    newEntry.fixedCase
                )
            } else {
                // Update personal word
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
                personalDictManager.save() // Save
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update entry"))
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error updating entry", e)
            Result.failure(e)
        }
    }

    /**
     * Delete an entry
     */
    override suspend fun deleteEntry(entry: DictionaryEntry): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val shortcut = if (entry.isSubstitution) entry.shortcut!! else entry.word
            personalDictManager.removeWordSubstitution(shortcut, entry.word)
            personalDictManager.save() // Save
            Result.success(Unit)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("DictionaryRepository", "Error deleting entry", e)
            Result.failure(e)
        }
    }

    /**
     * Check if DictionaryManager is loaded
     */
    fun isLoaded(): Boolean {
        return personalDictManager.getWordSubstitutions() != null || personalDictManager.getPersonalDictionary() != null
    }
}