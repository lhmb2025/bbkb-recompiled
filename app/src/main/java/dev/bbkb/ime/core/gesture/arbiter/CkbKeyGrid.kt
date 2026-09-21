package dev.bbkb.ime.core.gesture.arbiter

/**
 * The capacitive-keyboard letter grid, in the keypad's own normalized [0,1] coordinate space.
 *
 * The authoritative rectangles come from one of two sources (in priority): a committed
 * `<ckb-key-grid>` in the matched `device_config_<device>.xml`, or a runtime capture of ET9's
 * `NuanceSDK.getKeys()`. There is **no** reconstructed fallback — if neither is available the grid
 * is simply absent (the Gesture Lab hides the overlay). Cells are normalized so they're
 * independent of the actual pixel dimensions.
 */
data class CkbKey(
    /** Single-character label. */
    val label: String,
    /** Normalized center within the keypad rect [0,1]. */
    val cx: Float,
    val cy: Float,
    /** Normalized width/height within the keypad rect [0,1]. */
    val w: Float,
    val h: Float,
)

object CkbKeyGrid {

    /** Keypad coordinate-space dimensions for athena (BlackBerry Key2), from NuanceSDK.sPkbDimensionsMap. */
    const val WIDTH = 1080
    const val HEIGHT = 525

    /** Aspect ratio (w/h) of the keypad surface — used to letterbox the overlay accurately. */
    const val ASPECT = WIDTH.toFloat() / HEIGHT.toFloat()

    /** The letter key in [cells] whose cell contains the normalized keypad point, or null. */
    fun keyAt(cells: List<CkbKey>, nx: Float, ny: Float): CkbKey? = cells.firstOrNull { k ->
        nx >= k.cx - k.w / 2f && nx <= k.cx + k.w / 2f && ny >= k.cy - k.h / 2f && ny <= k.cy + k.h / 2f
    }

    /** A resolved grid plus where it came from (for the Lab readout). */
    data class Resolved(val cells: List<CkbKey>, val source: String)

    /**
     * The grid to use, or null if none is available:
     *  1. the matched device's `<ckb-key-grid>` (committed, authoritative — injectable per device),
     *  2. a runtime capture cached from ET9 (dev convenience),
     *  3. null — no grid; callers skip anything that needs one.
     */
    fun resolve(context: android.content.Context): Resolved? {
        val cfg = try {
            dev.bbkb.ime.core.device.profile.DeviceProfile.current().deviceMapping?.ckbKeyGridConfig
        } catch (e: Exception) {
            null
        }
        if (cfg != null && cfg.cells.isNotEmpty()) {
            return Resolved(cfg.cells.map { CkbKey(it.label, it.cx, it.cy, it.w, it.h) }, "device config")
        }
        CkbKeyGridCapture.loadCached(context)?.let { return Resolved(it, "captured") }
        return null
    }
}
