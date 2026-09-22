package dev.bbkb.ime.personaldictionary.util

/**
 * Callback interface for asynchronous operation completion.
 */
fun interface CompletionListener {
    fun complete(success: Boolean)
}
