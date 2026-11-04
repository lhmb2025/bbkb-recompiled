package dev.bbkb.ime.core.spellcheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A small LRU of "is this word in the dictionary" answers for one spell-checker session.
 *
 * <p>The framework re-sends the whole sentence on every edit, and most of its words are spelled
 * right, so without this every recheck asked the engine again for each of them (P-20). The
 * answers depend on the settings that change what counts as a word (accepting learned words,
 * using contacts), so those are part of the key; they also depend on which languages are loaded,
 * so the cache empties when the keyboard's subtype changes. Learned words and contact names can
 * change underneath without any signal reaching the session, so an answer also expires after
 * {@link #MAX_AGE_MS}: a word the engine has just forgotten keeps passing for at most that long.
 */
final class SpellVerdictCache {

    /** Enough for a few paragraphs of distinct words. */
    static final int MAX_ENTRIES = 300;

    /** How long one answer is trusted. */
    static final long MAX_AGE_MS = 2 * 60 * 1000L;

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

    /** What the answers were computed against (the keyboard's subtype); a change empties the cache. */
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
        if (mClockMs.getAsLong() - entry.timeMs > MAX_AGE_MS) {
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
