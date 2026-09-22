package dev.bbkb.ime.core.shared

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * A [Handler] that holds only a [WeakReference] to its owner, so a posted-but-undelivered
 * message cannot keep a destroyed view or service alive.
 *
 * It is the old AOSP `AsyncHandler` under a name that says what it does. (It was called
 * `CoroutineHandler` until 2026-09; there are and were no coroutines in it.)
 *
 * @param T The type of the owner instance
 * @param owner The owner instance to hold a weak reference to
 * @param looper The Looper to use for this handler (defaults to current thread's looper)
 */
open class WeakOwnerHandler<T : Any> : Handler {
    
    private val ownerRef: WeakReference<T>
    
    /**
     * Constructor with owner only (uses current thread's looper).
     */
    constructor(owner: T) : this(owner, Looper.myLooper() ?: throw IllegalStateException("No Looper available"))
    
    /**
     * Constructor with owner and explicit looper.
     */
    constructor(owner: T, looper: Looper) : super(looper) {
        ownerRef = WeakReference(owner)
    }
    
    /**
     * Get the owner instance if it hasn't been garbage collected.
     * Returns null if the owner has been collected.
     */
    fun getOwner(): T? = ownerRef.get()
}
