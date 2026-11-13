package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.CountDownTimer;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

import androidx.viewpager2.widget.ViewPager2;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.core.shared.GraphemeUtils;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.internal.KeyboardIconSet;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.keyboard.inputboard.BoardHeightPolicy;
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory;

/**
 * The emoji board: category tabs, paged emoji grids, delete and ABC keys.
 * Search ({@link EmojiSearchQuery}) has two origins. Physical keyboard: keys route to
 * {@link #appendToQuery}/{@link #backspaceQuery}; the first character turns the tab row into a search
 * field, from the second the overlay shows matches on any tab, a pick keeps the query. On-screen with
 * Dynamic Search: {@link #onEmojiOpened} seeds it from the composing word; results on Recents only,
 * and a pick replaces or appends the word.
 */

public final class EmojiPalettesView extends LinearLayout implements TabLayout.OnTabSelectedListener, EmojiPageKeyboardView.OnEmojiKeyListener {

    private static final String TAG = "EmojiPalettesView";


    private final boolean categoryIndicatorEnabled;

    private final int categoryIndicatorDrawable;

    private final int categoryIndicatorBackground;

    private final float indicatorRadiusRatio;

    private final float indicatorGapRatio;

    private final DeleteKeyTouchListener deleteKeyTouchListener;

    private EmojiPagerAdapter pagerAdapter;

    /** The category pager's spare page views; outlives the adapter's detach on every close. */
    private final EmojiPagerAdapter.RetainedPagePool pagePool = new EmojiPagerAdapter.RetainedPagePool();

    /** Pager events, forwarded below. Out-of-range positions are ignored: ViewPager2 reports them with no pages. */
    private final ViewPager2.OnPageChangeCallback pageChangeCallback = new ViewPager2.OnPageChangeCallback() {
        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
            if (isPage(position)) {
                EmojiPalettesView.this.onPageScrolled(position, positionOffset, positionOffsetPixels);
            }
        }

        @Override
        public void onPageSelected(int position) {
            if (isPage(position)) {
                EmojiPalettesView.this.onPageSelected(position);
            }
        }

