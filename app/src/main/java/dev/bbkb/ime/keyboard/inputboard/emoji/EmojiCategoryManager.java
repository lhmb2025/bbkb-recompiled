package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Log;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.keyboard.KeyboardBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import dev.bbkb.ime.BuildConfig;

/**
 * Emoji categories, their pages and the lazily built page keyboards for the emoji board.
 * Category ids run 0 (Recents) to 9 ({@code last_shown_emoji_category_id}). An empty category
 * gets no tab, so tab index can differ from id; pager positions run through non-empty categories
 * in tab order. An id with no tab maps to index/position/page-count 0
 * (pinned by {@code EmojiPalettesViewCharacterisationTest}).
 */


final class EmojiCategoryManager {

    private static final String TAG = "EmojiCategoryManager";

    // Tab icon attribute per category id.
    private static final int[] CATEGORY_ICON_ATTR_IDS = {
        R.styleable.EmojiPalettesView_iconEmojiRecentsTab,    // 0: Recents
        R.styleable.EmojiPalettesView_iconEmojiSmileyTab,     // 1: Smileys & Emotion
        R.styleable.EmojiPalettesView_iconEmojiPeopleTab,     // 2: People & Body
        R.styleable.EmojiPalettesView_iconEmojiNatureTab,     // 3: Animals & Nature
        R.styleable.EmojiPalettesView_iconEmojiFoodTab,       // 4: Food & Drink
        R.styleable.EmojiPalettesView_iconEmojiPlacesTab,     // 5: Travel & Places
        R.styleable.EmojiPalettesView_iconEmojiActivityTab,   // 6: Activities
        R.styleable.EmojiPalettesView_iconEmojiObjectsTab,    // 7: Objects
        R.styleable.EmojiPalettesView_iconEmojiSymbolsTab,    // 8: Symbols
        R.styleable.EmojiPalettesView_iconEmojiFlagTab        // 9: Flags
    };

    /** Category ids run 0 (Recents) to 9, one tab icon each. */
    private static final int CATEGORY_COUNT = CATEGORY_ICON_ATTR_IDS.length;

    /** The overlay's recents cap; the Recents page itself shows up to its grid size. */
    private static final int OVERLAY_RECENTS_LIMIT = 30;


    private final SharedPreferences sharedPreferences;

    private final Resources resources;

    private final int maxPageKeyCount;

    private final KeyboardBuilder keyboardBuilder;

    private int currentCategoryId;

    private EmojiKeyboardFactory emojiKeyboardFactory;

    private final int[] categoryIconIds = new int[CATEGORY_COUNT];

    private final ConcurrentHashMap<Long, EmojiKeyboard> keyboardCache = new ConcurrentHashMap<>();

    private int currentPageInCategory = 0;

    private int[] categoryPageCounts = new int[CATEGORY_COUNT];

    /** The ids of the categories that have pages, in tab order. Fixed at construction. */
    private final int[] categoryIds;

    /**
     * {@code categoryStarts[k]} is the first pager position of {@code categoryIds[k]}, and
     * {@code categoryStarts[categoryIds.length]} is the total page count.
     */
    private final int[] categoryStarts;

    private int defaultCategoryId = EmojiCategory.SMILEYS_EMOTION.getId();

    /**
     * The skin-tone memory as it stood when the cached category pages were (or could have been)
     * built. Keys show the remembered tone, so a change since then makes every page stale.
     */
    private String toneMemorySnapshot;

