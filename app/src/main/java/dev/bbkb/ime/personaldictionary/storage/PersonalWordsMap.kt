package dev.bbkb.ime.personaldictionary.storage

import dev.bbkb.ime.personaldictionary.model.PersonalWord
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores [PersonalWord] entries keyed by word string.
 *
 * Tracks dirty state for JSON persistence.
 */
class PersonalWordsMap(filename: String) : DictionaryWordObservable() {
    private val personalWords = HashMap<String, PersonalWord>()

    /**
     * The words alone, readable from any thread without the sync lock: the suggestion worker and
     * the spell checker ask on every word, while a sync can hold the lock for a long time.
     */
    private val words: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
        words.add(word)
        isDirty = true
        return true
    }

    fun put(personalWord: PersonalWord): Boolean {
        personalWords[personalWord.word] = personalWord
        words.add(personalWord.word)
        isDirty = true
        return true
    }

    fun removeIfPresent(personalWord: PersonalWord): Boolean {
        if (personalWords.remove(personalWord.word) == null) {
            return false
        }
        words.remove(personalWord.word)
        isDirty = true
        return true
    }

    override fun clear() {
        personalWords.clear()
        words.clear()
    }

    /** Whether [word] is in the map, exact spelling; safe on any thread. */
    fun containsWord(word: String): Boolean = words.contains(word)

    override fun asCollection(): Collection<PersonalWord> {
        return personalWords.values
    }
}
