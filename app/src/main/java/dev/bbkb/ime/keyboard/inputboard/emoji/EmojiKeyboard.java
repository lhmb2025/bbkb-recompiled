package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.SharedPreferences;
import android.text.TextUtils;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.internal.KeySpecParser;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import com.google.gson.JsonParseException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Keyboard data structure for displaying emoji grids.
 *
 * Extends the base Keyboard class to provide:
 * - Dynamic grid layout (auto-calculates columns based on keyboard width)
 * - Key positioning and sizing for emoji display
 * - Recents emoji management (add, remove, persist to SharedPreferences via {@link EmojiRecents})
 * - Template-based key creation from emoji strings
 * - Thread-safe key list management
 *
 * Each EmojiKeyboard represents one page of emojis arranged in a grid layout.
 * The Recents keyboard has special handling for adding/removing frequently used emojis.
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

    public EmojiKeyboard(SharedPreferences sharedPreferences, Keyboard c0965e, int i, int i2, Locale locale) {
        super(c0965e);
        this.lock = new Object();
        this.keyDeque = new ArrayDeque<>();
        this.pendingEmojiQueue = new ArrayDeque<>();
        this.nextKeyIndex = 0;
        this.leftTemplateKey = findTemplateKey(48);
        this.rightTemplateKey = findTemplateKey(49);
        this.keyWidth = Math.abs(this.rightTemplateKey.getX() - this.leftTemplateKey.getX());
        this.keyHeight = this.leftTemplateKey.getHeight() + this.mVerticalGap;
        this.columnCount = this.mBaseWidth / this.keyWidth;
        this.maxKeyCount = i;
        this.isRecentsKeyboard = i2 == 0;
        this.sharedPreferences = sharedPreferences;
        this.locale = locale;
    }

    private Key findTemplateKey(int i) {
        List<Key> keys = super.getKeys();
        for (int j = 0; j < keys.size(); j++) {
            Key key = keys.get(j);
            if (key.getCode() == i) {
                return key;
            }
        }
        throw new RuntimeException("Can't find template key: code=" + i);
    }

    /**
     * A recents key for {@code emoji}: an output-text key spec (code -4, the emoji as output) over
     * the parsed spec, on the left template key. The parse is what rejects a malformed entry.
     */
    private Key emojiKey(String emoji) {
        return new Key(this.leftTemplateKey, new MoreKeySpec(emoji, 0, -4, emoji),
                new MoreKeySpec[] { new MoreKeySpec(emoji, false, this.locale) });
    }

    public void enqueueEmoji(String str) {
        synchronized (this.lock) {
            this.pendingEmojiQueue.addLast(str);
        }
    }

    /**
     * Applies the queued emoji and writes the grid only if its content changed. This runs on opening
     * on Recents, leaving Recents and hiding the board, which used to rewrite emoji_recent_keys every
     * time even with nothing queued. (A queued emoji already writes via addEmojiToRecents; the final
     * write is kept for the changed case so the stored bytes are exactly the grid, as before.)
     */
    public void processEmojiQueue() {
        synchronized (this.lock) {
            final List<Object> before = recentsEntries();
            while (!this.pendingEmojiQueue.isEmpty()) {
                addEmojiToRecents(this.pendingEmojiQueue.pollFirst());
            }
            final List<Object> after = recentsEntries();
            if (!after.equals(before)) {
                EmojiRecents.write(this.sharedPreferences, after);
            }
        }
    }

    public void addEmojiToRecents(String str) {
        if (str == null || str.isEmpty()) {
            return;
        }
        synchronized (this.lock) {
            this.cachedKeyList = null;
            GridKey aVar = new GridKey(emojiKey(str));
            ArrayDeque arrayDeque = new ArrayDeque();
            while (!this.keyDeque.isEmpty()) {
                GridKey aVarPollFirst = this.keyDeque.pollFirst();
                String emojiString = aVarPollFirst.getEmojiString();
                if (emojiString != null && !emojiString.equals(str)) {
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

            // Fill remaining spaces with blank keys to prevent white background showing
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

    private void positionKey(GridKey aVar) {
        aVar.setPosition(getKeyX(this.nextKeyIndex), getKeyY(this.nextKeyIndex), getKeyRight(this.nextKeyIndex), getKeyBottom(this.nextKeyIndex));
        this.nextKeyIndex++;
    }

    /** The whole grid, blank filler keys included (they store {@code ""}); a key with no output text stores its code. */
    private void saveRecentsToPreferences() {
        EmojiRecents.write(this.sharedPreferences, recentsEntries());
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
     * A corrupted stored value must never break the board: malformed JSON loads as no recents, and an
     * entry the key-spec parser rejects (e.g. {@code "|x"}) is skipped on its own. The grid is always
     * padded. Nothing is written back, so the stored value is untouched until recents really change.
     * Pinned by EmojiRecentsPersistenceTest.
     */
    public void loadRecentsFromPreferences() {
        List<String> stored;
        try {
            stored = EmojiRecents.read(this.sharedPreferences, Integer.MAX_VALUE);
        } catch (JsonParseException e) {
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
        // Fill remaining grid spaces with blank keys to prevent white background showing
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

    /**
     * Fill remaining grid spaces with blank keys to prevent white background showing.
     * Call this after adding all emoji keys to a search results or recents keyboard.
     */
    public void fillEmptySpaces() {
        synchronized (this.lock) {
            fillEmptySpacesInternal();
        }
    }

    private void fillEmptySpacesInternal() {
        // This method should only be called when lock is already held
        int currentKeyCount = this.keyDeque.size();
        if (currentKeyCount < this.maxKeyCount) {

            // Create ClipboardItem blank key with CODE_UNSPECIFIED (-21) that won't trigger any action
            MoreKeySpec blankSpec = new MoreKeySpec("", 0, -21, "");

            // Fill the remaining slots with blank keys
            while (this.keyDeque.size() < this.maxKeyCount) {
                // Pass empty array instead of null to avoid NPE in Key constructor
                Key blankKey = new Key(this.leftTemplateKey, blankSpec, new dev.bbkb.ime.keyboard.internal.MoreKeySpec[0]);
                GridKey blankKeyWrapper = new GridKey(blankKey);
                this.keyDeque.addLast(blankKeyWrapper);
                positionKey(blankKeyWrapper);
            }

            // Clear the cached key list so it gets regenerated with the blank keys
            this.cachedKeyList = null;
        }
    }

    private int getKeyX(int i) {
        return (i % this.columnCount) * this.keyWidth;
    }

    private int getKeyRight(int i) {
        return ((i % this.columnCount) + 1) * this.keyWidth;
    }

    private int getKeyY(int i) {
        return ((i / this.columnCount) * this.keyHeight) + (this.mVerticalGap / 2);
    }

    private int getKeyBottom(int i) {
        return (((i / this.columnCount) + 1) * this.keyHeight) + (this.mVerticalGap / 2);
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
    public List<Key> getNearestKeys(int i, int i2) {
        return getKeys();
    }


    /** A Key with a settable grid position; AOSP calls this DynamicGridKeyboard.GridKey. */
    static final class GridKey extends Key {

        private int x;

        private int y;

        public GridKey(Key key) {
            super(key);
        }

        public void setPosition(int i, int i2, int i3, int i4) {
            this.x = i;
            this.y = i2;
            getHitBox().set(i, i2, i3, i4);
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
