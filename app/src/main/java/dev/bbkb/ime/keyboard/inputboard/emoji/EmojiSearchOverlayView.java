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
 * Overlay view for displaying emoji search results and recents.
 * 
 * This view sits on top of the main emoji category ViewPager and provides
 * a completely separate display pipeline for dynamic content (search results
 * and recents). This architectural separation prevents state conflicts between
 * dynamic search/recents content and static emoji categories.
 * 
 * Benefits of this approach:
 * - Categories ViewPager is never affected by search state
 * - No adapter resets or cache clearing needed
 * - Simple show/hide logic with no race conditions
 * - Clean state management: visible = search mode, hidden = category mode
 */
public class EmojiSearchOverlayView extends FrameLayout {
    
    private static final String TAG = "EmojiSearchOverlayView";
    
    private ViewPager2 viewPager;
    private SearchOverlayAdapter adapter;
    private EmojiPageKeyboardView.OnEmojiKeyListener keyListener;
    private EmojiCategoryPageIndicatorView pageIndicator;
    private EmojiPageKeyboardView.SharedMoreKeysPanel moreKeysPanel;

    private List<EmojiKeyboard> currentKeyboards = new ArrayList<>();

    /**
     * Where a forward swipe past the overlay's last page goes: the category pager behind it. Null
     * (or {@link PageHandoff#canHandOff} false) keeps today's behaviour, the swipe goes nowhere.
     */
    private PageHandoff pageHandoff;

    /** The paging slop, the threshold ViewPager2 itself drags at. */
    private final int pagingSlop;

    private float downX;
    private float downY;
    private float lastX;
    private boolean handingOff;

    /**
     * The category pager behind the overlay. The overlay shows Recents in place of the Recents
     * tab's own page, so without this a swipe on Recents never reached the pager and never moved on
     * to the first category, as a swipe between two categories does.
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

    /**
     * Spare page views: every showKeyboards (each recents refresh and each search result) rebinds
     * all pages, and RecyclerView takes the views for them from here instead of building new ones.
     * Never cleared, like the category pager's (this adapter is never replaced, so it would not be).
     */
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
        // Create ViewPager2 programmatically
        viewPager = new ViewPager2(context);
        viewPager.setId(View.generateViewId());
        viewPager.setLayoutParams(new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, 
            LayoutParams.MATCH_PARENT
        ));
        // One page either side (the v1 pager's limit), three spare views, left-to-right paging.
        EmojiPagerAdapter.configure(viewPager, pagePool);
        
        // Apply background color
        if (KeyboardColorManager.INSTANCE.isInitialized()) {
            viewPager.setBackgroundColor(KeyboardColorManager.INSTANCE.getBackgroundColor());
            setBackgroundColor(KeyboardColorManager.INSTANCE.getBackgroundColor());
        }
        
        adapter = new SearchOverlayAdapter();
        viewPager.setAdapter(adapter);
        
        addView(viewPager);
        
        // Start hidden
        setVisibility(View.GONE);
    }
    
    /**
     * Set the emoji key event listener.
     * This is called when an emoji is pressed or released.
     */
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
     * Takes a swipe from the page under the finger when it moves on (right to left) past the
     * overlay's last page and the board says the categories may page: from then on the finger drags
     * the category pager. Checked before the overlay's own pager sees the move, at the slop that
     * pager drags at, so it cannot claim the swipe first. A held skin-tone popup asks every ancestor
     * not to intercept ({@code EmojiPageKeyboardView.holdPagerForTones}), so this never steals the
     * popup's finger.
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

    /**
     * A skin tone was chosen with the board open: rebuilds the keys {@code rebuild} returns a new
     * key for in the shown pages, and re-sets the laid-out pages whose keys changed.
     */
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

    /**
     * Set the page indicator view to update when pages change.
     */
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
    
    /**
     * Display search results or recents keyboards.
     * Shows the overlay and populates with the given keyboards.
     */
    public void showKeyboards(List<EmojiKeyboard> keyboards) {
        
        currentKeyboards.clear();
        currentKeyboards.addAll(keyboards);
        setTranslationX(0f);
        
        adapter.notifyDataSetChanged();
        viewPager.setCurrentItem(0, false);
        
        // Update page indicator
        if (pageIndicator != null && keyboards.size() > 0) {
            pageIndicator.setPageInfo(keyboards.size(), 0, 0f);
        }
        
        setVisibility(View.VISIBLE);
    }
    
    /**
     * Hide the overlay and clear keyboards.
     */
    public void hide() {
        setVisibility(View.GONE);
        setTranslationX(0f);
        currentKeyboards.clear();
        adapter.notifyDataSetChanged();
    }
    
    /**
     * Check if overlay is currently visible.
     */
    public boolean isOverlayVisible() {
        return getVisibility() == View.VISIBLE;
    }
    
    /**
     * Get the current page count.
     */
    public int getPageCount() {
        return currentKeyboards.size();
    }
    
    /**
     * Adapter for the search/recents overlay pager: one page view per keyboard in
     * {@link #currentKeyboards}.
     *
     * <p>A new page set replaces the whole list ({@code notifyDataSetChanged}): every keyboard in it
     * is newly built, so there is nothing to match old pages against, and a DiffUtil/ListAdapter
     * diff would only make {@link #showKeyboards} asynchronous. RecyclerView then recycles the old
     * page views into {@link #pagePool} before laying out the new set, so they are rebound rather
     * than rebuilt (the v1 adapter forced the same rebuild with POSITION_NONE and a free list).
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
