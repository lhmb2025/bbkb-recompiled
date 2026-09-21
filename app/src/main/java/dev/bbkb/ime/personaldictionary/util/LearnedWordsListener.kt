package dev.bbkb.ime.personaldictionary.util

/**
 * Callback interface for asynchronous learned words retrieval.
 */
interface LearnedWordsListener {
    fun complete(words: List<String>)
}
