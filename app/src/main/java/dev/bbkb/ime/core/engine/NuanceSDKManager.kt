package dev.bbkb.ime.core.engine

import android.util.Log
import dev.bbkb.ime.core.ImeApplication
import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.shared.StartupTiming
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
     * Guards engine creation and disposal. Creation runs on the loader thread and disposal on the
     * engine thread ([EngineThreads]), so this orders the two against each other.
     */
    private val initLock = ReentrantLock()

    /**
     * Audit P-6: how long a caller waits for the primary engine's start on the loader thread
     * (normally the one [startPrimaryAsync] queued in onCreate) before giving up for this call.
     * Native init takes tens to a few hundred ms; this only guards against a wedged start turning
     * into a hang. A caller that times out gets null, the same answer as "the engine failed", and
     * nothing is recorded, so the next call joins again.
     */
    private const val INIT_JOIN_TIMEOUT_MS = 8_000L

    /** The same bound for the spell checker's engine, which may queue behind a language load. */
    private const val SECONDARY_CREATE_TIMEOUT_MS = 15_000L

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

    /**
     * Swipes whose `touchEnd` op the main thread has posted but the engine thread has not run yet.
     * The deposit seq is written by that op, so right after a swipe ends the main thread would
     * read "nothing pending" and skip the prediction request whenever suggestions are off (that
     * request is what commits the swiped word). Counted up on main at post time, down on the
     * engine thread when the op completes, accepted or not.
     */
    private val pendingGestureEnds = java.util.concurrent.atomic.AtomicInteger(0)

    /** Main thread, right before posting a `touchEnd` op. */
    @JvmStatic
    fun noteGestureEndPosted() {
        pendingGestureEnds.incrementAndGet()
    }

    /** Engine thread, at the end of the `touchEnd` op (after [noteGestureDeposit] when accepted). */
    @JvmStatic
    fun noteGestureEndResolved() {
        pendingGestureEnds.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /**
     * Main-thread meaning: a swipe has ended and its word may still be coming, so a prediction
     * request must be made and the strip must not be replaced by typed-word suggestions.
     */
    @JvmStatic
    fun isGesturePending(): Boolean {
        return pendingGestureEnds.get() > 0 || isGestureDepositPending()
    }

    /**
     * Engine-thread meaning, for the bridge: a deposit exists that no commit has consumed. The
     * bridge runs after the `touchEnd` op by FIFO order, so it never needs the posted count.
     */
    @JvmStatic
    fun isGestureDepositPending(): Boolean {
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
        // A published fact, not a wrapper call: callers ask from the main thread and from IO
        // coroutines, neither of which may enter the engine.
        return facts().isDLMReady
    }

    /**
     * The primary engine's published facts ([EngineFacts]), read without a lock and without
     * calling into the engine: safe on the main thread. [EngineFacts.NONE] while there is no
     * engine.
     */
    @JvmStatic
    fun facts(): EngineFacts = peekInstance()?.facts ?: EngineFacts.NONE
    
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
     * Audit P-6: the service queues the creation on the loader thread as the first thing in
     * onCreate ([startPrimaryAsync]). Creation always runs there, because the engine keeps
     * pointers into the stack of the thread that created it ([EngineThreads]): a caller on any
     * other thread queues a create-or-join op behind that start and waits for it, bounded by
     * [INIT_JOIN_TIMEOUT_MS]; the op finds the engine already published and only returns it.
     * Returning an already-published engine is free on any thread. Waiting is not allowed on the
     * main thread (debug builds throw), which will read [facts] instead once the callers move.
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
        EngineThreads.assertNotMain("NuanceSDKManager.getInstance join")
        val future = EngineThreads.load(Callable { createOrJoinPrimary() })
        return try {
            future.get(INIT_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // The op stays queued and publishes the engine when it runs; the next call finds it.
            Log.w(TAG, "NuanceSDK start still running after $INIT_JOIN_TIMEOUT_MS ms; " +
                    "continuing without the engine for this call")
            primaryInstance
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            primaryInstance
        } catch (e: ExecutionException) {
            Log.w(TAG, "NuanceSDK start failed", e.cause)
            primaryInstance
        }
    }

    /** The create-or-join half of [getInstance]; runs on the loader thread. */
    private fun createOrJoinPrimary(): NuanceSDK? {
        initLock.lock()
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
     * Audit P-6: start creating the primary engine on the loader thread so the native library
     * load and init overlap the rest of the service's onCreate. Returns at once.
     */
    @JvmStatic
    fun startPrimaryAsync() {
        if (primaryInstance != null) return
        EngineThreads.load(Runnable { getInstance() })
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
     * Neither engine is ever disposed while the process lives.
     *
     * Disposing the spell checker's engine while the typing engine is in use crashes the typing
     * engine on its next selection-list build (SIGSEGV in libnative-lib at 0xaf488; reproduced
     * twice on the emulator on 2026-11-04 by unbinding the spell checker and typing, and the
     * same address as the KEY2 crashes of §14 of the audit findings, which happened right after
     * the settings screen took the text field away from the spell checker). The engine keeps
     * process-global state behind the per-engine handle (the dispose of one handle frees memory
     * the other handle still dereferences); disposing the typing engine first did not crash the
     * spell checker's, but nothing says that order is safe either. So the instances are created
     * once and kept: an IME switch leaves the typing engine (and its loaded dictionaries, which
     * are clean file-backed pages for the built-in ones) for the next service instance, which
     * also saves that instance the engine init.
     *
     * Tests reset the published instances with [resetForTests]; they never hold a native engine.
     */
    @JvmStatic
    fun resetForTests() {
        initLock.lock()
        try {
            primaryInstance = null
            secondaryInstance = null
            primaryFailedAtNanos = 0L
            primaryRetryDelayMs = 0L
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
     * Get the secondary (spell-checker) NuanceSDK instance, creating it on first use.
     *
     * Created on the loader thread, like the primary: a caller elsewhere waits for that, bounded
     * by [SECONDARY_CREATE_TIMEOUT_MS] (not on the main thread; debug builds throw).
     *
     * @return NuanceSDK instance, or null if initialization failed or timed out
     */
    @JvmStatic
    fun getSecondary(): NuanceSDK? {
        secondaryInstance?.let { return it }
        EngineThreads.assertNotMain("NuanceSDKManager.getSecondary create")
        val future = EngineThreads.load(Callable { createSecondary() })
        return try {
            future.get(SECONDARY_CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "secondary NuanceSDK start still running after $SECONDARY_CREATE_TIMEOUT_MS ms")
            secondaryInstance
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            secondaryInstance
        } catch (e: ExecutionException) {
            Log.w(TAG, "secondary NuanceSDK start failed", e.cause)
            secondaryInstance
        }
    }

    /** The create half of [getSecondary]; runs on the loader thread. */
    private fun createSecondary(): NuanceSDK? {
        initLock.lock()
        try {
            if (secondaryInstance == null) {
                secondaryInstance = initializeNuanceSDK("secondary")
            }
            return secondaryInstance
        } finally {
            initLock.unlock()
        }
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
