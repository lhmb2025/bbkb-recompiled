package dev.bbkb.ime.core.engine

import android.util.Log
import dev.bbkb.ime.core.ImeApplication
import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.shared.StartupTiming
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
     * Guards engine creation and disposal. A [ReentrantLock] rather than a monitor so the main
     * thread's join on a background start can be bounded ([INIT_JOIN_TIMEOUT_MS]).
     */
    private val initLock = ReentrantLock()

    /**
     * Audit P-6: how long a caller waits for an engine start already running on another thread
     * (normally the one [startPrimaryAsync] queued in onCreate) before giving up for this call.
     * Native init takes tens to a few hundred ms; this only guards against a wedged start turning
     * into a hang. A caller that times out gets null, the same answer as "the engine failed", and
     * nothing is recorded, so the next call joins again.
     */
    private const val INIT_JOIN_TIMEOUT_MS = 8_000L

    /**
     * Audit F-7: a failed primary start used to be retried in full on EVERY [getInstance], and the
     * touch path calls it on each DOWN/MOVE/UP sample, so a broken engine re-ran the native init
     * on the main thread per sample. After a failure the next attempt waits this long, doubling
     * up to [RETRY_BACKOFF_MAX_MS]; [getInstance] answers null without trying in between.
     */
    private const val RETRY_BACKOFF_MIN_MS = 2_000L
    private const val RETRY_BACKOFF_MAX_MS = 60_000L

    /** Monotonic time of the last failed primary start; 0 = no failure since the last success. */
    @Volatile
    private var primaryFailedAtNanos = 0L

    /** The wait after [primaryFailedAtNanos] before the next attempt; 0 until a failure. */
    @Volatile
    private var primaryRetryDelayMs = 0L

    /**
     * The auto-commit handler for the primary engine. InputLogic hands it over from the service
     * constructor, where the engine must not be created yet (P-6); it is installed on the engine
     * as soon as the engine exists, and at once when it already does.
     */
    @Volatile
    private var primaryAutoCommitHandler: NuanceSDK.AutoCommitCallback? = null

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
     * Get the primary NuanceSDK instance, creating it on first use.
     *
     * Audit P-6: the service queues the creation on a worker as the first thing in onCreate
     * ([startPrimaryAsync]). A caller that arrives while that start is running joins it here (it
     * waits on [initLock], bounded by [INIT_JOIN_TIMEOUT_MS]); a caller that arrives when no start
     * was queued, or after the executor was shut down before running it, creates the engine itself.
     *
     * @return NuanceSDK instance, or null if initialization failed (or failed recently, see
     *   [RETRY_BACKOFF_MIN_MS]) or the join timed out
     */
    @JvmStatic
    fun getInstance(): NuanceSDK? {
        primaryInstance?.let { return it }
        // No context outside an Android process (JVM tests): nothing to try, nothing to back off.
        if (ImeApplication.getInstance() == null) return null
        if (inPrimaryBackoff()) return null
        val locked = try {
            initLock.tryLock(INIT_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!locked) {
            Log.w(TAG, "NuanceSDK start still running after $INIT_JOIN_TIMEOUT_MS ms; " +
                    "continuing without the engine for this call")
            return primaryInstance
        }
        try {
            primaryInstance?.let { return it }
            if (inPrimaryBackoff()) return null
            val token = StartupTiming.begin()
            val sdk = primaryFactory()
            StartupTiming.end("engine.primaryInit", token)
            if (sdk == null) {
                notePrimaryFailure()
                return null
            }
            primaryFailedAtNanos = 0L
            primaryRetryDelayMs = 0L
            // Installed before the instance is published, so no caller sees an engine without it;
            // and once more after, for a handler handed over in between (see
            // setPrimaryAutoCommitHandler, which publishes in the opposite order).
            val handler = primaryAutoCommitHandler
            if (handler != null) sdk.setAutoCommitCallbackHandler(handler)
            primaryInstance = sdk
            val latest = primaryAutoCommitHandler
            if (latest !== handler) sdk.setAutoCommitCallbackHandler(latest)
            return sdk
        } finally {
            initLock.unlock()
        }
    }

    /**
     * Audit P-6: start creating the primary engine on [executor] so the native library load and
     * init overlap the rest of the service's onCreate. Returns at once. A start that cannot be
     * queued (executor shut down) is skipped; the first [getInstance] then creates the engine.
     */
    @JvmStatic
    fun startPrimaryAsync(executor: Executor) {
        if (primaryInstance != null) return
        try {
            executor.execute { getInstance() }
        } catch (e: RejectedExecutionException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "background engine start rejected; the first use will create it")
        }
    }

    /**
     * Hands the primary engine its auto-commit handler without creating the engine (P-6): it is
     * installed now when the engine exists, otherwise when [getInstance] creates it.
     */
    @JvmStatic
    fun setPrimaryAutoCommitHandler(handler: NuanceSDK.AutoCommitCallback?) {
        primaryAutoCommitHandler = handler
        primaryInstance?.setAutoCommitCallbackHandler(handler)
    }

    /** Creates the primary engine. A seam for the start/join/backoff tests; production never sets it. */
    @androidx.annotation.VisibleForTesting
    internal var primaryFactory: () -> NuanceSDK? = { initializeNuanceSDK("primary") }

    private fun inPrimaryBackoff(): Boolean {
        val failedAt = primaryFailedAtNanos
        if (failedAt == 0L) return false
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - failedAt)
        return elapsedMs < primaryRetryDelayMs
    }

    private fun notePrimaryFailure() {
        val previous = primaryRetryDelayMs
        primaryRetryDelayMs = if (previous == 0L) RETRY_BACKOFF_MIN_MS
            else minOf(previous * 2, RETRY_BACKOFF_MAX_MS)
        primaryFailedAtNanos = System.nanoTime().let { if (it == 0L) 1L else it }
        Log.w(TAG, "NuanceSDK primary start failed; next attempt in ${primaryRetryDelayMs} ms")
    }

    /**
     * Dispose the primary NuanceSDK instance and release resources.
     */
    @JvmStatic
    fun disposePrimary() {
        initLock.lock()
        try {
            primaryInstance?.dispose()
            primaryInstance = null
            primaryFailedAtNanos = 0L
            primaryRetryDelayMs = 0L
        } finally {
            initLock.unlock()
        }
    }

    /**
     * Dispose the secondary (spell-checker) engine; the next [getSecondary] creates a fresh one.
     * The spell-checker service calls this when it unbinds or has been idle, so the up to four
     * dictionaries that engine holds (5 to 6 MB of native heap each) do not stay resident for
     * the life of the IME process.
     */
    @JvmStatic
    fun disposeSecondary() {
        initLock.lock()
        try {
            // Unpublished before it is disposed, so getSecondary/peekSecondary never hand out a
            // disposed engine.
            val engine = secondaryInstance
            secondaryInstance = null
            engine?.dispose()
        } finally {
            initLock.unlock()
        }
    }

    /** The primary engine if one has been created; never creates one. */
    @JvmStatic
    fun peekInstance(): NuanceSDK? = primaryInstance

    /** The secondary (spell-checker) engine if one has been created; never creates one. */
    @JvmStatic
    fun peekSecondary(): NuanceSDK? = secondaryInstance
    
    /**
     * Get the secondary NuanceSDK instance.
     * Lazily initializes on first access with comprehensive error handling.
     * 
     * @return NuanceSDK instance, or null if initialization failed
     */
    @JvmStatic
    fun getSecondary(): NuanceSDK? {
        if (secondaryInstance == null) {
            initLock.lock()
            try {
                if (secondaryInstance == null) {
                    secondaryInstance = initializeNuanceSDK("secondary")
                }
            } finally {
                initLock.unlock()
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
