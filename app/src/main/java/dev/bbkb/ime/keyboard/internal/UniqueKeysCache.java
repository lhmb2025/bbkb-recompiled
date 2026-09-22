package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Interns {@link Key} instances so identical keys are shared between keyboards.
 *
 * <p>The single instance is a {@code static final} field on {@code KeyboardBuilder}, so every Key
 * ever built — across every locale, theme change, rotation, layout variant and more-keys popup —
 * lands here. {@link #clear()} has no caller anywhere in the tree, so the map was growing for the
 * process lifetime while holding label strings and icon {@code Drawable} references.
 *
 * <p>Bounded with an LRU instead. Eviction is semantically free: {@link #intern} returns the
 * argument when there is no cached equal Key, so an evicted entry only costs the sharing, never
 * correctness.
 */
public final class UniqueKeysCache {

    /**
     * Enough for several full keyboards (an emoji page alone runs to a few hundred keys) while
     * still bounding the retained set.
     */
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
