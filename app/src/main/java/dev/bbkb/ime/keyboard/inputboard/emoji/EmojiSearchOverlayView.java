package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import java.util.ArrayList;
import java.util.List;

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
     * Spare page views: every showKeyboards (each recents refresh and each search result) rebinds
     * all pages, and RecyclerView takes the views for them from here instead of building new ones.
     * Never cleared, like the category pager's (this adapter is never replaced, so it would not be).
     */
    private final RecyclerView.RecycledViewPool pagePool = new EmojiPagerAdapter.RetainedPagePool();
    
    public EmojiSearchOverlayView(Context context) {
        super(context);
        init(context);
    }
    
    public EmojiSearchOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public EmojiSearchOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
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
