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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import dev.bbkb.ime.BuildConfig;

/**
 * Manages emoji categories, pages, and keyboard instances for the emoji picker.
 *
 * Coordinates emoji organization across 9 categories (Recents, Smileys, People, Animals,
 * Food, Travel, Activities, Objects, Symbols, Flags). Each category contains multiple
 * pages of emojis, and this manager handles:
 * - Category initialization and page counting
 * - Keyboard caching and lazy loading
 * - Current category/page state tracking
 * - Navigation between categories and pages
 * - Integration with ModernEmojiKeyboardFactory for emoji data
 *
 * <h3>Ids, tab indices and pager positions</h3>
 * A category id is 0 (Recents) to 9 and is what {@code last_shown_emoji_category_id} stores. A
 * category with no pages gets no tab, so once one is empty a tab index is no longer its id; the
 * pager's positions run through the non-empty categories in tab order. Lookups of an id that has
 * no tab fall back to index/position/page-count 0. All of this is pinned by
 * {@code EmojiPalettesViewCharacterisationTest}.
 */


final class EmojiCategoryManager {

    private static final String TAG = "EmojiCategoryManager";

    // Direct mapping of category index to R.styleable attribute index
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

    public EmojiCategoryManager(SharedPreferences sharedPreferences, Context context, KeyboardBuilder c0978h, TypedArray typedArray) {
        this.currentCategoryId = -1;
        this.sharedPreferences = sharedPreferences;
        this.resources = context.getResources();
        this.maxPageKeyCount = this.resources.getInteger(R.integer.config_emoji_keyboard_max_page_key_count);
        this.keyboardBuilder = c0978h;
        for (int i = 0; i < CATEGORY_COUNT; i++) {
            this.categoryIconIds[i] = typedArray.getResourceId(CATEGORY_ICON_ATTR_IDS[i], 0);
        }
        // Use modern emoji keyboard factory
        this.emojiKeyboardFactory = new EmojiKeyboardFactory(context, sharedPreferences, c0978h);
        final int[] ids = new int[CATEGORY_COUNT];
        final int[] starts = new int[CATEGORY_COUNT + 1];
        int present = initializeCategory(0, ids, starts, 0);
        for (EmojiCategory enumC0972c : EmojiCategory.values()) {
            present = initializeCategory(enumC0972c.getId(), ids, starts, present);
        }
        this.categoryIds = Arrays.copyOf(ids, present);
        this.categoryStarts = Arrays.copyOf(starts, present + 1);
        this.currentCategoryId = SettingsManager.getLastShownEmojiCategory(this.sharedPreferences, this.defaultCategoryId);
        // Must name a category that has a tab. This used to compare the id against the COUNT of
        // non-empty categories, which rejected the last ids whenever one category was empty and let
        // negative or empty-category ids through to open on Recents (and stay remembered). An invalid
        // id becomes the default here, and onFinishInflate's setCategory persists that default.
        if (indexOf(this.currentCategoryId) < 0) {
            this.currentCategoryId = this.defaultCategoryId;
        }
        EmojiKeyboard c0970aM6665b = getOrCreateKeyboard(0, 0);
        // hasEmoji(), not getKeys().isEmpty(): this port pads the Recents grid with blank keys (the
        // original did not), so an empty-key test never fired and "no recents" opened on a blank page.
        if (this.currentCategoryId == 0 && !c0970aM6665b.hasEmoji()) {
            if (BuildConfig.DEBUG) Log.i(TAG, "No recent emojis found, starting in category " + this.currentCategoryId);
            this.currentCategoryId = this.defaultCategoryId;
        }
    }

    /**
     * Records how many pages a category has, without building any of them, and appends it to
     * {@code ids}/{@code starts} when it has any. Returns the new number of categories with pages.
     *
     * <p>Audit IB-1: this used to call {@link #getOrCreateKeyboard(int, int)}, which
     * materialises a {@link dev.bbkb.ime.keyboard.Key} plus
     * MoreKeySpec for <em>every</em> emoji in the category. Run over all ten
     * categories from the constructor — itself reached from
     * {@code KeyboardSwitcher.createInputView()} on every theme/orientation change —
     * that built ~1800 keys on the main thread for users who never open the emoji
     * board. The page count is a size division over the same emoji list, so the
     * category info is identical; keyboards are still built lazily per (category,
     * page) by {@code getKeyboardAtPosition}.</p>
     */
    private int initializeCategory(int i, int[] ids, int[] starts, int present) {
        final int pageCount;
        if (i == 0) {
            // Recents is at most one page, read straight from preferences, and the
            // constructor inspects its keys to pick the starting category.
            getOrCreateKeyboard(0, 0);
            pageCount = this.categoryPageCounts[0];
        } else {
            EmojiCategory category = EmojiCategory.fromId(i);
            pageCount = (category == null) ? 0 : this.emojiKeyboardFactory.getPageCount(category);
            this.categoryPageCounts[i] = pageCount;
        }
        if (pageCount <= 0) {
            return present;
        }
        ids[present] = i;
        starts[present + 1] = starts[present] + pageCount;
        return present + 1;
    }

