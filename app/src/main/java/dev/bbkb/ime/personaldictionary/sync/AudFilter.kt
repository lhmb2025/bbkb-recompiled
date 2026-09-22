package dev.bbkb.ime.personaldictionary.sync

import dev.bbkb.ime.personaldictionary.model.DictionaryWord

/**
 * Functional interface for filtering dictionary words during AUD sync.
 *
 * Returns `true` if the word should be excluded from synchronization.
 */
fun interface AudFilter {
    fun filter(dictionaryWord: DictionaryWord): Boolean
}
