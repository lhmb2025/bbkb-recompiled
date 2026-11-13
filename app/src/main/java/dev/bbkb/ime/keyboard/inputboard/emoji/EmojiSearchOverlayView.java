package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shows search results and recents in its own pager on top of the category pager, so the
 * categories are never reset by search state. Visible means search/recents mode.
 */
public class EmojiSearchOverlayView extends FrameLayout {
    
    private static final String TAG = "EmojiSearchOverlayView";
    
    private ViewPager2 viewPager;
    private SearchOverlayAdapter adapter;
    private EmojiPageKeyboardView.OnEmojiKeyListener keyListener;
    private EmojiCategoryPageIndicatorView pageIndicator;
    private EmojiPageKeyboardView.SharedMoreKeysPanel moreKeysPanel;

    private List<EmojiKeyboard> currentKeyboards = new ArrayList<>();

    /** Takes a forward swipe past the overlay's last page; null means the swipe goes nowhere. */
    private PageHandoff pageHandoff;

    /** The paging slop, the threshold ViewPager2 itself drags at. */
    private final int pagingSlop;

    private float downX;
    private float downY;
    private float lastX;
    private boolean handingOff;

    /**
     * The category pager behind the overlay. The overlay covers the Recents tab's page, so this
     * lets a swipe on Recents move on to the first category.
     */
    interface PageHandoff {
        /** Whether a forward swipe on the overlay should page the categories now. */
        boolean canHandOff();

        /** Starts dragging the category pager; false if it cannot start. */
        boolean beginDrag();

        /** Drags the category pager by {@code dx} pixels (negative moves on to the next page). */
        void dragBy(float dx);

        /** The finger lifted or was cancelled: the category pager settles as after a real drag. */
        void endDrag();
    }

    /** Spare page views, reused by every showKeyboards rebind instead of building new ones. Never cleared. */
    private final RecyclerView.RecycledViewPool pagePool = new EmojiPagerAdapter.RetainedPagePool();
    
    public EmojiSearchOverlayView(Context context) {
        super(context);
        this.pagingSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();
        init(context);
    }
    
