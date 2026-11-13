package dev.bbkb.ime.personaldictionary.sync

import android.content.Context
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import dev.bbkb.ime.personaldictionary.util.LocaleUtil
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.InitialisationIncompleteException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyNotFoundException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.TermNotFoundException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil
import dev.bbkb.ime.personaldictionary.model.DictionaryWord
import dev.bbkb.ime.personaldictionary.model.PersonalWord
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import dev.bbkb.ime.personaldictionary.storage.DefaultWSLoader
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.collections.LinkedHashSet

/**
 * Two-way sync with Android's User Dictionary (AUD): pushes our words to AUD and pulls external
 * changes back. AUD is the master; all access is serialised by [AUD_SYNC_LOCK].
 */
class AudSyncer private constructor() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** In-flight plus queued sync launches; see [syncDifferencesToBasl]. */
    private val pendingSyncs = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        @JvmField
        val AUD_SYNC_LOCK = ReentrantLock(true)
        private const val TAG = "AudSyncer"
        private const val EXTRA_SPACE_FOR_MAPS = 30

        /** Cap on queued syncs: each blocks an IO thread on the fair lock, so unbounded starves IO. */
        private const val MAX_PENDING_SYNCS = 2

        @Volatile
        private var instance: AudSyncer? = null

        @JvmStatic
        fun getInstance(): AudSyncer {
            return instance ?: synchronized(this) {
                instance ?: AudSyncer().also { instance = it }
            }
        }
        
        @JvmStatic
        fun shouldSync(dictionaryWord: DictionaryWord): Boolean {
            // entries, not values(): values() clones the array, and this runs per word.
            for (type in AudFilterType.entries) {
                 if (type.filter.filter(dictionaryWord)) {
                     LogUtil.d(TAG, "Filtered DW will not be synced to AUD: $dictionaryWord")
                     return false
                 }
            }
            return true
        }
    }

    fun syncDifferencesToBasl(
        context: Context,
        personalDictionaryUtil: PersonalDictionaryUtil,
        completionListener: CompletionListener
    ): Boolean {
        // Returns false when the queue is full: a burst of AUD writes (a locale switch) would
        // otherwise queue one lock-blocked IO coroutine per notification.
        if (pendingSyncs.incrementAndGet() > MAX_PENDING_SYNCS) {
            pendingSyncs.decrementAndGet()
            LogUtil.w(TAG, "AUD sync queue full - dropping request")
            return false
        }
        scope.launch {
            try {
                AUD_SYNC_LOCK.lock()
                var success = false
                try {
                    throwIfInactive()
                    val counts = doSyncDifferencesToBasl(context, personalDictionaryUtil)
                    success = true
                    // INFO: tells a working AUD bridge from a dead one on a release build.
                    LogUtil.i(TAG, "AUD -> personal dictionary sync complete: $counts")
                } catch (e: CancellationException) {
                    LogUtil.w(TAG, "Sync cancelled")
                } catch (e: AudUnavailableException) {
                    LogUtil.w(TAG, "AUD sync skipped: the system user dictionary provider is "
                            + "unreachable (nothing removed from the personal dictionary)")
                } catch (e: IllegalStateException) {
                    LogUtil.w(TAG, "SDK session may have been disposed")
                } catch (e: InitialisationIncompleteException) {
                    LogUtil.e(TAG, "Initialisation incomplete")
                } catch (e: Exception) {
                    LogUtil.w(TAG, "Sync exception: ${e.message}")
                } finally {
                    AUD_SYNC_LOCK.unlock()
                    completionListener.complete(success)
                }
            } finally {
                pendingSyncs.decrementAndGet()
            }
        }
        LogUtil.d(TAG, "Submitted sync task")
        return true
    }

    /** What one AUD -> BASL reconciliation moved; logged at INFO on every completed sync. */
    data class SyncCounts(
        var substitutionsAdded: Int = 0,
        var substitutionsRemoved: Int = 0,
        var wordsAdded: Int = 0,
        var wordsRemoved: Int = 0
    ) {
        val added: Int get() = substitutionsAdded + wordsAdded
        val removed: Int get() = substitutionsRemoved + wordsRemoved
        override fun toString(): String =
            "added=$added (words=$wordsAdded, substitutions=$substitutionsAdded), " +
                "removed=$removed (words=$wordsRemoved, substitutions=$substitutionsRemoved)"
    }

    /** Thrown when the system user dictionary provider cannot be reached; see [populateAudData]. */
    class AudUnavailableException : Exception("System user dictionary provider unreachable")

    @Throws(InterruptedException::class, InitialisationIncompleteException::class)
    fun doSyncDifferencesToBasl(context: Context, personalDictionaryUtil: PersonalDictionaryUtil): SyncCounts {
        LogUtil.d(TAG, "Syncing diffs to PersonalDictionaryManager")
        throwIfInactive()
        val counts = SyncCounts()
        val allUserAndEnabledDefaultWs = personalDictionaryUtil.allUserAndEnabledDefaultWs
        var size = 0
        for (map in allUserAndEnabledDefaultWs.values) {
            size += map.size
        }
        val size2 = personalDictionaryUtil.personalDictionary.size + EXTRA_SPACE_FOR_MAPS
        val baslMap = HashMap<String, TreeMap<String, WordSubstitution>>(size + EXTRA_SPACE_FOR_MAPS)
        val audPersonalWordsMap = HashMap<String, PersonalWord>(size2)

        populateAudData(context, audPersonalWordsMap, baslMap, personalDictionaryUtil.immutableLastActiveLocales)
        syncWordSubstitutions(personalDictionaryUtil, baslMap, allUserAndEnabledDefaultWs, counts)
        syncPersonalWords(personalDictionaryUtil, audPersonalWordsMap, counts)
        return counts
    }

    @Throws(InterruptedException::class, AudUnavailableException::class)
    private fun populateAudData(
        context: Context,
        audPersonalWordsMap: HashMap<String, PersonalWord>,
        baslMap: HashMap<String, TreeMap<String, WordSubstitution>>,
        locales: List<Locale>
    ) {
        LogUtil.d(TAG, "Checking AUD...")
        throwIfInactive()
        // null, not empty: an unreachable provider must abort, since "missing from AUD" means
        // "delete from BASL" below.
        val audWords = AudWrapper.getAudWordsWithoutSpacedKeys(context) ?: throw AudUnavailableException()
        for (dictionaryWord in audWords) {
            if (dictionaryWord is WordSubstitution) {
                val locale = dictionaryWord.locale
                if (PersonalDictionaryConstants.LOCALE_ALL == locale || LocaleUtil.isLocaleStringInLocaleList(locales, locale)) {
                    var treeMap = baslMap[locale]
                    if (treeMap == null) {
                        treeMap = TreeMap()
                        baslMap[locale] = treeMap
                    }
                    treeMap[dictionaryWord.key] = dictionaryWord
                }
            } else {
                audPersonalWordsMap[dictionaryWord.word] = dictionaryWord as PersonalWord
            }
        }
    }

    @Throws(InterruptedException::class)
    private fun syncWordSubstitutions(
        personalDictionaryUtil: PersonalDictionaryUtil,
        audMap: Map<String, TreeMap<String, WordSubstitution>>,
        baslMap: Map<String, TreeMap<String, WordSubstitution>>,
        counts: SyncCounts
    ) {
        val allLocales = HashSet<String>()
        allLocales.addAll(baslMap.keys)
        allLocales.addAll(audMap.keys)

        for (locale in allLocales) {
            val baslTreeMap = baslMap[locale]
            val audTreeMap = audMap[locale]

            if (baslTreeMap == null) {
                throwIfInactive()
                audTreeMap?.let { addWsLocalesOnlyInAudToBasl(personalDictionaryUtil, it, counts) }
            }
            if (audTreeMap == null) {
                throwIfInactive()
                baslTreeMap?.let { removeWsLocalesOnlyInBasl(personalDictionaryUtil, it, counts) }
            }
            if (audTreeMap != null && baslTreeMap != null) {
                throwIfInactive()
                syncWsForMatchingLocales(personalDictionaryUtil, baslTreeMap, audTreeMap, counts)
            }
        }
    }

    private fun removeWsLocalesOnlyInBasl(
        personalDictionaryUtil: PersonalDictionaryUtil,
        treeMap: TreeMap<String, WordSubstitution>,
        counts: SyncCounts
    ) {
        val toRemove = ArrayList<WordSubstitution>()
        for (ws in treeMap.values) {
            if (ws.canBeSynchronised()) {
                toRemove.add(ws)
            }
        }
        for (ws in toRemove) {
            try {
                personalDictionaryUtil.removeFromBasl(ws, false)
                counts.substitutionsRemoved++
            } catch (e: KeyNotFoundException) {
                LogUtil.w(TAG, "WordSubstitution went missing during sync")
            } catch (e: InterruptedException) {
                LogUtil.w(TAG, "Interrupted")
            }
        }
    }

    private fun addWsLocalesOnlyInAudToBasl(
        personalDictionaryUtil: PersonalDictionaryUtil,
        treeMap: TreeMap<String, WordSubstitution>,
        counts: SyncCounts
    ) {
        for (ws in treeMap.values) {
            try {
                ws.setCanBeSynchronised(true)
                personalDictionaryUtil.addToBasl(ws, true)
                counts.substitutionsAdded++
            } catch (e: Exception) {
                LogUtil.e(TAG, "Exception during addToBasl: ${e.javaClass.simpleName} - ${ws.key}")
            }
        }
    }

    private fun syncWsForMatchingLocales(
        personalDictionaryUtil: PersonalDictionaryUtil,
        baslTreeMap: TreeMap<String, WordSubstitution>,
        audTreeMap: TreeMap<String, WordSubstitution>,
        counts: SyncCounts
    ) {
        val baslSet = HashSet(baslTreeMap.values)
        val audSet = HashSet(audTreeMap.values)

        // In PersonalDictionaryManager but not AUD -> remove from PersonalDictionaryManager
        val baslOnlyItems = HashSet(baslSet)
        baslOnlyItems.removeAll(audSet)

        // In AUD but not PersonalDictionaryManager -> add to PersonalDictionaryManager
        val audOnlyItems = HashSet(audSet)
        audOnlyItems.removeAll(baslSet)

        for (ws in baslOnlyItems) {
            if (ws.canBeSynchronised()) {
                try {
                    personalDictionaryUtil.removeFromBasl(ws, false)
                    counts.substitutionsRemoved++
                } catch (e: Exception) {
                    LogUtil.e(TAG, "Could not find key while syncing: ${ws.key}")
                }
            }
        }

        // Keys (case-insensitive) of our entries AUD still holds unchanged. An AUD row under one of
        // these keys in another case is a second row beside ours, not a change: taking it in makes
        // the entry flip case on every sync. It is left in AUD, which other apps share.
        val heldInAud = TreeSet(String.CASE_INSENSITIVE_ORDER)
        for (ws in baslSet) {
            if (ws in audSet) heldInAud.add(ws.key)
        }

        for (ws in audOnlyItems) {
            if (ws.key in heldInAud) {
                LogUtil.d(TAG, "Ignoring an AUD row beside an entry it already holds: ${ws.key}")
                continue
            }
            ws.setCanBeSynchronised(true)
            try {
                personalDictionaryUtil.addToBasl(ws, true)
                counts.substitutionsAdded++
            } catch (e: Exception) {
                LogUtil.e(TAG, "Exception during addToBasl: ${e.javaClass.simpleName} - ${ws.key}")
            }
        }
    }

    @Throws(InterruptedException::class, InitialisationIncompleteException::class)
    private fun syncPersonalWords(
        personalDictionaryUtil: PersonalDictionaryUtil,
        audMap: Map<String, PersonalWord>,
        counts: SyncCounts
    ) {
        throwIfInactive()
        val baslPersonalDictionary = personalDictionaryUtil.personalDictionary

        val baslOnlyWords = HashSet(baslPersonalDictionary.keys)
        baslOnlyWords.removeAll(audMap.keys)

        throwIfInactive()

        val audOnlyWords = HashSet(audMap.keys)
        audOnlyWords.removeAll(baslPersonalDictionary.keys)

        val wordsToRemove = LinkedHashSet<PersonalWord>()
        for (key in baslOnlyWords) {
            baslPersonalDictionary[key]?.let { wordsToRemove.add(it) }
        }

        for (word in wordsToRemove) {
            try {
                personalDictionaryUtil.removeFromBasl(word)
                counts.wordsRemoved++
            } catch (e: TermNotFoundException) {
                LogUtil.w(TAG, "Personal word went missing during sync")
            }
        }

        throwIfInactive()

        for (key in audOnlyWords) {
            try {
                audMap[key]?.let {
                    personalDictionaryUtil.addToBasl(it, true)
                    counts.wordsAdded++
                }
            } catch (e: Exception) {
                LogUtil.e(TAG, "Exception during addToBasl in PWs: ${e.javaClass.simpleName}")
            }
        }
    }

    fun syncDefaultWordSubstitutions(
        context: Context,
        resourceDir: String,
        localesToRemove: List<Locale>,
        localesToAdd: List<Locale>,
        deletedKeys: Set<String>
    ): Boolean {
        // Synchronous under the lock: callers are on a background thread.
        AUD_SYNC_LOCK.lock()
        try {
            LogUtil.d(TAG, "Starting default WS sync")
            doSyncDefaultWordSubstitutions(context, resourceDir, localesToRemove, localesToAdd, deletedKeys)
            return true
        } catch (e: IllegalStateException) {
            LogUtil.w(TAG, "SDK session may have been disposed")
            return false
        } catch (e: InterruptedException) {
            LogUtil.w(TAG, "Sync interrupted")
            return false
        } finally {
            AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(InterruptedException::class)
    private fun doSyncDefaultWordSubstitutions(
        context: Context,
        resourceDir: String,
        localesToRemove: List<Locale>,
        localesToAdd: List<Locale>,
        deletedKeys: Set<String>
    ) {
        if (localesToRemove.isNotEmpty()) {
            LogUtil.d(TAG, "Removing WS from locales: $localesToRemove")
            val toRemove = loadAndFilterDefaultWs(context, resourceDir, localesToRemove, deletedKeys)
            if (!AudWrapper.deleteBatch(context, toRemove)) {
                LogUtil.w(TAG, "Batch delete from AUD during sync failed")
            }
        }

        LogUtil.d(TAG, "Adding WS from locales: $localesToAdd")
        val toAdd = loadAndFilterDefaultWs(context, resourceDir, localesToAdd, deletedKeys)
        duplicateDefaultWsWorkaround(context, toAdd)
        if (!AudWrapper.addBatch(context, toAdd)) {
            LogUtil.w(TAG, "Batch add to AUD during sync failed")
        }
    }

    @Throws(InterruptedException::class)
    private fun duplicateDefaultWsWorkaround(context: Context, collection: Collection<WordSubstitution>) {
        if (!AudWrapper.deleteBatch(context, collection)) {
            LogUtil.w(TAG, "Batch delete from AUD during sync failed")
        }
    }

    private fun loadAndFilterDefaultWs(
        context: Context,
        resourceDir: String,
        locales: List<Locale>,
        deletedKeys: Set<String>
    ): Collection<WordSubstitution> {
        val map = DefaultWSLoader.load(context, resourceDir, locales, deletedKeys, true)
        val result = HashSet<WordSubstitution>(map.size)
        for (ws in map.values) {
            if (shouldSync(ws)) {
                result.add(ws)
            }
        }
        return result
    }

    fun shutDown(@Suppress("UNUSED_PARAMETER") clearData: Boolean) {
        instance = null
        scope.cancel()
    }

    /**
     * Checks the singleton's scope (not the current job) and the thread interrupt, which is the
     * abort signal for the blocking ContentResolver calls. Note `Thread.interrupted()` clears it.
     */
    private fun throwIfInactive() {
        if (!scope.isActive) throw CancellationException()
        if (Thread.interrupted()) throw InterruptedException()
    }
}
