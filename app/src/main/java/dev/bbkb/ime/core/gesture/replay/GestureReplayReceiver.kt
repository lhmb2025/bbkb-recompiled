package dev.bbkb.ime.core.gesture.replay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.shared.SystemProps
import dev.bbkb.ime.BuildConfig
import com.blackberry.nuanceshim.Xt9KdbVariant

/**
 * W2 replay doorway (debug builds only; master plan W2, owner-approved 2026-08-15).
 *
 * One broadcast = one gesture. The `trace` extra is "x,y,t;x,y,t;…" with t in ms (any epoch —
 * only the deltas matter; they are re-based onto uptimeMillis so the replayed MotionEvents carry
 * live timestamps with the ORIGINAL inter-sample spacing). `tag` labels the completion log line;
 * `speed` scales pacing (1.0 = real time). The status action logs every gate the CKB pipeline
 * checks, so the host harness can assert readiness.
 *
 * CKB traces (default) are injected into [BlackBerryIME.onGenericMotionEvent] — the exact entry
 * the capacitive pad's hardware events use — carrying the profile's touchKeypadDeviceId so the
 * isFromTouchKeypad gate passes identically to hardware. `surface=vkb` traces are dispatched as
 * real touchscreen MotionEvents to the live MainKeyboardView instead (see [replayVkbGestureTrace]).
 */
class GestureReplayReceiver(private val ime: BlackBerryIME) : BroadcastReceiver() {

    companion object {
        private const val TAG = "GESTREPLAY"
        /**
         * Debug broadcast actions, namespaced under whatever this build actually installs as
         * (`dev.bbkb.ime.debug` today). Derived rather than hardcoded so the harness and the
         * receiver can never drift apart: `tools/w2_replay.py` builds the same strings from its
         * `PKG`.
         */
        const val ACTION_GESTURE = "${BuildConfig.APPLICATION_ID}.REPLAY_GESTURE"
        const val ACTION_STATUS = "${BuildConfig.APPLICATION_ID}.REPLAY_STATUS"
        const val ACTION_SETUP = "${BuildConfig.APPLICATION_ID}.REPLAY_SETUP"

        /**
         * debug.et9.replayvkbpkg: package to VKB-force at process start (a sysprop is race-free
         * across the harness's per-epoch force-stops, unlike a broadcast, which can only reach an
         * already-running process).
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
        if (Build.VERSION.SDK_INT >= 33) ime.registerReceiver(this, filter, Context.RECEIVER_EXPORTED)
        else ime.registerReceiver(this, filter)
    }

    fun unregister() {
        try { ime.unregisterReceiver(this) } catch (_: IllegalArgumentException) {}
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
            // Setup: mirror the device's refreshOnScreenKeyboardShowing()=true state (on the KEY2
            // the visible aux bar's UIM term supplies it; the emulator has no UIM). A VKB-forced
            // target package makes the switcher build the SAME element family as the device
            // (VKB elements -> sync isTouchKb=false -> engine isPkb=true -> qwerty_pkb, the
            // 324-space flat KDB). In-memory only, so the harness re-broadcasts after every IME
            // restart.
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
     * VKB replay (surface=vkb): dispatch the trace as real touchscreen MotionEvents to the live
     * MainKeyboardView so the genuine on-screen pipeline runs (PointerTracker gesture detection →
     * batch input → engine feed with the SetKeyboardSize stretch). Points arrive in the CAPTURE
     * device's keyboard-view space (srcW×srcH — the KEY2 sitting logged SetKeyboardSize 1080×600)
     * and are rescaled proportionally to this view, which is exact because the VKB KDB is authored
     * at on-screen proportions and the engine restretches per view. Requires the on-screen
     * keyboard visible (debug_force_vkb_mode) and debug.et9.replayvkbpkg CLEAR so layout syncs
     * classify VKB — the CKB rig guard would otherwise pin the engine to the PKB frame.
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
