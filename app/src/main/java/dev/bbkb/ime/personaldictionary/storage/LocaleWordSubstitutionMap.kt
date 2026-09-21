package dev.bbkb.ime.personaldictionary.storage

import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import java.util.*

/**
 * Stores [WordSubstitution] entries organized by locale.
 *
 * Maps locale strings to TreeMaps of word substitutions (keyed by shortcut).
 * Tracks dirty state for JSON persistence.
 */
class LocaleWordSubstitutionMap(filename: String) : DictionaryWordObservable() {
    private val localeWsMap = HashMap<String, TreeMap<String, WordSubstitution>>()

    init {
        this.filename = filename
    }

    val backingMap: Map<String, TreeMap<String, WordSubstitution>>
        get() = localeWsMap

    val wordSubstitutionsKeySet: Set<String>
        get() {
            val keys = HashSet<String>()
            for (map in localeWsMap.values) {
                keys.addAll(map.keys)
            }
            return keys
        }

    fun putIfAbsent(ws: WordSubstitution): Boolean {
        val map = getMapForLocale(ws.locale)
        val key = ws.key
        if (map.containsKey(key)) {
            return false
        }
        map[key] = ws
        isDirty = true
        return true
    }

    fun put(ws: WordSubstitution) {
        getMapForLocale(ws.locale)[ws.key] = ws
        isDirty = true
    }

    private fun getMapForLocale(locale: String): TreeMap<String, WordSubstitution> {
        var map = localeWsMap[locale]
        if (map != null) {
            return map
        }
        map = TreeMap(String.CASE_INSENSITIVE_ORDER)
        localeWsMap[locale] = map
        return map
    }

    fun removeIfPresent(ws: WordSubstitution): Boolean {
        val locale = ws.locale
        val map = localeWsMap[locale]
        if (map == null || map.remove(ws.key) == null) {
            return false
        }
        isDirty = true
        if (map.isEmpty()) {
            localeWsMap.remove(locale)
        }
        return true
    }

    override fun clear() {
        localeWsMap.clear()
    }

    override fun asCollection(): Collection<WordSubstitution> {
        val list = ArrayList<WordSubstitution>()
        for (map in localeWsMap.values) {
            list.addAll(map.values)
        }
        return list
    }

    fun getWordSubstitutionsSet(locales: List<Locale>): Set<WordSubstitution> {
        val set = HashSet<WordSubstitution>()
        for (locale in locales) {
            val map = localeWsMap[locale.toString()]
            if (map != null) {
                set.addAll(map.values)
            }
        }
        return set
    }
}
