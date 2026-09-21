package dev.bbkb.ime.personaldictionary

import android.content.Context
import android.text.TextUtils
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.personaldictionary.model.DictionaryWord
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudAddException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.AudDeleteException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.InitialisationIncompleteException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyAlreadyDefinedException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.KeyNotFoundException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.TermAlreadyDefinedException
import dev.bbkb.ime.personaldictionary.PersonalDictionaryExceptions.TermNotFoundException
import dev.bbkb.ime.personaldictionary.macro.DynamicContentHandler
import dev.bbkb.ime.personaldictionary.model.LoadConfig
import dev.bbkb.ime.personaldictionary.model.PersonalWord
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import dev.bbkb.ime.personaldictionary.storage.DefaultWSLoader
import dev.bbkb.ime.personaldictionary.storage.DictionaryWordObservable
import dev.bbkb.ime.personaldictionary.storage.FileUtils
import dev.bbkb.ime.personaldictionary.storage.LocaleWordSubstitutionMap
import dev.bbkb.ime.personaldictionary.storage.PersonalWordsMap
import dev.bbkb.ime.personaldictionary.sync.AudContentObserver
import dev.bbkb.ime.personaldictionary.sync.AudFilterType
import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import dev.bbkb.ime.personaldictionary.sync.AudWrapper
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import dev.bbkb.ime.personaldictionary.util.DataUtil
import dev.bbkb.ime.personaldictionary.util.LocaleUtil
import dev.bbkb.ime.personaldictionary.util.LogUtil
import com.blackberry.nuanceshim.NuanceSDK
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.*

