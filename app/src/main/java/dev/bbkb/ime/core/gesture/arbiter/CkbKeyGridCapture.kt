package dev.bbkb.ime.core.gesture.arbiter

import android.content.Context
import androidx.preference.PreferenceManager
import com.blackberry.nuanceshim.KeyInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Captures the **real** CKB key rectangles from the native Nuance/ET9 engine and caches them so
 * the Gesture Lab (which runs in the settings Activity, where Nuance isn't live) can display the
 * authoritative grid instead of the reconstructed [CkbKeyGrid].
 *
 * The rectangles can't be read statically from `libnative-lib.so` — ET9 computes them from a
 * loaded keyboard database scaled to the keypad size — but they're available at runtime via
 * `NuanceSDK.getKeys()` once the layout is synced. [capture] is called from the IME right after
 * `syncKeyboardLayout` (see KeyboardSwitcher), normalizes the letter keys into the keypad's
 * [0,1] coordinate space, and persists them; [loadCached] reads them back.
 *
 * [needsCapture] keeps the read off syncs that would only reproduce the grid already held. That
 * is a cost decision and not a safety one — see its own doc for the measurement that retired the
 * old "read the engine once per process" rule.
 */
object CkbKeyGridCapture {

    private const val PREF_KEY = "ckb_key_grid_cache_v2"

    private const val NOT_CAPTURED = -1L

    /**
     * The keypad geometry the cached grid was captured at, packed `width shl 32 or height`, or
     * [NOT_CAPTURED]. One field rather than two so a concurrent read can never see a half-updated
     * pair.
     */
    @Volatile
    private var capturedFor: Long = NOT_CAPTURED

    /**
     * Whether a capture is still needed for this keypad geometry.
     *
     * This is a **cost** cache, nothing more: `getKeys()` allocates 30 `KeyInfo` objects through
     * JNI and this whole feature only feeds the debug Gesture Lab's grid overlay, so there is no
     * reason to pay for it on a sync that would produce the grid we already hold.
     *
     * It used to be a process-level latch — read the engine at most once, ever — on the theory
     * that "repeated reads can perturb ET9's gesture recognition accuracy". That theory was a
     * 2026-06 candidate fix that was never assessed, and it is now measured and false: the blob's
     * JNI shim at `0x20fc0` neither reads nor writes any engine or context state (it calls
     * `ET9KDB_GetKeyPositions` once and then only JNI functions —
     * `docs/2026-09_kdb-touch-abi_audit/getkeys_jni_shim_0x20fc0.asm`), the owned
     * `ET9KDB_GetKeyPositions` is pure, and replaying 240 real swipes with 0, 1 and 20 `getKeys`
     * reads interleaved between every pair of touch stages gives byte-identical stored sample
     * counts and recognition (`xt9kdb-scaffold/cpp/test/getkeys_probe.sh`, audit §4.3).
     *
     * Dropping the latch also fixes a real staleness bug it caused: the first capture won forever,
     * so a grid captured at one keypad size was never refreshed when the size changed.
     */
    fun needsCapture(width: Int, height: Int): Boolean =
        capturedFor != geometryKey(normalizedWidth(width), normalizedHeight(height))

    private fun normalizedWidth(width: Int): Int = if (width > 0) width else CkbKeyGrid.WIDTH
    private fun normalizedHeight(height: Int): Int = if (height > 0) height else CkbKeyGrid.HEIGHT
    private fun geometryKey(width: Int, height: Int): Long =
        (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)

    /** Called from the IME after the PKB layout is synced. Best-effort; never throws. */
    fun capture(context: Context, keys: Array<KeyInfo>?, width: Int, height: Int) {
        if (keys.isNullOrEmpty()) return
        val wi = normalizedWidth(width)
        val hi = normalizedHeight(height)
        val w = wi.toFloat()
        val h = hi.toFloat()
        val arr = JSONArray()
        for (k in keys) {
            val label = letterLabel(k.keyCode) ?: continue
            arr.put(
                JSONObject()
                    .put("l", label)
                    .put("cx", k.x / w)
                    .put("cy", k.y / h)
                    .put("w", (k.right - k.left) / w)
                    .put("h", (k.bottom - k.top) / h)
            )
        }
        if (arr.length() == 0) return
        val root = JSONObject().put("w", wi).put("h", hi).put("keys", arr)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_KEY, root.toString()).apply()
        capturedFor = geometryKey(wi, hi) // success — skip the cost until the geometry changes
    }

    /** The cached real grid, or null if nothing has been captured yet. */
    fun loadCached(context: Context): List<CkbKey>? {
        val root = loadCachedRoot(context) ?: return null
        return try {
            val arr = root.getJSONArray("keys")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CkbKey(
                    o.getString("l"),
                    o.getDouble("cx").toFloat(),
                    o.getDouble("cy").toFloat(),
                    o.getDouble("w").toFloat(),
                    o.getDouble("h").toFloat(),
                )
            }.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadCachedRoot(context: Context): JSONObject? {
        val s = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null) ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    /**
     * Write the cached grid as a ready-to-paste `<ckb-key-grid>` block into the app's external
     * files dir, returning the file (or null if nothing captured yet). Paste the contents into the
     * matching `device_config_<device>.xml`. Capture happens in the IME, so bring up the keyboard
     * once before exporting.
     */
    fun exportXml(context: Context): java.io.File? {
        val root = loadCachedRoot(context) ?: return null
        val cells = loadCached(context) ?: return null
        val w = root.optInt("w", CkbKeyGrid.WIDTH)
        val h = root.optInt("h", CkbKeyGrid.HEIGHT)
        val sb = StringBuilder()
        sb.append("<ckb-key-grid width=\"").append(w).append("\" height=\"").append(h).append("\">\n")
        for (c in cells) {
            sb.append("    <key label=\"").append(c.label).append("\"")
                .append(" cx=\"").append("%.4f".format(c.cx)).append("\"")
                .append(" cy=\"").append("%.4f".format(c.cy)).append("\"")
                .append(" w=\"").append("%.4f".format(c.w)).append("\"")
                .append(" h=\"").append("%.4f".format(c.h)).append("\"/>\n")
        }
        sb.append("</ckb-key-grid>\n")
        val file = java.io.File(context.getExternalFilesDir(null), "ckb_key_grid_${android.os.Build.DEVICE}.xml")
        file.writeText(sb.toString())
        return file
    }

    private fun letterLabel(keyCode: Int): String? = when (keyCode) {
        in 'a'.code..'z'.code -> ('A' + (keyCode - 'a'.code)).toString()
        in 'A'.code..'Z'.code -> keyCode.toChar().toString()
        else -> null
    }
}
