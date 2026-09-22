package dev.bbkb.ime.personaldictionary.storage

import dev.bbkb.ime.personaldictionary.model.DictionaryWord

/**
 * Abstract base class for observable dictionary word collections.
 *
 * Tracks dirty state for persistence and provides collection access.
 * Extended by [LocaleWordSubstitutionMap] and [PersonalWordsMap].
 */
abstract class DictionaryWordObservable {
    var filename: String = ""
        protected set
    
    var isDirty: Boolean = false

    abstract fun asCollection(): Collection<DictionaryWord>
    abstract fun clear()
}
