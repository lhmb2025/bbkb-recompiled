package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.viewpager.widget.PagerAdapter;

import dev.bbkb.ime.R;

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
 * <p>{@code getItemPosition} is not overridden. It returned {@code POSITION_NONE}, but ViewPager
 * only consults it from {@code notifyDataSetChanged()}, which is never called on this adapter.
 */
final class EmojiPagerAdapter extends PagerAdapter {

    private final EmojiPageKeyboardView.OnEmojiKeyListener mKeyListener;

    private final EmojiKeyboard mEmojiKeyboard;

    private final EmojiCategoryManager mCategoryManager;

    private final SparseArray<EmojiPageKeyboardView> mPageViews = new SparseArray<>();

    private int mCurrentPosition = 0;

    @Override // androidx.viewpager.widget.PagerAdapter
    public boolean isViewFromObject(View view, Object obj) {
        return view == obj;
    }

    public EmojiPagerAdapter(EmojiCategoryManager c0971b, EmojiPageKeyboardView.OnEmojiKeyListener interfaceC0967a) {
        this.mCategoryManager = c0971b;
        this.mKeyListener = interfaceC0967a;
        this.mEmojiKeyboard = this.mCategoryManager.getOrCreateKeyboard(0, 0);
    }

    public void commitPendingRecents() {
        this.mEmojiKeyboard.processEmojiQueue();
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(this.mCategoryManager.getRecentsIndex());
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.invalidateAllKeys();
        }
    }

    public void addToRecents(String str) {
        if (this.mCategoryManager.isRecentsCategory()) {
            this.mEmojiKeyboard.enqueueEmoji(str);
            return;
        }
        this.mEmojiKeyboard.addEmojiToRecents(str);
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
        EmojiPageKeyboardView emojiPageKeyboardView2 = (EmojiPageKeyboardView) LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.emoji_keyboard_page, viewGroup, false);
        emojiPageKeyboardView2.setKeyboard(keyboard);
        emojiPageKeyboardView2.setOnKeyEventListener(this.mKeyListener);
        viewGroup.addView(emojiPageKeyboardView2);
        this.mPageViews.put(i, emojiPageKeyboardView2);
        return emojiPageKeyboardView2;
    }

    @Override // androidx.viewpager.widget.PagerAdapter
    public void destroyItem(ViewGroup viewGroup, int i, Object obj) {
        EmojiPageKeyboardView emojiPageKeyboardView = this.mPageViews.get(i);
        if (emojiPageKeyboardView != null) {
            emojiPageKeyboardView.cleanup();
            this.mPageViews.remove(i);
        }
        // Always detach, but on the next main-loop turn, never synchronously. A page's
        // onDetachedFromWindow removes its more-keys placer from the window's android.R.id.content,
        // and on open (updateDeleteButton -> setAdapter + setCurrentItem) ViewPager destroys pages
        // inside the layout traversal: a synchronous removal mutates the content frame while it is
        // iterating its children (NPE in FrameLayout.layoutChildren). The original APK avoided that
        // by not removing at all while "switching to emoji", but that flag stays set from every open
        // until requestShiftOff, so ordinary paging piled stale page views into the pager.
        final View page = (View) obj;
        viewGroup.post(() -> {
            // Skip a page that already left (setAdapter(null) strips pages itself).
            if (page.getParent() == viewGroup) {
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
