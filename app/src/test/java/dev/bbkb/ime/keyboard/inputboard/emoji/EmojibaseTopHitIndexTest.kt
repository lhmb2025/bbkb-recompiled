package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * topHit and searchEmojis score only the bigram-index candidates and memoise (audit P-10). Their
 * answers must be exactly the full scan's, ties included, which this checks against a brute-force
 * copy of the old code over the real dataset and a few thousand queries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojibaseTopHitIndexTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val provider: EmojibaseDataProvider by lazy {
        val entries = context.assets.open(EmojibaseCompactFormat.ASSET).bufferedReader(Charsets.UTF_8).use {
            EmojibaseCompactFormat.read(it).entries
        }
        EmojibaseDataProvider(context).apply { applyDataset(entries) { BooleanArray(it.size) { true } } }
    }

    // ---- the pre-index implementation, verbatim in behaviour

    private fun bruteTopHit(query: String, minRelevance: Int): EmojiData? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        var best: EmojiData? = null
        var bestScore = minRelevance - 1
        for (e in provider.getAllEmojis()) {
            val score = e.searchRelevance(q)
            if (score > bestScore) {
                bestScore = score
                best = e
            }
        }
        return best
    }

    private fun bruteSearch(query: String): List<EmojiData> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return provider.getAllEmojis()
            .map { it to it.searchRelevance(q) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(80)
            .map { it.first }
    }

    private fun queries(): List<String> {
        val words = LinkedHashSet<String>()
        for (e in provider.getAllEmojis()) {
            e.description.split(' ', '-', ':', ',').filter { it.isNotEmpty() }.forEach { words.add(it) }
            e.tags?.forEach { words.add(it) }
        }
        val rnd = Random(20261104)
        val pool = words.toList()
        val out = LinkedHashSet<String>()
        // Dictionary words and the strip's real inputs: prefixes of them, as typed letter by letter.
        repeat(300) {
            val w = pool[rnd.nextInt(pool.size)]
            out.add(w)
            for (n in 1 until w.length) out.add(w.substring(0, n))
        }
        // Mid-word substrings (contains-only matches), mixed case, punctuation, padding.
        repeat(200) {
            val w = pool[rnd.nextInt(pool.size)]
            if (w.length > 3) out.add(w.substring(1, w.length - 1))
            out.add(w.uppercase())
            out.add(w.replaceFirstChar { it.titlecase() })
            out.add(" $w ")
            out.add("$w.")
            out.add("$w's")
        }
        // Whole descriptions (exact matches) and plain English words.
        provider.getAllEmojis().shuffled(rnd).take(150).forEach { out.add(it.description) }
        out += listOf(
            "the", "and", "you", "hello", "heart", "love", "lol", "ok", "cat", "dog", "happy", "sad",
            "fire", "pizza", "smile", "thumbs", "party", "flag", "uk", "us", "tea", "sun", "ar", "an",
            "xyzzy", "qq", "zz", "a", "I", "  ", "", "!", "??", "...", "e-mail", "o'clock", "café",
            "ünïcödé", "日本", "😀", "1st", "100", "#", "*",
        )
        // Random strings over letters, digits and a little punctuation.
        val alphabet = "abcdefghijklmnopqrstuvwxyzABC0123456789 -'."
        repeat(300) { out.add(String(CharArray(1 + rnd.nextInt(6)) { alphabet[rnd.nextInt(alphabet.length)] })) }
        return out.toList()
    }

    @Test
    fun topHitMatchesTheFullScanForEveryQuery() {
        val qs = queries()
        assertTrue("query count ${qs.size}", qs.size > 1000)
        var hits = 0
        for (min in intArrayOf(EmojiSearchScoring.STRIP_MIN_RELEVANCE, 1, 25, 100, 0, -3)) {
            for (q in qs) {
                val expected = bruteTopHit(q, min)
                val actual = provider.topHit(q, min)
                assertSame("topHit(\"$q\", $min)", expected, actual)
                // The memo answers the same thing again.
                assertSame("memoised topHit(\"$q\", $min)", expected, provider.topHit(q, min))
                if (min == EmojiSearchScoring.STRIP_MIN_RELEVANCE && expected != null) hits++
            }
        }
        assertTrue("strip hits $hits", hits > 300)
    }

    @Test
    fun searchEmojisMatchesTheFullScanForEveryQuery() {
        for (q in queries()) {
            val expected = bruteSearch(q)
            val actual = provider.searchEmojis(q)
            assertEquals("searchEmojis(\"$q\")", expected.size, actual.size)
            for (i in expected.indices) assertSame("searchEmojis(\"$q\")[$i]", expected[i], actual[i])
        }
    }

    @Test
    fun aReloadDropsTheIndexAndTheMemo() {
        val p = EmojibaseDataProvider(context)
        val cat = EmojiData("🐈", "1F408", "cat", tags = listOf("pet"), group = 3, subgroup = 0, emojiVersion = 1.0, order = 1)
        val dog = EmojiData("🐕", "1F415", "dog", tags = listOf("pet"), group = 3, subgroup = 0, emojiVersion = 1.0, order = 2)
        p.applyDataset(listOf(cat)) { BooleanArray(it.size) { true } }
        assertSame(cat, p.topHit("pet", 40))
        p.applyDataset(listOf(dog, cat)) { BooleanArray(it.size) { true } }
        assertSame(dog, p.topHit("pet", 40))
    }
}
