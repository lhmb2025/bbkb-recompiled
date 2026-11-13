package dev.bbkb.ime.personaldictionary

import java.util.Locale

/**
 * One-tap "add to dictionary": a plain word (shortcut == word, case not fixed), so the engine
 * learns it and AUD is synced. Returns false when not loaded or already defined, so the caller
 * can fall back.
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
