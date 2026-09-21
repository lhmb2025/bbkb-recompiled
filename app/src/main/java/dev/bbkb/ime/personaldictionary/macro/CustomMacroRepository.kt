package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.personaldictionary.util.LogUtil
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for storing and retrieving user-defined custom macros.
 * Uses SharedPreferences with JSON serialization.
 *
 * The parsed list is memoised process-wide (the backing store is a single
 * `MODE_PRIVATE` prefs file, so every instance sees the same data) and dropped on
 * write. Without it `getAllMacros()` re-parsed the whole JSON array on every macro
 * expansion, i.e. once per candidate word on the commit path.
 */
class CustomMacroRepository(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, 
        Context.MODE_PRIVATE
    )
    
    /**
     * Returns all custom macros defined by the user.
     */
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
    
    /**
     * Returns a specific macro by its tag, or null if not found.
     */
    fun getMacro(tag: String): CustomMacro? {
        return getAllMacros().find { it.tag == tag }
    }
    
    /**
     * Saves a macro. If a macro with the same tag exists, it will be updated.
     */
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
    
    /**
     * Deletes a macro by its tag.
     */
    fun deleteMacro(tag: String) {
        val macros = getAllMacros().filterNot { it.tag == tag }
        saveMacros(macros)
    }
    
    /**
     * Checks if a tag is available (not reserved and not already in use).
     */
    fun isTagAvailable(tag: String): Boolean {
        if (!CustomMacro.isTagValid(tag)) return false
        return getAllMacros().none { it.tag == tag }
    }
    
    /**
     * Checks if a tag is available for editing (not reserved and not used by another macro).
     */
    fun isTagAvailableForEdit(tag: String, currentTag: String): Boolean {
        if (!CustomMacro.isTagValid(tag)) return false
        return getAllMacros().none { it.tag == tag && it.tag != currentTag }
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

        /**
         * Process-wide memo of the parsed macro list. Written only through
         * [saveMacros], so any repository instance that persists a change publishes
         * the new list to every other reader.
         */
        @Volatile
        private var cached: List<CustomMacro>? = null

        /** Drops the memo; the next [getAllMacros] re-reads the store. */
        @JvmStatic
        fun invalidateCache() {
            cached = null
        }
    }
}
