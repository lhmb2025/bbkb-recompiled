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

/** Owns the two NuanceSDK engines: the primary (typing) and the secondary (spell checker). */
object NuanceSDKManager {
    
    private const val TAG = "NuanceSDKManager"

    @Volatile
    private var primaryInstance: NuanceSDK? = null

    @Volatile
    private var secondaryInstance: NuanceSDK? = null

    /** Orders engine creation (loader thread) against disposal (engine thread). */
    private val initLock = ReentrantLock()

    /**
     * How long a caller waits to join the primary start on the loader thread. Init takes up to a few
     * hundred ms; this only stops a wedged start from hanging. A timeout returns null, not recorded.
     */
    private const val INIT_JOIN_TIMEOUT_MS = 8_000L

    /** The same bound for the spell checker's engine, which may queue behind a language load. */
    private const val SECONDARY_CREATE_TIMEOUT_MS = 15_000L

    /**
     * Backoff after a failed primary start, doubling up to [RETRY_BACKOFF_MAX_MS]; [getInstance]
     * answers null meanwhile. The touch path calls it on every sample, so it must not retry each time.
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
     * Handed over from the service constructor, where the engine must not be created yet; installed
     * as soon as the engine exists.
     */
    @Volatile
    private var primaryAutoCommitHandler: NuanceSDK.AutoCommitCallback? = null

    /* Gesture pending is sequence-routed: native stamps every deposit (kdb_trace.c g_rank_seq) and
     * pending means the latest one is unconsumed, so a late commit of gesture N cannot clear N+1. */
    private val gestureDepositSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val gestureConsumedSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** Records the deposit; call after the native touchEnd/ProcessTrace. */
    @JvmStatic
    fun noteGestureDeposit() {
        val seq = com.blackberry.nuanceshim.Xt9KdbVariant.rankSeq()
        if (seq > 0) gestureDepositSeq.set(seq)
        noteRecognizerHealth(com.blackberry.nuanceshim.Xt9KdbVariant.recognizerUnavailable(), seq)
    }

    /** Set once per process when the owned module reports no recognizer (fatal until restart). */
    @PublishedApi
    internal var warnedRecognizerDead: Boolean = false

    /**
     * Warns once when swipe recognition is dead: `ET9KDB_TouchEnd` reports success whatever
     * happened, so this is the only Java-side signal.
     *
     * @param unavailable `Xt9KdbVariant.recognizerUnavailable()`; -1 means no owned library (normal
     *   for stock or DIFF builds), only a positive count is a fault
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
     * `touchEnd` ops posted but not yet run. Without it, main would see nothing pending right after
     * a swipe and skip the prediction request that commits the swiped word (when suggestions are off).
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

    /** Main thread: a swipe ended and its word may still be coming, so request predictions. */
    @JvmStatic
    fun isGesturePending(): Boolean {
        return pendingGestureEnds.get() > 0 || isGestureDepositPending()
    }

    /** Engine thread (the bridge): a deposit exists that no commit has consumed. */
    @JvmStatic
    fun isGestureDepositPending(): Boolean {
        return gestureDepositSeq.get() != gestureConsumedSeq.get()
    }

    /** Teardown: consumes whatever is pending; true if something was. */
    @JvmStatic
    fun consumeGesturePending(): Boolean {
        val d = gestureDepositSeq.get()
        return gestureConsumedSeq.getAndSet(d) != d
    }

    /**
     * Commit handler: consumes up to [seq] only, so a newer deposit stays pending; true if it was
     * still pending. seq <= 0 consumes all.
     */
    @JvmStatic
    fun consumeGestureSeq(seq: Int): Boolean {
        if (seq <= 0) return consumeGesturePending()
        while (true) {
            val c = gestureConsumedSeq.get()
            if (seq <= c) return false
            if (gestureConsumedSeq.compareAndSet(c, seq)) return true
        }
    }

