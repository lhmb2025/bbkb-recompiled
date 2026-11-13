package dev.bbkb.ime.core.device.touch.shizuku

/**
 * Shizuku's availability, kept current from Shizuku's callbacks with no polling. Not
 * thread-safe: calls and facade callbacks must share one thread. [onChange] fires only on change.
 */
class ShizukuStatusTracker(
    private val facade: ShizukuFacade,
    private val onChange: (ShizukuTouchStatus) -> Unit,
) : ShizukuFacade.Listener {
    var status: ShizukuTouchStatus = ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING)
        private set

    /** Called after [status] has been refreshed. */
    var onPermissionResult: ((requestCode: Int, granted: Boolean) -> Unit)? = null

    private var attached = false

    /** Idempotent. */
    fun attach() {
        if (attached) return
        attached = true
        facade.setListener(this)
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        facade.setListener(null)
    }

    fun refresh(): ShizukuTouchStatus {
        val next = compute()
        if (next != status) {
            status = next
            onChange(next)
        }
        return next
    }

    /** False when there is nothing to ask or Shizuku would show no dialog (denied forever). */
    fun requestPermission(requestCode: Int): Boolean {
        val current = refresh()
        if (current.state != ShizukuTouchState.NOT_GRANTED || current.permissionDeniedForever) return false
        return try {
            facade.requestPermission(requestCode)
            true
        } catch (unavailable: RuntimeException) {
            refresh()
            false
        }
    }

    override fun onBinderReceived() {
        refresh()
    }

    override fun onBinderDead() {
        refresh()
    }

    override fun onPermissionResult(requestCode: Int, granted: Boolean) {
        refresh()
        onPermissionResult?.invoke(requestCode, granted)
    }

    private fun compute(): ShizukuTouchStatus {
        if (!ask { facade.pingBinder() }) {
            return ShizukuTouchStatus(
                if (ask { facade.isManagerInstalled() }) ShizukuTouchState.NOT_RUNNING
                else ShizukuTouchState.NOT_INSTALLED,
            )
        }
        if (ask { facade.isPreV11() }) {
            return ShizukuTouchStatus(ShizukuTouchState.UNSUPPORTED, ShizukuTouchErrors.SERVER_TOO_OLD)
        }
        if (ask { facade.isPermissionGranted() }) return ShizukuTouchStatus(ShizukuTouchState.READY)
        return ShizukuTouchStatus(
            ShizukuTouchState.NOT_GRANTED,
            permissionDeniedForever = ask { facade.isPermissionDeniedForever() },
        )
    }

    /** Shizuku throws when its binder dies between two calls; that reads as "no". */
    private inline fun ask(call: () -> Boolean): Boolean = try {
        call()
    } catch (unavailable: RuntimeException) {
        false
    }
}

/** Exponential retry delays: [initialMs], doubling, capped at [maxMs]; [reset] after a success. */
class RetryBackoff(private val initialMs: Long = 250L, private val maxMs: Long = 30_000L) {

    var attempts: Int = 0
        private set

    fun nextDelayMs(): Long {
        val shift = attempts.coerceAtMost(20)
        attempts++
        return (initialMs shl shift).coerceAtMost(maxMs)
    }

    fun reset() {
        attempts = 0
    }
}
