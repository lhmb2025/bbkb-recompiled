package dev.bbkb.ime.personaldictionary.sync

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.net.Uri
import android.os.RemoteException
import android.provider.UserDictionary
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.personaldictionary.util.LocaleUtil
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudDeleteException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudLocaleException
import dev.bbkb.ime.personaldictionary.model.DictionaryWord
import dev.bbkb.ime.personaldictionary.model.PersonalWord
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import java.util.*

/** Low-level add, delete, query and batch access to Android's UserDictionary provider. */
object AudWrapper {
    const val SELECTION_ALL_LOCALE_NO_SHORTCUT = "word=? AND locale is null AND shortcut is null"
    const val SELECTION_ALL_LOCALE_WITH_SHORTCUT = "word=? AND locale is null AND shortcut=?"
    const val SELECTION_ONE_LOCALE_NO_SHORTCUT = "word=? AND locale=? AND shortcut is null"
    const val SELECTION_ONE_LOCALE_WITH_SHORTCUT = "word=? AND locale=? AND shortcut=?"
    private const val TAG = "AudWrapper"

    @VisibleForTesting
    var sAuthority: String? = null

    val PROJECTION_WORD = arrayOf("word")
    val PROJECTION_BASL_WORD = arrayOf("locale", "shortcut", "word")

    private enum class BatchOperation {
        INSERT,
        DELETE
    }

    @Throws(InterruptedException::class)
    fun add(context: Context, dictionaryWord: DictionaryWord): Boolean {
        if (!dictionaryWord.canBeSynchronised()) {
            return false
        }
        val key = if (dictionaryWord is WordSubstitution) dictionaryWord.key else null
        try {
            val locale = LocaleUtil.parse(LocaleUtil.convertBaslLocaleToAudLocale(dictionaryWord.locale))
            if (Thread.interrupted()) throw InterruptedException()
            LogUtil.d(TAG, "Adding $dictionaryWord to AUD")
            UserDictionary.Words.addWord(
                context, dictionaryWord.word,
                PersonalDictionaryConstants.DEFAULT_AUD_FREQUENCY, key, locale
            )
            return true
        } catch (unused: AudLocaleException) {
            dictionaryWord.setCanBeSynchronised(false)
            LogUtil.w(TAG, "Invalid locale while adding DW to AUD: $dictionaryWord")
            return false
        }
    }

    fun addBatch(context: Context, collection: Collection<DictionaryWord>): Boolean {
        return addBatchWithMaxBatchSize(
            context, collection,
            PersonalDictionaryConstants.MAX_AUD_CONTENT_PROVIDER_OPERATIONS
        )
    }

    /** @param maxBatchSize the maximum operations per applyBatch call */
    @VisibleForTesting
    fun addBatchWithMaxBatchSize(context: Context, collection: Collection<DictionaryWord>, maxBatchSize: Int): Boolean {
        return try {
            performAudBatchOperation(context, collection, maxBatchSize, BatchOperation.INSERT)
        } catch (e: InterruptedException) {
            LogUtil.w(TAG, "Add batch interrupted")
            Thread.currentThread().interrupt()
            false
        }
    }

    fun deleteBatch(context: Context, collection: Collection<DictionaryWord>): Boolean {
        return deleteBatchWithMaxBatchSize(
            context, collection,
            PersonalDictionaryConstants.MAX_AUD_CONTENT_PROVIDER_OPERATIONS
        )
    }

    /** @param maxBatchSize the maximum operations per applyBatch call */
    @VisibleForTesting
    fun deleteBatchWithMaxBatchSize(context: Context, collection: Collection<DictionaryWord>, maxBatchSize: Int): Boolean {
        return try {
            performAudBatchOperation(context, collection, maxBatchSize, BatchOperation.DELETE)
        } catch (e: InterruptedException) {
            LogUtil.w(TAG, "Delete batch interrupted")
            Thread.currentThread().interrupt()
            false
        }
    }