        @Override
        public void onPageScrollStateChanged(int state) {
            EmojiPalettesView.this.onPageScrollStateChanged(state);
        }
    };

    private final EmojiPalettesLayoutParams layoutParams;

    private final KeyboardSwitcher keyboardSwitcher;

    private ImageButton deleteButton;

    /** The category tabs, one per category with pages, in tab order; each tab's tag is its category id. */
    private TabLayout tabLayout;

    private ViewPager2 viewPager;

    private int currentPagePosition;

    private EmojiCategoryPageIndicatorView pageIndicatorView;

    private KeyboardActionListenerInterface keyboardActionListener;

    private final EmojiCategoryManager categoryManager;

    /** One skin-tone popup view for the whole board (pager and overlay), inflated on the first long-press. */
    private final EmojiPageKeyboardView.SharedMoreKeysPanel moreKeysPanel = new EmojiPageKeyboardView.SharedMoreKeysPanel();

    // Search query state (there is no on-screen text field).
    private final EmojiSearchQuery query = new EmojiSearchQuery();
    private android.os.Handler searchHandler;
    private Runnable pendingSearchRunnable;
    private ExecutorService searchExecutor;
    private boolean attached = false;
    private static final long SEARCH_DEBOUNCE_MS = 150;

    // Search/Recents overlay, separate from the category pager to avoid state conflicts.
    private EmojiSearchOverlayView searchOverlay;
    private FrameLayout pagerContainer;

    /**
     * A swipe on the Recents overlay fake-drags the category pager behind it, and the overlay follows
     * ({@link #onPageScrolled}); landing on the next category hides it. Only for Recents, not typed results.
     */
    private final EmojiSearchOverlayView.PageHandoff recentsHandoff = new EmojiSearchOverlayView.PageHandoff() {
        @Override
        public boolean canHandOff() {
            return !query.isTypedInBoard()
                    && categoryManager.getCurrentCategoryId() == 0
                    && viewPager.getAdapter() != null
                    && viewPager.canScrollHorizontally(1);
        }

        @Override
        public boolean beginDrag() {
            return viewPager.beginFakeDrag();
        }

        @Override
        public void dragBy(float dx) {
            if (viewPager.isFakeDragging()) {
                viewPager.fakeDragBy(dx);
            }
        }

        @Override
        public void endDrag() {
            if (viewPager.isFakeDragging()) {
                viewPager.endFakeDrag();
            }
        }
    };

    // The tab row and the search field that takes its place while a query is typed.
    private View tabRow;
    private View searchFieldRow;
    private ImageView searchIcon;
    private TextView searchQueryText;
    private ImageButton clearSearchButton;

    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = () -> {
        applyColors();
        return kotlin.Unit.INSTANCE;
    };


    public EmojiPalettesView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.emojiPalettesViewStyle);
    }

    /** @param i default style attribute */
    public EmojiPalettesView(Context context, AttributeSet attributeSet, int i) throws Resources.NotFoundException {
        super(context, attributeSet, i);
        this.currentPagePosition = 0;
        this.keyboardActionListener = KeyboardActionListenerInterface.EMPTY;
        Resources resources = context.getResources();
        this.layoutParams = new EmojiPalettesLayoutParams(resources);
        // Emoji pages are pager-height, so this passes its own geometry; the themed context comes
        // from the shared factory.
        KeyboardBuilder c0978hM6751b = BoardKeyboardFactory.builder(
                context,
                SubtypeManager.getInstance().getEmojiSubtype(),
                ResourceConfigManager.getScreenWidthPixels(resources),
                this.layoutParams.pagerHeight);
        TypedArray typedArrayObtainStyledAttributes2 = context.obtainStyledAttributes(attributeSet, R.styleable.EmojiPalettesView, i, R.style.EmojiPalettesView);
        this.categoryManager = new EmojiCategoryManager(PrefsManager.INSTANCE.getPrefs(context), context, c0978hM6751b, typedArrayObtainStyledAttributes2);
        this.categoryIndicatorEnabled = typedArrayObtainStyledAttributes2.getBoolean(R.styleable.EmojiPalettesView_categoryIndicatorEnabled, false);
        this.categoryIndicatorDrawable = typedArrayObtainStyledAttributes2.getResourceId(R.styleable.EmojiPalettesView_categoryIndicatorDrawable, 0);
        this.categoryIndicatorBackground = typedArrayObtainStyledAttributes2.getResourceId(R.styleable.EmojiPalettesView_categoryIndicatorBackground, 0);
        this.indicatorRadiusRatio = typedArrayObtainStyledAttributes2.getFloat(R.styleable.EmojiPalettesView_emojiPageIndicatorRadiusRatio, 0.0f);
        this.indicatorGapRatio = typedArrayObtainStyledAttributes2.getFloat(R.styleable.EmojiPalettesView_emojiPageIndicatorHorizontalGapRatio, 0.0f);
        typedArrayObtainStyledAttributes2.recycle();
        this.deleteKeyTouchListener = new DeleteKeyTouchListener(context);
        this.keyboardSwitcher = KeyboardSwitcher.getInstance();
    }

    public void cleanup() {
        setKeyboardActionListener(KeyboardActionListenerInterface.EMPTY);
        detachPagerAdapter();
    }

    @Override // android.widget.LinearLayout, android.view.View
    protected void onMeasure(int i, int i2) {
        // Unlike other boards, super runs first (the pager and tabs need their own measure pass)
        // and there is no EXACTLY re-measure.
        super.onMeasure(i, i2);
        setMeasuredDimension(
                BoardHeightPolicy.measuredWidth(this),
                BoardHeightPolicy.measuredHeight(this, this.keyboardSwitcher, this.layoutParams.keyboardHeight));
    }

    /**
     * Appends the tab for {@code categoryId}; the first is selected with no callback (no listener yet).
     * The icon is a custom view with id {@code android:id/icon} (where TabLayout applies its tint). Tabs are
     * unfocusable, so physical keys reach the query, and silent: the board plays its own feedback.
     */
    private void addCategoryTab(int categoryId) {
        TabLayout.Tab tab = this.tabLayout.newTab();
        tab.setTag(categoryId);
        tab.setCustomView(LayoutInflater.from(this.tabLayout.getContext())
                .inflate(R.layout.emoji_keyboard_tab_icon, (ViewGroup) null));
        tab.setIcon(this.categoryManager.getCategoryIconId(categoryId));
        this.tabLayout.addTab(tab);
        tab.view.setFocusable(false);
        tab.view.setSoundEffectsEnabled(false);
        tab.view.setHapticFeedbackEnabled(false);
    }

    @Override // android.view.View
    protected void onFinishInflate() {
        super.onFinishInflate();
        this.tabLayout = (TabLayout) findViewById(R.id.emoji_category_tabhost);
        // Never takes key focus from the editor (the XML says so too; a style must not undo it).
        this.tabLayout.setFocusable(false);
        this.tabLayout.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        for (int categoryId : this.categoryManager.getCategoryIds()) {
            addCategoryTab(categoryId);
        }
        this.tabLayout.addOnTabSelectedListener(this);
        // Category indicator strip (no shipped style enables it): the background plus the strip
        // drawable as the selected-tab indicator.
        if (this.categoryIndicatorEnabled) {
            if (this.categoryIndicatorDrawable != 0) {
                this.tabLayout.setBackgroundResource(this.categoryIndicatorDrawable);
            }
            if (this.categoryIndicatorBackground != 0) {
                android.graphics.drawable.Drawable strip =
                        getContext().getDrawable(this.categoryIndicatorBackground);
                // Colour 0 leaves the drawable untinted.
                this.tabLayout.setSelectedTabIndicatorColor(android.graphics.Color.TRANSPARENT);
                this.tabLayout.setSelectedTabIndicator(strip);
                if (strip != null) {
                    this.tabLayout.setSelectedTabIndicatorHeight(strip.getIntrinsicHeight());
                }
            }
        }
        // Before the pager setup.
        this.pagerContainer = findViewById(R.id.emoji_pager_container);
        this.searchOverlay = findViewById(R.id.emoji_search_overlay);

        this.pagerAdapter = new EmojiPagerAdapter(this.categoryManager, this, this.moreKeysPanel);
        this.viewPager = (ViewPager2) findViewById(R.id.emoji_keyboard_pager);
        // One page either side, three spare views, no cache or prefetch.
        EmojiPagerAdapter.configure(this.viewPager, this.pagePool);
        this.viewPager.setAdapter(this.pagerAdapter);
        this.viewPager.registerOnPageChangeCallback(this.pageChangeCallback);

        // The container, not the pager, is the LinearLayout's child.
        this.layoutParams.applyToPagerContainer(this.pagerContainer);

        this.pageIndicatorView = (EmojiCategoryPageIndicatorView) findViewById(R.id.emoji_category_page_id_view);
        this.pageIndicatorView.setIndicatorRatios(this.indicatorRadiusRatio, this.indicatorGapRatio);
        this.layoutParams.applyToIndicatorView(this.pageIndicatorView);

        if (this.searchOverlay != null) {
            this.searchOverlay.setOnKeyEventListener(this);
            this.searchOverlay.setPageHandoff(this.recentsHandoff);
            this.searchOverlay.setPageIndicator(this.pageIndicatorView);
            this.searchOverlay.setSharedMoreKeysPanel(this.moreKeysPanel);
        }

        setCategory(this.categoryManager.getCurrentCategoryId(), true);
        this.deleteButton = (ImageButton) findViewById(R.id.emoji_keyboard_delete);
        this.deleteButton.setTag(-5);
        this.deleteButton.setOnTouchListener(this.deleteKeyTouchListener);

        this.tabRow = findViewById(R.id.emoji_tab_row);
        this.searchFieldRow = findViewById(R.id.emoji_search_field);
        this.searchIcon = findViewById(R.id.emoji_search_icon);
        this.searchQueryText = findViewById(R.id.emoji_search_query);
        this.clearSearchButton = findViewById(R.id.emoji_search_clear);
        this.clearSearchButton.setOnClickListener(v -> {
            AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(-21, this);
            clearQuery();
        });

        applyColors();

        initSearch();
    }

    /** Sets up the search handler, warms the emoji data and shows recents if starting on Recents. */
    private void initSearch() {
        searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // getSharedLoaded() locks its own parse, so this cannot duplicate it. The result is not
        // kept: a field would pin a released dataset.
        searchExecutor().execute(() -> EmojibaseDataProvider.getSharedLoaded(getContext()));

        updateRecentsForCategory(categoryManager.getCurrentCategoryId());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        KeyboardColorManager.INSTANCE.addObserver(colorObserver);
    }

    @Override
    protected void onDetachedFromWindow() {
        KeyboardColorManager.INSTANCE.removeObserver(colorObserver);
        super.onDetachedFromWindow();
        attached = false;

        if (pendingSearchRunnable != null && searchHandler != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }

        // searchExecutor() recreates it on re-attach (initSearch() runs only from onFinishInflate).
        if (searchExecutor != null) {
            if (!searchExecutor.isShutdown()) {
                searchExecutor.shutdown();
            }
            searchExecutor = null;
        }
    }

    /** The background search executor, created on demand; never a shut-down one. */
    private ExecutorService searchExecutor() {
        if (searchExecutor == null || searchExecutor.isShutdown()) {
            searchExecutor = Executors.newSingleThreadExecutor();
        }
        return searchExecutor;
    }

    private void debounceSearch(String queryText) {

        if (searchHandler == null) {
            return;
        }

        cancelPendingSearch();

        final int generation = query.generation();
        pendingSearchRunnable = () -> performSearchAsync(queryText, generation);
        searchHandler.postDelayed(pendingSearchRunnable, SEARCH_DEBOUNCE_MS);
    }

    private void cancelPendingSearch() {
        if (pendingSearchRunnable != null && searchHandler != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
        }
        pendingSearchRunnable = null;
    }

    /** Searches in the background and posts results to the main thread; the query has 2+ characters. */
    private void performSearchAsync(String queryText, int generation) {
        if (!attached) {
            return;
        }

        searchExecutor().execute(() -> {
            if (!attached) {
                return;
            }

            List<EmojiData> results =
                    EmojibaseDataProvider.getSharedLoaded(getContext()).searchEmojis(queryText);

            postToMainThread(() -> {
                if (!attached) return;
                // The query changed while this search ran (shortened, cleared, or retyped): drop it.
                if (generation != query.generation()) return;
                showSearchResults(results);
            });
        });
    }

    private void postToMainThread(Runnable action) {
        if (searchHandler != null) {
            searchHandler.post(action);
        }
    }

    /** Recents in the overlay; with no recents the overlay hides and the category pager shows. */
    private void showRecents() {
        if (!overlayMayShow()) {
            return;
        }
        // Results of a typed query are never displaced by recents.
        if (query.isTypedInBoard() && query.isSearchable()) {
            return;
        }
        List<String> recentEmojis = categoryManager.getRecentEmojis();
        if (recentEmojis.isEmpty()) {
            searchOverlay.hide();
        } else {
            searchOverlay.showKeyboards(categoryManager.getKeyboardFactory().createRecentsKeyboards(recentEmojis));
        }
    }

    /**
     * Search results in the overlay. With none, a typed query shows an empty page (so the failure is
     * visible), and an editor query hides the overlay to show the category pager.
     */
    private void showSearchResults(List<EmojiData> results) {
        if (!overlayMayShow()) {
            return;
        }
        EmojiKeyboardFactory factory = categoryManager.getKeyboardFactory();
        if (results.isEmpty()) {
            if (query.isTypedInBoard()) {
                searchOverlay.showKeyboards(factory.createEmptyResultsPage());
            } else {
                searchOverlay.hide();
            }
        } else {
            searchOverlay.showKeyboards(factory.createSearchResultsKeyboards(results));
        }
        updateDotsForTypedQuery();
    }

    /**
     * With the search field up, dots show only for multi-page results, else blank. The row itself stays
     * so the board keeps its height; tab dots return with the tab row ({@link #updatePageIndicator}).
     */
    private void updateDotsForTypedQuery() {
        if (pageIndicatorView == null || !query.isTypedInBoard()) {
            return;
        }
        pageIndicatorView.setVisibility(VISIBLE);
        boolean multiPage = searchOverlay != null && searchOverlay.isOverlayVisible()
                && searchOverlay.getPageCount() > 1;
        if (!multiPage) {
            // One page draws no dots (the overlay sets the real count itself when it has pages).
            pageIndicatorView.setPageInfo(1, 0, 0.0f);
        }
    }

    /**
     * Whether the overlay may change now: always for a typed query; for an editor query only on
     * Recents (0), so delayed results do not land on a tab the user moved to.
     */
    private boolean overlayMayShow() {
        if (searchOverlay == null) {
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Search overlay not initialized");
            return false;
        }
        return query.isTypedInBoard() || categoryManager.getCurrentCategoryId() == 0;
    }

    /**
     * A hardware key typed with the board open (from {@code KeyEventProcessor}); space is allowed.
     * @return false only for empty input; true means the board took the key
     */
    public boolean appendToQuery(CharSequence chars) {
        if (chars == null || chars.length() == 0) {
            return false;
        }
        boolean started = query.append(chars, categoryManager.getCurrentCategoryId());
        if (started) {
            showSearchField();
        }
        searchQueryText.setText(query.text());
        refreshTypedResults();
        return true;
    }

    /**
     * Hardware Backspace with the board open; removing the last character ends the query.
     * @return false when there is no typed query, so the key passes through to the editor
     */
    public boolean backspaceQuery() {
        if (!query.isTypedInBoard() || query.isEmpty()) {
            return false;
        }
        query.backspace();
        if (query.isEmpty()) {
            endTypedQuery();
        } else {
            searchQueryText.setText(query.text());
            refreshTypedResults();
        }
        return true;
    }

    /** Ends whichever query is active: the clear button, and every route that closes the board. */
    public void clearQuery() {
        if (query.isTypedInBoard()) {
            endTypedQuery();
        } else if (query.isFromEditor()) {
            query.clear();
            cancelPendingSearch();
        }
    }

    /** Whether a typed query is active, i.e. the search field stands in for the tab row. */
    public boolean isSearchFieldShowing() {
        return query.isTypedInBoard();
    }

    /** The current query text, of either origin; empty when there is none. */
    public String getQueryText() {
        return query.text();
    }

    private void endTypedQuery() {
        query.clear();
        cancelPendingSearch();
        hideSearchField();
        if (searchOverlay != null) {
            searchOverlay.hide();
        }
        updatePageIndicator();
        // The tabs were hidden throughout, so the current category is still the pre-query tab.
        updateRecentsForCategory(categoryManager.getCurrentCategoryId());
    }

    /** Searches a typed query of two or more characters; a shorter one shows the tab's own page. */
    private void refreshTypedResults() {
        if (query.isSearchable()) {
            debounceSearch(query.text());
            return;
        }
        cancelPendingSearch();
        if (categoryManager.getCurrentCategoryId() == 0) {
            showRecents();
        } else if (searchOverlay != null) {
            searchOverlay.hide();
        }
        updateDotsForTypedQuery();
    }

    private void showSearchField() {
        if (tabRow == null || searchFieldRow == null) {
            return;
        }
        tabRow.setVisibility(GONE);
        searchFieldRow.setVisibility(VISIBLE);
        // No results yet: blank dots over the same background until a multi-page result set.
        if (pageIndicatorView != null) {
            pageIndicatorView.setVisibility(VISIBLE);
            pageIndicatorView.setPageInfo(1, 0, 0.0f);
        }
    }

    private void hideSearchField() {
        if (tabRow == null || searchFieldRow == null) {
            return;
        }
        searchQueryText.setText("");
        searchFieldRow.setVisibility(GONE);
        tabRow.setVisibility(VISIBLE);
    }

    /**
     * Opened on-screen with Dynamic Search: the composing word becomes the query (2+ chars search,
     * fewer show recents). Each call supersedes the last; stale results are dropped.
     */
    public void onEmojiOpened(String composingText) {
        if (query.isTypedInBoard()) {
            endTypedQuery();
        }
        query.setFromEditor(composingText);
        if (query.isSearchable()) {
            debounceSearch(query.text());
        } else {
            cancelPendingSearch();
            showRecents();
        }
    }

    /** An editor query long enough to search; a pick then replaces or appends the composing word. */
    public boolean isDynamicSearchActive() {
        return query.isFromEditor() && query.isSearchable();
    }

    private boolean isReplaceMode() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        return settings != null && settings.isEmojiSearchReplaceText;
    }

    /** Shows recents in the overlay on category 0; an active dynamic query replaces them once debounced. */
    private void updateRecentsForCategory(int categoryId) {
        if (categoryId == 0) {
            showRecents();
        }
    }

    @Override // TabLayout.OnTabSelectedListener
    public void onTabSelected(TabLayout.Tab tab) {
        onTabChanged((Integer) tab.getTag());
    }

    @Override // TabLayout.OnTabSelectedListener
    public void onTabUnselected(TabLayout.Tab tab) {
        // onTabSelected handles the change.
    }

    /** Ignored: reselecting the current tab does nothing. */
    @Override // TabLayout.OnTabSelectedListener
    public void onTabReselected(TabLayout.Tab tab) {
    }

    /**
     * A tab became current, by a tap or from {@link #setCategory}. Re-entry is harmless: the category
     * is already current when setCategory selects the tab.
     */
    private void onTabChanged(int iM6661a) {
        AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(-21, this);

        // On-screen only: with a physical keyboard the query is what is typed into the board.
        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        boolean dynamicSearch = sv != null && sv.isEmojiDynamicSearchEnabled
                && DeviceProfile.isOnScreenKeyboardVisible();
        boolean isSearchCategory = iM6661a == 0;

        if (!isSearchCategory) {
            if (searchOverlay != null && searchOverlay.isOverlayVisible()) {
                searchOverlay.hide();
            }
            if (dynamicSearch && query.isFromEditor()) {
                query.clear();
            }
        } else if (dynamicSearch) {
            // Back on Recents in dynamic mode: search the composing text again.
            String composing = KeyboardSwitcher.getInstance().getComposingText();
            onEmojiOpened(composing);
        }

        setCategory(iM6661a, false);
        updatePageIndicator();

        updateRecentsForCategory(iM6661a);
    }

    /** @param z true while switching to the emoji board */
    public void setSwitchingToEmoji(boolean z) {
        this.pagerAdapter.setSwitchingToEmoji(z);
    }

    private boolean isPage(int position) {
        return position >= 0 && position < this.categoryManager.getTotalPageCount();
    }

    /** The pager's current page changed (ViewPager2.OnPageChangeCallback). */
    public void onPageSelected(int i) {
        this.pagerAdapter.onPageSelected(i);
        setCategory(this.categoryManager.getCategoryAt(i), false);
        this.categoryManager.setCurrentPageInCategory(this.categoryManager.getPageInCategoryAt(i));
        updatePageIndicator();
        this.currentPagePosition = i;
    }

    /** ViewPager2.OnPageChangeCallback: a drag starting releases the current page's keys. */
    public void onPageScrollStateChanged(int i) {
        if (i == ViewPager2.SCROLL_STATE_DRAGGING) {
            this.pagerAdapter.releaseCurrentPage();
        }
    }

    /** ViewPager2.OnPageChangeCallback: the page dots follow the scroll within the current category. */
    public void onPageScrolled(int i, float f, int i2) {
        followRecentsPage(i, i2);
        int iIntValue = this.categoryManager.getCategoryAt(i);
        int iM6668c = this.categoryManager.getPageCount(iIntValue);
        int iM6664b = this.categoryManager.getCurrentCategoryId();
        int iM6669d = this.categoryManager.getCurrentPageInCategory();
        int iM6667c = this.categoryManager.getCurrentPageCount();
        if (iIntValue == iM6664b) {
            this.pageIndicatorView.setPageInfo(iM6668c, this.categoryManager.getPageInCategoryAt(i), f);
        } else if (iIntValue > iM6664b) {
            this.pageIndicatorView.setPageInfo(iM6667c, iM6669d, f);
        } else if (iIntValue < iM6664b) {
            this.pageIndicatorView.setPageInfo(iM6667c, iM6669d, f - 1.0f);
        }
    }

    /** The Recents overlay moves with the Recents page it covers during a swipe. */
    private void followRecentsPage(int position, int offsetPixels) {
        if (this.searchOverlay == null) {
            return;
        }
        float x = 0f;
        if (this.searchOverlay.isOverlayVisible() && !this.query.isTypedInBoard()
                && this.categoryManager.getCurrentCategoryId() == 0
                && this.categoryManager.getCategoryAt(position) == 0) {
            x = -offsetPixels;
        }
        if (this.searchOverlay.getTranslationX() != x) {
            this.searchOverlay.setTranslationX(x);
        }
    }

    /**
     * A remembered tone changed with the board open: rebuilds that emoji's keys in pages and overlay.
     * Posted: this runs inside the popup's key release, and the page must not change until it closes.
     */
    @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
    public void onToneMemoryChanged(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return;
        }
        final String base = EmojiToneMemory.normalize(emoji);
        post(() -> retone(base));
    }

    /** Rebuilds the keys of the emoji whose base form is {@code base}; see {@link #onToneMemoryChanged}. */
    void retone(String base) {
        this.pagerAdapter.refreshPages(this.categoryManager.retone(base));
        if (this.searchOverlay != null) {
            final EmojiKeyboardFactory factory = this.categoryManager.getKeyboardFactory();
            this.searchOverlay.replaceKeys(key -> factory.retoneKey(key, base));
        }
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
    public void onEmojiKeyPressed(Key key) {
        this.keyboardActionListener.onPressKey(key.getCode(), 0, true);
    }

    /** A pick from the skin-tone popup reaches Recents like a tapped key (the popup already skips incognito fields). */
    @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
    public void onPopupEmojiPicked(String emoji) {
        if (emoji == null || emoji.isEmpty()) {
            return;
        }
        this.pagerAdapter.addToRecents(emoji);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
    public void onEmojiKeyReleased(Key key) {
        // Editor query: replace or append the composing word and end the query. A typed query stays.
        if (isDynamicSearchActive()) {
            if (isReplaceMode()) {
                KeyboardSwitcher.getInstance().clearComposingText();
            } else {
                KeyboardSwitcher.getInstance().commitComposingText();
            }
            query.clear();
        }

        // Recents and tones persist to prefs, so incognito fields skip them.
        if (!KeyboardSwitcher.getInstance().isNoPersonalizedLearning()) {
            this.pagerAdapter.addToRecents(key.getKeySpecOutputText());
            if (EmojiToneMemory.onEmojiPicked(PrefsManager.INSTANCE.getPrefs(getContext()), key.getKeySpecOutputText())) {
                onToneMemoryChanged(key.getKeySpecOutputText());
            }
        }
        int iM6232c = key.getCode();
        if (iM6232c == -4) {
            int iM5661a = GraphemeUtils.surrogatePairToCodePoint(key.getKeySpecOutputText());
            if (iM5661a != 0) {
                this.keyboardActionListener.onCodeInput(iM5661a, -1, -1, 0L, false);
            } else {
                this.keyboardActionListener.onTextInput(key.getKeySpecOutputText(), 0L);
            }
        } else {
            this.keyboardActionListener.onCodeInput(iM6232c, -1, -1, 0L, false);
        }
        this.keyboardActionListener.onReleaseKey(iM6232c, false);
    }

    /** @param z true to enable hardware-accelerated drawing */
    public void setHardwareAcceleratedDrawingEnabled(boolean z) {
        if (z) {
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
    }

    /** The delete key's icon for the current language (a mirrored one for RTL scripts). */
    public void updateDeleteButton(KeyboardIconSet icons, String languageCode) {
        int iconId;
        if (languageCode.equals("ar") || languageCode.equals("fa") || languageCode.equals("iw")) {
            iconId = icons.getIconResourceId("delete_rtl_key");
        } else {
            iconId = icons.getIconResourceId("delete_key");
        }
        if (iconId != 0) {
            this.deleteButton.setImageResource(iconId);
            if (KeyboardColorManager.INSTANCE.isInitialized()) {
                KeyboardColorManager.INSTANCE.tint(this.deleteButton);
            }
        }
    }

    /** Re-attaches the pager adapter after {@link #detachPagerAdapter()}, at the page the user left. */
    public void attachPagerAdapter() {
        // Pages are kept across closes; drop only what went stale before rebinding.
        this.categoryManager.prepareForOpen();
        this.viewPager.setAdapter(this.pagerAdapter);
        // Not smooth: jump straight to the page (the one-argument setCurrentItem animates).
        this.viewPager.setCurrentItem(this.currentPagePosition, false);
        // The overlay keeps its last grid, and Recents picks reach the preference only on close,
        // so rebuild it or a reopen on Recents shows stale recents.
        updateRecentsForCategory(this.categoryManager.getCurrentCategoryId());
    }

    public void detachPagerAdapter() {
        // Often already detached: leaveEmojiModeInternal() calls this on every main setKeyboard.
        if (this.viewPager.getAdapter() == null) {
            return;
        }
        this.pagerAdapter.commitPendingRecents();
        final int position = this.viewPager.getCurrentItem();
        // Pages go to pagePool, which survives this. Nothing lays out until the next attach sets
        // the remembered page, so that open binds only the pages around it.
        this.viewPager.setAdapter(null);
        // Keep what the next open binds first; prepareForOpen() drops stale pages.
        this.categoryManager.retainPagesAround(position);
    }

    /** @param interfaceC0976f the key action listener */
    public void setKeyboardActionListener(KeyboardActionListenerInterface interfaceC0976f) {
        this.keyboardActionListener = interfaceC0976f;
        this.deleteKeyTouchListener.setActionListener(this.keyboardActionListener);
    }

    private void updatePageIndicator() {
        EmojiCategoryPageIndicatorView emojiCategoryPageIndicatorView = this.pageIndicatorView;
        if (emojiCategoryPageIndicatorView == null) {
            return;
        }
        emojiCategoryPageIndicatorView.setPageInfo(this.categoryManager.getCurrentPageCount(), this.categoryManager.getCurrentPageInCategory(), 0.0f);
    }

    /**
     * @param i the category id
     * @param z true to apply even if already current
     */
    private void setCategory(int i, boolean z) {
        int iM6664b = this.categoryManager.getCurrentCategoryId();
        if (iM6664b != i || z) {
            if (iM6664b == 0) {
                this.pagerAdapter.commitPendingRecents();
            }
            this.categoryManager.setCurrentCategoryId(i);
            int iM6674f = this.categoryManager.getCategoryIndex(i);
            int iM6676g = this.categoryManager.getCategoryStartPosition(i);
            if (z || this.categoryManager.getCategoryAt(this.viewPager.getCurrentItem()) != i) {
                this.viewPager.setCurrentItem(iM6676g, false);
            }
            if (z || this.tabLayout.getSelectedTabPosition() != iM6674f) {
                // Selected state drives the icon tint (applyColors' ColorStateList).
                this.tabLayout.selectTab(this.tabLayout.getTabAt(iM6674f));
            }
        }
    }

    private void applyColors() {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) return;
        boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
        // Modern boards sit on the keyboard background so emoji caps (keyColor) read against it.
        int panelColor = modernBoards
                ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                : KeyboardColorManager.INSTANCE.getKeyColor();
        this.viewPager.setBackgroundColor(panelColor);
        this.deleteButton.setBackgroundColor(panelColor);
        KeyboardColorManager.INSTANCE.tint(this.deleteButton);
        // Modern boards: rounded state-layer press feedback on delete (legacy: none).
        this.deleteButton.setForeground(
                modernBoards ? KeyboardColorManager.pressedHighlight() : null);
        ((android.view.View) this.tabLayout.getParent()).setBackgroundColor(panelColor);
        this.pageIndicatorView.setColors(
            modernBoards ? KeyboardColorManager.INSTANCE.getAccentColor()
                     : KeyboardColorManager.INSTANCE.getIconColor(),
            KeyboardColorManager.INSTANCE.getIconColor(0.3f),
            panelColor
        );
        // Search field: the tab row's surface; icon in accent (modern) or icon colour; clear button like delete.
        if (this.searchFieldRow != null) {
            this.searchFieldRow.setBackgroundColor(panelColor);
            KeyboardColorManager.INSTANCE.tint(this.searchIcon,
                    modernBoards ? KeyboardColorManager.INSTANCE.getAccentColor()
                                 : KeyboardColorManager.INSTANCE.getIconColor());
            this.searchQueryText.setTextColor(KeyboardColorManager.INSTANCE.getTextColor());
            KeyboardColorManager.INSTANCE.tint(this.clearSearchButton);
            this.clearSearchButton.setForeground(
                    modernBoards ? KeyboardColorManager.pressedHighlight() : null);
        }
        // Category icons: icon colour when selected, secondary otherwise. Re-applied on every colour
        // change (colorObserver).
        this.tabLayout.setTabIconTint(new android.content.res.ColorStateList(
                new int[][] { { android.R.attr.state_selected }, {} },
                new int[] {
                        KeyboardColorManager.INSTANCE.getIconColor(),
                        KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY)
                }));
    }

    /**
     * Whether the palettes are on screen. A view question, not "is emoji open", which KeyboardState
     * answers through {@link EmojiBoardController}.
     */
    public boolean isPalettesVisible() {
        return isShown() && this.viewPager != null && this.viewPager.getVisibility() == VISIBLE;
    }


    private static class DeleteKeyTouchListener implements View.OnTouchListener {

        static final long MAX_REPEAT_DURATION = TimeUnit.SECONDS.toMillis(30);

        final long repeatStartDelay;

        final long repeatInterval;

        private final CountDownTimer repeatTimer;

        private KeyboardActionListenerInterface keyboardActionListener = KeyboardActionListenerInterface.EMPTY;

        private int touchState = 0;

        private int repeatCount = 0;

        public DeleteKeyTouchListener(Context context) {
            Resources resources = context.getResources();
            this.repeatStartDelay = resources.getInteger(R.integer.config_key_repeat_start_timeout);
            this.repeatInterval = resources.getInteger(R.integer.config_key_repeat_interval);
            this.repeatTimer = new CountDownTimer(MAX_REPEAT_DURATION, this.repeatInterval) {
                @Override // android.os.CountDownTimer
                public void onTick(long j) {
                    if (DeleteKeyTouchListener.MAX_REPEAT_DURATION - j < DeleteKeyTouchListener.this.repeatStartDelay) {
                        return;
                    }
                    DeleteKeyTouchListener.this.onRepeatTick();
                }

                @Override // android.os.CountDownTimer
                public void onFinish() {
                    DeleteKeyTouchListener.this.onRepeatTick();
                }
            };
        }

        /** @param interfaceC0976f the key action listener */
        public void setActionListener(KeyboardActionListenerInterface interfaceC0976f) {
            this.keyboardActionListener = interfaceC0976f;
        }

        @Override // android.view.View.OnTouchListener
        public boolean onTouch(View view, MotionEvent motionEvent) {
            switch (motionEvent.getActionMasked()) {
                case 0:
                    onTouchDown(view);
                    break;
                case 1:
                case 3:
                    onTouchUp(view);
                    break;
                case 2:
                    float x = motionEvent.getX();
                    float y = motionEvent.getY();
                    if (x < 0.0f || view.getWidth() < x || y < 0.0f || view.getHeight() < y) {
                        onTouchCancel(view);
                        break;
                    }
                    break;
            }
            return true;
        }

        private void sendPressEvent() {
            this.keyboardActionListener.onPressKey(-5, this.repeatCount, true);
        }

        private void sendReleaseEvent() {
            this.keyboardActionListener.onCodeInput(-5, -1, -1, 0L, false);
            this.keyboardActionListener.onReleaseKey(-5, false);
            this.repeatCount++;
        }

        private void onTouchDown(View view) {
            this.repeatTimer.cancel();
            this.repeatCount = 0;
            sendPressEvent();
            view.setPressed(true);
            this.touchState = 1;
            this.repeatTimer.start();
        }

        private void onTouchUp(View view) {
            this.repeatTimer.cancel();
            if (this.touchState == 1) {
                sendReleaseEvent();
            }
            view.setPressed(false);
            this.touchState = 0;
        }

        private void onTouchCancel(View view) {
            this.repeatTimer.cancel();
            this.touchState = 0;
        }

        void onRepeatTick() {
            switch (this.touchState) {
                case 1:
                    sendReleaseEvent();
                    this.touchState = 2;
                    break;
                case 2:
                    sendPressEvent();
                    sendReleaseEvent();
                    break;
            }
        }
    }
}
