package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import dev.bbkb.ime.R;

/**
 * ViewPager2 adapter for the emoji category pages: one view holder per {@link EmojiPageKeyboardView},
 * bound to the page {@link EmojiCategoryManager#getKeyboardAtPosition} builds for its position.
 *
 * <h3>Recycled pages (audit P-16)</h3>
 * Page views are recycled by RecyclerView, not by hand: a page that leaves the laid-out range goes to
 * the {@link RetainedPagePool} and the next position that needs one rebinds it with
 * {@code setKeyboard} instead of inflating a new view (with the v1 pager every swipe and every open
 * inflated one, each with its own more-keys KeyboardView, until a hand-written free list replaced
 * that). {@link #configure} sizes everything to what the v1 pager kept: three live pages (the current
 * one and one either side) and at most three spare views, no bound-but-detached cache, no prefetch.
 *
 * <p>The v1 adapter removed destroyed pages one main-loop turn late because ViewPager destroyed them
 * inside a layout pass, where a page's {@code onDetachedFromWindow} removing its more-keys placer from
 * the window's content frame crashed that frame's layout. RecyclerView also recycles inside layout
 * (a tab jump), so the deferral now lives where the removal happens, in
 * {@link EmojiPageKeyboardView#onDetachedFromWindow}.
 *
 * <p>The Recents grid is asked of {@link EmojiCategoryManager#getRecentsKeyboard()} every time.
 * It used to be kept from construction, so once the manager rebuilt the grid (the board dropped
 * every page on close) picks went to a grid no page showed, and Recents looked stale until the
 * board was reopened.
 */
final class EmojiPagerAdapter extends RecyclerView.Adapter<EmojiPagerAdapter.PageHolder> {

    /**
     * Spare page views kept for rebinding. The v1 adapter's free list held three: the pager's three
     * live pages (offscreen limit 1) can all be swapped by a tab jump.
     */
    static final int SPARE_PAGE_VIEWS = 3;

    /** Pages laid out either side of the current one: the v1 pager's effective limit (it clamped 0 to 1). */
    static final int OFFSCREEN_PAGES = 1;

    private final EmojiPageKeyboardView.OnEmojiKeyListener mKeyListener;

    private final EmojiCategoryManager mCategoryManager;

    private final EmojiPageKeyboardView.SharedMoreKeysPanel mMoreKeysPanel;

    /** The page view bound to each position, for the recents and release calls below. */
    private final SparseArray<EmojiPageKeyboardView> mPageViews = new SparseArray<>();

    private int mCurrentPosition = 0;

    /** One page: the holder's item view is a full-size frame around the page view (see {@link #wrap}). */
    static final class PageHolder extends RecyclerView.ViewHolder {
        final EmojiPageKeyboardView page;

        private PageHolder(View frame, EmojiPageKeyboardView page) {
            super(frame);
            this.page = page;
        }

        /**
         * ViewPager2 requires every page to be match_parent both ways, but a page view sizes itself
         * from its keyboard ({@code EmojiPageKeyboardView.onMeasure}) and the v1 pager laid it out
         * at that size at the top-left of its slot. A FrameLayout that fills the page slot does the
         * same: it lays the page out at its own measured size, top-start (the pager is LTR).
         */
        static PageHolder wrap(EmojiPageKeyboardView page, ViewGroup parent) {
            FrameLayout frame = new FrameLayout(parent.getContext());
            frame.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.addView(page);
            return new PageHolder(frame, page);
        }
    }

    /**
     * A pool that is never cleared. The board detaches its adapter on every close and attaches it
     * again on open; RecyclerView clears its pool when the adapter changes, which would throw the
     * spare page views away and make every open inflate its pages again (audit P-16). The pool only
     * ever holds {@link #SPARE_PAGE_VIEWS} views of one type, all from this board's own adapter.
     */
    static final class RetainedPagePool extends RecyclerView.RecycledViewPool {
        RetainedPagePool() {
            setMaxRecycledViews(0, SPARE_PAGE_VIEWS);
        }

        @Override
        public void clear() {
        }
    }

