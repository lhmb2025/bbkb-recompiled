package dev.bbkb.ime.personaldictionary.storage

import dev.bbkb.ime.personaldictionary.model.DictionaryWord

/** A persisted word collection with its file name and dirty flag. */
abstract class DictionaryWordObservable {
    var filename: String = ""
        protected set
    
    var isDirty: Boolean = false

    abstract fun asCollection(): Collection<DictionaryWord>
    abstract fun clear()
}
