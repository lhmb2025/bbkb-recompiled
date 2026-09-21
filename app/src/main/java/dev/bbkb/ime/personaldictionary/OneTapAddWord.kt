package dev.bbkb.ime.personaldictionary

import java.util.Locale

/**
 * One-tap "add to dictionary": the word goes into the personal dictionary as a plain word
 * (shortcut == word, case not fixed), the same entry the settings editor writes, so the engine's
 * DLM learns it (`PersonalDictionaryUtil.addToBasl` -> `NuanceSDK.addWord`) and the system user
 * dictionary is synced. Returns false when the dictionary is not loaded or the add is refused
 * (already defined), so the caller can fall back.
 */
object OneTapAddWord {
    @JvmStatic
    fun add(manager: DictionaryManager, word: String, locale: Locale?): Boolean {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return false
        val localeKey = locale?.toString()?.takeIf { it.isNotEmpty() } ?: PersonalDictionaryConstants.LOCALE_ALL
        if (!manager.addWordSubstitution(trimmed, trimmed, localeKey, false)) return false
        manager.save()
        return true
    }
}
