package dev.bbkb.ime.core.settings.screens

import dev.bbkb.ime.core.engine.dlm.EngineLanguages
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** The single-pass sectioning (P-19) gives the same sections as the List-membership version. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WordListSectionsTest {

    /** The implementation before P-19, kept verbatim as the reference. */
    private fun reference(words: List<String>, details: Map<String, WordDetail>?, other: String): List<Pair<String?, List<String>>> {
        if (details == null) return listOf(null to words)
        val byLanguage = words.groupBy { details[it]?.language ?: EngineLanguages.ANY }
        val languages = byLanguage.keys.filter { it != EngineLanguages.ANY }
        if (languages.size <= 1) return listOf(null to words)
        val largest = languages.maxByOrNull { byLanguage.getValue(it).size }!!
        return languages
            .sortedWith(compareByDescending<Int> { byLanguage.getValue(it).size }.thenBy { EngineLanguages.displayName(it) ?: "" })
            .map { language ->
                val own = byLanguage.getValue(language)
                val list = if (language == largest) words.filter { it in own || (details[it]?.language ?: EngineLanguages.ANY) == EngineLanguages.ANY } else own
                (EngineLanguages.displayName(language) ?: other) to list
            }
    }

    private fun actual(words: List<String>, details: Map<String, WordDetail>?) =
        sectionsOf(words, details, "Other").map { it.title to it.words }

    @Test
    fun matchesReference() {
        val languages = listOf(0x09, 0x0c, 0x19, 0x07, EngineLanguages.ANY, 0x3ff /* unnamed */)
        for (seed in 0 until 25) {
            val random = Random(seed)
            val n = random.nextInt(0, 600)
            val words = List(n) { "w" + random.nextInt(0, 400) }.sorted()
            val details = HashMap<String, WordDetail>()
            // Some words have no detail at all (they count as no language).
            words.forEach { if (random.nextInt(10) != 0) details[it] = WordDetail(random.nextInt(50), 0, languages[random.nextInt(languages.size)]) }
            assertEquals("seed $seed", reference(words, details, "Other"), actual(words, details))
        }
        assertEquals(reference(listOf("a", "b"), null, "Other"), actual(listOf("a", "b"), null))
    }
}
