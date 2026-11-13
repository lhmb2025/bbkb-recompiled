package dev.bbkb.ime.personaldictionary.sync

import dev.bbkb.ime.personaldictionary.model.DictionaryWord

/** Returns true if the word should be excluded from AUD sync. */
fun interface AudFilter {
    fun filter(dictionaryWord: DictionaryWord): Boolean
}
