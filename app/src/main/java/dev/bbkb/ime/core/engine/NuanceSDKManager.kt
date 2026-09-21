package dev.bbkb.ime.core.engine

import android.util.Log
import dev.bbkb.ime.core.ImeApplication
import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.BuildConfig

/**
 * Singleton manager for NuanceSDK instances.
 * Converted from Java static accessor pattern to Kotlin object for cleaner singleton implementation.
 * 
 * Provides lazy initialization with comprehensive error handling for both primary and secondary
 * NuanceSDK instances used for dictionary operations.
 */
object NuanceSDKManager {
    
    private const val TAG = "NuanceSDKManager"
    
    /**
     * Primary NuanceSDK instance for main dictionary operations.
     * Lazily initialized on first access with comprehensive error handling.
     */
    @Volatile
    private var primaryInstance: NuanceSDK? = null
    
    /**
     * Secondary NuanceSDK instance for additional dictionary operations.
     * Lazily initialized on first access with comprehensive error handling.
     */
    @Volatile
    private var secondaryInstance: NuanceSDK? = null
    
    /**
     * Lock object for thread-safe initialization.
     */
    private val lock = Any()

    /**
     * Set to true after NuanceSDK.touchEnd() returns true (gesture accepted) and cleared
     * after the gesture word is committed via MSG_BATCH_INPUT_SUGGESTIONS.
     * Guards setContextBuffer() in NuanceDictionary.getSuggestions — calling setContextBuffer
     * after touchEnd resets the native engine's gesture state, causing buildSelectionList()
     * to return ambient predictions instead of the gesture word.
     */
    /* §8.7.11c: gesture-pending is SEQUENCE-ROUTED, not a boolean. The native side stamps every
     * deposit (kdb_trace.c g_rank_seq); "pending" means the latest deposit has not been consumed.
     * The boolean it replaces had a fatal steal: consumeGesturePending() in the commit handler was
     * getAndSet(false), so gesture N's commit (main thread, late) could lower the flag gesture
     * N+1's deposit had just raised — N+1's request then took the non-gesture branch and its
     * defensive clear() wiped the freshly fed sets (log §8.7.11). */
    private val gestureDepositSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val gestureConsumedSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Serializes the main thread's [touchEnd + setGesturePending] against the suggestion
     * worker's [read isGesturePending + defensive clear()] in NuanceDictionary.getSuggestions.
     * Without it there is a window where the worker reads pending=false, the gesture then
     * deposits its native state and sets pending=true, and the worker's clear() wipes that
     * state before the flag can protect it -- the engine then returns ambient predictions
     * instead of the swiped word. Both sides take this lock, so a clear either runs before
     * the deposit (harmless, no state yet) or sees the flag and skips.
     */
    private val gestureLock = Any()

    @JvmStatic
    fun getGestureLock(): Any = gestureLock

    /** Record the just-completed deposit (call AFTER the native touchEnd/ProcessTrace). */
    @JvmStatic
    fun noteGestureDeposit() {
        val seq = com.blackberry.nuanceshim.Xt9KdbVariant.rankSeq()
        if (seq > 0) gestureDepositSeq.set(seq)
        noteRecognizerHealth(com.blackberry.nuanceshim.Xt9KdbVariant.recognizerUnavailable(), seq)
    }

    /**
     * AUDIT L10: raised once per process the first time the owned module reports that it could
     * not reach a recognizer at all. Not a counter — the condition it names is fatal for the
     * whole process, so one line is the entire signal and a line per swipe would only bury it.
     * Writable from tests, which have to start from a known state.
     */
    @PublishedApi
    internal var warnedRecognizerDead: Boolean = false

