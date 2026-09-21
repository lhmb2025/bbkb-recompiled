package dev.bbkb.ime.personaldictionary.storage

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import android.content.Context
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import java.io.File
import java.io.IOException
import java.util.*

/**
 * Loads default word substitutions (macros) from JSON asset files.
 *
 * Supports locale-specific substitutions with fallback to language-only or base macros.
 * Filters out user-deleted default substitutions.
 */
object DefaultWSLoader {
    private const val TAG = "DefaultWSLoader"
    private val gson = Gson()

    /**
     * Memo of parsed default-substitution sets.
     *
     * Audit PD-38: `isWsDefaultForLocale` re-read and re-parsed the locale's
     * macros.json asset on *every* call, just to answer a membership question - once
     * per removed substitution, inside the loop that holds the process-global
     * AUD_SYNC_LOCK. Assets cannot change at runtime, and the deleted-key set is part
     * of the key, so the result is safely memoisable. Every caller treats the returned
     * map as read-only.
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
        // Audit PD-5: this whole body used to sit inside `catch (Exception)` with an
        // empty handler, so the method could never throw despite its @Throws(IOException)
        // contract - and every caller's IOException fallback was dead code. A device
        // whose locale is e.g. en_ZA, with only assets/.../en/ present, therefore
        // loaded ZERO default word substitutions: the language-only retry in
        // loadLocaleWithLanguageCountry never ran. The asset read is deliberately
        // outside the try so a missing file still propagates.
        val jsonContent = FileUtils.readAndroidAssetToString(context, filePath)
        try {
            val type = object : TypeToken<List<String>>() {}.type
            val lines: List<String> = gson.fromJson(jsonContent, type)

            for (line in lines) {
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
        } catch (e: com.google.gson.JsonSyntaxException) {
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

    private fun isAutoCapsEnabled(c: Char): Boolean {
        return c == PersonalDictionaryConstants.AUTO_CAPS_ENABLED
    }
}
