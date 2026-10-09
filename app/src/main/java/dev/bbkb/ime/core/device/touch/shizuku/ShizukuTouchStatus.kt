package dev.bbkb.ime.core.device.touch.shizuku

/**
 * Where the Shizuku touch engine stands, in the order a user climbs it. Everything from
 * [READY] up means Shizuku is usable; [BOUND] and [STREAMING] say how far the engine itself got.
 */
enum class ShizukuTouchState {
    /** API < 24 (Shizuku's own floor), or a Shizuku server older than v11. Nothing to offer. */
    UNSUPPORTED,
    /** The Shizuku manager app is not installed (and no Sui binder arrived either). */
    NOT_INSTALLED,
    /** Installed, but its server is not running (start it from the Shizuku app, by root or adb). */
    NOT_RUNNING,
    /** Running, but this app has not been granted access in Shizuku. */
    NOT_GRANTED,
    /** Usable; the reader service is not bound. */
    READY,
    /** The reader service (EvdevUserService) is bound, no device is streaming. */
    BOUND,
    /** A device is open and its touches are being delivered. */
    STREAMING,
}

/**
 * A snapshot of the engine's state, for a settings screen or a log line.
 *
 * [error] explains why the engine is lower than asked: a [ShizukuTouchErrors] value, or one of
 * them with ":<errno>" appended. It is cleared once the stream runs (apart from a grab failure,
 * which streams anyway).
 */
data class ShizukuTouchStatus(
    val state: ShizukuTouchState,
    val error: String? = null,
    /** The streaming device (with its ABS ranges and grab state), when [state] is STREAMING. */
    val device: TouchDeviceInfo? = null,
    /**
     * NOT_GRANTED only: the user chose "deny and don't ask again" in Shizuku, so a permission
     * request shows no dialog; access has to be granted from the Shizuku app itself.
     */
    val permissionDeniedForever: Boolean = false,
) {
    /** Shizuku is running and this app may use it. */
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
    const val API_TOO_OLD = "api_below_24"
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
