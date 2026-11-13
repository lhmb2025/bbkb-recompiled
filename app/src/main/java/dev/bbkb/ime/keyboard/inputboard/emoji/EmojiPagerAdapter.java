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
 * ViewPager2 adapter for the emoji category pages, bound from
 * {@link EmojiCategoryManager#getKeyboardAtPosition}. Pages are rebound from the
 * {@link RetainedPagePool} rather than inflated. The Recents grid is fetched from the manager
 * every time, since the manager rebuilds it when the board closes.
 */
final class EmojiPagerAdapter extends RecyclerView.Adapter<EmojiPagerAdapter.PageHolder> {
    /** Spare page views kept for rebinding: a tab jump can swap all three live pages. */
    static final int SPARE_PAGE_VIEWS = 3;

    /** Pages laid out either side of the current one. */
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
         * ViewPager2 needs match_parent pages, but a page sizes itself from its keyboard; the frame
         * fills the slot and lays the page out at its own size, top-start.
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
     * A pool that is never cleared. The board detaches its adapter on every close, and RecyclerView
     * clears the pool on an adapter change, which would make every open inflate its pages again.
     */
    static final class RetainedPagePool extends RecyclerView.RecycledViewPool {
        RetainedPagePool() {
            setMaxRecycledViews(0, SPARE_PAGE_VIEWS);
        }

        @Override
        public void clear() {
            // Deliberately keeps the spare views (see class comment).
        }
    }

    /**
     * Setup shared by both emoji pagers: the page counts above, left-to-right paging in every
     * language (the board is {@code layoutDirection="locale"}), no item animations or prefetch.
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
            ((LinearLayoutManager) layout).setItemPrefetchEnabled(false);
        }
    }

    /**
     * @param c0971b the category manager that builds the pages
     * @param interfaceC0967a receives emoji key presses and releases
     */
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

    /** @param str the emoji to add */
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

    /**
     * The page left behind drops its pressed keys and timers.
     *
     * @param i the newly selected position
     */
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
     * No effect; kept because {@code KeyboardSwitcher} still calls it.
     *
     * @param z whether the board is switching to emoji
     */
    public void setSwitchingToEmoji(boolean z) {
        // Nothing to do with ViewPager2.
    }
}
