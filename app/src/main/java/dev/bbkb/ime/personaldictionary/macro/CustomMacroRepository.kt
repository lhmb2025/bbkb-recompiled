package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.personaldictionary.util.LogUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * User-defined macros, stored as JSON in SharedPreferences. The parsed list is memoised
 * process-wide (one prefs file) because macro expansion runs on the commit path.
 */
class CustomMacroRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, 
        Context.MODE_PRIVATE
    )

    fun getAllMacros(): List<CustomMacro> {
        cached?.let { return it }
        val parsed = parseMacros()
        cached = parsed
        return parsed
    }

    private fun parseMacros(): List<CustomMacro> {
        val json = prefs.getString(KEY_MACROS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                CustomMacro(
                    tag = obj.getString("tag"),
                    name = obj.getString("name"),
                    value = obj.getString("value"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            LogUtil.e(TAG, "Custom macro store is corrupt; ignoring it: " + e)
            emptyList()
        }
    }

    fun getMacro(tag: String): CustomMacro? {
        return getAllMacros().find { it.tag == tag }
    }

    /** Adds the macro, or replaces the one with the same tag. */
    fun saveMacro(macro: CustomMacro) {
        val macros = getAllMacros().toMutableList()
        val existingIndex = macros.indexOfFirst { it.tag == macro.tag }
        if (existingIndex >= 0) {
            macros[existingIndex] = macro
        } else {
            macros.add(macro)
        }
        saveMacros(macros)
    }

    fun deleteMacro(tag: String) {
        val macros = getAllMacros().filterNot { it.tag == tag }
        saveMacros(macros)
    }

    /** Whether the tag is valid, not reserved and not already in use. */
    fun isTagAvailable(tag: String): Boolean {
        if (!CustomMacro.isTagValid(tag)) return false
        return getAllMacros().none { it.tag == tag }
    }

    /** As [isTagAvailable], but [currentTag] (the macro being edited) does not count as in use. */
    fun isTagAvailableForEdit(tag: String, currentTag: String): Boolean {
        if (!CustomMacro.isTagValid(tag)) return false
        return getAllMacros().none { it.tag == tag && it.tag != currentTag }
    }

    /** Replaces the whole set (backup restore, or reset with an empty list); updates the memo. */
    fun replaceAll(macros: List<CustomMacro>) {
        saveMacros(macros)
    }

    private fun saveMacros(macros: List<CustomMacro>) {
        val jsonArray = JSONArray()
        macros.forEach { macro ->
            val obj = JSONObject().apply {
                put("tag", macro.tag)
                put("name", macro.name)
                put("value", macro.value)
                put("createdAt", macro.createdAt)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_MACROS, jsonArray.toString()).apply()
        cached = macros.toList()
    }
    
    companion object {
        private const val TAG = "CustomMacroRepository"
        private const val PREFS_NAME = "custom_macros"
        private const val KEY_MACROS = "macros"

        /** Process-wide memo of the parsed list, written only through [saveMacros]. */
        @Volatile
        private var cached: List<CustomMacro>? = null

        /** Drops the memo; the next [getAllMacros] re-reads the store. */
        @JvmStatic
        fun invalidateCache() {
            cached = null
        }
    }
}