    /** The TabHost tag for a category's tab. Internal to the TabHost: nothing persists or restores it. */
    public String getCategoryTag(int categoryId) {
        return String.valueOf(categoryId);
    }

    public int getCategoryIdFromTag(String tag) {
        return Integer.parseInt(tag);
    }

    public int getCategoryIconId(int i) {
        return this.categoryIconIds[i];
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

    public int getPageCount(int i) {
        final int k = indexOf(i);
        if (k < 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Invalid category id: " + i);
            return 0;
        }
        return this.categoryStarts[k + 1] - this.categoryStarts[k];
    }

    public void setCurrentCategoryId(int i) {
        this.currentCategoryId = i;
        SettingsManager.setLastShownEmojiCategory(this.sharedPreferences, i);
    }

    public void setCurrentPageInCategory(int i) {
        this.currentPageInCategory = i;
    }

    public int getCurrentPageInCategory() {
        return this.currentPageInCategory;
    }

    public boolean isRecentsCategory() {
        return this.currentCategoryId == 0;
    }

    /** The category's tab index, or 0 when it has no tab. */
    public int getCategoryIndex(int i) {
        final int k = indexOf(i);
        if (k < 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "categoryId not found: " + i);
            return 0;
        }
        return k;
    }

    /** The category's first pager position, or 0 when it has no tab. */
    public int getCategoryStartPosition(int i) {
        final int k = indexOf(i);
        if (k < 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "categoryId not found: " + i);
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
     * The category id at pager {@code position}. Called every frame of a fling from
     * {@code EmojiPalettesView.onPageScrolled}, so it allocates nothing (audit IB-10: this used to
     * return a boxed Pair, memoised to avoid the allocation). {@code position} must be a page.
     */
    public int getCategoryAt(int position) {
        return this.categoryIds[indexAtPosition(position)];
    }

    /** The page within its category of pager {@code position}, which must be a page. */
    public int getPageInCategoryAt(int position) {
        return position - this.categoryStarts[indexAtPosition(position)];
    }

    public EmojiKeyboard getKeyboardAtPosition(int i) {
        final int k = indexAtPosition(i);
        if (k < 0) {
            return null;
        }
        return getOrCreateKeyboard(this.categoryIds[k], i - this.categoryStarts[k]);
    }

    private static final Long makeCacheKey(int i, int i2) {
        return Long.valueOf(i2 | ((long)i << 32));
    }

    public EmojiKeyboard getOrCreateKeyboard(int i, int i2) {
        synchronized (this.keyboardCache) {
            Long lM6657c = makeCacheKey(i, i2);
            if (this.keyboardCache.containsKey(lM6657c)) {
                EmojiKeyboard cached = this.keyboardCache.get(lM6657c);
                return cached;
            }
            if (i == 0) {
                EmojiKeyboard c0970a = new EmojiKeyboard(this.sharedPreferences, this.keyboardBuilder.getKeyboard(12), this.maxPageKeyCount, i, this.emojiKeyboardFactory.getLocale());
                c0970a.loadRecentsFromPreferences();
                this.keyboardCache.put(lM6657c, c0970a);
                this.categoryPageCounts[i] = 1;
                return c0970a;
            }
            EmojiCategory category = EmojiCategory.fromId(i);
            if (category == null) {
                this.categoryPageCounts[i] = 0;
                return null;
            }
            List<EmojiKeyboard> listM6584a = this.emojiKeyboardFactory.createEmojiKeyboards(category);
            this.categoryPageCounts[i] = listM6584a.size();
            for (int i3 = 0; i3 < listM6584a.size(); i3++) {
                this.keyboardCache.put(makeCacheKey(i, i3), listM6584a.get(i3));
            }
            return this.keyboardCache.get(lM6657c);
        }
    }

    public int getTotalPageCount() {
        return this.categoryStarts[this.categoryIds.length];
    }

    /**
     * Get the keyboard factory for creating keyboards.
     * Used by overlay to create search/recents keyboards directly.
     */
    public EmojiKeyboardFactory getKeyboardFactory() {
        return this.emojiKeyboardFactory;
    }

    /**
     * Recent emojis for the search/recents overlay: at most {@value #OVERLAY_RECENTS_LIMIT}
     * non-empty entries, read from the preference (not from the Recents keyboard's queue).
     *
     * <p>Reading here never throws: any failure reads as no recents. Entries are returned unparsed;
     * {@link EmojiKeyboardFactory#createRecentsKeyboards} skips any the key-spec parser rejects.
     * Pinned by EmojiRecentsPersistenceTest.
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
