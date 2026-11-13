package dev.bbkb.ime.personaldictionary.storage

import android.content.Context
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import org.json.JSONArray
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.*

/**
 * Loads the built-in word substitutions from macros.json assets, falling back from
 * language_country to language, plus the base set; user-deleted keys are skipped.
 */
object DefaultWSLoader {
    private const val TAG = "DefaultWSLoader"

    /**
     * Memo of parsed sets (callers run under AUD_SYNC_LOCK). Assets never change and the
     * deleted keys are part of the key; callers treat the map as read-only.
     */
    private val defaultsCache = android.util.LruCache<String, Map<String, WordSubstitution>>(8)

    private fun cacheKey(
        baseDir: String,
        locales: List<Locale>,
        deletedKeys: Set<String>,
        loadBaseMacros: Boolean
    ): String = buildString {
        append(baseDir).append('|')
        for (locale in locales) {
            append(locale.toString()).append(',')
        }
        append('|').append(loadBaseMacros).append('|').append(deletedKeys.hashCode())
    }

    /** Drops the memo; call after the deleted-defaults set is rewritten wholesale. */
    @JvmStatic
    fun invalidateCache() {
        defaultsCache.evictAll()
    }

    fun load(
        context: Context,
        baseDir: String,
        locales: List<Locale>,
        deletedKeys: Set<String>,
        loadBaseMacros: Boolean
    ): Map<String, WordSubstitution> {
        require(baseDir.isNotEmpty()) { "baseDir must not be empty in DefaultWsLoader.load" }
        require(locales.isNotEmpty())

        val key = cacheKey(baseDir, locales, deletedKeys, loadBaseMacros)
        defaultsCache.get(key)?.let { return it }

        val map = TreeMap<String, WordSubstitution>(String.CASE_INSENSITIVE_ORDER)
        for (locale in locales) {
            if (locale.variant.isNotEmpty() && isLocaleWithLanguageAndCountry(locale)) {
                try {
                    loadLocaleWithVariants(context, map, baseDir, locale, deletedKeys)
                } catch (e: IOException) {
                    LogUtil.d(TAG, "File for:$locale cannot be loaded")
                    loadLocaleWithLanguageCountry(context, map, baseDir, locale, deletedKeys)
                }
            } else {
                loadLocaleWithLanguageCountry(context, map, baseDir, locale, deletedKeys)
            }
        }
        if (loadBaseMacros) {
            try {
                putWSFromFileIntoMap(context, map, baseDir, PersonalDictionaryConstants.LOCALE_ALL, deletedKeys)
            } catch (e: IOException) {
                LogUtil.d(TAG, "Base macros.json cannot be loaded")
            }
        }
        defaultsCache.put(key, map)
        return map
    }

    @Throws(IOException::class)
    private fun loadLocaleWithVariants(
        context: Context,
        map: MutableMap<String, WordSubstitution>,
        baseDir: String,
        locale: Locale,
        deletedKeys: Set<String>
    ) {
        val localeStr = locale.toString()
        val path = baseDir + File.separator + localeStr
        LogUtil.d(TAG, "Loading file:$path")
        putWSFromFileIntoMap(context, map, path, localeStr, deletedKeys)
    }

    private fun loadLocaleWithLanguageCountry(
        context: Context,
        map: MutableMap<String, WordSubstitution>,
        baseDir: String,
        locale: Locale,
        deletedKeys: Set<String>
    ) {
        val path = baseDir + File.separator + getFullLocaleStringWithoutVariant(locale)
        try {
            LogUtil.d(TAG, "Loading file:$path")
            putWSFromFileIntoMap(context, map, path, locale.toString(), deletedKeys)
        } catch (e: IOException) {
            val language = locale.language
            val langPath = baseDir + File.separator + language
            LogUtil.d(TAG, "File for $locale cannot be loaded. Trying: $langPath")
            try {
                putWSFromFileIntoMap(context, map, langPath, locale.toString(), deletedKeys)
            } catch (e2: IOException) {
                LogUtil.d(TAG, "File for $language cannot be loaded")
            }
        }
    }

    @Throws(IOException::class)
    private fun putWSFromFileIntoMap(
        context: Context,
        map: MutableMap<String, WordSubstitution>,
        dirPath: String,
        localeStr: String,
        deletedKeys: Set<String>
    ) {
        val filePath = dirPath + File.separator + PersonalDictionaryConstants.DEFAULT_MACROS_FILENAME
        // Outside the try: a missing file must throw so callers fall back to the language-only file.
        val jsonContent = FileUtils.readAndroidAssetToString(context, filePath)
        try {
            val lines = JSONArray(jsonContent)

            for (i in 0 until lines.length()) {
                // A JSON array of strings; a number or boolean is read as its text (as Gson did).
                val line = when (val value = lines.opt(i)) {
                    is String -> value
                    is Int, is Long, is Boolean -> value.toString()
                    else -> {
                        LogUtil.d(TAG, "Invalid default macro: $value")
                        continue
                    }
                }
                if (line.isNotEmpty()) {
                    try {
                        val ws = lineToWS(localeStr, line)
                        val key = ws.key
                        if (!deletedKeys.contains(key) && !map.containsKey(key)) {
                            map[key] = ws
                        }
                    } catch (e: Exception) {
                        LogUtil.d(TAG, "Invalid default macro: $line")
                    }
                } else {
                    LogUtil.d(TAG, "Empty line while reading WS file: $filePath")
                }
            }
        } catch (e: JSONException) {
            LogUtil.e(TAG, "File [$filePath] has invalid JSON: ${e.message}")
        }
    }

    private fun lineToWS(locale: String, line: String): WordSubstitution {
        val index = line.indexOf(' ', 2)
        return WordSubstitution.createWithoutPreconditions(
            locale,
            line.substring(2, index),
            line.substring(index + 1),
            WordSubstitution.Type.DEFAULT
        ).apply { 
            isAutoCapsEnabled = isAutoCapsEnabled(line[0])
        }
    }

    private fun getFullLocaleStringWithoutVariant(locale: Locale): String {
        return if (locale.variant.isNotEmpty()) {
            locale.language + "_" + locale.country
        } else {
            locale.toString()
        }
    }

    private fun isLocaleWithLanguageAndCountry(locale: Locale): Boolean {
        return locale.language.isNotEmpty() && locale.country.isNotEmpty()
    }

    /** @param c the first character of a macros.json line */
    private fun isAutoCapsEnabled(c: Char): Boolean {
        return c == PersonalDictionaryConstants.AUTO_CAPS_ENABLED
    }
}