    @Throws(InterruptedException::class)
    private fun performAudBatchOperation(
        context: Context,
        collection: Collection<DictionaryWord>,
        maxBatchEditSize: Int,
        batchOperation: BatchOperation
    ): Boolean {
        require(maxBatchEditSize > 0) { "maxBatchEditSize must be greater than 0" }

        if (collection.isEmpty()) {
            return true
        }

        val iterator = collection.iterator()
        val maxBatchSize = minOf(collection.size, maxBatchEditSize)
        val operations = ArrayList<ContentProviderOperation>(maxBatchSize)
        var applied = 0

        while (iterator.hasNext()) {
            var operationCount = 0
            while (operationCount < maxBatchSize && iterator.hasNext()) {
                if (Thread.interrupted()) throw InterruptedException()
                val dictionaryWord = iterator.next()
                operations.add(getContentProviderOperation(UserDictionary.Words.CONTENT_URI, dictionaryWord, batchOperation))
                operationCount++
            }

            val contentResolver = context.contentResolver
            var authority = sAuthority
            if (authority == null) {
                authority = UserDictionary.Words.CONTENT_URI.authority
            }

            if (authority == null) {
                LogUtil.e(TAG, "Authority not found")
                return false
            }

            try {
                val results = contentResolver.applyBatch(authority, operations)
                // A silently rejected operation must return false: callers rely on it
                // (PersonalDictionaryUtil.add throws AudAddException).
                for (result in results) {
                    if (result.count == null && result.uri == null) {
                        LogUtil.w(TAG, "$batchOperation of ${operations.size} rejected by the "
                                + "provider at authority '$authority'")
                        return false
                    }
                }
                applied += operations.size
            } catch (e: Exception) {
                // Resolver failures return false rather than escaping and aborting a load; the
                // log names the authority ("Unknown authority" means package visibility).
                LogUtil.w(TAG, "$batchOperation of ${operations.size} against authority "
                        + "'$authority' failed: $e")
                if (e is IllegalArgumentException && e.message?.contains("Unknown authority") == true) {
                    LogUtil.w(TAG, "The system user dictionary provider is not visible to this "
                            + "app; a <queries><provider android:authorities=\"$authority\"/> "
                            + "declaration is required on API 30+")
                }
                if (e !is OperationApplicationException && e !is RemoteException
                    && e !is IllegalArgumentException && e !is SecurityException
                ) {
                    throw e
                }
                return false
            }
            operations.clear()
        }
        LogUtil.d(TAG, "$batchOperation applied to $applied AUD rows")
        return true
    }

    private fun getContentProviderOperation(
        uri: Uri,
        dictionaryWord: DictionaryWord,
        batchOperation: BatchOperation
    ): ContentProviderOperation {
        return when (batchOperation) {
            BatchOperation.INSERT -> ContentProviderOperation.newInsert(uri)
                .withValues(convertDictionaryWordToContentValues(dictionaryWord)).build()
            BatchOperation.DELETE -> {
                val selectionArg = DictionaryWordSelectionArgument(dictionaryWord)
                ContentProviderOperation.newDelete(uri)
                    .withSelection(selectionArg.selection, selectionArg.arguments).build()
            }
        }
    }

    @VisibleForTesting
    fun convertDictionaryWordToContentValues(dictionaryWord: DictionaryWord): ContentValues {
        val contentValues = ContentValues()
        contentValues.put("locale", LocaleUtil.convertBaslLocaleToAudLocale(dictionaryWord.locale))
        contentValues.put("word", dictionaryWord.word)
        contentValues.put("frequency", PersonalDictionaryConstants.DEFAULT_AUD_FREQUENCY)
        if (dictionaryWord is WordSubstitution) {
            contentValues.put("shortcut", dictionaryWord.key)
        }
        return contentValues
    }

