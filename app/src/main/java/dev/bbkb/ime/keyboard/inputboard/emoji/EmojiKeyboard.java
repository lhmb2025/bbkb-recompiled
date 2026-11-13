package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.text.TextUtils;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.internal.KeySpecParser;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;

import org.json.JSONException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * One page of emoji laid out as a grid, columns derived from the keyboard width and the two
 * template keys (codes 48 and 49). The Recents page also persists itself via {@link EmojiRecents}.
 */


public final class EmojiKeyboard extends Keyboard {

    private final int columnCount;

    private final int maxKeyCount;

    private final boolean isRecentsKeyboard;

    private final ArrayDeque<GridKey> keyDeque;

    private final ArrayDeque<String> pendingEmojiQueue;

    private List<Key> cachedKeyList;

    private int nextKeyIndex;

    private final Locale locale;

    private final Key leftTemplateKey;

    private final Key rightTemplateKey;

    private final Object lock;

    private final SharedPreferences sharedPreferences;

    private final int keyWidth;

    private final int keyHeight;

    /**
     * The recents preference as this grid last read or wrote it (null off Recents). The board keeps
     * the grid across closes; this shows whether a restore or reset rewrote the preference meanwhile.
     */
    private String syncedRecentsJson;

    /**
     * @param keyboard the template keyboard
     * @param maxKeyCount the maximum number of keys on the page
     * @param categoryId the category id (0 is Recents)
     */
    public EmojiKeyboard(SharedPreferences sharedPreferences, Keyboard keyboard, int maxKeyCount, int categoryId, Locale locale) {
        super(keyboard);
        this.lock = new Object();
        this.keyDeque = new ArrayDeque<>();
        this.pendingEmojiQueue = new ArrayDeque<>();
        this.nextKeyIndex = 0;
        this.leftTemplateKey = findTemplateKey(48);
        this.rightTemplateKey = findTemplateKey(49);
        this.keyWidth = Math.abs(this.rightTemplateKey.getX() - this.leftTemplateKey.getX());
        this.keyHeight = this.leftTemplateKey.getHeight() + this.mVerticalGap;
        this.columnCount = this.mBaseWidth / this.keyWidth;
        this.maxKeyCount = maxKeyCount;
        this.isRecentsKeyboard = categoryId == 0;
        this.sharedPreferences = sharedPreferences;
        this.locale = locale;
    }

    /** @param code the template key's code */
    private Key findTemplateKey(int code) {
        List<Key> keys = super.getKeys();
        for (int j = 0; j < keys.size(); j++) {
            Key key = keys.get(j);
            if (key.getCode() == code) {
                return key;
            }
        }
        throw new RuntimeException("Can't find template key: code=" + code);
    }

    /** A recents key for {@code emoji} (output-text code -4); the parse rejects a malformed entry. */
    private Key emojiKey(String emoji) {
        return new Key(this.leftTemplateKey, new MoreKeySpec(emoji, 0, -4, emoji),
                new MoreKeySpec[] { new MoreKeySpec(emoji, false, this.locale) });
    }

    /** @param emoji the emoji to add on the next processEmojiQueue */
    public void enqueueEmoji(String emoji) {
        synchronized (this.lock) {
            this.pendingEmojiQueue.addLast(emoji);
        }
    }

    /** Applies the queued emoji and writes the grid only if its content changed. */
    public void processEmojiQueue() {
        synchronized (this.lock) {
            final List<Object> before = recentsEntries();
            while (!this.pendingEmojiQueue.isEmpty()) {
                addEmojiToRecents(this.pendingEmojiQueue.pollFirst());
            }
            final List<Object> after = recentsEntries();
            if (!after.equals(before)) {
                this.syncedRecentsJson = EmojiRecents.write(this.sharedPreferences, after);
            }
        }
    }

