package dev.bbkb.ime.core.shared

import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/**
 * A [Handler] that holds only a [WeakReference] to its owner, so a pending message cannot keep a
 * destroyed view or service alive. Defaults to the current thread's looper.
 */
open class WeakOwnerHandler<T : Any> : Handler {
    
    private val ownerRef: WeakReference<T>

    constructor(owner: T) : this(owner, Looper.myLooper() ?: throw IllegalStateException("No Looper available"))

    constructor(owner: T, looper: Looper) : super(looper) {
        ownerRef = WeakReference(owner)
    }

    /** The owner, or null once it has been collected. */
    fun getOwner(): T? = ownerRef.get()
}
