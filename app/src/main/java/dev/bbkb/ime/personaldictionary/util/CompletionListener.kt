package dev.bbkb.ime.personaldictionary.util

/** Completion callback for asynchronous personal dictionary operations. */
fun interface CompletionListener {
    fun complete(success: Boolean)
}