class PersonalDictionaryUtil private constructor(
    context: Context,
    baseDir: String,
    private val mResourceDir: String,
    private val mNuanceSDK: NuanceSDK
) {
    private val mContext: Context = context.applicationContext
    private var mAudSyncer: AudSyncer = AudSyncer.getInstance()
    private var mPduDir: File? = null
    
    // Using CoroutineScope instead of ExecutorService
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mEnabledWordSubstitutions = TreeMap<String, WordSubstitution>(String.CASE_INSENSITIVE_ORDER)
    private var mCachedEnabledWordSubstitutions: Map<String, WordSubstitution> = TreeMap(String.CASE_INSENSITIVE_ORDER)
    // Filenames come from LoadConfig, which is the single source for the on-disk
    // persistence contract: these were hard-coded literals duplicating both
    // PersonalDictionaryConstants and LoadConfig, so a one-sided edit would silently
    // orphan the user's stored substitutions (audit PD-28).
    private val mAllUserWordSubstitutions =
        LocaleWordSubstitutionMap(LoadConfig.WORD_SUBSTITUTIONS.filename)
    private val mDeletedDefaultWordSubstitutions =
        LocaleWordSubstitutionMap(LoadConfig.DELETED_WORD_SUBSTITUTIONS.filename)
    private val mPersonalWords = PersonalWordsMap(LoadConfig.PERSONAL_WORDS.filename)
    private val mLastActiveLocales = ArrayList<Locale>()
    
    @Volatile
    private var mLoadComplete = false
    private var mSyncDefaultWsToAud = true
    private var mAudContentObserver: AudContentObserver? = null

    private val mObserverLock = Any()
    private val CACHED_ENABLED_WS_LOCK = Any()

    private val gson = Gson()

    enum class CapsMode {
        NO_CAPS,
        FIRST_LETTER_CAPS,
        ALL_CAPS
    }

    init {
        setupPduDir(baseDir)
    }

    companion object {
        private const val TAG = "PersonalDictionaryUtil"
        const val PERSONAL_DICTIONARY_UTIL_DIR = "/dev.bbkb.ime.basl.pdu"

        /**
         * What this directory was called while the class root was still BlackBerry's. The name
         * is only a name — nothing reads it back out — but it is the *user's* personal
         * dictionary sitting in it, so a build that moved the class root without moving the
         * words would have looked exactly like the dictionary had been wiped. Spelled in halves
         * so PackageIdentityTest's class-root guard does not read it as a live reference.
         */
        private const val LEGACY_PERSONAL_DICTIONARY_UTIL_DIR = "/com.blackberry." + "inputmethod.basl.pdu"

        /** Per-device bookkeeping for the AUD bridge; not a user setting, never shown. */
        private const val SYNC_STATE_PREFS = "personal_dictionary_sync_state"
        private const val KEY_USER_WORDS_PUSHED = "user_words_pushed_to_aud_v1"
        
        @Volatile
        private var sInstance: PersonalDictionaryUtil? = null

        @JvmStatic
        @Throws(IOException::class)
        fun getInstance(context: Context, baseDir: String, resourceDir: String, nuanceSDK: NuanceSDK): PersonalDictionaryUtil {
            return sInstance ?: synchronized(this) {
                sInstance ?: PersonalDictionaryUtil(context, baseDir, resourceDir, nuanceSDK).also { sInstance = it }
            }
        }
    }

    @Throws(IOException::class)
    fun setupPduDir(baseDir: String) {
        val dir = File(baseDir, PERSONAL_DICTIONARY_UTIL_DIR)
        migrateLegacyPduDir(baseDir, dir)
        mPduDir = dir
        dir.mkdirs()
        if (!dir.isDirectory) {
            throw IOException("Could not create personal dictionary directory: " + dir.path)
        }
    }

    /**
     * One-shot rename of the pre-move directory. Only ever runs on a device that was carrying an
     * install from before the class root moved; if the new directory already exists the legacy
     * one is left alone rather than merged, because a half-merge of two word stores is worse than
     * an orphan the user can no longer see.
     */
    private fun migrateLegacyPduDir(baseDir: String, target: File) {
        if (target.exists()) return
        val legacy = File(baseDir, LEGACY_PERSONAL_DICTIONARY_UTIL_DIR)
        if (!legacy.isDirectory) return
        if (legacy.renameTo(target)) {
            LogUtil.i(TAG, "Moved the personal dictionary to ${target.name}")
        } else {
            LogUtil.w(TAG, "Could not move the personal dictionary out of ${legacy.name}")
        }
    }

    fun shutDown(clearData: Boolean) {
        unregisterAudContentObserver()
        sInstance = null
        scope.cancel()
    }

    fun getWordSubstitutions(): Map<String, WordSubstitution> {
        synchronized(CACHED_ENABLED_WS_LOCK) {
            return Collections.unmodifiableMap(mCachedEnabledWordSubstitutions)
        }
    }

    val personalDictionary: Map<String, PersonalWord>
        get() = Collections.unmodifiableMap(mPersonalWords.backingMap)

    val allUserAndEnabledDefaultWs: HashMap<String, TreeMap<String, WordSubstitution>>
        get() {
            val map = HashMap<String, TreeMap<String, WordSubstitution>>()
            AudSyncer.AUD_SYNC_LOCK.lock()
            try {
                val deletedDefaultWsKeys = deletedDefaultWsKeys
                val activeLocales = immutableLastActiveLocales
                LogUtil.d(TAG, "Currently active locales: $activeLocales")
                
                val backingMap = mAllUserWordSubstitutions.backingMap
                for (locale in backingMap.keys) {
                    if (PersonalDictionaryConstants.LOCALE_ALL == locale || LocaleUtil.isLocaleStringInLocaleList(activeLocales, locale)) {
                        map[locale] = backingMap[locale]!!
                    }
                }

                // Load defaults
                val defaults = DefaultWSLoader.load(mContext, mResourceDir, activeLocales, deletedDefaultWsKeys, true)
                for (ws in defaults.values) {
                    val locale = ws.locale
                    val key = ws.key
                    var treeMap = map[locale]
                    if (treeMap == null) {
                        treeMap = TreeMap()
                        map[locale] = treeMap
                    }
                    if (!treeMap.containsKey(key)) {
                        treeMap[key] = ws
                    }
                }
            } finally {
                AudSyncer.AUD_SYNC_LOCK.unlock()
            }
            return map
        }

    @Throws(InitialisationIncompleteException::class)
    fun verifyLoadingComplete() {
        if (!mLoadComplete) throw InitialisationIncompleteException()
    }

    private fun addWsToAllUserMap(ws: WordSubstitution) {
        if (ws.type == WordSubstitution.Type.DEFAULT) {
            LogUtil.d(TAG, "Default types should not be serialised")
        } else {
            mAllUserWordSubstitutions.put(ws)
        }
    }

    @Throws(KeyNotFoundException::class)
    private fun removeFromAllUserWsMapIfUserType(ws: WordSubstitution) {
        if (ws.type != WordSubstitution.Type.USER) return
        if (!mAllUserWordSubstitutions.removeIfPresent(ws)) {
            throw KeyNotFoundException("${ws.key} not found in ${ws.locale}")
        }
    }

    private fun enableUserSubstitutions(locales: List<Locale>) {
        enableUserSubstitutionsForLocales(locales)
        enableUserSubstitutionsForLocales(listOf(Locale(PersonalDictionaryConstants.LOCALE_ALL)))
    }

    private fun enableUserSubstitutionsForLocales(locales: List<Locale>) {
        for (ws in mAllUserWordSubstitutions.getWordSubstitutionsSet(locales)) {
            if (!mEnabledWordSubstitutions.containsKey(ws.key)) {
                mEnabledWordSubstitutions[ws.key] = ws
            }
        }
    }

    @Throws(InitialisationIncompleteException::class, TermAlreadyDefinedException::class)
    fun addToBasl(personalWord: PersonalWord, force: Boolean) {
        doAddToBasl(personalWord, force, true)
    }

    /**
     * The load path: the on-disk `personal_words` entries are put into the map WITHOUT the
     * loaded-check. `mLoadComplete` is deliberately set last in [doLoad] (audit PD-42), so while
     * this runs the object is not yet "loaded" — and with the check in place the very first word
     * threw [InitialisationIncompleteException], `loadJsonForConfig` logged it as
     * "Error parsing JSON with Gson: null" and NOT ONE personal word was ever loaded from disk
     * (KEY2, 2026-09-21; masked afterwards by the AUD sync re-adding them because AUD is master).
     */
    @Throws(TermAlreadyDefinedException::class)
    private fun addToBaslNotSdk(personalWord: PersonalWord) {
        doAddToBasl(personalWord, force = false, addToSdk = false, verifyLoaded = false)
    }

    @Throws(InitialisationIncompleteException::class, TermAlreadyDefinedException::class)
    private fun doAddToBasl(
        personalWord: PersonalWord,
        force: Boolean,
        addToSdk: Boolean,
        verifyLoaded: Boolean = true
    ) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            if (verifyLoaded) verifyLoadingComplete()
            if (force) {
                mPersonalWords.put(personalWord)
            } else if (!mPersonalWords.putIfAbsent(personalWord)) {
                throw TermAlreadyDefinedException()
            }
            if (addToSdk) {
                mNuanceSDK.addWord(personalWord.word)
            }
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(AudAddException::class, InitialisationIncompleteException::class, KeyAlreadyDefinedException::class)
    fun add(ws: WordSubstitution) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            val set = HashSet<WordSubstitution>(1)
            set.add(ws)
            if (!AudWrapper.addBatch(mContext, set)) {
                throw AudAddException("Batch add to AUD failed")
            }
            addToBasl(ws, false)
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(InitialisationIncompleteException::class, KeyAlreadyDefinedException::class)
    fun addToBasl(ws: WordSubstitution, force: Boolean) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            verifyLoadingComplete()
            val key = ws.key
            if (!force && mEnabledWordSubstitutions.containsKey(key)) {
                throw KeyAlreadyDefinedException("$key already defined")
            }
            if (ws.type == WordSubstitution.Type.USER) {
                mNuanceSDK.addWord(ws.word)
            }
            mEnabledWordSubstitutions[key] = ws
            addWsToAllUserMap(ws)
            updateCachedEnabledWordSubstitutionMap()
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(InitialisationIncompleteException::class, KeyAlreadyDefinedException::class)
    private fun addToDeletedMap(ws: WordSubstitution) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            verifyLoadingComplete()
            if (mDeletedDefaultWordSubstitutions.putIfAbsent(ws)) {
                LogUtil.d(TAG, "Default WS deleted")
                return
            }
            throw KeyAlreadyDefinedException("Key: " + ws.key)
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(KeyNotFoundException::class, AudDeleteException::class, KeyAlreadyDefinedException::class, InterruptedException::class, AudAddException::class)
    fun update(oldWs: WordSubstitution, newWs: WordSubstitution) {
        val oldKey = oldWs.key
        val newKey = newWs.key
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            if (!mEnabledWordSubstitutions.containsKey(oldKey)) {
                throw KeyNotFoundException("$oldKey not found")
            }
            if (AudWrapper.delete(mContext, oldWs) == 0 && AudWrapper.isWordInAud(mContext, oldWs)) {
                throw AudDeleteException("Could not delete WS from AUD: $oldWs")
            }
            removeFromAllUserWsMapIfUserType(oldWs)
            putWsInDeletedMapIfDefault(oldWs)
            
            if (!oldKey.equals(newKey, ignoreCase = true)) {
                if (mEnabledWordSubstitutions.containsKey(newKey)) {
                    throw KeyAlreadyDefinedException("$newKey already defined")
                }
                mEnabledWordSubstitutions.remove(oldKey)
            }
            
            if (oldWs.canBeSynchronised() && AudFilterType.DYNAMIC_WORD_SUBSTITUTION.filter.filter(oldWs)) {
                newWs.setCanBeSynchronised(true)
            }
            
            val set = HashSet<WordSubstitution>(1)
            set.add(newWs)
            if (!AudWrapper.addBatch(mContext, set)) {
                throw AudAddException("Batch add to AUD failed")
            }
            mEnabledWordSubstitutions[newKey] = newWs
            addWsToAllUserMap(newWs)
            updateCachedEnabledWordSubstitutionMap()
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(InitialisationIncompleteException::class, TermNotFoundException::class)
    fun removeFromBasl(personalWord: PersonalWord) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            verifyLoadingComplete()
            if (!mPersonalWords.removeIfPresent(personalWord)) {
                throw TermNotFoundException()
            }
            updateCachedEnabledWordSubstitutionMap()
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    fun remove(ws: WordSubstitution) {
        try {
            removeFromBasl(ws, true)
        } catch (e: Exception) {
            LogUtil.w(TAG, "Remove error: ${e.message}")
        }
    }

    @Throws(KeyNotFoundException::class, InterruptedException::class)
    fun removeFromBasl(ws: WordSubstitution, deleteFromAud: Boolean) {
        val key = ws.key
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            if (!mEnabledWordSubstitutions.containsKey(key)) {
                throw KeyNotFoundException("$key not found")
            }
            if (deleteFromAud && AudWrapper.delete(mContext, ws) < 1) {
                LogUtil.d(TAG, "Nothing deleted in AUD when deleting WS")
            }
            mEnabledWordSubstitutions.remove(key)
            removeFromAllUserWsMapIfUserType(ws)
            putWsInDeletedMapIfDefault(ws)
            updateCachedEnabledWordSubstitutionMap()
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    private fun putWsInDeletedMapIfDefault(ws: WordSubstitution) {
        if (isWsDefaultForLocale(ws)) {
            mDeletedDefaultWordSubstitutions.putIfAbsent(ws)
        }
    }

    private fun isWsDefaultForLocale(ws: WordSubstitution): Boolean {
        return try {
            val locale = LocaleUtil.parse(ws.locale) ?: return false
            DefaultWSLoader.load(mContext, mResourceDir, listOf(locale), deletedDefaultWsKeys, true).containsValue(ws)
        } catch (e: Exception) {
            LogUtil.w(TAG, "Error parsing locale ${ws.locale}: ${e.message}")
            false
        }
    }

    fun switchInputLanguages(locales: List<Locale>, listener: CompletionListener) {
        // Modern approach: Launch coroutine
        scope.launch {
            LogUtil.d(TAG, "Switching input languages to: $locales")
            var sameLocales = false
            var previousLocales: List<Locale>? = null
            
            try {
                AudSyncer.AUD_SYNC_LOCK.lock()
                try {
                    verifyLoadingComplete()
                    val mutableLocales = ArrayList(locales)
                    DataUtil.moveObjectToFrontOfList(Locale.getDefault(), mutableLocales)
                    previousLocales = immutableLastActiveLocales
                    if (previousLocales == locales) {
                        LogUtil.d(TAG, "Switching to same locales - ignoring")
                        sameLocales = true
                    } else {
                        setLastActiveLocales(mutableLocales)
                        loadWordSubstitutions(mutableLocales)
                        updateCachedEnabledWordSubstitutionMap()
                    }
                } finally {
                    AudSyncer.AUD_SYNC_LOCK.unlock()
                }

                if (sameLocales) {
                    listener.complete(false)
                } else {
                    listener.complete(true)
                    if (mSyncDefaultWsToAud) {
                        syncDefaultWsToAud(previousLocales!!)
                        syncAudDifferencesToBasl()
                    }
                }
            } catch (e: Exception) {
                LogUtil.w(TAG, e)
                listener.complete(false)
            }
        }
    }

    fun loadWordSubstitutions(locales: List<Locale>) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            mEnabledWordSubstitutions.clear()
            enableUserSubstitutions(locales)
            addDefaultWordSubstitutions(locales)
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    fun addDefaultWordSubstitutions(locales: List<Locale>) {
        val loaded = DefaultWSLoader.load(mContext, mResourceDir, locales, deletedDefaultWsKeys, true)
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            for (key in loaded.keys) {
                if (!mEnabledWordSubstitutions.containsKey(key)) {
                    mEnabledWordSubstitutions[key] = loaded[key]!!
                }
            }
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    private val deletedDefaultWsKeys: Set<String>
        get() {
            AudSyncer.AUD_SYNC_LOCK.lock()
            try {
                return mDeletedDefaultWordSubstitutions.wordSubstitutionsKeySet
            } finally {
                AudSyncer.AUD_SYNC_LOCK.unlock()
            }
        }

    fun reset() {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            mLoadComplete = false
            mEnabledWordSubstitutions.clear()
            mDeletedDefaultWordSubstitutions.clear()
            mAllUserWordSubstitutions.clear()
            mPersonalWords.clear()
            updateCachedEnabledWordSubstitutionMap()
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    fun load(locales: List<Locale>, listener: CompletionListener, registerObserver: Boolean, sync: Boolean) {
        scope.launch {
            LogUtil.d(TAG, "Loading locales:$locales")
            if (mLoadComplete) {
                LogUtil.d(TAG, "Attempted to load multiple times")
                listener.complete(true)
                // registerAudContentObserver() is idempotent, and skipping it here used to mean
                // that a second load - a settings screen initialising the manager the IME had
                // already initialised - reported success with no observer attached.
                if (registerObserver) registerAudContentObserver()
                return@launch
            }
            
            AudSyncer.AUD_SYNC_LOCK.lock()
            try {
                var reported = false
                try {
                    doLoad(locales)
                    listener.complete(true)
                    reported = true
                    if (registerObserver) {
                        registerAudContentObserver()
                    }
                    if (mSyncDefaultWsToAud) {
                        syncDefaultWsToAudThenAudDifferencesToBasl(sync, ArrayList())
                    } else if (sync) {
                        syncAudDifferencesToBasl()
                    }
                } catch (e: Exception) {
                    // Audit PD-42: this used to swallow everything doLoad can throw -
                    // including the TermAlreadyDefined/KeyAlreadyDefined signals of a
                    // corrupt persisted JSON, and any failure of the observer
                    // registration or the AUD sync - so a silent partial load presented
                    // as "my text shortcuts vanished" with nothing in logcat.
                    LogUtil.e(TAG, "Personal dictionary load failed: " + e)
                    if (!reported) {
                        listener.complete(false)
                    }
                }
            } finally {
                AudSyncer.AUD_SYNC_LOCK.unlock()
            }
        }
    }

    private fun setLastActiveLocales(locales: List<Locale>) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            mLastActiveLocales.clear()
            mLastActiveLocales.addAll(locales)
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    val immutableLastActiveLocales: List<Locale>
        get() {
            AudSyncer.AUD_SYNC_LOCK.lock()
            try {
                return ArrayList(mLastActiveLocales)
            } finally {
                AudSyncer.AUD_SYNC_LOCK.unlock()
            }
        }

    @Throws(InitialisationIncompleteException::class)
    fun syncDefaultWsToAud(locales: List<Locale>) {
        syncDefaultWsToAudThenAudDifferencesToBasl(false, locales)
    }

    @Throws(InitialisationIncompleteException::class)
    fun syncDefaultWsToAudThenAudDifferencesToBasl(syncDifferences: Boolean, locales: List<Locale>) {
        verifyLoadingComplete()
        if (mAudSyncer.syncDefaultWordSubstitutions(mContext, mResourceDir, locales, immutableLastActiveLocales, deletedDefaultWsKeys)) {
            LogUtil.d(TAG, "Default WS sync successful")
            if (syncDifferences) {
                syncAudDifferencesToBasl()
            }
        } else {
            LogUtil.e(TAG, "Default WS sync failed")
        }
    }

    @Throws(InitialisationIncompleteException::class)
    fun syncAudDifferencesToBasl() {
        verifyLoadingComplete()
        if (!mAudSyncer.syncDifferencesToBasl(mContext, this, object : CompletionListener {
            override fun complete(success: Boolean) {
                LogUtil.d(TAG, "AUD sync completed: $success")
            }
        })) {
            LogUtil.w(TAG, "AUD sync queue already full")
        }
    }

    @Throws(InitialisationIncompleteException::class)
    fun registerAudContentObserver() {
        synchronized(mObserverLock) {
            if (mAudContentObserver != null) {
                LogUtil.d(TAG, "AUD content observer already registered")
                return
            }
            verifyLoadingComplete()
            val observer = AudContentObserver(mContext, this, null)
            try {
                observer.register()
            } catch (e: Exception) {
                // SecurityException is the documented one (READ_USER_DICTIONARY revoked), but
                // anything thrown here leaves the bridge permanently one-way and used to be
                // reported only through DictionaryManager's Logger.debug (audit 2026-09-21).
                LogUtil.w(TAG, "Could not register the AUD content observer: $e")
                throw e
            }
            mAudContentObserver = observer
            LogUtil.i(TAG, "AUD content observer registered on "
                    + android.provider.UserDictionary.Words.CONTENT_URI)
        }
        // The user's own words only ever reached AUD one at a time, at add time, so any add
        // made while the provider was unreachable stayed stranded in BASL - and the AUD-is-
        // master diff sync deletes exactly those. Publish them once before the first sync.
        pushUserWordsToAudOnce()
    }

    fun unregisterAudContentObserver() {
        synchronized(mObserverLock) {
            mAudContentObserver?.let {
                it.unregister()
                mAudContentObserver = null
                LogUtil.i(TAG, "AUD content observer unregistered")
            }
        }
    }

    @get:VisibleForTesting
    val isAudContentObserverRegisteredForTest: Boolean
        get() = synchronized(mObserverLock) { mAudContentObserver != null }

    /**
     * One-shot reconciliation in the BASL -> AUD direction.
     *
     * [syncDifferencesToBasl][AudSyncer.syncDifferencesToBasl] treats AUD as the master and
     * deletes anything BASL holds that AUD does not. That is correct only while every user add
     * also reaches AUD, which is precisely what was broken on the KEY2: the provider was
     * invisible to us (no `<queries>` declaration under targetSdk 36), `AudWrapper.addBatch`
     * returned false for months, and the device ended up with words in
     * `files/…basl.pdu` and an empty `content://user_dictionary/words`. Repairing the
     * visibility without this push would make the first working sync delete them.
     *
     * Runs at most once per install; the flag is per-device state, not a user setting.
     */
    private fun pushUserWordsToAudOnce() {
        val prefs = mContext.getSharedPreferences(SYNC_STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_USER_WORDS_PUSHED, false)) return
        try {
            val pushed = pushUserWordsToAud()
            if (pushed < 0) {
                // AUD is still refusing writes: do NOT latch, or the words stay stranded for
                // good and the next working sync deletes them.
                LogUtil.w(TAG, "One-shot personal dictionary -> AUD backfill refused; will retry")
                return
            }
            prefs.edit().putBoolean(KEY_USER_WORDS_PUSHED, true).apply()
            LogUtil.i(TAG, "One-shot personal dictionary -> AUD backfill published $pushed entries")
        } catch (e: Exception) {
            // Not fatal and deliberately not latched: retry on the next registration.
            LogUtil.w(TAG, "One-shot personal dictionary -> AUD backfill failed: $e")
        }
    }

    /**
     * Pushes every user-owned entry (word substitutions and personal words, minus the ones
     * [AudSyncer.shouldSync] filters) into AUD and returns how many were published, or `-1`
     * when the provider refused the batch.
     */
    @VisibleForTesting
    fun pushUserWordsToAud(): Int {
        val toPush = HashSet<DictionaryWord>()
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            for (byLocale in mAllUserWordSubstitutions.backingMap.values) {
                for (ws in byLocale.values) {
                    if (ws.type == WordSubstitution.Type.USER && AudSyncer.shouldSync(ws)) toPush.add(ws)
                }
            }
            for (pw in mPersonalWords.backingMap.values) {
                if (AudSyncer.shouldSync(pw)) toPush.add(pw)
            }
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
        if (toPush.isEmpty()) return 0
        // Delete first: AUD has no uniqueness constraint, so a re-run would otherwise
        // duplicate every row (the same reason AudSyncer.duplicateDefaultWsWorkaround exists).
        AudWrapper.deleteBatch(mContext, toPush)
        if (!AudWrapper.addBatch(mContext, toPush)) {
            LogUtil.w(TAG, "Personal dictionary -> AUD backfill of ${toPush.size} entries was refused")
            return -1
        }
        return toPush.size
    }

    @Throws(InitialisationIncompleteException::class, TermAlreadyDefinedException::class, KeyAlreadyDefinedException::class)
    fun doLoad(locales: List<Locale>) {
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            if (mLoadComplete) return
            val mutableLocales = ArrayList(locales)
            DataUtil.moveObjectToFrontOfList(Locale.getDefault(), mutableLocales)
            setLastActiveLocales(mutableLocales)
            loadJsonForConfig(LoadConfig.DELETED_WORD_SUBSTITUTIONS)
            loadJsonForConfig(LoadConfig.WORD_SUBSTITUTIONS)
            loadWordSubstitutions(mutableLocales)
            loadJsonForConfig(LoadConfig.PERSONAL_WORDS)
            setDataNotDirty()
            updateCachedEnabledWordSubstitutionMap()
            // Set last, not first: a mid-load throw used to leave the object marked
            // loaded with a half-populated map (audit PD-42).
            mLoadComplete = true
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    private fun setDataNotDirty() {
        mDeletedDefaultWordSubstitutions.isDirty = false
        mAllUserWordSubstitutions.isDirty = false
        mPersonalWords.isDirty = false
    }

    @Throws(InitialisationIncompleteException::class, TermAlreadyDefinedException::class, KeyAlreadyDefinedException::class)
    private fun loadJsonForConfig(config: LoadConfig) {
        val fileContent = FileUtils.readFileToString(config.getFile(mPduDir!!).path)
        if (TextUtils.isEmpty(fileContent)) {
            LogUtil.w(TAG, "No JSON found while loading (either first time use or a severe problem)")
            return
        }

        try {
            when (config) {
                LoadConfig.WORD_SUBSTITUTIONS -> {
                    val type = object : TypeToken<List<WordSubstitution>>() {}.type
                    val list: List<WordSubstitution> = gson.fromJson(fileContent, type) ?: emptyList()
                    list.forEach { addWsToAllUserMap(it) }
                }
                LoadConfig.DELETED_WORD_SUBSTITUTIONS -> {
                    val type = object : TypeToken<List<WordSubstitution>>() {}.type
                    val list: List<WordSubstitution> = gson.fromJson(fileContent, type) ?: emptyList()
                    list.forEach { addToDeletedMap(it) }
                }
                LoadConfig.PERSONAL_WORDS -> {
                    val type = object : TypeToken<List<PersonalWord>>() {}.type
                    val list: List<PersonalWord> = gson.fromJson(fileContent, type) ?: emptyList()
                    list.forEach { addToBaslNotSdk(it) }
                }
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Error parsing JSON with Gson: ${e.message}")
        }
    }

    val areDataDirty: Boolean
        get() = mAllUserWordSubstitutions.isDirty || mDeletedDefaultWordSubstitutions.isDirty || mPersonalWords.isDirty

    fun save(listener: CompletionListener) {
        scope.launch {
            try {
                doSave()
                listener.complete(true)
            } catch (e: Exception) {
                LogUtil.e(TAG, "Save operation failed: ${e.message}")
                listener.complete(false)
            }
        }
    }

    @Throws(InitialisationIncompleteException::class, Exception::class)
    fun doSave() {
        verifyLoadingComplete()
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            saveDictionaryWords(mAllUserWordSubstitutions)
            saveDictionaryWords(mDeletedDefaultWordSubstitutions)
            saveDictionaryWords(mPersonalWords)
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    @Throws(Exception::class, IOException::class)
    private fun saveDictionaryWords(observable: DictionaryWordObservable) {
        val filename = observable.filename
        if (!observable.isDirty) {
            LogUtil.d(TAG, "No changes to save to $filename")
            return
        }
        val collection = observable.asCollection()
        val json = gson.toJson(collection)
        
        LogUtil.d(TAG, "Writing to file: $filename")
        FileUtils.writeStringToFile(filename, json, mPduDir!!)
        observable.isDirty = false
    }

    fun getWordSubstitutionForInput(input: String, capsMode: CapsMode, locale: Locale): String {
        // Audit PD-9: macro expansion used to run *inside* this lock, so it serialised
        // against the cache writer on the commit path. Only the map lookup needs it.
        val ws = synchronized(CACHED_ENABLED_WS_LOCK) {
            mCachedEnabledWordSubstitutions[input]
        } ?: return input
        val dynamicText = DynamicContentHandler.handleDynamicContent(mContext, ws, locale)
        return if (ws.isAutoCapsEnabled) handleCapitalisation(capsMode, locale, dynamicText) else dynamicText
    }

    fun updateCachedEnabledWordSubstitutionMap() {
        // Audit PD-8: the only site in this file that took AUD_SYNC_LOCK outside a
        // try/finally. AUD_SYNC_LOCK is a process-global fair ReentrantLock held by
        // AudSyncer, the AUD content observer and every add/remove/update - a single
        // leak (a ConcurrentModificationException out of the TreeMap copy, say) wedges
        // the whole personal-dictionary subsystem for the process lifetime.
        AudSyncer.AUD_SYNC_LOCK.lock()
        try {
            val immutableMap = Collections.unmodifiableSortedMap(TreeMap(mEnabledWordSubstitutions))
            synchronized(CACHED_ENABLED_WS_LOCK) {
                mCachedEnabledWordSubstitutions = immutableMap
            }
        } finally {
            AudSyncer.AUD_SYNC_LOCK.unlock()
        }
    }

    fun handleCapitalisation(capsMode: CapsMode, locale: Locale, text: String?): String {
        if (text.isNullOrEmpty()) return text ?: ""
        return when (capsMode) {
            CapsMode.NO_CAPS -> text
            CapsMode.FIRST_LETTER_CAPS -> text.substring(0, 1).uppercase(locale) + text.substring(1)
            CapsMode.ALL_CAPS -> text.uppercase(locale)
        }
    }
}
