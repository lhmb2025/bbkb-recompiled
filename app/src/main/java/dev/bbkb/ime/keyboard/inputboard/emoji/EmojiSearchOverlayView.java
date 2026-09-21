package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

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
    
    private ViewPager viewPager;
    private SearchOverlayAdapter adapter;
    private EmojiPageKeyboardView.OnEmojiKeyListener keyListener;
    private EmojiCategoryPageIndicatorView pageIndicator;
    
    private List<EmojiKeyboard> currentKeyboards = new ArrayList<>();
    
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
        // Create ViewPager programmatically
        viewPager = new ViewPager(context);
        viewPager.setId(View.generateViewId());
        viewPager.setLayoutParams(new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, 
            LayoutParams.MATCH_PARENT
        ));
        // ViewPager silently clamps 0 to 1 and emits an UNGATED framework
        // Log.w("ViewPager", "Requested offscreen page limit 0 too small; defaulting to 1")
        // every time it is called. Ask for what it will actually use.
        viewPager.setOffscreenPageLimit(1);
        
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
    
    /**
     * Set the page indicator view to update when pages change.
     */
    public void setPageIndicator(EmojiCategoryPageIndicatorView indicator) {
        this.pageIndicator = indicator;
        
        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (pageIndicator != null && currentKeyboards.size() > 0) {
                    pageIndicator.setPageInfo(currentKeyboards.size(), position, positionOffset);
                }
            }
            
            @Override
            public void onPageSelected(int position) {
                if (pageIndicator != null && currentKeyboards.size() > 0) {
                    pageIndicator.setPageInfo(currentKeyboards.size(), position, 0f);
                }
            }
            
            @Override
            public void onPageScrollStateChanged(int state) {
                // Not needed
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
     * Adapter for the search/recents overlay ViewPager.
     * Manages EmojiPageKeyboardView instances for displaying keyboards.
     */
    private class SearchOverlayAdapter extends PagerAdapter {
        
        @Override
        public int getCount() {
            return currentKeyboards.size();
        }
        
        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }
        
        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            
            Context context = container.getContext();
            
            // Create EmojiPageKeyboardView
            EmojiPageKeyboardView keyboardView = new EmojiPageKeyboardView(context, null);
            keyboardView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            
            // Set keyboard if available
            if (position < currentKeyboards.size()) {
                EmojiKeyboard keyboard = currentKeyboards.get(position);
                keyboardView.setKeyboard(keyboard);
            }
            
            // Set key listener
            if (keyListener != null) {
                keyboardView.setOnKeyEventListener(keyListener);
            }
            
            container.addView(keyboardView);
            return keyboardView;
        }
        
        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            if (object instanceof EmojiPageKeyboardView) {
                EmojiPageKeyboardView view = (EmojiPageKeyboardView) object;
                view.cleanup();
                container.removeView(view);
            }
        }
        
        @Override
        public int getItemPosition(Object object) {
            // Force recreation of all views when data changes
            return POSITION_NONE;
        }
    }
}
