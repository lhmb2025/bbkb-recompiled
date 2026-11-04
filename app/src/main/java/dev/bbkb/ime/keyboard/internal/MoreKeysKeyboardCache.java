package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;

import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * Long-press popup keyboards, kept per parent keyboard and per key, as LatinIME keeps them.
 *
 * <p>The view used to hold one map keyed by key and clear it on every keyboard swap (each shift
 * change) and every {@code closing()} (each field start), so the first long-press after either
 * rebuilt the popup. A popup only depends on its parent keyboard, its key and the preview
 * geometry, so it lives as long as the parent keyboard does: the outer map is weak on the parent,
 * which KeyboardBuilder's cache owns. Shifted and unshifted states are separate Keyboard objects
 * with their own keys, so each gets its own popups and the labels always match the board shown.
 */
public final class MoreKeysKeyboardCache {

    private final WeakHashMap<Keyboard, HashMap<Key, Keyboard>> mByParent = new WeakHashMap<>();

    /** The preview geometry the cached popups were built for; popups depend on it. */
    private int mGeometry;

    public Keyboard get(Keyboard parent, Key key, int geometry) {
        if (parent == null) {
            return null;
        }
        if (geometry != mGeometry) {
            mByParent.clear();
            mGeometry = geometry;
            return null;
        }
        HashMap<Key, Keyboard> popups = mByParent.get(parent);
        return popups == null ? null : popups.get(key);
    }

    public void put(Keyboard parent, Key key, int geometry, Keyboard popup) {
        if (parent == null) {
            return;
        }
        if (geometry != mGeometry) {
            mByParent.clear();
            mGeometry = geometry;
        }
        HashMap<Key, Keyboard> popups = mByParent.get(parent);
        if (popups == null) {
            popups = new HashMap<>();
            mByParent.put(parent, popups);
        }
        popups.put(key, popup);
    }

    public void clear() {
        mByParent.clear();
    }

    int parentCountForTest() {
        return mByParent.size();
    }
}
