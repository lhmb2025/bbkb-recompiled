package dev.bbkb.ime.core.spellcheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-session LRU of "is this word in the dictionary" answers; the framework re-sends the whole
 * sentence on every edit. Keyed by word plus the word-affecting settings; emptied when the engine
 * generation moves. Learned words and contacts change without a signal, so answers expire: "not a
 * word" soon (a just-learned word loses its underline), "a word" much later (only a removal flips it).
 */
final class SpellVerdictCache {

    /** Enough for a few paragraphs of distinct words. */
    static final int MAX_ENTRIES = 300;

    /** How long a "not in the dictionary" answer is trusted. */
    static final long NEGATIVE_MAX_AGE_MS = 2 * 60 * 1000L;

    /** How long an "in the dictionary" answer is trusted. */
    static final long POSITIVE_MAX_AGE_MS = 30 * 60 * 1000L;

    private static final class Verdict {
        final boolean inDictionary;
        final long timeMs;

        Verdict(boolean inDictionary, long timeMs) {
            this.inDictionary = inDictionary;
            this.timeMs = timeMs;
        }
    }

    private final LinkedHashMap<String, Verdict> mEntries = new LinkedHashMap<String, Verdict>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Verdict> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private final LongSupplier mClockMs;

    /** What the answers were computed against (the engine generation); a change empties the cache. */
    private Object mDictionaryState;

    SpellVerdictCache() {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    SpellVerdictCache(LongSupplier clockMs) {
        this.mClockMs = clockMs;
    }

    private static String key(String word, int settings) {
        return word + (char) 65532 + settings;
    }

    /** The cached answer for {@code word} under {@code settings}, or null when there is none. */
    synchronized Boolean get(String word, int settings) {
        final String key = key(word, settings);
        final Verdict entry = mEntries.get(key);
        if (entry == null) {
            return null;
        }
        final long maxAgeMs = entry.inDictionary ? POSITIVE_MAX_AGE_MS : NEGATIVE_MAX_AGE_MS;
        if (mClockMs.getAsLong() - entry.timeMs > maxAgeMs) {
            mEntries.remove(key);
            return null;
        }
        return entry.inDictionary;
    }

    synchronized void put(String word, int settings, boolean inDictionary) {
        mEntries.put(key(word, settings), new Verdict(inDictionary, mClockMs.getAsLong()));
    }

    /** Empties the cache when {@code state} differs from the one the answers were computed against. */
    synchronized void syncDictionaryState(Object state) {
        if (!Objects.equals(state, mDictionaryState)) {
            mDictionaryState = state;
            mEntries.clear();
        }
    }

    synchronized void clear() {
        mEntries.clear();
    }

    synchronized int size() {
        return mEntries.size();
    }
}