    /**
     * AUDIT L10 (docs/2026-09_kdb-touch-abi_audit.md §5). [noteGestureDeposit] is the only place
     * in Java that runs immediately after an ACCEPTED gesture, so it is the one place that can
     * tell "the engine took a swipe" from "the engine cannot take swipes at all". It has to be
     * told, because `ET9KDB_TouchEnd` is required to report success whatever happened — the
     * blob's JNI shim maps any non-zero status to `false` — so the failure is otherwise a native
     * logcat line and nothing more. That is how a KEY2 build shipped in September 2026 with swipe
     * dead for the life of every process.
     *
     * @param unavailable `Xt9KdbVariant.recognizerUnavailable()`. **-1 means there is no owned
     *   library in this build**, which is the normal state of a stock or DIFF build and must not
     *   warn; only a positive count is a fault.
     */
    @PublishedApi
    internal fun noteRecognizerHealth(unavailable: Int, seq: Int) {
        if (unavailable <= 0 || warnedRecognizerDead) return
        warnedRecognizerDead = true
        Log.w(TAG, "gesture recognition is UNAVAILABLE in this process ($unavailable failed " +
                "deposit(s); native deposit seq stuck at $seq). Swipe will produce nothing until " +
                "the process restarts — see the XT9KDB log for the reason, and audit L10.")
    }

    @JvmStatic
    fun isGesturePending(): Boolean {
        return gestureDepositSeq.get() != gestureConsumedSeq.get()
    }

    /** Teardown semantics: consume WHATEVER is pending (session end invalidates all gestures).
     * Returns true if something was pending. */
    @JvmStatic
    fun consumeGesturePending(): Boolean {
        val d = gestureDepositSeq.get()
        return gestureConsumedSeq.getAndSet(d) != d
    }

    /** Commit-handler semantics: consume ONLY the given gesture's sequence. Returns true if that
     * gesture was still pending (i.e. its session was not torn down); a deposit NEWER than seq
     * stays pending — the §8.7.11 steal is structurally impossible. seq<=0 = legacy payload,
     * falls back to consume-all. */
    @JvmStatic
    fun consumeGestureSeq(seq: Int): Boolean {
        if (seq <= 0) return consumeGesturePending()
        while (true) {
            val c = gestureConsumedSeq.get()
            if (seq <= c) return false
            if (gestureConsumedSeq.compareAndSet(c, seq)) return true
        }
    }

    /**
     * Check if the primary NuanceSDK instance is initialized and ready for use.
     * Does NOT trigger initialization - only checks current state.
     * 
     * @return true if primary instance is initialized and ready
     */
    @JvmStatic
    fun isReady(): Boolean {
        return primaryInstance != null
    }
    
    /**
     * Check if the DLM (Dynamic Learning Model) is ready for word operations.
     * This is a stricter check than isReady() - it verifies that language has been
     * set and the DLM file is loaded.
     * 
     * @return true if NuanceSDK is initialized AND DLM is ready for word operations
     */
    @JvmStatic
    fun isDLMReady(): Boolean {
        val instance = primaryInstance ?: return false
        return instance.isDLMReady()
    }
    
    /**
     * Sites that already warned about a null SDK, so a persistent load failure
     * doesn't spam the log once per touch event.
     */
    @PublishedApi
    internal val warnedSites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @PublishedApi
    internal fun warnSdkUnavailable(site: String) {
        if (warnedSites.add(site)) {
            Log.w(TAG, "NuanceSDK unavailable at $site — event dropped (warned once per site)")
        }
    }

    /**
     * Single choke point for "SDK may not have loaded" call sites: runs [block] with the
     * primary instance, or logs a warn-once-per-site when it is unavailable. Keeps touch
     * handling behavior uniform under SDK-load failure instead of a mix of silent no-ops
     * and ad-hoc null checks.
     */
    inline fun withSdk(site: String, block: (NuanceSDK) -> Unit) {
        val sdk = sdkOrWarn(site)
        if (sdk != null) block(sdk)
    }

    /**
     * The same choke point as [withSdk], shaped for Java callers: returns the primary instance or
     * null, warning once per site on the way. [withSdk] is an inline function taking a lambda, so
     * from Java it would mean `INSTANCE.withSdk(site, sdk -> { ...; return Unit.INSTANCE; })` —
     * and it returns Unit, which the touch path cannot use (`touchEnd`'s boolean is the caller's
     * gestureAccepted).
     *
     * Audit L8: every touch call site used to dereference [getInstance] straight through
     * (PointerTracker, GestureEventProcessor), so an engine that failed to load threw NPE on the
     * IME main thread for every DOWN/MOVE/UP — while the layout sync beside them was already
     * null-checked ("FIX-D2", KeyboardSwitcher). A keyboard with no engine should type without
     * prediction, not crash.
     */
    @JvmStatic
    fun sdkOrWarn(site: String): NuanceSDK? {
        val sdk = getInstance()
        if (sdk == null) warnSdkUnavailable(site)
        return sdk
    }