    /** @param emoji the emoji to move to the front */
    public void addEmojiToRecents(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return;
        }
        synchronized (this.lock) {
            this.cachedKeyList = null;
            GridKey aVar = new GridKey(emojiKey(emoji));
            ArrayDeque arrayDeque = new ArrayDeque();
            while (!this.keyDeque.isEmpty()) {
                GridKey aVarPollFirst = this.keyDeque.pollFirst();
                String emojiString = aVarPollFirst.getEmojiString();
                if (emojiString != null && !emojiString.equals(emoji)) {
                    arrayDeque.addLast(aVarPollFirst);
                }
            }
            this.keyDeque.addAll(arrayDeque);
            this.keyDeque.addFirst(aVar);
            while (this.keyDeque.size() > this.maxKeyCount) {
                this.keyDeque.removeLast();
            }
            this.nextKeyIndex = 0;
            Iterator<GridKey> it = this.keyDeque.iterator();
            while (it.hasNext()) {
                positionKey(it.next());
            }

            fillEmptySpacesInternal();

            if (this.isRecentsKeyboard) {
                saveRecentsToPreferences();
            }
        }
    }

    public void addKey(Key key) {
        if (key == null) {
            return;
        }
        synchronized (this.lock) {
            this.cachedKeyList = null;
            if (this.keyDeque.size() < this.maxKeyCount) {
                GridKey aVar = new GridKey(key);
                this.keyDeque.addLast(aVar);
                positionKey(aVar);
            }
        }
    }

    /** Whether this is the Recents grid (category 0), whose keys show exactly what was picked. */
    boolean isRecentsGrid() {
        return this.isRecentsKeyboard;
    }

    /**
     * Swaps each key {@code rebuild} returns a new key for into the same slot (null keeps the key).
     *
     * @return whether any key was replaced
     */
    boolean replaceKeys(Function<Key, Key> rebuild) {
        synchronized (this.lock) {
            boolean changed = false;
            final ArrayDeque<GridKey> updated = new ArrayDeque<>(this.keyDeque.size());
            for (GridKey old : this.keyDeque) {
                final Key replacement = rebuild.apply(old);
                if (replacement == null) {
                    updated.addLast(old);
                    continue;
                }
                final GridKey key = new GridKey(replacement);
                final Rect box = old.getHitBox();
                key.setPosition(old.getX(), old.getY(), box.right, box.bottom);
                updated.addLast(key);
                changed = true;
            }
            if (changed) {
                this.keyDeque.clear();
                this.keyDeque.addAll(updated);
                this.cachedKeyList = null;
            }
            return changed;
        }
    }

    /** @param gridKey the key to place in the next free slot */
    private void positionKey(GridKey gridKey) {
        gridKey.setPosition(getKeyX(this.nextKeyIndex), getKeyY(this.nextKeyIndex), getKeyRight(this.nextKeyIndex), getKeyBottom(this.nextKeyIndex));
        this.nextKeyIndex++;
    }

    /** The whole grid, blank filler keys included (they store {@code ""}); a key with no output text stores its code. */
    private void saveRecentsToPreferences() {
        this.syncedRecentsJson = EmojiRecents.write(this.sharedPreferences, recentsEntries());
    }

    /**
     * Whether the stored recents are still the ones this grid last read or wrote. False means the
     * grid must be rebuilt from the preference to show what is stored.
     */
    public boolean matchesStoredRecents() {
        synchronized (this.lock) {
            return TextUtils.equals(this.syncedRecentsJson, EmojiRecents.stored(this.sharedPreferences));
        }
    }

    private List<Object> recentsEntries() {
        ArrayList<Object> entries = new ArrayList<>();
        for (GridKey key : this.keyDeque) {
            String outputText = key.getKeySpecOutputText();
            entries.add(outputText != null ? outputText : (Object) Integer.valueOf(key.getCode()));
        }
        return entries;
    }

    /**
     * Never breaks on a corrupt value: malformed JSON loads as no recents and a rejected entry
     * (e.g. {@code "|x"}) is skipped. Nothing is written back until recents really change.
     */
    public void loadRecentsFromPreferences() {
        this.syncedRecentsJson = EmojiRecents.stored(this.sharedPreferences);
        List<String> stored;
        try {
            stored = EmojiRecents.read(this.sharedPreferences, Integer.MAX_VALUE);
        } catch (JSONException e) {
            stored = Collections.emptyList();
        }
        for (String emoji : stored) {
            final Key key;
            try {
                key = emojiKey(emoji);
            } catch (KeySpecParser.KeySpecParserError e) {
                continue;
            }
            addKey(key);
        }
        fillEmptySpaces();
    }

    /** Whether the grid holds at least one real emoji; the blank filler keys store {@code ""} and do not count. */
    public boolean hasEmoji() {
        synchronized (this.lock) {
            for (GridKey key : this.keyDeque) {
                if (!TextUtils.isEmpty(key.getEmojiString())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Pads the grid with blank keys so no empty background shows; call after adding the keys. */
    public void fillEmptySpaces() {
        synchronized (this.lock) {
            fillEmptySpacesInternal();
        }
    }

    private void fillEmptySpacesInternal() {
        // Caller holds the lock.
        int currentKeyCount = this.keyDeque.size();
        if (currentKeyCount < this.maxKeyCount) {
            // CODE_UNSPECIFIED (-21): a blank key that does nothing.
            MoreKeySpec blankSpec = new MoreKeySpec("", 0, -21, "");

            while (this.keyDeque.size() < this.maxKeyCount) {
                // An empty array, not null, avoids an NPE in the Key constructor.
                Key blankKey = new Key(this.leftTemplateKey, blankSpec, new dev.bbkb.ime.keyboard.internal.MoreKeySpec[0]);
                GridKey blankKeyWrapper = new GridKey(blankKey);
                this.keyDeque.addLast(blankKeyWrapper);
                positionKey(blankKeyWrapper);
            }

            this.cachedKeyList = null;
        }
    }

    /** @param index the slot index (also for getKeyRight, getKeyY and getKeyBottom) */
    private int getKeyX(int index) {
        return (index % this.columnCount) * this.keyWidth;
    }

    private int getKeyRight(int index) {
        return ((index % this.columnCount) + 1) * this.keyWidth;
    }

    private int getKeyY(int index) {
        return ((index / this.columnCount) * this.keyHeight) + (this.mVerticalGap / 2);
    }

    private int getKeyBottom(int index) {
        return (((index / this.columnCount) + 1) * this.keyHeight) + (this.mVerticalGap / 2);
    }

    @Override // dev.bbkb.ime.keyboard.Keyboard
    public List<Key> getKeys() {
        synchronized (this.lock) {
            if (this.cachedKeyList != null) {
                return this.cachedKeyList;
            }
            this.cachedKeyList = Collections.unmodifiableList(new ArrayList(this.keyDeque));
            return this.cachedKeyList;
        }
    }

    @Override // dev.bbkb.ime.keyboard.Keyboard
    /**
     * @param x x coordinate
     * @param y y coordinate
     */
    public List<Key> getNearestKeys(int x, int y) {
        return getKeys();
    }

    /** A Key with a settable grid position. */
    static final class GridKey extends Key {

        private int x;

        private int y;

        public GridKey(Key key) {
            super(key);
        }

        /** @param left left, {@code top} top, {@code right} right, {@code bottom} bottom of the hit box */
        public void setPosition(int left, int top, int right, int bottom) {
            this.x = left;
            this.y = top;
            getHitBox().set(left, top, right, bottom);
        }

        @Override // dev.bbkb.ime.keyboard.Key
        public int getX() {
            return this.x;
        }

        @Override // dev.bbkb.ime.keyboard.Key
        public int getY() {
            return this.y;
        }

        @Override // dev.bbkb.ime.keyboard.Key
        public boolean equals(Object obj) {
            if (!(obj instanceof Key)) {
                return false;
            }
            Key key = (Key) obj;
            if (getCode() == key.getCode() && TextUtils.equals(getLabel(), key.getLabel())) {
                return TextUtils.equals(getKeySpecOutputText(), key.getKeySpecOutputText());
            }
            return false;
        }

        @Override // dev.bbkb.ime.keyboard.Key
        public String toString() {
            return "GridKey: " + super.toString();
        }

        public String getEmojiString() {
            MoreKeySpec keySpec = super.getKeySpec();
            return keySpec != null ? keySpec.getOutputText() : null;
        }
    }
}
