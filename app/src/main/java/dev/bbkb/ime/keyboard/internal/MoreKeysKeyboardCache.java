package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;

import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * Long-press popup keyboards per parent keyboard and key. A popup depends only on its parent, its
 * key and the preview geometry, so the outer map is weak on the parent (owned by KeyboardBuilder's
 * cache). Shifted and unshifted boards are separate Keyboards, so labels always match.
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
