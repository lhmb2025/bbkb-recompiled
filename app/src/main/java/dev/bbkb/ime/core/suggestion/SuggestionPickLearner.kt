package dev.bbkb.ime.core.suggestion

import android.util.Log
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.engine.EngineOps

/**
 * Teaches the DLM a word picked from the editor's suggestion popup, but only if it is one of the
 * alternatives we attached to exactly that committed word. The receiver must be exported, so any
 * app can broadcast; this membership check stops unsolicited broadcasts from teaching words.
 */
object SuggestionPickLearner {
    private const val CAPACITY = 32

    /** Teaches the engine; replaceable for tests. True once the add is posted as an engine op. */
    @Volatile
    var sink: (String) -> Boolean = { word ->
        EngineOps.post("suggestionPick") { sdk ->
            val added = sdk.addWord(word)
            if (BuildConfig.DEBUG) Log.d("SuggestionPickLearner", "addWord '$word' -> $added")
        }
        true
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
