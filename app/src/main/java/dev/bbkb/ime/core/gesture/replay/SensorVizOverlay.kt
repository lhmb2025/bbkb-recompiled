package dev.bbkb.ime.core.gesture.replay

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.gesture.replay.SensorViz
import dev.bbkb.ime.core.gesture.replay.SensorVizView
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.settings.PrefsManager

/**
 * Debug-only in-IME sensor visualizer (see [SensorVizView]). The view is added as a content view
 * on top of the IME window so it never disturbs the keyboard view hierarchy / inset handling.
 */
class SensorVizOverlay(private val ime: BlackBerryIME) {

    private var view: SensorVizView? = null

    /** Re-read the activation pref (once per input session) and hide the overlay when it is off. */
    fun syncActiveFromPrefs() {
        SensorViz.active = PrefsManager.getPrefs(ime).getBoolean(SensorViz.PREF_KEY, false)
        if (!SensorViz.active) view?.visibility = View.GONE
    }

    /**
     * When active, forward every raw keypad sample (including batched history) to the overlay
     * and return true so the caller swallows the event (typing is suppressed while mapping the
     * capacitive strips). Returns false, doing nothing, when the visualizer is off.
     */
    fun forwardIfActive(event: MotionEvent): Boolean {
        if (!SensorViz.active) return false
        ensureView()
        val v = view
        if (v != null && v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
        for (h in 0 until event.historySize) {
            val hx = event.getHistoricalX(h); val hy = event.getHistoricalY(h)
            SensorViz.addPoint(hx, hy)
            v?.plot(hx, hy)
        }
        SensorViz.addPoint(event.x, event.y)
        v?.plot(event.x, event.y)
        return true
    }

    private fun ensureView() {
        val existing = view
        if (existing != null && existing.windowToken != null) return
        val w = ime.window?.window ?: return
        try {
            val viz = SensorVizView(ime)
            viz.visibility = View.GONE
            w.addContentView(
                viz,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
            view = viz
        } catch (e: Throwable) {
            Logger.warn(BlackBerryIME.LOG_TAG, "SensorViz overlay add failed: ${e.message}")
        }
    }
}
