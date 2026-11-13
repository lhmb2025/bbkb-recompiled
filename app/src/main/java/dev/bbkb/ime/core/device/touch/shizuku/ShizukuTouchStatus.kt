package dev.bbkb.ime.core.device.touch.shizuku

/** The Shizuku touch engine's state, in climbing order; [READY] and above mean Shizuku is usable. */
enum class ShizukuTouchState {
    /** Shizuku server older than v11. */
    UNSUPPORTED,
    /** The Shizuku manager app is not installed (and no Sui binder arrived either). */
    NOT_INSTALLED,
    /** Installed, server not running. */
    NOT_RUNNING,
    /** Running, this app not granted access. */
    NOT_GRANTED,
    /** Usable; the reader service is not bound. */
    READY,
    /** EvdevUserService bound, nothing streaming. */
    BOUND,
    STREAMING,
}

/**
 * Snapshot of the engine's state. [error] is a [ShizukuTouchErrors] value (maybe with ":<errno>"),
 * cleared once streaming except for a grab failure, which streams anyway.
 */
data class ShizukuTouchStatus(
    val state: ShizukuTouchState,
    val error: String? = null,
    /** The streaming device (with its ABS ranges and grab state), when [state] is STREAMING. */
    val device: TouchDeviceInfo? = null,
    /** NOT_GRANTED only: denied forever, so access must be granted in the Shizuku app. */
    val permissionDeniedForever: Boolean = false,
) {
    val isAvailable: Boolean get() = state >= ShizukuTouchState.READY

    fun describe(): String = buildString {
        append(state.name)
        error?.let { append(" error=").append(it) }
        if (permissionDeniedForever) append(" deniedForever")
        device?.let { append(" device=").append(it.describe()) }
    }
}

/** Machine-readable [ShizukuTouchStatus.error] values. Not user-facing text. */
object ShizukuTouchErrors {
    const val SERVER_TOO_OLD = "shizuku_pre_v11"
    /** No input device's name matched; retried with backoff (the firmware may not have it yet). */
    const val NOT_FOUND = "not_found"
    /** The open device's node vanished; rediscovered by name with backoff. */
    const val DEVICE_GONE = "device_gone"
    /** The regex did not compile. Not retried. */
    const val BAD_PATTERN = "bad_pattern"
    const val BIND_FAILED = "bind_failed"
    /** Shizuku accepted the bind but the service never connected. */
    const val BIND_TIMEOUT = "bind_timeout"
    const val SERVICE_DIED = "service_died"
    /** A call into the reader service threw (it died mid-call, or a bug). */
    const val REMOTE_FAILED = "remote_failed"
    /** Prefixes, followed by ":<errno>". */
    const val OPEN_FAILED = "open_failed"
    const val GRAB_FAILED = "grab_failed"
    const val READ_ERROR = "read_error"
}