    /** @param keyboardBuilder builder for the page keyboards */
    public EmojiCategoryManager(SharedPreferences sharedPreferences, Context context, KeyboardBuilder keyboardBuilder, TypedArray typedArray) {
        this.currentCategoryId = -1;
        this.sharedPreferences = sharedPreferences;
        this.resources = context.getResources();
        this.maxPageKeyCount = this.resources.getInteger(R.integer.config_emoji_keyboard_max_page_key_count);
        this.keyboardBuilder = keyboardBuilder;
        this.toneMemorySnapshot = EmojiToneMemory.snapshot(sharedPreferences);
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            this.categoryIconIds[i] = typedArray.getResourceId(CATEGORY_ICON_ATTR_IDS[i], 0);
        }
        this.emojiKeyboardFactory = new EmojiKeyboardFactory(context, sharedPreferences, keyboardBuilder);
        final int[] ids = new int[CATEGORY_COUNT];
        final int[] starts = new int[CATEGORY_COUNT + 1];
        int present = initializeCategory(0, ids, starts, 0);
        for (EmojiCategory enumC0972c : EmojiCategory.values()) {
            present = initializeCategory(enumC0972c.getId(), ids, starts, present);
        }
        this.categoryIds = Arrays.copyOf(ids, present);
        this.categoryStarts = Arrays.copyOf(starts, present + 1);
        this.currentCategoryId = SettingsManager.getLastShownEmojiCategory(this.sharedPreferences, this.defaultCategoryId);
        // Must name a category with a tab; otherwise the default, which onFinishInflate's
        // setCategory then persists.
        if (indexOf(this.currentCategoryId) < 0) {
            this.currentCategoryId = this.defaultCategoryId;
        }
        EmojiKeyboard c0970aM6665b = getOrCreateKeyboard(0, 0);
        // hasEmoji(), not getKeys().isEmpty(): the Recents grid is padded with blank keys.
        if (this.currentCategoryId == 0 && !c0970aM6665b.hasEmoji()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "No recent emojis found, starting in category " + this.currentCategoryId);
            this.currentCategoryId = this.defaultCategoryId;
        }
    }

    /**
     * Counts a category's pages without building them (this runs on every input view creation) and
     * appends it to {@code ids}/{@code starts} if it has any. Returns the new count of categories with pages.
     * @param categoryId the category id
     */
    private int initializeCategory(int categoryId, int[] ids, int[] starts, int present) {
        final int pageCount;
        if (categoryId == 0) {
            // Recents is at most one page, read straight from preferences, and the
            // constructor inspects its keys to pick the starting category.
            getOrCreateKeyboard(0, 0);
            pageCount = this.categoryPageCounts[0];
        } else {
            EmojiCategory category = EmojiCategory.fromId(categoryId);
            pageCount = (category == null) ? 0 : this.emojiKeyboardFactory.getPageCount(category);
            this.categoryPageCounts[categoryId] = pageCount;
        }
        if (pageCount <= 0) {
            return present;
        }
        ids[present] = categoryId;
        starts[present + 1] = starts[present] + pageCount;
        return present + 1;
    }

    /** @param categoryId the category id */
    public int getCategoryIconId(int categoryId) {
        return this.categoryIconIds[categoryId];
    }

    /** The ids of the categories that have pages, in tab order. */
    public int[] getCategoryIds() {
        return this.categoryIds;
    }

    public int getCurrentCategoryId() {
        return this.currentCategoryId;
    }

    public int getCurrentPageCount() {
        return getPageCount(this.currentCategoryId);
    }

    /** @param categoryId the category id */
    public int getPageCount(int categoryId) {
        final int k = indexOf(categoryId);
        if (k < 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Invalid category id: " + categoryId);
            return 0;
        }
        return this.categoryStarts[k + 1] - this.categoryStarts[k];
    }

    /** @param categoryId the category id */
    public void setCurrentCategoryId(int categoryId) {
        this.currentCategoryId = categoryId;
        SettingsManager.setLastShownEmojiCategory(this.sharedPreferences, categoryId);
    }

    /** @param pageInCategory the page within the current category */
    public void setCurrentPageInCategory(int pageInCategory) {
        this.currentPageInCategory = pageInCategory;
    }

    public int getCurrentPageInCategory() {
        return this.currentPageInCategory;
    }

    public boolean isRecentsCategory() {
        return this.currentCategoryId == 0;
    }

    /**
     * The category's tab index, or 0 when it has no tab.
     * @param categoryId the category id
     */
    public int getCategoryIndex(int categoryId) {
        final int k = indexOf(categoryId);
        if (k < 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "categoryId not found: " + categoryId);
            return 0;
        }
        return k;
    }

    /**
     * The category's first pager position, or 0 when it has no tab.
     * @param categoryId the category id
     */
    public int getCategoryStartPosition(int categoryId) {
        final int k = indexOf(categoryId);
        if (k < 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "categoryId not found: " + categoryId);
            return 0;
        }
        return this.categoryStarts[k];
    }

    public int getRecentsIndex() {
        return getCategoryIndex(0);
    }

    private int indexOf(int categoryId) {
        for (int k = 0; k < this.categoryIds.length; k++) {
            if (this.categoryIds[k] == categoryId) {
                return k;
            }
        }
        return -1;
    }

    /** The tab index whose pages contain pager {@code position}, or -1 past the last page. */
    private int indexAtPosition(int position) {
        for (int k = 0; k < this.categoryIds.length; k++) {
            if (position < this.categoryStarts[k + 1]) {
                return k;
            }
        }
        return -1;
    }

    /**
     * The category id at pager {@code position}, which must be a page. Called every fling frame from
     * {@code EmojiPalettesView.onPageScrolled}, so it must not allocate.
     */
    public int getCategoryAt(int position) {
        return this.categoryIds[indexAtPosition(position)];
    }

    /** The page within its category of pager {@code position}, which must be a page. */
    public int getPageInCategoryAt(int position) {
        return position - this.categoryStarts[indexAtPosition(position)];
    }

    /** @param position the pager position */
    public EmojiKeyboard getKeyboardAtPosition(int position) {
        final int k = indexAtPosition(position);
        if (k < 0) {
            return null;
        }
        return getOrCreateKeyboard(this.categoryIds[k], position - this.categoryStarts[k]);
    }

    /**
     * @param categoryId the category id
     * @param pageIndex the page
     */
    private static final Long makeCacheKey(int categoryId, int pageIndex) {
        return Long.valueOf(pageIndex | ((long)categoryId << 32));
    }

    /**
     * @param categoryId the category id
     * @param pageIndex the page within it
     */
    public EmojiKeyboard getOrCreateKeyboard(int categoryId, int pageIndex) {
        synchronized (this.keyboardCache) {
            Long lM6657c = makeCacheKey(categoryId, pageIndex);
            if (this.keyboardCache.containsKey(lM6657c)) {
                EmojiKeyboard cached = this.keyboardCache.get(lM6657c);
                return cached;
            }
            if (categoryId == 0) {
                EmojiKeyboard c0970a = new EmojiKeyboard(this.sharedPreferences, this.keyboardBuilder.getKeyboard(12), this.maxPageKeyCount, categoryId, this.emojiKeyboardFactory.getLocale());
                c0970a.loadRecentsFromPreferences();
                this.keyboardCache.put(lM6657c, c0970a);
                this.categoryPageCounts[categoryId] = 1;
                return c0970a;
            }
            EmojiCategory category = EmojiCategory.fromId(categoryId);
            if (category == null) {
                this.categoryPageCounts[categoryId] = 0;
                return null;
            }
            List<EmojiKeyboard> listM6584a = this.emojiKeyboardFactory.createEmojiKeyboards(category);
            this.categoryPageCounts[categoryId] = listM6584a.size();
            for (int i3 = 0; i3 < listM6584a.size(); i3++) {
                this.keyboardCache.put(makeCacheKey(categoryId, i3), listM6584a.get(i3));
            }
            return this.keyboardCache.get(lM6657c);
        }
    }

    /** The one Recents grid. Do not keep it: {@link #prepareForOpen} may replace it. */
    public EmojiKeyboard getRecentsKeyboard() {
        return getOrCreateKeyboard(0, 0);
    }

    /**
     * Before an open, drops only stale pages: all category pages if the skin-tone memory changed since
     * they were built, and the Recents grid if recents were written elsewhere (restore, reset).
     * An emojibase reload drops nothing (same asset; page counts are fixed at construction).
     */
    public void prepareForOpen() {
        synchronized (this.keyboardCache) {
            final String tones = EmojiToneMemory.snapshot(this.sharedPreferences);
            if (!android.text.TextUtils.equals(tones, this.toneMemorySnapshot)) {
                this.toneMemorySnapshot = tones;
                // Every cached keyboard except the Recents ones (category 0).
                for (Iterator<Long> it = this.keyboardCache.keySet().iterator(); it.hasNext(); ) {
                    if (categoryOf(it.next()) != 0) {
                        it.remove();
                    }
                }
            }
            final EmojiKeyboard recents = this.keyboardCache.get(makeCacheKey(0, 0));
            if (recents != null && !recents.matchesStoredRecents()) {
                this.keyboardCache.remove(makeCacheKey(0, 0));
            }
        }
    }

    /**
     * A tone changed for {@code base} while open: rebuilds its keys in every cached category page
     * (not Recents), so the cache matches the stored memory again.
     * @return the pages whose keys changed
     */
    public java.util.Set<EmojiKeyboard> retone(String base) {
        final java.util.Set<EmojiKeyboard> changed =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        synchronized (this.keyboardCache) {
            for (EmojiKeyboard page : this.keyboardCache.values()) {
                if (!page.isRecentsGrid()
                        && page.replaceKeys(key -> this.emojiKeyboardFactory.retoneKey(key, base))) {
                    changed.add(page);
                }
            }
            this.toneMemorySnapshot = EmojiToneMemory.snapshot(this.sharedPreferences);
        }
        return changed;
    }

    /** On close: keeps Recents and the categories at and next to {@code position}; drops the rest. */
    public void retainPagesAround(int position) {
        final int last = getTotalPageCount() - 1;
        if (last < 0) {
            return;
        }
        final int keepA = getCategoryAt(Math.max(0, Math.min(last, position - 1)));
        final int keepB = getCategoryAt(Math.max(0, Math.min(last, position)));
        final int keepC = getCategoryAt(Math.max(0, Math.min(last, position + 1)));
        synchronized (this.keyboardCache) {
            for (Iterator<Long> it = this.keyboardCache.keySet().iterator(); it.hasNext(); ) {
                final int id = categoryOf(it.next());
                if (id != 0 && id != keepA && id != keepB && id != keepC) {
                    it.remove();
                }
            }
        }
    }

    private static int categoryOf(Long cacheKey) {
        return (int) (cacheKey >>> 32);
    }

    public int getTotalPageCount() {
        return this.categoryStarts[this.categoryIds.length];
    }

    /** For the overlay, which builds search/recents keyboards directly. */
    public EmojiKeyboardFactory getKeyboardFactory() {
        return this.emojiKeyboardFactory;
    }

    /**
     * Up to {@value #OVERLAY_RECENTS_LIMIT} recents for the overlay, from the preference. Never throws
     * (failure = none); entries are unparsed, the keyboard factory skips rejected ones.
     */
    public List<String> getRecentEmojis() {
        try {
            return EmojiRecents.read(this.sharedPreferences, OVERLAY_RECENTS_LIMIT);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "Error loading recents", e);
            return new ArrayList<>();
        }
    }
}
