package dev.bbkb.ime.core.gesture.arbiter

import android.content.Context
import dev.bbkb.ime.core.settings.PrefsManager
import com.blackberry.nuanceshim.KeyInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches the engine's real CKB key rectangles (`NuanceSDK.getKeys()` after `syncKeyboardLayout`;
 * ET9 computes them from the loaded KDB scaled to the keypad) normalized to [0,1], so the Gesture
 * Lab, where the engine isn't live, can show them instead of the reconstructed [CkbKeyGrid].
 */
object CkbKeyGridCapture {

    private const val PREF_KEY = "ckb_key_grid_cache_v2"

    private const val NOT_CAPTURED = -1L

    /** Packed `width shl 32 or height` (one field, so a concurrent read never sees half a pair). */
    @Volatile
    private var capturedFor: Long = NOT_CAPTURED

    /**
     * A cost cache only (`getKeys()` allocates 30 KeyInfo through JNI). Repeated reads are safe:
     * the blob's getKeys JNI shim at `0x20fc0` touches no engine or context state.
     */
    fun needsCapture(width: Int, height: Int): Boolean =
        capturedFor != geometryKey(normalizedWidth(width), normalizedHeight(height))

    private fun normalizedWidth(width: Int): Int = if (width > 0) width else CkbKeyGrid.width
    private fun normalizedHeight(height: Int): Int = if (height > 0) height else CkbKeyGrid.height
    private fun geometryKey(width: Int, height: Int): Long =
        (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)

    /** Called after the PKB layout sync. Best-effort; never throws. */
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
        PrefsManager.defaultSharedPreferences(context)
            .edit().putString(PREF_KEY, root.toString()).apply()
        capturedFor = geometryKey(wi, hi)
    }

    /** Null if nothing has been captured yet. */
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
        val s = PrefsManager.defaultSharedPreferences(context).getString(PREF_KEY, null) ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    /**
     * Writes a `<ckb-key-grid>` block for `device_config_<device>.xml` to the external files dir;
     * null if nothing captured yet (show the keyboard once first).
     */
    fun exportXml(context: Context): java.io.File? {
        val root = loadCachedRoot(context) ?: return null
        val cells = loadCached(context) ?: return null
        val w = root.optInt("w", CkbKeyGrid.width)
        val h = root.optInt("h", CkbKeyGrid.height)
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
