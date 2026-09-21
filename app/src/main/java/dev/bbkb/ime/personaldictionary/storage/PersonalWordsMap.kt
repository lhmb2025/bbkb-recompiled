package dev.bbkb.ime.personaldictionary.storage

import dev.bbkb.ime.personaldictionary.model.PersonalWord
import java.util.HashMap

/**
 * Stores [PersonalWord] entries keyed by word string.
 *
 * Tracks dirty state for JSON persistence.
 */
class PersonalWordsMap(filename: String) : DictionaryWordObservable() {
    private val personalWords = HashMap<String, PersonalWord>()

    init {
        this.filename = filename
    }

    val backingMap: Map<String, PersonalWord>
        get() = personalWords

    fun putIfAbsent(personalWord: PersonalWord): Boolean {
        val word = personalWord.word
        if (personalWords.containsKey(word)) {
            return false
        }
        personalWords[word] = personalWord
        isDirty = true
        return true
    }

    fun put(personalWord: PersonalWord): Boolean {
        personalWords[personalWord.word] = personalWord
        isDirty = true
        return true
    }

    fun removeIfPresent(personalWord: PersonalWord): Boolean {
        if (personalWords.remove(personalWord.word) == null) {
            return false
        }
        isDirty = true
        return true
    }

    override fun clear() {
        personalWords.clear()
    }

    override fun asCollection(): Collection<PersonalWord> {
        return personalWords.values
    }
}