    @Throws(InterruptedException::class)
    fun delete(context: Context, dictionaryWord: DictionaryWord): Int {
        val selectionArg = DictionaryWordSelectionArgument(dictionaryWord)
        if (Thread.interrupted()) throw InterruptedException()
        LogUtil.d(TAG, "Deleting $dictionaryWord")
        return context.contentResolver.delete(
            UserDictionary.Words.CONTENT_URI,
            selectionArg.selection,
            selectionArg.arguments
        )
    }

    @Throws(AudDeleteException::class, InterruptedException::class)
    fun update(context: Context, oldWord: DictionaryWord, newWord: DictionaryWord): Boolean {
        if (delete(context, oldWord) < 1) {
            throw AudDeleteException("Failed to delete DW: $oldWord")
        }
        return add(context, newWord)
    }

    fun isWordInAud(context: Context, dictionaryWord: DictionaryWord): Boolean {
        val selectionArg = DictionaryWordSelectionArgument(dictionaryWord)
        val cursor = context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI,
            PROJECTION_WORD,
            selectionArg.selection,
            selectionArg.arguments,
            null
        )
        return cursor?.use {
            it.count > 0
        } ?: false
    }

    /**
     * The match must stay total: null selection fields would make delete() wipe the entire
     * shared system user dictionary.
     */
    private class DictionaryWordSelectionArgument(private val target: DictionaryWord) {
        var arguments: Array<String>? = null
        var selection: String? = null

        init {
            generate()
        }

        private fun generate() {
            if (target is PersonalWord) {
                if (target.locale == PersonalDictionaryConstants.LOCALE_ALL) {
                    selection = SELECTION_ALL_LOCALE_NO_SHORTCUT
                    arguments = arrayOf(target.word)
                } else {
                    selection = SELECTION_ONE_LOCALE_NO_SHORTCUT
                    arguments = arrayOf(target.word, target.locale)
                }
            } else if (target is WordSubstitution) {
                if (target.locale == PersonalDictionaryConstants.LOCALE_ALL) {
                    selection = SELECTION_ALL_LOCALE_WITH_SHORTCUT
                    arguments = arrayOf(target.word, target.key)
                } else {
                    selection = SELECTION_ONE_LOCALE_WITH_SHORTCUT
                    arguments = arrayOf(target.word, target.locale, target.key)
                }
            } else {
                throw IllegalArgumentException("unsupported DictionaryWord: $target")
            }
        }
    }

    /**
     * Every AUD row, or `null` when the provider is unreachable (missing, or hidden by package
     * visibility). Never empty for unreachable: the caller deletes what is missing from this list.
     */
    fun getAudWordsWithoutSpacedKeys(context: Context): List<DictionaryWord>? {
        val cursor = context.contentResolver.query(UserDictionary.Words.CONTENT_URI, PROJECTION_BASL_WORD, null, null, null)
            ?: run {
                LogUtil.w(TAG, "No cursor from ${UserDictionary.Words.CONTENT_URI} - the system "
                        + "user dictionary provider is unreachable")
                return null
            }

        val list = ArrayList<DictionaryWord>(cursor.count)
        
        cursor.use {
            while (it.moveToNext()) {
                val locale = LocaleUtil.convertAudLocaleToBaslLocale(it.getString(0))
                val shortcut = it.getString(1)
                val word = it.getString(2)

                val dictionaryWord = if (shortcut == null) {
                    PersonalWord(word, locale, false)
                } else {
                    try {
                        if (shortcut.contains(" ")) {
                            LogUtil.d(TAG, "Skipping AUD entry that has a key with a space, locale: $locale, key: '$shortcut', word: '$word'")
                            null
                        } else {
                            WordSubstitution.createUserSubstitution(locale, shortcut, word, false)
                        }
                    } catch (e: Exception) {
                        LogUtil.w(TAG, "Cannot create DW, locale: $locale, word: $word, key: $shortcut")
                        null
                    }
                }
                dictionaryWord?.let { dw -> list.add(dw) }
            }
        }
        return list
    }
}
