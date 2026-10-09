package dev.bbkb.ime.core.device.touch.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.shared.Logger

/**
 * Debug-build validation doorway for [ShizukuTouchEngine], in the spirit of
 * GestureReplayReceiver: one adb broadcast streams a device for a few seconds and logs every
 * decoded touch under the `ShizukuTouch` tag. Declared ONLY in app/src/debug/AndroidManifest.xml
 * (so release builds neither register nor, after R8, contain it), guarded by the DUMP
 * permission so only adb's shell can send it, and inert if a release build ever reached it.
 *
 * ```
 * adb shell am broadcast -p dev.bbkb.ime.debug -a dev.bbkb.ime.debug.SHIZUKU_TOUCH_PROBE \
 *     --es device touch_keypad --ez grab true --ei seconds 20
 * adb shell am broadcast -p dev.bbkb.ime.debug -a dev.bbkb.ime.debug.SHIZUKU_TOUCH_DEVICES
 * adb shell am broadcast -p dev.bbkb.ime.debug -a dev.bbkb.ime.debug.SHIZUKU_TOUCH_STOP
 * adb logcat -s ShizukuTouch
 * ```
 * `--es regex <pattern>` replaces `--es device <name>`. The probe asks Shizuku for permission
 * itself when it is missing (the dialog appears on the device).
 */
class ShizukuTouchProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        when (intent?.action) {
            ACTION_PROBE -> when (val parsed = ShizukuTouchProbeArgs.parse(extrasOf(intent))) {
                is ShizukuTouchProbeArgs.Parsed.Ok -> ShizukuTouchProbe.run(context, parsed.args)
                is ShizukuTouchProbeArgs.Parsed.Error -> Logger.warn(TAG, "probe not started: ${parsed.message}")
            }
            ACTION_DEVICES -> ShizukuTouchProbe.dumpDevices(context)
            ACTION_STOP -> ShizukuTouchProbe.finish("stopped by broadcast")
        }
    }

    @Suppress("DEPRECATION") // Bundle.get(String): the probe accepts whatever type adb sent.
    private fun extrasOf(intent: Intent): Map<String, Any?> {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet().associateWith { extras.get(it) }
    }

    companion object {
        private const val TAG = "ShizukuTouch"
        const val ACTION_PROBE = "${BuildConfig.APPLICATION_ID}.SHIZUKU_TOUCH_PROBE"
        const val ACTION_DEVICES = "${BuildConfig.APPLICATION_ID}.SHIZUKU_TOUCH_DEVICES"
        const val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.SHIZUKU_TOUCH_STOP"
    }
}

/** The probe broadcast's extras, parsed leniently (adb's --es/--ei/--ez all accepted). */
data class ShizukuTouchProbeArgs(
    val matcher: TouchDeviceMatcher,
    val grab: Boolean,
    val seconds: Int,
) {
    sealed class Parsed {
        data class Ok(val args: ShizukuTouchProbeArgs) : Parsed()
        data class Error(val message: String) : Parsed()
    }

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_REGEX = "regex"
        const val EXTRA_GRAB = "grab"
        const val EXTRA_SECONDS = "seconds"
        const val DEFAULT_SECONDS = 15
        const val MAX_SECONDS = 600

        @JvmStatic
        fun parse(extras: Map<String, Any?>): Parsed {
            val device = (extras[EXTRA_DEVICE] as? String)?.takeIf { it.isNotEmpty() }
            val regex = (extras[EXTRA_REGEX] as? String)?.takeIf { it.isNotEmpty() }
            val matcher = when {
                device != null && regex != null -> return Parsed.Error("give '$EXTRA_DEVICE' or '$EXTRA_REGEX', not both")
                device != null -> TouchDeviceMatcher.exact(device)
                regex != null -> TouchDeviceMatcher.parse(regex, isRegex = true)
                    ?: return Parsed.Error("'$EXTRA_REGEX' is not a valid pattern: $regex")
                else -> return Parsed.Error("missing '$EXTRA_DEVICE' (exact input device name) or '$EXTRA_REGEX'")
            }
            val grab = when (val raw = extras[EXTRA_GRAB]) {
                null -> false
                is Boolean -> raw
                is String -> when (raw.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    else -> return Parsed.Error("'$EXTRA_GRAB' must be true or false: $raw")
                }
                is Number -> raw.toInt() != 0
                else -> return Parsed.Error("'$EXTRA_GRAB' must be true or false: $raw")
            }
            val seconds = when (val raw = extras[EXTRA_SECONDS]) {
                null -> DEFAULT_SECONDS
                is Number -> raw.toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                is String -> raw.trim().toIntOrNull() ?: return Parsed.Error("'$EXTRA_SECONDS' is not a number: $raw")
                else -> return Parsed.Error("'$EXTRA_SECONDS' is not a number: $raw")
            }
            if (seconds <= 0) return Parsed.Error("'$EXTRA_SECONDS' must be positive: $seconds")
            return Parsed.Ok(ShizukuTouchProbeArgs(matcher, grab, seconds.coerceAtMost(MAX_SECONDS)))
        }
    }
}