    /**
     * Get the primary NuanceSDK instance.
     * Lazily initializes on first access with comprehensive error handling.
     *
     * @return NuanceSDK instance, or null if initialization failed
     */
    @JvmStatic
    fun getInstance(): NuanceSDK? {
        if (primaryInstance == null) {
            synchronized(lock) {
                if (primaryInstance == null) {
                    primaryInstance = initializeNuanceSDK("primary")
                }
            }
        }
        return primaryInstance
    }
    
    /**
     * Dispose the primary NuanceSDK instance and release resources.
     */
    @JvmStatic
    fun disposePrimary() {
        synchronized(lock) {
            primaryInstance?.dispose()
            primaryInstance = null
        }
    }
    
    /**
     * Get the secondary NuanceSDK instance.
     * Lazily initializes on first access with comprehensive error handling.
     * 
     * @return NuanceSDK instance, or null if initialization failed
     */
    @JvmStatic
    fun getSecondary(): NuanceSDK? {
        if (secondaryInstance == null) {
            synchronized(lock) {
                if (secondaryInstance == null) {
                    secondaryInstance = initializeNuanceSDK("secondary")
                }
            }
        }
        return secondaryInstance
    }
    
    /**
     * Initialize a NuanceSDK instance with comprehensive error handling.
     * 
     * @param instanceType Type of instance being initialized ("primary" or "secondary")
     * @return NuanceSDK instance, or null if initialization failed
     */
    private fun initializeNuanceSDK(instanceType: String): NuanceSDK? {
        return try {
            if (BuildConfig.DEBUG) Log.i(TAG, "Starting NuanceSDK $instanceType initialization...")
            
            // Check context availability
            val context = ImeApplication.getInstance()
            if (context == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "ImeApplication context is null - cannot initialize $instanceType NuanceSDK")
                return null
            }
            
            if (BuildConfig.DEBUG) Log.i(TAG, "$instanceType context available: ${context.javaClass.simpleName}")
            if (BuildConfig.DEBUG) Log.i(TAG, "Assets dir: ${context.assets}")
            if (BuildConfig.DEBUG) Log.i(TAG, "Files dir: ${context.filesDir.absolutePath}")
            if (BuildConfig.DEBUG) Log.i(TAG, "NoBackup dir: ${context.noBackupFilesDir.absolutePath}")
            
            // Attempt NuanceSDK initialization
            val sdk = NuanceSDK(context)
            if (BuildConfig.DEBUG) Log.i(TAG, "NuanceSDK $instanceType initialization successful: $sdk")
            sdk
            
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) Log.e(TAG, "NuanceSDK $instanceType IllegalStateException during init", e)
            if (BuildConfig.DEBUG) Log.e(TAG, "Exception message: ${e.message}")
            if (BuildConfig.DEBUG) Log.e(TAG, "Exception cause: ${e.cause}")
            null
        } catch (e: UnsatisfiedLinkError) {
            if (BuildConfig.DEBUG) Log.e(TAG, "NuanceSDK $instanceType UnsatisfiedLinkError - native library issue", e)
            if (BuildConfig.DEBUG) Log.e(TAG, "Library error message: ${e.message}")
            null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(TAG, "NuanceSDK $instanceType unexpected exception during init", e)
            if (BuildConfig.DEBUG) Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            if (BuildConfig.DEBUG) Log.e(TAG, "Exception message: ${e.message}")
            null
        } catch (e: Error) {
            if (BuildConfig.DEBUG) Log.e(TAG, "NuanceSDK $instanceType fatal error during init", e)
            if (BuildConfig.DEBUG) Log.e(TAG, "Error type: ${e.javaClass.simpleName}")
            if (BuildConfig.DEBUG) Log.e(TAG, "Error message: ${e.message}")
            null
        }
    }
}
