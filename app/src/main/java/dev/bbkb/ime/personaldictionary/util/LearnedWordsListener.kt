package dev.bbkb.ime.personaldictionary.util

/** Receives the learned words asynchronously. */
interface LearnedWordsListener {
    fun complete(words: List<String>)
}