    public EmojiSearchOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.pagingSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();
        init(context);
    }
    
    public EmojiSearchOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.pagingSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();
        init(context);
    }
    
    private void init(Context context) {
        viewPager = new ViewPager2(context);
        viewPager.setId(View.generateViewId());
        viewPager.setLayoutParams(new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, 
            LayoutParams.MATCH_PARENT
        ));
        EmojiPagerAdapter.configure(viewPager, pagePool);

        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            viewPager.setBackgroundColor(KeyboardColorManager.INSTANCE.getBackgroundColor());
            setBackgroundColor(KeyboardColorManager.INSTANCE.getBackgroundColor());
        }
        
        adapter = new SearchOverlayAdapter();
        viewPager.setAdapter(adapter);
        
        addView(viewPager);

        setVisibility(View.GONE);
    }

    public void setOnKeyEventListener(EmojiPageKeyboardView.OnEmojiKeyListener listener) {
        this.keyListener = listener;
    }
    
    /** The board's skin-tone popup, shared with its category pages. */
    void setSharedMoreKeysPanel(EmojiPageKeyboardView.SharedMoreKeysPanel panel) {
        this.moreKeysPanel = panel;
    }

    void setPageHandoff(PageHandoff handoff) {
        this.pageHandoff = handoff;
    }

    /**
     * Hands a right-to-left swipe past the last page to the category pager. Checked before the
     * inner pager sees the move, at its own slop, so it cannot claim the swipe first. An open
     * skin-tone popup disallows intercepts, so its finger is never stolen.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Raw coordinates: the overlay slides with the page it covers while handing off, so
                // its local coordinates move under the finger.
                downX = ev.getRawX();
                downY = ev.getRawY();
                lastX = downX;
                handingOff = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (handingOff || ev.getPointerCount() != 1) {
                    break;
                }
                float dx = ev.getRawX() - downX;
                float dy = ev.getRawY() - downY;
                if (dx < -pagingSlop && Math.abs(dx) > Math.abs(dy)
                        && !viewPager.canScrollHorizontally(1)
                        && pageHandoff != null && pageHandoff.canHandOff()
                        && pageHandoff.beginDrag()) {
                    handingOff = true;
                    lastX = ev.getRawX();
                    releasePages();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!handingOff) {
            return super.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pageHandoff.dragBy(ev.getRawX() - lastX);
                lastX = ev.getRawX();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handingOff = false;
                pageHandoff.endDrag();
                break;
            default:
                break;
        }
        return true;
    }

    /** Whether a swipe is being passed to the category pager. */
    boolean isHandingOff() {
        return handingOff;
    }

    /** Lets go of the keys pressed on the overlay's pages (the finger now drags the categories). */
    private void releasePages() {
        RecyclerView recycler = (RecyclerView) viewPager.getChildAt(0);
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View frame = recycler.getChildAt(i);
            if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildAt(0) instanceof EmojiPageKeyboardView) {
                ((EmojiPageKeyboardView) ((ViewGroup) frame).getChildAt(0)).releaseAllKeys();
            }
        }
    }

    /** Swaps in the keys {@code rebuild} returns a new key for, and re-sets laid-out pages that changed. */
    void replaceKeys(Function<dev.bbkb.ime.keyboard.Key, dev.bbkb.ime.keyboard.Key> rebuild) {
        java.util.Set<EmojiKeyboard> changed =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (EmojiKeyboard keyboard : currentKeyboards) {
            if (keyboard.replaceKeys(rebuild)) {
                changed.add(keyboard);
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        RecyclerView recycler = (RecyclerView) viewPager.getChildAt(0);
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View frame = recycler.getChildAt(i);
            if (frame instanceof ViewGroup && ((ViewGroup) frame).getChildAt(0) instanceof EmojiPageKeyboardView) {
                EmojiPageKeyboardView page = (EmojiPageKeyboardView) ((ViewGroup) frame).getChildAt(0);
                if (changed.contains(page.getKeyboard())) {
                    page.setKeyboard(page.getKeyboard());
                }
            }
        }
    }

    public void setPageIndicator(EmojiCategoryPageIndicatorView indicator) {
        this.pageIndicator = indicator;
        
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (pageIndicator != null && position >= 0 && position < currentKeyboards.size()) {
                    pageIndicator.setPageInfo(currentKeyboards.size(), position, positionOffset);
                }
            }
            
            @Override
            public void onPageSelected(int position) {
                if (pageIndicator != null && position >= 0 && position < currentKeyboards.size()) {
                    pageIndicator.setPageInfo(currentKeyboards.size(), position, 0f);
                }
            }
            
        });
    }

    /** Shows the overlay with the given search-result or recents pages. */
    public void showKeyboards(List<EmojiKeyboard> keyboards) {
        
        currentKeyboards.clear();
        currentKeyboards.addAll(keyboards);
        setTranslationX(0f);
        
        adapter.notifyDataSetChanged();
        viewPager.setCurrentItem(0, false);

        if (pageIndicator != null && keyboards.size() > 0) {
            pageIndicator.setPageInfo(keyboards.size(), 0, 0f);
        }
        
        setVisibility(View.VISIBLE);
    }

    public void hide() {
        setVisibility(View.GONE);
        setTranslationX(0f);
        currentKeyboards.clear();
        adapter.notifyDataSetChanged();
    }

    public boolean isOverlayVisible() {
        return getVisibility() == View.VISIBLE;
    }

    public int getPageCount() {
        return currentKeyboards.size();
    }
    
    /**
     * One page per keyboard in {@link #currentKeyboards}. Each new set uses notifyDataSetChanged:
     * every keyboard is new, so a DiffUtil diff gains nothing and would make showKeyboards async.
     */

    private class SearchOverlayAdapter extends RecyclerView.Adapter<EmojiPagerAdapter.PageHolder> {

        @Override
        public int getItemCount() {
            return currentKeyboards.size();
        }

        @NonNull
        @Override
        public EmojiPagerAdapter.PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            EmojiPageKeyboardView keyboardView = new EmojiPageKeyboardView(parent.getContext(), null);
            keyboardView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            keyboardView.setSharedMoreKeysPanel(moreKeysPanel);
            return EmojiPagerAdapter.PageHolder.wrap(keyboardView, parent);
        }

        @Override
        public void onBindViewHolder(@NonNull EmojiPagerAdapter.PageHolder holder, int position) {
            holder.page.setKeyboard(currentKeyboards.get(position));
            if (keyListener != null) {
                holder.page.setOnKeyEventListener(keyListener);
            }
        }

        @Override
        public void onViewRecycled(@NonNull EmojiPagerAdapter.PageHolder holder) {
            holder.page.cleanup();
        }
    }
}
