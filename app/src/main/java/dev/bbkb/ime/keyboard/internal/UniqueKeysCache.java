package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Interns {@link Key} instances so identical keys are shared between keyboards. The process-wide
 * instance sees every Key ever built, so it is a bounded LRU; eviction only costs sharing, since
 * {@link #intern} returns its argument when nothing equal is cached.
 */
public final class UniqueKeysCache {
    /** Enough for several full keyboards (an emoji page alone is a few hundred keys). */
    private static final int MAX_ENTRIES = 2048;

    private final LinkedHashMap<Key, Key> cache =
            new LinkedHashMap<Key, Key>(256, 0.75f, true /* accessOrder */) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Key> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    public void clear() {
        this.cache.clear();
    }

    public Key intern(Key key) {
        Key key2 = this.cache.get(key);
        if (key2 != null) {
            return key2;
        }
        this.cache.put(key, key);
        return key;
    }
}
