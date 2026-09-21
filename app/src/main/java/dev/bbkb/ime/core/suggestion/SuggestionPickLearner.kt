package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.engine.NuanceSDKManager

/**
 * Learns from the editor's suggestion popup. When a word is committed with alternatives
 * ([SuggestionSpanBuilder.getTextWithSuggestionSpan]) the pair is remembered here; when the
 * editor later broadcasts `SUGGESTION_PICKED` for that word, the pick is taught to the engine's
 * DLM — but only if the picked word is one of the alternatives we offered for exactly that word.
 * The receiver has to be exported for the app's explicit broadcast to reach it, so any app can
 * send one; the membership check is what keeps an unsolicited broadcast from teaching the
 * dictionary a word we never suggested.
 */
object SuggestionPickLearner {
    private const val CAPACITY = 32

    /** The sink that teaches the engine; replaceable for tests. Returns true when learned. */
    @Volatile
    var sink: (String) -> Boolean = { word ->
        var learned = false
        NuanceSDKManager.withSdk("suggestionPick") { learned = it.addWord(word) }
        learned
    }

    private val recent = object : LinkedHashMap<String, Set<String>>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>) = size > CAPACITY
    }

    /** A word was committed carrying these alternatives in its span. */
    @JvmStatic
    fun remember(committedWord: String, alternatives: Collection<String>) {
        if (committedWord.isEmpty() || alternatives.isEmpty()) return
        synchronized(recent) { recent[committedWord] = alternatives.toSet() }
    }

    /** The editor replaced [before] with [after] from the popup. Returns true when learned. */
    @JvmStatic
    fun onPicked(before: String?, after: String?): Boolean {
        if (before.isNullOrEmpty() || after.isNullOrEmpty() || before == after) return false
        val offered = synchronized(recent) { recent[before] } ?: return false
        if (after !in offered) return false
        return sink(after)
    }

    @JvmStatic
    fun clearForTest() = synchronized(recent) { recent.clear() }
}
