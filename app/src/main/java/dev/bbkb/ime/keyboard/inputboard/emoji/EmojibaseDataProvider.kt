package dev.bbkb.ime.keyboard.inputboard.emoji

import android.content.Context
import android.graphics.Paint
import android.os.Build
import dev.bbkb.ime.core.shared.Logger
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Emoji data provider using the Emojibase dataset (read from a compact preprocessed asset, see
 * [EmojibaseCompactFormat]).
 * Provides comprehensive emoji data with proper Unicode 16.0 category support.
 *
 * Key features:
 * - Uses Emojibase datasets for emoji metadata with Unicode 16.0
 * - Built-in skin tone variants via `skins` array
 * - Clean category grouping with numeric IDs (0-8)
 * - Simplified filtering - just check for `tone` field
 */
class EmojibaseDataProvider(private val context: Context) {

    companion object {
        private const val TAG = "EmojibaseDataProvider"
        private const val MAX_SEARCH_RESULTS = 80  // ~2 pages worth

        // Shared, already-loaded instance for callers outside the emoji board (e.g. the
        // custom symbol page in KeyboardBuilder) so repeated keyboard rebuilds don't
        // re-parse the emojibase data. Loaded once on first use. Every holder reads it through
        // getSharedLoaded/peekShared and keeps no field of its own, so after releaseShared
        // nothing retains the old dataset and there is never more than one copy (audit P-2).
        @Volatile
        private var sharedLoadedInstance: EmojibaseDataProvider? = null

        /** Guards the parse only. [releaseShared] must never take it (see there). */
        private val loadLock = Any()

        /** True while [getSharedLoaded] is parsing, so a preload does not queue behind it. */
        @Volatile
        private var loadInFlight = false

        /** True from a preload's submission until it has run: coalesces repeated preloads. */
        private val preloadQueued = AtomicBoolean(false)

        /**
         * For [preloadAsync] callers without an executor of their own: one low-priority daemon
         * thread that exits when idle, instead of a new Thread per call (every keyboard show
         * used to start one after a release).
         */
        private val defaultPreloadExecutor: Executor by lazy {
            ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
                Thread(r, "emojibase-preload").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        }

        @JvmStatic
        fun getSharedLoaded(context: Context): EmojibaseDataProvider {
            sharedLoadedInstance?.let { return it }
            synchronized(loadLock) {
                sharedLoadedInstance?.let { return it }
                loadInFlight = true
                try {
                    val provider = EmojibaseDataProvider(context.applicationContext)
                    provider.loadEmojiData()
                    sharedLoadedInstance = provider
                    return provider
                } finally {
                    loadInFlight = false
                }
            }
        }

        /** The shared provider if it is already loaded, else null: for UI-thread callers that must not pay for the parse. */
        @JvmStatic
        fun peekShared(): EmojibaseDataProvider? = sharedLoadedInstance

        /**
         * Warms the shared provider on a low-priority background thread so the first strip
         * update or long-press does not pay for the parse. Same as the two-argument form on this
         * class's own single-thread executor.
         */
        @JvmStatic
        fun preloadAsync(context: Context) = preloadAsync(context, defaultPreloadExecutor)

        /**
         * Warms the shared provider on [executor]. A no-op when the data is already loaded, a
         * load is running, or an earlier preload is still queued, so calling it on every
         * keyboard show costs nothing.
         */
        @JvmStatic
        fun preloadAsync(context: Context, executor: Executor) {
            if (sharedLoadedInstance != null || loadInFlight) return
            if (!preloadQueued.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            try {
                executor.execute {
                    try {
                        getSharedLoaded(appContext)
                    } finally {
                        preloadQueued.set(false)
                    }
                }
            } catch (e: RejectedExecutionException) {
                preloadQueued.set(false)
                Logger.warn(TAG, "emojibase preload rejected: $e")
            }
        }

        /**
         * IB-26: drop the shared parsed dataset (the ~1,900 EmojiData objects plus the
         * per-category, skin-tone and search indexes). Nothing else evicts it, and an IME process
         * is deliberately long-lived, so it otherwise stays resident whether or not the emoji
         * board is ever opened again. [getSharedLoaded] re-parses on the next use.
         *
         * A plain volatile write, never the parse lock: it is called from onTrimMemory on the main
         * thread, which was seen blocked 931 ms here waiting for a background parse (audit P-2).
         * A parse that is running when this is called still publishes its result afterwards;
         * that is the caller who needed the data, so it is the right outcome.
         */
        @JvmStatic
        fun releaseShared() {
            sharedLoadedInstance = null
        }

        /** The glyph cache key: a new OS build or a new dataset invalidates it. */
        private fun glyphCacheKey(identity: String) =
            "${Build.FINGERPRINT}|${Build.VERSION.SDK_INT}|$identity"
    }

    private var emojiList: List<EmojiData>? = null

    /** Lower-cased search fields and their bigram index, built lazily; see [searchData]. */
    @Volatile
    private var searchData: SearchData? = null
    private var emojiByCategory: MutableMap<EmojiCategory, MutableList<EmojiData>>? = null

    /**
     * Skin-tone variants indexed by base emoji, under both the fully-qualified form
     * and the VS16-stripped form. Built once in [loadEmojiData] so
     * [getSkinToneVariants] is O(1): it used to linear-scan the whole ~3700-entry
     * dataset (twice, on a miss) once per emoji key created, i.e. millions of string
     * comparisons per emoji-board build.
     */
    private var skinsByEmoji: Map<String, List<String>> = emptyMap()
    private val paint = Paint()

    /**
     * Load and categorize emoji data from the compact asset the build generates from emojibase's
     * JSON (see [EmojibaseCompactFormat]).
     */
    fun loadEmojiData() {

        try {
            val compact = context.assets.open(EmojibaseCompactFormat.ASSET).bufferedReader(Charsets.UTF_8).use {
                EmojibaseCompactFormat.read(it)
            }
            applyDataset(compact.entries) { base ->
                glyphFlags(base.map { it.emoji }, compact.identity)
            }
        } catch (e: Exception) {
            // A missing or malformed asset: the board still opens, with no emoji and no search; say why.
            Logger.warn(TAG, "emojibase data failed to load: $e")
        }
    }

    /**
     * Builds the category and skin-tone indexes from the full parsed list. [supported] answers,
     * for the base emoji in order, whether each one can be drawn; tests pass their own.
     */
    internal fun applyDataset(allEmojis: List<EmojiData>, supported: (List<EmojiData>) -> BooleanArray) {
        // Filter to base emojis only (no variants)
        val baseEmojis = allEmojis.filter { isBaseEmoji(it) }

        // Filter device-supported emojis
        val flags = supported(baseEmojis)
        val supportedEmojis = baseEmojis.filterIndexed { i, _ -> flags[i] }

        // Group by category
        val categoryMap = supportedEmojis.groupBy { it.category }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()

        emojiByCategory = categoryMap
        emojiList = null
        searchData = null

        // Index skin-tone variants for O(1) lookup (see skinsByEmoji).
        val skins = HashMap<String, List<String>>(allEmojis.size * 2)
        for (e in allEmojis) {
            val variants = e.skins?.map { it.emoji } ?: continue
            if (variants.isEmpty()) continue
            // Index under the stripped form too, but never let it displace an
            // entry whose own (fully-qualified) form is that exact string.
            val stripped = stripVariationSelectors(e.emoji)
            if (stripped != null && !skins.containsKey(stripped)) {
                skins[stripped] = variants
            }
            skins[e.emoji] = variants
        }
        skinsByEmoji = skins
    }

    /** [isEmojiSupported] for each emoji, through the on-disk glyph cache. */
    private fun glyphFlags(emojis: List<String>, identity: String): BooleanArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return BooleanArray(emojis.size) { true } // Skip check on older devices
        }
        // No cache dir (e.g. credential storage still locked) only means no cache, never no emoji.
        val dir = try { context.cacheDir } catch (e: Exception) { null }
        return EmojiGlyphCache.flags(dir, glyphCacheKey(identity), emojis, ::isEmojiSupported)
    }

    /**
     * Check if emoji is a base emoji (not a variant).
     *
     * Filtering logic:
     * - tone != null: Has a skin tone applied, it's a variant
     * - group < 0: Component emoji (e.g., regional indicators)
     * - gender != null: Gender variant (man/woman specific versions)
     * - Contains ZWJ with directional arrows
     * - Regional indicator symbols (alphabet letters 🇦-🇿)
     * - Skin tone modifiers (🏻-🏿)
     * - Hair component emojis (🦰🦱🦲🦳)
     */
    private fun isBaseEmoji(emoji: EmojiData): Boolean {
        // Has a skin tone applied - it's a variant
        if (emoji.tone != null) {
            return false
        }

        // Filter out component emojis (regional indicators, etc.)
        // These have group < 0 or are otherwise not categorized
        if (emoji.group < 0) {
            return false
        }

        // Filter out gender-specific variants (keep only gender-neutral versions)
        if (emoji.gender != null) {
            return false
        }

        val hexcode = emoji.codepoints
        val desc = emoji.description.lowercase()

        // Filter out skin tone modifier components (🏻-🏿)
        // Hexcodes: 1F3FB, 1F3FC, 1F3FD, 1F3FE, 1F3FF
        if (hexcode.startsWith("1F3F") && hexcode.length == 5) {
            return false
        }

        // Filter out hair component emojis (🦰🦱🦲🦳)
        // Hexcodes: 1F9B0 (red hair), 1F9B1 (curly), 1F9B2 (bald), 1F9B3 (white)
        if (hexcode.startsWith("1F9B") && hexcode.length == 5) {
            val lastChar = hexcode.last()
            if (lastChar in '0'..'3') {
                return false
            }
        }

        // Filter out emojis with ZWJ + arrows (head shaking/nodding)
        // Contains: 2194 (left-right arrow) or 2195 (up-down arrow)
        if (hexcode.contains("2194") || hexcode.contains("2195")) {
            return false
        }

        // Filter out directional variants (facing right/left)
        if (desc.contains("facing right") ||
            desc.contains("facing left") ||
            desc.contains("rightward") ||
            desc.contains("leftward")) {
            return false
        }

        // Filter out regional indicators (🇦-🇿)
        if (desc.startsWith("regional indicator")) {
            return false
        }

        // Filter out explicit skin tone/hair descriptions
        if (desc.endsWith("skin tone") ||
            (desc.length < 15 && (desc.contains("hair") || desc == "bald"))) {
            return false
        }

        return true
    }

    /**
     * Check if device can render this emoji.
     * Uses Paint.hasGlyph() on API 23+ to verify glyph support.
     */
    private fun isEmojiSupported(emoji: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true // Skip check on older devices
        }

        try {
            // Check if paint can render the first codepoint
            val codePoint = emoji.codePointAt(0)
            return paint.hasGlyph(emoji) || paint.hasGlyph(String(intArrayOf(codePoint), 0, 1))
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Get skin tone variants for a base emoji.
     *
     * Returns the variants from the built-in `skins` array.
     * This is much simpler than JEmoji's approach!
     */
    fun getSkinToneVariants(baseEmoji: String): List<String> {
        // The dataset stores fully-qualified forms (with U+FE0F variation
        // selectors), but callers may hold an unqualified form (e.g. a bare
        // U+1F44D stored in the custom symbol page prefs), so the index carries
        // both keys and we fall back to the VS16-insensitive one.
        skinsByEmoji[baseEmoji]?.let { return it }
        val stripped = stripVariationSelectors(baseEmoji) ?: return emptyList()
        return skinsByEmoji[stripped] ?: emptyList()
    }

    private fun stripVariationSelectors(s: String?): String? = s?.replace("\uFE0F", "")

    /**
     * Get emojis for a specific category.
     */
    fun getEmojisByCategory(category: EmojiCategory): List<EmojiData> {
        return emojiByCategory?.get(category) ?: emptyList()
    }

    /**
     * Get all emoji data.
     */
    fun getAllEmojis(): List<EmojiData> {
        if (emojiList == null) {
            emojiList = emojiByCategory?.values?.flatten() ?: emptyList()
        }
        return emojiList ?: emptyList()
    }

    /**
     * Search emojis by query string.
     * Searches in description, tags, and aliases.
     * Returns results sorted by relevance, limited to MAX_SEARCH_RESULTS.
     *
     * A relevance of 0 is exactly "no match" (see [EmojiData.searchRelevance]), so one score per
     * emoji both filters and ranks. The sort is stable: equal scores keep dataset order.
     *
     * Thread-safe: can be called from background thread.
     */
    fun searchEmojis(query: String): List<EmojiData> {
        val lowerQuery = query.trim().lowercase()
        if (lowerQuery.isEmpty()) return emptyList()

        val data = searchData()
        val scored = ArrayList<Pair<SearchEntry, Int>>()
        data.forEachCandidate(lowerQuery) { entry ->
            val score = EmojiSearchScoring.score(entry.description, entry.tags, entry.aliases, lowerQuery)
            if (score > 0) scored.add(entry to score)
        }
        return scored
            .sortedByDescending { it.second }
            .take(MAX_SEARCH_RESULTS)
            .map { it.first.data }
    }

    /**
     * The single best match for [query], or null when nothing scores at least [minRelevance].
     * Ties keep dataset order, like [searchEmojis]. This is what the suggestion strip offers.
     *
     * Runs on every suggestion request, so it scores only the entries the bigram index says can
     * contain the query, and remembers the last few answers (a repeated request for the same
     * composing word, or a backspace to it, is a map lookup). The answer depends only on the
     * query and the dataset: the strip applies the remembered skin tone afterwards, so neither
     * tone nor locale belongs in the memo key.
     */
    fun topHit(query: String, minRelevance: Int): EmojiData? {
        val lowerQuery = query.trim().lowercase()
        if (lowerQuery.isEmpty()) return null
        val data = searchData()
        val memoKey = "$minRelevance\u0000$lowerQuery"
        data.memoGet(memoKey)?.let { return it.hit }

        var best: SearchEntry? = null
        var bestScore = minRelevance - 1
        val visit: (SearchEntry) -> Unit = { entry ->
            val score = EmojiSearchScoring.score(entry.description, entry.tags, entry.aliases, lowerQuery)
            if (score > bestScore) {
                bestScore = score
                best = entry
            }
        }
        // With minRelevance <= 0 a zero score (no match at all) can win, and the index only
        // knows about matches, so that case keeps the full scan.
        if (minRelevance > 0) data.forEachCandidate(lowerQuery, visit) else data.entries.forEach(visit)
        val hit = best?.data
        data.memoPut(memoKey, Memo(hit))
        return hit
    }

    /**
     * The search fields lower-cased once per dataset. [EmojiData.searchRelevance] lower-cased the
     * description, tags and aliases of all ~1,900 emoji on every call, and the strip now searches
     * on every keystroke.
     */
    private fun searchData(): SearchData =
        searchData ?: SearchData(getAllEmojis().map { SearchEntry.of(it) }).also { searchData = it }

    private class SearchEntry(
        val data: EmojiData,
        val description: String,
        val tags: List<String>,
        val aliases: List<String>
    ) {
        companion object {
            fun of(data: EmojiData) = SearchEntry(
                data,
                data.description.lowercase(),
                data.tags?.map { it.lowercase() } ?: emptyList(),
                data.aliases?.map { it.lowercase() } ?: emptyList()
            )
        }
    }

    private class Memo(val hit: EmojiData?)

    /**
     * The search entries plus a bigram index over them (audit P-10: the strip used to score all
     * ~1,767 entries per keystroke).
     *
     * Every non-zero [EmojiSearchScoring.score] needs the query to be a substring of the
     * description, a tag or an alias, so a matching entry contains every two-character piece of
     * the query in one of those fields. Scoring only the entries in the shortest posting list
     * among the query's bigrams therefore skips exactly entries that score 0, and walking that
     * list in dataset order keeps the tie-breaking. (An index on word prefixes would not be
     * exact: "ar" scores 40 on a "star" entry tagged "starry" with a "star" alias.)
     */
    private class SearchData(entryList: List<SearchEntry>) {
        val entries: Array<SearchEntry> = entryList.toTypedArray()
        private val bigrams: Map<Int, IntArray>

        private val memo = object : LinkedHashMap<String, Memo>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Memo>?) = size > MEMO_SIZE
        }

        init {
            val building = HashMap<Int, IntList>(4096)
            for (i in entries.indices) {
                val e = entries[i]
                addBigrams(building, e.description, i)
                e.tags.forEach { addBigrams(building, it, i) }
                e.aliases.forEach { addBigrams(building, it, i) }
            }
            bigrams = building.mapValuesTo(HashMap(building.size * 2)) { it.value.toArray() }
        }

        /** Calls [action] on every entry that can score above 0 for [lowerQuery], in dataset order. */
        inline fun forEachCandidate(lowerQuery: String, action: (SearchEntry) -> Unit) {
            if (lowerQuery.length < 2) {
                entries.forEach(action)
                return
            }
            var shortest: IntArray? = null
            for (j in 0 until lowerQuery.length - 1) {
                val postings = bigrams[key(lowerQuery[j], lowerQuery[j + 1])] ?: return  // nothing contains it
                if (shortest == null || postings.size < shortest.size) shortest = postings
            }
            for (i in shortest!!) action(entries[i])
        }

        fun memoGet(key: String): Memo? = synchronized(memo) { memo[key] }

        fun memoPut(key: String, value: Memo) {
            synchronized(memo) { memo[key] = value }
        }

        private fun addBigrams(into: HashMap<Int, IntList>, field: String, index: Int) {
            for (j in 0 until field.length - 1) {
                into.getOrPut(key(field[j], field[j + 1])) { IntList() }.addOnce(index)
            }
        }

        companion object {
            /** A handful covers the composing word, its retries and a backspace or two. */
            const val MEMO_SIZE = 16

            fun key(a: Char, b: Char): Int = (a.code shl 16) or b.code
        }
    }

    /** An append-only int list that skips a repeat of its last value (indexes arrive in order). */
    private class IntList {
        private var items = IntArray(4)
        private var size = 0

        fun addOnce(value: Int) {
            if (size > 0 && items[size - 1] == value) return
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = value
        }

        fun toArray(): IntArray = items.copyOf(size)
    }
}

/**
 * The relevance scale shared by [EmojibaseDataProvider.searchEmojis] and [EmojiData.searchRelevance]:
 * description exact 100, prefix 50, contains 25; tag exact 40, prefix 20, contains 10; alias exact
 * 30, contains 5. Zero is exactly "no match". Inputs are already lower-cased.
 */
object EmojiSearchScoring {
    /** What a strip hit needs: an exact or prefix match on the description or a tag. */
    const val STRIP_MIN_RELEVANCE = 40

    @JvmStatic
    fun score(description: String, tags: List<String>, aliases: List<String>, lowerQuery: String): Int {
        var score = 0
        if (description == lowerQuery) score += 100
        else if (description.startsWith(lowerQuery)) score += 50
        else if (description.contains(lowerQuery)) score += 25

        if (tags.any { it == lowerQuery }) score += 40
        else if (tags.any { it.startsWith(lowerQuery) }) score += 20
        else if (tags.any { it.contains(lowerQuery) }) score += 10

        if (aliases.any { it == lowerQuery }) score += 30
        else if (aliases.any { it.contains(lowerQuery) }) score += 5
        return score
    }
}
