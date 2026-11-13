package dev.bbkb.ime.core.gesture.replay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.shared.SystemProps
import dev.bbkb.ime.BuildConfig
import com.blackberry.nuanceshim.Xt9KdbVariant

/**
 * Debug-only gesture replay: one broadcast is one gesture. `trace` is "x,y,t;…" (t in ms, any
 * epoch; re-based onto uptimeMillis keeping the spacing), `tag` labels the log line, `speed` scales
 * pacing. CKB traces go into [BlackBerryIME.onGenericMotionEvent] with the profile's
 * touchKeypadDeviceId, as hardware does; `surface=vkb` traces go to the MainKeyboardView.
 * The status action logs every CKB gate so the harness can assert readiness.
 */
class GestureReplayReceiver(private val ime: BlackBerryIME) : BroadcastReceiver() {

    companion object {
        private const val TAG = "GESTREPLAY"
        /** Namespaced by the installed application id; `tools/w2_replay.py` builds the same strings from its `PKG`. */
        const val ACTION_GESTURE = "${BuildConfig.APPLICATION_ID}.REPLAY_GESTURE"
        const val ACTION_STATUS = "${BuildConfig.APPLICATION_ID}.REPLAY_STATUS"
        const val ACTION_SETUP = "${BuildConfig.APPLICATION_ID}.REPLAY_SETUP"

        /**
         * debug.et9.replayvkbpkg: package to VKB-force at process start. A sysprop survives the
         * harness's force-stops; a broadcast only reaches a running process.
         */
        private fun applyVkbForcedPackageFromProp() {
            val p = SystemProps.get("debug.et9.replayvkbpkg")
            if (!p.isNullOrBlank()) {
                DeviceProfile.addVkbForcedPackage(p.trim())
                Logger.info(TAG, "vkb-forced package from prop: '${p.trim()}'")
            }
        }
    }