/** The probe run itself: main thread only, one at a time. */
internal object ShizukuTouchProbe {

    private const val TAG = "ShizukuTouch"
    private val handler = Handler(Looper.getMainLooper())
    private val counts = IntArray(TouchAction.entries.size)
    private var running: ShizukuTouchProbeArgs? = null
    private var startedAt = 0L
    private var askedPermission = false
    private val timeout = Runnable { finish("timeout") }

    fun run(context: Context, args: ShizukuTouchProbeArgs) {
        if (running != null) finish("replaced")
        running = args
        startedAt = SystemClock.uptimeMillis()
        askedPermission = false
        counts.fill(0)
        Logger.info(TAG, "probe start device=${args.matcher} grab=${args.grab} seconds=${args.seconds}")
        val app = context.applicationContext
        ShizukuTouchEngine.start(
            app, args.matcher, args.grab,
            listener = { touch ->
                counts[touch.action.ordinal]++
                Logger.info(TAG, "touch ${touch.action} x=${touch.x} y=${touch.y}" +
                    " t=${touch.eventTimeMs} down=${touch.downTimeMs}" +
                    " lagMs=${SystemClock.uptimeMillis() - touch.eventTimeMs}")
            },
            statusListener = { status ->
                Logger.info(TAG, "status ${status.describe()}")
                if (status.state == ShizukuTouchState.NOT_GRANTED && !askedPermission) {
                    askedPermission = true
                    val asked = ShizukuTouchEngine.requestPermission(app)
                    Logger.info(TAG, if (asked) "asked Shizuku for permission: answer the dialog on the device"
                    else "no permission dialog possible: allow this app in the Shizuku app")
                }
            },
            touchHandler = null,
        )
        handler.postDelayed(timeout, args.seconds * 1000L)
    }

    fun finish(why: String) {
        val args = running ?: return
        running = null
        handler.removeCallbacks(timeout)
        ShizukuTouchEngine.stop()
        val total = counts.sum()
        Logger.info(TAG, "probe end ($why) device=${args.matcher} after " +
            "${SystemClock.uptimeMillis() - startedAt}ms: $total touches " +
            TouchAction.entries.joinToString(" ") { "${it.name}=${counts[it.ordinal]}" } +
            " final=${ShizukuTouchEngine.status.describe()}")
    }

    /**
     * Logs every input device the reader can open. A broadcast that has just started this
     * process arrives before Shizuku's binder does, so this waits (briefly) for Shizuku first.
     */
    fun dumpDevices(context: Context) {
        val app = context.applicationContext
        var done = false
        var asked = false
        lateinit var waiter: ShizukuTouchEngine.StatusListener
        val giveUp = Runnable {
            if (done) return@Runnable
            done = true
            ShizukuTouchEngine.removeStatusListener(waiter)
            Logger.info(TAG, "devices: unavailable (${ShizukuTouchEngine.status.describe()})")
        }
        waiter = ShizukuTouchEngine.StatusListener { status ->
            if (done) return@StatusListener
            if (status.state == ShizukuTouchState.NOT_GRANTED && !asked) {
                asked = true
                if (ShizukuTouchEngine.requestPermission(app)) {
                    Logger.info(TAG, "asked Shizuku for permission: answer the dialog on the device")
                }
            }
            if (!status.isAvailable) return@StatusListener
            done = true
            handler.removeCallbacks(giveUp)
            ShizukuTouchEngine.removeStatusListener(waiter)
            ShizukuTouchEngine.listDevices(app) { devices ->
                if (devices == null) {
                    Logger.info(TAG, "devices: unavailable (${ShizukuTouchEngine.status.describe()})")
                } else {
                    Logger.info(TAG, "devices: ${devices.size}")
                    devices.forEach { Logger.info(TAG, "device ${it.describe()}") }
                }
            }
        }
        handler.postDelayed(giveUp, AVAILABILITY_WAIT_MS)
        ShizukuTouchEngine.addStatusListener(app, waiter)
    }

    /** Long enough for Shizuku's binder to reach a freshly started process, and a dialog tap. */
    private const val AVAILABILITY_WAIT_MS = 20_000L
}