    /** True once the primary engine exists; never creates it. */
    @JvmStatic
    fun isReady(): Boolean {
        return primaryInstance != null
    }

    /** True when a language is set and the DLM is loaded. */
    @JvmStatic
    fun isDLMReady(): Boolean {
        // A published fact: callers on main and IO coroutines may not enter the engine.
        return facts().isDLMReady
    }

    /** The primary engine's published facts, safe on main; [EngineFacts.NONE] without an engine. */
    @JvmStatic
    fun facts(): EngineFacts = peekInstance()?.facts ?: EngineFacts.NONE

    /** Sites already warned about a null SDK, so a load failure logs once, not per touch. */
    @PublishedApi
    internal val warnedSites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @PublishedApi
    internal fun warnSdkUnavailable(site: String) {
        if (warnedSites.add(site)) {
            Log.w(TAG, "NuanceSDK unavailable at $site — event dropped (warned once per site)")
        }
    }

    /** Runs [block] with the primary engine, or warns once per site when there is none. */
    inline fun withSdk(site: String, block: (NuanceSDK) -> Unit) {
        val sdk = sdkOrWarn(site)
        if (sdk != null) block(sdk)
    }

    /**
     * [withSdk] for Java callers: the primary engine or null, warning once per site. Touch call
     * sites must use this, since a keyboard with no engine should type without prediction, not crash.
     */
    @JvmStatic
    fun sdkOrWarn(site: String): NuanceSDK? {
        val sdk = getInstance()
        if (sdk == null) warnSdkUnavailable(site)
        return sdk
    }

    /**
     * The primary engine, created on first use. Creation always runs on the loader thread (the
     * engine keeps pointers into its creator's stack); other callers join, bounded by
     * [INIT_JOIN_TIMEOUT_MS]. Waiting is not allowed on main (debug builds throw).
     *
     * @return null if init failed (or failed recently) or the join timed out
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
            // Installed before publishing, and again after for a handler handed over in between
            // (setPrimaryAutoCommitHandler publishes in the opposite order).
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

    /** Starts creating the primary engine on the loader thread, overlapping onCreate; returns at once. */
    @JvmStatic
    fun startPrimaryAsync() {
        if (primaryInstance != null) return
        EngineThreads.load(Runnable { getInstance() })
    }

    /** Sets the auto-commit handler without creating the engine; installed when it exists. */
    @JvmStatic
    fun setPrimaryAutoCommitHandler(handler: NuanceSDK.AutoCommitCallback?) {
        primaryAutoCommitHandler = handler
        primaryInstance?.setAutoCommitCallbackHandler(handler)
    }

    /** Test seam for the start/join/backoff tests. */
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
     * Neither engine is ever disposed while the process lives: the engine keeps process-global
     * state behind each handle, and disposing one crashes the other on its next selection-list
     * build (SIGSEGV in libnative-lib at 0xaf488). Tests reset the published instances here.
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
     * The spell checker's engine, created on the loader thread on first use; callers wait up to
     * [SECONDARY_CREATE_TIMEOUT_MS] (never on main). Null if init failed or timed out.
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

    /** Creates an engine; null on any failure. */
    private fun initializeNuanceSDK(instanceType: String): NuanceSDK? {
        return try {
            if (BuildConfig.DEBUG) Log.i(TAG, "Starting NuanceSDK $instanceType initialization...")

            val context = ImeApplication.getInstance()
            if (context == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "ImeApplication context is null - cannot initialize $instanceType NuanceSDK")
                return null
            }
            
            if (BuildConfig.DEBUG) Log.i(TAG, "$instanceType context available: ${context.javaClass.simpleName}")
            if (BuildConfig.DEBUG) Log.i(TAG, "Assets dir: ${context.assets}")
            if (BuildConfig.DEBUG) Log.i(TAG, "Files dir: ${context.filesDir.absolutePath}")
            if (BuildConfig.DEBUG) Log.i(TAG, "NoBackup dir: ${context.noBackupFilesDir.absolutePath}")

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
