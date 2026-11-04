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
import android.widget.TabHost;
import android.widget.TabWidget;
import android.widget.TextView;

import androidx.viewpager.widget.ViewPager;

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
 * Main emoji picker view that displays emoji categories, pages, and controls.
 * Provides a tabbed interface with category tabs, paginated emoji grids, and functional
 * keys (delete, alphabet switch). Integrates with the unified input board system and
 * supports both legacy emoji data and modern Emojibase data sources.
 *
 * <h2>Search</h2>
 *
 * <p>The query lives in {@link EmojiSearchQuery} and has two origins. On a physical keyboard the
 * user types into the open board: {@code KeyEventProcessor} routes each printable key to
 * {@link #appendToQuery} and Backspace to {@link #backspaceQuery}. The first character turns the
 * tab row into the search field (search icon, the query, a clear button where the delete key was)
 * and the page dots show only while the results span more than one page; from the second
 * character the results overlay shows the matches whatever tab was current; a pick inserts the
 * emoji and leaves the query alone; Backspace on the
 * last character, the clear button, or closing the board ends it and the tab row returns. On an
 * on-screen keyboard, with Dynamic Search on, {@link #onEmojiOpened} seeds the query from the word
 * being composed and today's behaviour stands: results on the Recents tab only, and a pick replaces
 * or appends the word. Owner decisions, 2026-11-02.
 */

public final class EmojiPalettesView extends LinearLayout implements ViewPager.OnPageChangeListener, TabHost.OnTabChangeListener, EmojiPageKeyboardView.OnEmojiKeyListener {

    private static final String TAG = "EmojiPalettesView";

    private final boolean categoryIndicatorEnabled;

    private final int categoryIndicatorDrawable;

    private final int categoryIndicatorBackground;

    private final float indicatorRadiusRatio;

    private final float indicatorGapRatio;

    private final DeleteKeyTouchListener deleteKeyTouchListener;

    private EmojiPagerAdapter pagerAdapter;

    private final EmojiPalettesLayoutParams layoutParams;

    private final KeyboardSwitcher keyboardSwitcher;

    private ImageButton deleteButton;

    private TabHost tabHost;

    private ViewPager viewPager;

    private int currentPagePosition;

    private EmojiCategoryPageIndicatorView pageIndicatorView;

    private KeyboardActionListenerInterface keyboardActionListener;

    private final EmojiCategoryManager categoryManager;

    // The search query and its machinery (no on-screen text field: a physical keyboard types into
    // the board, the on-screen keyboard seeds it from the composing word).
    private final EmojiSearchQuery query = new EmojiSearchQuery();
    private android.os.Handler searchHandler;
    private Runnable pendingSearchRunnable;
    private ExecutorService searchExecutor;
    private boolean attached = false;
    private static final long SEARCH_DEBOUNCE_MS = 150;

    // Search/Recents overlay - separate from category ViewPager to avoid state conflicts
    private EmojiSearchOverlayView searchOverlay;
    private FrameLayout pagerContainer;

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

    public EmojiPalettesView(Context context, AttributeSet attributeSet, int i) throws Resources.NotFoundException {
        super(context, attributeSet, i);
        this.currentPagePosition = 0;
        this.keyboardActionListener = KeyboardActionListenerInterface.EMPTY;
        Resources resources = context.getResources();
        this.layoutParams = new EmojiPalettesLayoutParams(resources);
        // The emoji pages are pager-height, not keyboard-height, so this passes its own geometry;
        // the themed context (C7) comes from the shared factory like every other board.
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
        // Note the shape difference from the other boards: super runs FIRST (the pager and tab
        // strip need their own measure pass) and there is no EXACTLY re-measure afterwards. Only
        // the sizing rule is shared.
        super.onMeasure(i, i2);
        setMeasuredDimension(
                BoardHeightPolicy.measuredWidth(this),
                BoardHeightPolicy.measuredHeight(this, this.keyboardSwitcher, this.layoutParams.keyboardHeight));
    }

    private void addCategoryTab(TabHost tabHost, int i) {
        TabHost.TabSpec tabSpecNewTabSpec = tabHost.newTabSpec(this.categoryManager.getCategoryTag(i));
        tabSpecNewTabSpec.setContent(R.id.emoji_keyboard_dummy);
        ImageView imageView = (ImageView) LayoutInflater.from(getContext()).inflate(R.layout.emoji_keyboard_tab_icon, (ViewGroup) null);
        imageView.setImageResource(this.categoryManager.getCategoryIconId(i));

        // Don't apply color here - it will be applied in updateTabIconColors() based on active/inactive state

        tabSpecNewTabSpec.setIndicator(imageView);
        tabHost.addTab(tabSpecNewTabSpec);
    }

    @Override // android.view.View
    protected void onFinishInflate() {
        super.onFinishInflate();
        this.tabHost = (TabHost) findViewById(R.id.emoji_category_tabhost);
        this.tabHost.setup();
        for (int categoryId : this.categoryManager.getCategoryIds()) {
            addCategoryTab(this.tabHost, categoryId);
        }
        this.tabHost.setOnTabChangedListener(this);
        TabWidget tabWidget = this.tabHost.getTabWidget();
        tabWidget.setStripEnabled(this.categoryIndicatorEnabled);
        if (this.categoryIndicatorEnabled) {
            tabWidget.setBackgroundResource(this.categoryIndicatorDrawable);
            tabWidget.setLeftStripDrawable(this.categoryIndicatorBackground);
            tabWidget.setRightStripDrawable(this.categoryIndicatorBackground);
        }
        // Initialize pager container and overlay first (before viewPager setup)
        this.pagerContainer = findViewById(R.id.emoji_pager_container);
        this.searchOverlay = findViewById(R.id.emoji_search_overlay);

        this.pagerAdapter = new EmojiPagerAdapter(this.categoryManager, this);
        this.viewPager = (ViewPager) findViewById(R.id.emoji_keyboard_pager);
        this.viewPager.setAdapter(this.pagerAdapter);
        // addOnPageChangeListener, not the deprecated single-slot setter: a second
        // observer added later would silently displace this one.
        this.viewPager.addOnPageChangeListener(this);
        // setOffscreenPageLimit(0) was clamped to 1 by ViewPager anyway, and logged an
        // ungated framework warning on every input-view creation while doing it.
        // setPersistentDrawingCache() is a no-op on hardware-accelerated views.

        // Apply layout params to pagerContainer (FrameLayout parent of viewPager)
        // since pagerContainer is the direct child of the LinearLayout
        this.layoutParams.applyToPagerContainer(this.pagerContainer);

        this.pageIndicatorView = (EmojiCategoryPageIndicatorView) findViewById(R.id.emoji_category_page_id_view);
        this.pageIndicatorView.setIndicatorRatios(this.indicatorRadiusRatio, this.indicatorGapRatio);
        this.layoutParams.applyToIndicatorView(this.pageIndicatorView);

        // Connect overlay to key listener and page indicator
        if (this.searchOverlay != null) {
            this.searchOverlay.setOnKeyEventListener(this);
            this.searchOverlay.setPageIndicator(this.pageIndicatorView);
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

        // Initialize dynamic search infrastructure
        initSearch();
    }

    /**
     * Initialize the search infrastructure (background executor, handler and emoji data
     * provider). There is no on-screen search bar: a physical keyboard types into the board, the
     * on-screen keyboard seeds the query from the composing word.
     */
    private void initSearch() {
        // Use main looper handler for UI updates
        searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // Warm the process-wide provider off the UI thread. getSharedLoaded()
        // double-checked-locks its own parse, so this does not duplicate it (audit IB-3); the
        // result is not kept in a field, which would pin a released dataset (audit P-2).
        searchExecutor().execute(() -> EmojibaseDataProvider.getSharedLoaded(getContext()));

        // Show recents in the overlay if we start on the recents/search category
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

        // Clean up to prevent memory leaks
        if (pendingSearchRunnable != null && searchHandler != null) {
            searchHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }

        // Shutdown executor gracefully. searchExecutor() recreates it if the view is
        // attached again - initSearch() only runs from onFinishInflate(), so a
        // detach/re-attach without re-inflation used to kill emoji search for the rest
        // of the view's life (audit IB-13).
        if (searchExecutor != null) {
            if (!searchExecutor.isShutdown()) {
                searchExecutor.shutdown();
            }
            searchExecutor = null;
        }
    }

    /**
     * The background search executor, created on demand. Never returns a shut-down
     * executor.
     */
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

    /**
     * Perform search on background thread to keep UI responsive.
     * Results are posted back to main thread. The query is never empty: a search is only
     * scheduled for two or more characters ({@link EmojiSearchQuery#isSearchable()}).
     */
    private void performSearchAsync(String queryText, int generation) {
        if (!attached) {
            return;
        }

        searchExecutor().execute(() -> {
            // Guard: Don't process if view is detached
            if (!attached) {
                return;
            }

            // Perform search on background thread
            List<EmojiData> results =
                    EmojibaseDataProvider.getSharedLoaded(getContext()).searchEmojis(queryText);

            // Post results to main thread
            postToMainThread(() -> {
                if (!attached) return;  // Double-check after post
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
     * Search results in the overlay. With no results, a typed query shows an empty page under
     * its field (so the user sees the query failed rather than the tab behind it); the on-screen
     * dynamic-search query hides the overlay and the category pager shows, as before.
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
     * While the search field is up the page dots are for the results only: drawn when they span
     * more than one page (owner decision 2026-11-03), blank for a single page, an empty page or no
     * results yet. The indicator row itself stays, background and all, so the board keeps its
     * height and colour; the tab row's own dots return with the tab row ({@link #updatePageIndicator}).
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
     * Whether the overlay may change now. A typed query shows its results whatever tab was
     * current when typing began (owner decision 2026-11-02). The on-screen dynamic-search query
     * keeps today's rule and only changes the overlay while the Recents/search category (0) is
     * current, so delayed results do not land on a tab the user has moved to.
     */
    private boolean overlayMayShow() {
        if (searchOverlay == null) {
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Search overlay not initialized");
            return false;
        }
        return query.isTypedInBoard() || categoryManager.getCurrentCategoryId() == 0;
    }

    // ==================== Typed query (physical keyboard) ====================

    /**
     * A hardware key typed while the board is open, routed here by {@code KeyEventProcessor}.
     * The first character turns the tab row into the search field; from the second character the
     * page shows the matches. Space is a space, so a two-word query is allowed.
     *
     * @return false only for empty input; the caller treats true as "the board took the key"
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
     * Hardware Backspace while the board is open. Removes the last character of a typed query;
     * removing the only one ends the query and the tab row returns.
     *
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
        // Back to the tab that was current before the first letter: the tabs were hidden
        // throughout, so it is the current category still.
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

    // ==================== Query from the editor (on-screen dynamic search) ====================

    /**
     * The board was opened from the on-screen keyboard with Dynamic Search on: the word being
     * composed becomes the query. Two or more characters search; fewer show recents.
     *
     * <p>Every call supersedes the previous query: shorter text cancels a debounced search, and a
     * search already running for an older query has its results dropped.
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

    /**
     * Check if dynamic search is currently active: a query from the editor that is long enough to
     * search on. A pick then replaces or appends the composing word.
     */
    public boolean isDynamicSearchActive() {
        return query.isFromEditor() && query.isSearchable();
    }

    /**
     * Check if emoji replace mode is enabled (vs append mode).
     */
    private boolean isReplaceMode() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        return settings != null && settings.isEmojiSearchReplaceText;
    }

    /**
     * Refresh the recents overlay when the visible category changes.
     * Recents are shown in the overlay whenever we land on the Recents/Search
     * category (index 0). In dynamic-search mode an active query will replace the
     * recents shortly afterwards via the debounced search.
     */
    private void updateRecentsForCategory(int categoryId) {
        if (categoryId == 0) {
            showRecents();
        }
    }

    @Override // android.widget.TabHost.OnTabChangeListener
    public void onTabChanged(String str) {
        AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(-21, this);
        int iM6661a = this.categoryManager.getCategoryIdFromTag(str);

        // The on-screen switch only (owner decision 2026-11-03): with a physical keyboard the
        // query is what the user types into the board, never the word being composed.
        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        boolean dynamicSearch = sv != null && sv.isEmojiDynamicSearchEnabled
                && DeviceProfile.isOnScreenKeyboardVisible();
        boolean isSearchCategory = iM6661a == 0;

        if (!isSearchCategory) {
            // Leaving search/recents tab — hide overlay
            if (searchOverlay != null && searchOverlay.isOverlayVisible()) {
                searchOverlay.hide();
            }
            if (dynamicSearch && query.isFromEditor()) {
                query.clear();
            }
        } else if (dynamicSearch) {
            // Returning to first tab in dynamic mode — re-trigger search if composing text exists
            String composing = KeyboardSwitcher.getInstance().getComposingText();
            onEmojiOpened(composing);
        }

        setCategory(iM6661a, false);
        updatePageIndicator();

        // Refresh recents overlay when tab changes
        updateRecentsForCategory(iM6661a);
    }

    public void setSwitchingToEmoji(boolean z) {
        this.pagerAdapter.setSwitchingToEmoji(z);
    }

    @Override // androidx.viewpager.widget.ViewPager.OnPageChangeListener
    public void onPageSelected(int i) {
        setCategory(this.categoryManager.getCategoryAt(i), false);
        this.categoryManager.setCurrentPageInCategory(this.categoryManager.getPageInCategoryAt(i));
        updatePageIndicator();
        this.currentPagePosition = i;
    }

    @Override // androidx.viewpager.widget.ViewPager.OnPageChangeListener
    public void onPageScrollStateChanged(int i) {
        if (i == 1) {
            this.pagerAdapter.releaseCurrentPage();
        }
    }

    @Override // androidx.viewpager.widget.ViewPager.OnPageChangeListener
    public void onPageScrolled(int i, float f, int i2) {
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

    @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
    public void onEmojiKeyPressed(Key key) {
        this.keyboardActionListener.onPressKey(key.getCode(), 0, true);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
    public void onEmojiKeyReleased(Key key) {
        // On-screen dynamic search: replace or append the composing word, and the query ends. A
        // typed query stays, so a second emoji from the same results is one more tap.
        if (isDynamicSearchActive()) {
            if (isReplaceMode()) {
                KeyboardSwitcher.getInstance().clearComposingText();
            } else {
                KeyboardSwitcher.getInstance().commitComposingText();
            }
            query.clear();
        }

        // Recents and the remembered skin tone are persisted to prefs: an incognito field leaves
        // no trace there.
        if (!KeyboardSwitcher.getInstance().isNoPersonalizedLearning()) {
            this.pagerAdapter.addToRecents(key.getKeySpecOutputText());
            EmojiToneMemory.onEmojiPicked(PrefsManager.INSTANCE.getPrefs(getContext()), key.getKeySpecOutputText());
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

    /**
     * Re-attaches the pager adapter after {@link #detachPagerAdapter()}, at the page the user left.
     * The open path calls it on every open; it used to hide inside the delete-icon update.
     */
    public void attachPagerAdapter() {
        this.viewPager.setAdapter(this.pagerAdapter);
        this.viewPager.setCurrentItem(this.currentPagePosition);
    }

    public void detachPagerAdapter() {
        this.pagerAdapter.commitPendingRecents();
        this.viewPager.setAdapter(null);
        // Pages are rebuilt on the next open, so a skin tone remembered meanwhile shows on the keys.
        this.categoryManager.clearKeyboardCache();
    }

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
            if (z || this.tabHost.getCurrentTab() != iM6674f) {
                this.tabHost.setCurrentTab(iM6674f);
            }
            // Update tab icon colors based on active/inactive state
            updateTabIconColors();
        }
    }

    private void applyColors() {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) return;
        boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
        // Modern boards: the board shares the keyboard's background surface so the
        // emoji key caps (keyColor) read against it; legacy keeps the flat keyColor panel.
        int panelColor = modernBoards
                ? KeyboardColorManager.INSTANCE.getBackgroundColor()
                : KeyboardColorManager.INSTANCE.getKeyColor();
        this.viewPager.setBackgroundColor(panelColor);
        this.deleteButton.setBackgroundColor(panelColor);
        KeyboardColorManager.INSTANCE.tint(this.deleteButton);
        // Modern boards: rounded state-layer press feedback on delete (legacy: none).
        this.deleteButton.setForeground(
                modernBoards ? KeyboardColorManager.pressedHighlight() : null);
        ((android.view.View) this.tabHost.getParent()).setBackgroundColor(panelColor);
        this.pageIndicatorView.setColors(
            modernBoards ? KeyboardColorManager.INSTANCE.getAccentColor()
                     : KeyboardColorManager.INSTANCE.getIconColor(),
            KeyboardColorManager.INSTANCE.getIconColor(0.3f),
            panelColor
        );
        // The search field: same surface as the tab row it replaces; the search icon in the
        // accent (modern) or icon colour, the clear button like the delete key it stands in for.
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
        updateTabIconColors();
    }


    private void updateTabIconColors() {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) return;
        android.widget.TabWidget tabWidget = this.tabHost.getTabWidget();
        int currentTab = this.tabHost.getCurrentTab();
        int tabCount = tabWidget.getTabCount();
        for (int i = 0; i < tabCount; i++) {
            android.view.View tabView = tabWidget.getChildTabViewAt(i);
            android.widget.ImageView iconView = null;
            if (tabView instanceof android.widget.ImageView) {
                iconView = (android.widget.ImageView) tabView;
            } else if (tabView instanceof android.view.ViewGroup) {
                android.view.ViewGroup container = (android.view.ViewGroup) tabView;
                for (int j = 0; j < container.getChildCount(); j++) {
                    android.view.View child = container.getChildAt(j);
                    if (child instanceof android.widget.ImageView) {
                        iconView = (android.widget.ImageView) child;
                        break;
                    }
                }
            }
            if (iconView != null) {
                if (i == currentTab) {
                    KeyboardColorManager.INSTANCE.tint(iconView);
                } else {
                    KeyboardColorManager.INSTANCE.tint(iconView,
                        KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY));
                }
            }
        }
    }

    /**
     * Whether the palettes are actually on screen. This is a <em>view</em> question and is
     * deliberately not the board's "is emoji open" answer — that lives in the KeyboardState
     * machine and is asked through {@link EmojiBoardController} (§5.6 item 4). This view used to
     * implement UnifiedInputBoardComponent itself, which meant the two questions were conflated
     * and had to be special-cased apart again in UnifiedInputBoardManager.
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
