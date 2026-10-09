package dev.bbkb.ime.core.device.touch.shizuku

/**
 * Shizuku's availability, from UNSUPPORTED up to READY, kept current from Shizuku's own
 * callbacks (binder received, binder dead, permission result) with no polling.
 *
 * Pure logic over a [ShizukuFacade]; not thread-safe, so the engine calls it, and has the
 * facade post its callbacks, on one thread. [onChange] fires only when the status differs from
 * the last one reported.
 */
class ShizukuStatusTracker(
    private val facade: ShizukuFacade,
    private val onChange: (ShizukuTouchStatus) -> Unit,
) : ShizukuFacade.Listener {

    /** The last computed availability; never above READY. */
    var status: ShizukuTouchStatus = ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING)
        private set

    /** Called with every Shizuku permission answer, after [status] has been refreshed. */
    var onPermissionResult: ((requestCode: Int, granted: Boolean) -> Unit)? = null

    private var attached = false

    /** Starts listening to Shizuku (idempotent) and computes the first status. */
    fun attach() {
        if (attached) return
        attached = true
        if (facade.sdkInt >= ShizukuFacade.MIN_SDK) facade.setListener(this)
        refresh()
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (facade.sdkInt >= ShizukuFacade.MIN_SDK) facade.setListener(null)
    }

    /** Recomputes [status], reporting it if it changed, and returns it. */
    fun refresh(): ShizukuTouchStatus {
        val next = compute()
        if (next != status) {
            status = next
            onChange(next)
        }
        return next
    }

    /**
     * Asks Shizuku to show its permission dialog. Returns false when there is nothing to ask
     * (already granted, Shizuku not running, unsupported) or Shizuku would not show a dialog
     * (denied forever: the user has to allow it in the Shizuku app).
     */
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
        if (facade.sdkInt < ShizukuFacade.MIN_SDK) {
            return ShizukuTouchStatus(ShizukuTouchState.UNSUPPORTED, ShizukuTouchErrors.API_TOO_OLD)
        }
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
