package dev.bbkb.ime.core.gesture.arbiter

/** A capacitive-keyboard letter cell in the keypad's normalized [0,1] space (see [CkbKeyGrid.resolve]). */
data class CkbKey(
    val label: String,
    /** Center. */
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
)

object CkbKeyGrid {
    /** The active pad's frame width (1080 on the KEY2, which the root KDB is authored for). */
    @JvmStatic
    val width: Int
        get() = dev.bbkb.ime.core.device.touch.TouchKeypadGeometry.current().frameWidth()

    /** 525 on the KEY2. */
    @JvmStatic
    val height: Int
        get() = dev.bbkb.ime.core.device.touch.TouchKeypadGeometry.current().frameHeight()

    /** w/h, for letterboxing the overlay. */
    @JvmStatic
    val aspect: Float
        get() = dev.bbkb.ime.core.device.touch.TouchKeypadGeometry.current().frameAspect()

    fun keyAt(cells: List<CkbKey>, nx: Float, ny: Float): CkbKey? = cells.firstOrNull { k ->
        nx >= k.cx - k.w / 2f && nx <= k.cx + k.w / 2f && ny >= k.cy - k.h / 2f && ny <= k.cy + k.h / 2f
    }

    /** [source] is for the Lab readout. */
    data class Resolved(val cells: List<CkbKey>, val source: String)

    /**
     * The device profile's `<ckb-key-grid>`, else a runtime capture from ET9, else null (no
     * reconstructed fallback; the Lab hides the overlay).
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