    /**
     * What both emoji pagers (categories and the search/recents overlay) share: the page counts
     * above, the v1 pager's left-to-right paging whatever the language (v1 ignored layout direction,
     * and the board is {@code layoutDirection="locale"}), its over-scroll mode, and no item animations.
     */
    static void configure(ViewPager2 pager, RecyclerView.RecycledViewPool pool) {
        pager.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pager.setOffscreenPageLimit(OFFSCREEN_PAGES);
        RecyclerView recycler = (RecyclerView) pager.getChildAt(0);
        recycler.setItemViewCacheSize(0);
        recycler.setRecycledViewPool(pool);
        recycler.setItemAnimator(null);
        recycler.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        RecyclerView.LayoutManager layout = recycler.getLayoutManager();
        if (layout instanceof LinearLayoutManager) {
            // A prefetched page would be one more bound view than the v1 pager kept.
            ((LinearLayoutManager) layout).setItemPrefetchEnabled(false);
        }
    }

    public EmojiPagerAdapter(EmojiCategoryManager c0971b, EmojiPageKeyboardView.OnEmojiKeyListener interfaceC0967a,
            EmojiPageKeyboardView.SharedMoreKeysPanel moreKeysPanel) {
        this.mCategoryManager = c0971b;
        this.mKeyListener = interfaceC0967a;
        this.mMoreKeysPanel = moreKeysPanel;
    }

    public void commitPendingRecents() {
        this.mCategoryManager.getRecentsKeyboard().processEmojiQueue();
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(this.mCategoryManager.getRecentsIndex());
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.invalidateAllKeys();
        }
    }

    public void addToRecents(String str) {
        final EmojiKeyboard recents = this.mCategoryManager.getRecentsKeyboard();
        if (this.mCategoryManager.isRecentsCategory()) {
            recents.enqueueEmoji(str);
            return;
        }
        recents.addEmojiToRecents(str);
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(this.mCategoryManager.getRecentsIndex());
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.invalidateAllKeys();
        }
    }

    /** Re-sets each laid-out page showing one of {@code changed}, so it draws and hit-tests its new keys. */
    void refreshPages(java.util.Set<EmojiKeyboard> changed) {
        for (int i = 0; i < this.mPageViews.size(); i++) {
            EmojiPageKeyboardView page = this.mPageViews.valueAt(i);
            if (changed.contains(page.getKeyboard())) {
                page.setKeyboard(page.getKeyboard());
            }
        }
    }

    public void releaseCurrentPage() {
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(this.mCurrentPosition);
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.releaseAllKeys();
        }
    }

    /** The pager's current page changed: the page left behind drops its pressed keys and timers. */
    public void onPageSelected(int i) {
        int i2 = this.mCurrentPosition;
        if (i2 == i) {
            return;
        }
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(i2);
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.cleanup();
            emojiPageKeyboardView.releaseAllKeys();
        }
        this.mCurrentPosition = i;
    }

    @Override
    public int getItemCount() {
        return this.mCategoryManager.getTotalPageCount();
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        EmojiPageKeyboardView page = (EmojiPageKeyboardView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.emoji_keyboard_page, parent, false);
        page.setOnKeyEventListener(this.mKeyListener);
        page.setSharedMoreKeysPanel(this.mMoreKeysPanel);
        return PageHolder.wrap(page, parent);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        unmap(holder.page);
        holder.page.setKeyboard(this.mCategoryManager.getKeyboardAtPosition(position));
        this.mPageViews.put(position, holder.page);
    }

    @Override
    public void onViewRecycled(@NonNull PageHolder holder) {
        holder.page.cleanup();
        unmap(holder.page);
    }

    private void unmap(EmojiPageKeyboardView page) {
        int k = this.mPageViews.indexOfValue(page);
        if (k >= 0) {
            this.mPageViews.removeAt(k);
        }
    }

    /**
     * No effect. The flag this set only stopped the v1 adapter's destroyItem from detaching pages,
     * which leaked page views. Kept because {@code KeyboardSwitcher} still calls it.
     */
    public void setSwitchingToEmoji(boolean z) {
    }
}
