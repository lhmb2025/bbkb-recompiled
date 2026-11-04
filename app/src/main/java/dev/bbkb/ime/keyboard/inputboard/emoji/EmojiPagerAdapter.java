package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.viewpager.widget.PagerAdapter;

import dev.bbkb.ime.R;

import java.util.ArrayList;

/**
 * ViewPager adapter for the emoji category pages.
 *
 * Manages the lifecycle of EmojiPageKeyboardView instances within the ViewPager:
 * - Creates/destroys emoji page views on demand
 * - Handles recents emoji updates and persistence
 * - Coordinates with EmojiCategoryManager for page content
 *
 * <h3>Destroyed pages</h3>
 * A destroyed page is always removed from the pager, one main-loop turn later (see
 * {@link #destroyItem}). It used to stay attached while {@link #setSwitchingToEmoji(boolean)} was in
 * force (every open until {@code requestShiftOff()}), so paging accumulated stale page views; that
 * setter is now a no-op.
 *
 * <p>This used to be wrapped in {@code mReleasing}/{@code mTabChanged}/instantiate/destroy counters.
 * Every page object is the {@link EmojiPageKeyboardView} {@link #instantiateItem} returned, so the
 * branch those counters guarded was unreachable, and they are gone.
 *
 * <h3>Recycled pages</h3>
 * A destroyed page view goes to a small free list and the next {@link #instantiateItem} rebinds it
 * to its page with {@code setKeyboard} instead of inflating a new one (audit P-16: every swipe and
 * every open inflated a page view, each with its own more-keys KeyboardView). A freed view may
 * still be a child of the pager until its posted removal runs; if it is taken again first, the
 * removal skips it, because it is in use again.
 *
 * <p>The Recents grid is asked of {@link EmojiCategoryManager#getRecentsKeyboard()} every time.
 * It used to be kept from construction, so once the manager rebuilt the grid (the board dropped
 * every page on close) picks went to a grid no page showed, and Recents looked stale until the
 * board was reopened.
 *
 * <p>{@code getItemPosition} is not overridden. It returned {@code POSITION_NONE}, but ViewPager
 * only consults it from {@code notifyDataSetChanged()}, which is never called on this adapter.
 */
final class EmojiPagerAdapter extends PagerAdapter {

    /** Free views kept for rebinding: the pager's three live pages (offscreen limit 1) can all be swapped by a tab jump. */
    private static final int FREE_VIEW_LIMIT = 3;

    private final EmojiPageKeyboardView.OnEmojiKeyListener mKeyListener;

    private final EmojiCategoryManager mCategoryManager;

    private final EmojiPageKeyboardView.SharedMoreKeysPanel mMoreKeysPanel;

    private final SparseArray<EmojiPageKeyboardView> mPageViews = new SparseArray<>();

    private final ArrayList<EmojiPageKeyboardView> mFreeViews = new ArrayList<>();

    private int mCurrentPosition = 0;

    @Override // androidx.viewpager.widget.PagerAdapter
    public boolean isViewFromObject(View view, Object obj) {
        return view == obj;
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

    public void releaseCurrentPage() {
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(this.mCurrentPosition);
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.releaseAllKeys();
        }
    }

    @Override // androidx.viewpager.widget.PagerAdapter
    public int getCount() {
        return this.mCategoryManager.getTotalPageCount();
    }

    @Override // androidx.viewpager.widget.PagerAdapter
    public void setPrimaryItem(ViewGroup viewGroup, int i, Object obj) {
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

    @Override // androidx.viewpager.widget.PagerAdapter
    public Object instantiateItem(ViewGroup viewGroup, int i) {
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(i);
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.cleanup();
            this.mPageViews.remove(i);
            emojiPageKeyboardView.releaseAllKeys();
        }

        EmojiKeyboard keyboard = this.mCategoryManager.getKeyboardAtPosition(i);
        EmojiPageKeyboardView page = takeFreeView();
        if (page == null) {
            page = (EmojiPageKeyboardView) LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.emoji_keyboard_page, viewGroup, false);
            page.setOnKeyEventListener(this.mKeyListener);
            page.setSharedMoreKeysPanel(this.mMoreKeysPanel);
        }
        page.setKeyboard(keyboard);
        // A freed view whose posted removal has not run yet is still a child: it stays where it is.
        if (page.getParent() == null) {
            viewGroup.addView(page);
        }
        this.mPageViews.put(i, page);
        return page;
    }

    /** A free view, preferring one already out of the pager; null when there is none. */
    private EmojiPageKeyboardView takeFreeView() {
        final int n = this.mFreeViews.size();
        if (n == 0) {
            return null;
        }
        for (int k = n - 1; k >= 0; k--) {
            if (this.mFreeViews.get(k).getParent() == null) {
                return this.mFreeViews.remove(k);
            }
        }
        return this.mFreeViews.remove(n - 1);
    }

    @Override // androidx.viewpager.widget.PagerAdapter
    public void destroyItem(ViewGroup viewGroup, int i, Object obj) {
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(i);
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.cleanup();
            this.mPageViews.remove(i);
        }
        final EmojiPageKeyboardView page = (EmojiPageKeyboardView) obj;
        if (this.mFreeViews.size() < FREE_VIEW_LIMIT && !this.mFreeViews.contains(page)) {
            this.mFreeViews.add(page);
        }
        // Always detach, but on the next main-loop turn, never synchronously. A page's
        // onDetachedFromWindow removes its more-keys placer from the window's android.R.id.content,
        // and on open (updateDeleteButton -> setAdapter + setCurrentItem) ViewPager destroys pages
        // inside the layout traversal: a synchronous removal mutates the content frame while it is
        // iterating its children (NPE in FrameLayout.layoutChildren). The original APK avoided that
        // by not removing at all while "switching to emoji", but that flag stays set from every open
        // until requestShiftOff, so ordinary paging piled stale page views into the pager.
        viewGroup.post(() -> {
            // Skip a page that already left (setAdapter(null) strips pages itself) or that was
            // taken from the free list for another page in the meantime.
            if (page.getParent() == viewGroup && this.mPageViews.indexOfValue(page) < 0) {
                viewGroup.removeView(page);
            }
        });
    }

    /**
     * No effect. The flag this set only stopped {@link #destroyItem} from detaching pages, which
     * leaked page views (see there). Kept because {@code KeyboardSwitcher} still calls it.
     */
    public void setSwitchingToEmoji(boolean z) {
    }
}