    fun register() {
        applyVkbForcedPackageFromProp()
        val filter = IntentFilter().apply {
            addAction(ACTION_GESTURE)
            addAction(ACTION_STATUS)
            addAction(ACTION_SETUP)
        }
        ContextCompat.registerReceiver(ime, this, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun unregister() {
        try { ime.unregisterReceiver(this) } catch (_: IllegalArgumentException) { /* was not registered */ }
    }

    override fun onReceive(c: Context?, intent: Intent?) {
        when (intent?.action) {
            ACTION_GESTURE -> {
                val trace = intent.getStringExtra("trace") ?: return
                val tag = intent.getStringExtra("tag") ?: "?"
                val speed = intent.getFloatExtra("speed", 1f).let { if (it > 0f) it else 1f }
                if (intent.getStringExtra("surface") == "vkb") {
                    replayVkbGestureTrace(trace, tag, speed,
                        intent.getFloatExtra("srcw", 1080f), intent.getFloatExtra("srch", 600f))
                } else {
                    replayGestureTrace(trace, tag, speed)
                }
            }
            ACTION_STATUS -> logReplayStatus()
            // Mirrors the device's refreshOnScreenKeyboardShowing()=true state (the emulator has no
            // UIM), so the engine gets qwerty_pkb, the 324-space flat KDB. In memory only: the
            // harness re-broadcasts after every IME restart.
            ACTION_SETUP -> intent.getStringExtra("vkbpkg")?.let {
                DeviceProfile.addVkbForcedPackage(it)
                Logger.info(TAG, "setup: vkb-forced package '$it'")
            }
        }
    }

    private fun logReplayStatus() {
        val p = DeviceProfile.current()
        val sv = ime.getSettingsManager().getSettingsValues()
        Logger.info(TAG, "status hasTouchKeypad=" + p.hasTouchKeypad() +
            " keypadDeviceId=" + p.touchKeypadDeviceId +
            " isPkb=" + p.isPkbDevice +
            " inputViewShown=" + ime.isInputViewShown() +
            " gestureReady=" + ime.isGestureInputReady() +
            " cursorMode=" + ime.isCursorModeEnabled +
            " ckbSwipeForLocale=" + sv.isCkbGestureInputEnabledForLocale +
            " kdbVariant='" + Xt9KdbVariant.applied() + "'")
    }

    private fun parseReplayTrace(trace: String, tag: String): ArrayList<FloatArray>? {
        val pts = ArrayList<FloatArray>(128)
        for (s in trace.split(';')) {
            if (s.isEmpty()) continue
            val c = s.split(',')
            if (c.size < 3) continue
            try {
                pts.add(floatArrayOf(c[0].toFloat(), c[1].toFloat(), c[2].toFloat()))
            } catch (_: NumberFormatException) {
                Logger.warn(TAG, "bad point '$s' tag=$tag — trace dropped"); return null
            }
        }
        if (pts.size < 3) { Logger.warn(TAG, "trace too short tag=$tag n=${pts.size}"); return null }
        return pts
    }

    /**
     * Posts one MotionEvent per sample at the original inter-sample spacing (scaled by [speed]).
     * [obtain] builds the event for (downTime, eventTime, action, x, y); [dispatch] delivers it.
     */
    private fun scheduleReplay(
        pts: List<FloatArray>, speed: Float, tag: String, devLabel: String,
        obtain: (Long, Long, Int, Float, Float) -> MotionEvent,
        dispatch: (MotionEvent) -> Unit,
    ) {
        val t0 = pts[0][2]
        // Small lead so the DOWN isn't already late by the time the posts are scheduled.
        val down = SystemClock.uptimeMillis() + 50
        val h = Handler(Looper.getMainLooper())
        for (i in pts.indices) {
            val at = down + ((pts[i][2] - t0) / speed).toLong()
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                pts.size - 1 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val x = pts[i][0]; val y = pts[i][1]
            h.postAtTime({
                val ev = obtain(down, at, action, x, y)
                try { dispatch(ev) } finally { ev.recycle() }
                if (action == MotionEvent.ACTION_UP)
                    Logger.info(TAG, "done tag=$tag n=${pts.size} devId=$devLabel")
            }, at)
        }
    }

    /**
     * Dispatches the trace as touchscreen events to the live MainKeyboardView, so the real
     * on-screen pipeline runs. Points in the capture view's srcW×srcH space are rescaled to this
     * view (exact: the engine restretches per view). Needs the on-screen keyboard visible and
     * debug.et9.replayvkbpkg clear, or the CKB rig guard pins the engine to the PKB frame.
     */
    private fun replayVkbGestureTrace(trace: String, tag: String, speed: Float, srcW: Float, srcH: Float) {
        val pts = parseReplayTrace(trace, tag) ?: return
        val view = ime.getKeyboardSwitcher().getMainKeyboardView()
        if (view == null || view.width == 0 || view.height == 0) {
            Logger.warn(TAG, "vkb replay: no visible keyboard view (tag=$tag)"); return
        }
        val sx = view.width / srcW
        val sy = view.height / srcH
        scheduleReplay(pts, speed, tag, "vkb",
            obtain = { down, at, action, x, y ->
                MotionEvent.obtain(down, at, action, x * sx, y * sy, 0).also {
                    it.source = InputDevice.SOURCE_TOUCHSCREEN
                }
            },
            dispatch = { view.dispatchTouchEvent(it) })
    }

    private fun replayGestureTrace(trace: String, tag: String, speed: Float) {
        val pts = parseReplayTrace(trace, tag) ?: return
        val devId = DeviceProfile.current().touchKeypadDeviceId
        scheduleReplay(pts, speed, tag, devId.toString(),
            obtain = { down, at, action, x, y ->
                MotionEvent.obtain(down, at, action, x, y, 1.0f, 0.1f, 0, 1f, 1f, devId, 0).also {
                    it.source = InputDevice.SOURCE_TOUCHPAD
                }
            },
            dispatch = { ime.onGenericMotionEvent(it) })
    }
}
