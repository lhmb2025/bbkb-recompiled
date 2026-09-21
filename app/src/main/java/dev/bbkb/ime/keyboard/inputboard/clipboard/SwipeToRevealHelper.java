package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.res.Resources;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import dev.bbkb.ime.BuildConfig;

/**
 * Custom swipe-to-reveal helper for clipboard items.
 * Replaces ItemTouchHelper to provide clean touch event handling without conflicts.
 * 
 * Features:
 * - Swipe to reveal share/delete buttons (fixed 90dp reveal width)
 * - Tap buttons when revealed
 * - Swipe back to close
 * - Tap outside item to close
 * - Smooth animations
 * 
 * This implementation directly handles touch events via RecyclerView.OnItemTouchListener,
 * giving us full control over event routing and eliminating conflicts with button clicks.
 */
public class SwipeToRevealHelper implements RecyclerView.OnItemTouchListener {
    
    // Constants
    private static final float REVEAL_WIDTH_DP = 90f;
    /** Material schema: one left swipe reveals BOTH actions on the right. */
    private static final float SINGLE_SIDED_REVEAL_WIDTH_DP = 144f;
    private static final float SWIPE_THRESHOLD = 0.3f; // 30% of reveal width to trigger reveal
    private static final int ANIMATION_DURATION_MS = 200;
    private static final String TAG = "SwipeToRevealHelper";
    
    // State tracking
    private enum SwipeState {
        IDLE,           // No swipe in progress
        TRACKING,       // Tracking potential swipe
        SWIPING,        // Active swipe in progress
        REVEALED        // Item is revealed
    }
    
    private enum RevealDirection {
        NONE,
        LEFT,   // Swiping left reveals buttons on right
        RIGHT   // Swiping right reveals buttons on left
    }
    
    // Components
    private final RecyclerView recyclerView;
    private final ClipboardAdapter adapter;
    private final GestureDetector gestureDetector;
    private final float revealWidthPx;
    private final float swipeThresholdPx;
    private final int touchSlop;
    /** Material: actions live only on the right; only left swipes reveal. */
    private final boolean singleSidedReveal;
    
    // Current swipe state
    private SwipeState currentState = SwipeState.IDLE;
    private int currentlyRevealedPosition = RecyclerView.NO_POSITION;
    private View currentlyRevealedForeground = null;
    private RevealDirection currentRevealDirection = RevealDirection.NONE;
    
    // Touch tracking
    private float initialTouchX;
    private float initialTouchY;
    private float lastTouchX;
    private ClipboardViewHolder activeViewHolder;
    private VelocityTracker velocityTracker;
    
    /**
     * Create a SwipeToRevealHelper for the given RecyclerView
     */
    public SwipeToRevealHelper(@NonNull RecyclerView recyclerView, @NonNull ClipboardAdapter adapter) {
        this.recyclerView = recyclerView;
        this.adapter = adapter;
        
        // Convert reveal width to pixels
        Resources resources = recyclerView.getResources();
        this.singleSidedReveal =
            dev.bbkb.ime.keyboard.KeyboardColorManager.styleSpec().getModernBoards();
        this.revealWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            singleSidedReveal ? SINGLE_SIDED_REVEAL_WIDTH_DP : REVEAL_WIDTH_DP, 
            resources.getDisplayMetrics()
        );
        this.swipeThresholdPx = revealWidthPx * SWIPE_THRESHOLD;
        
        // Get touch slop from system
        ViewConfiguration viewConfig = ViewConfiguration.get(recyclerView.getContext());
        this.touchSlop = viewConfig.getScaledTouchSlop();
        
        // Setup gesture detector for tap detection
        this.gestureDetector = new GestureDetector(
            recyclerView.getContext(),
            new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    return handleTap(e);
                }
            }
        );
        
        // Attach to RecyclerView
        recyclerView.addOnItemTouchListener(this);
    }
    
    // ============================================================================
    // Public API
    // ============================================================================
    
    /**
     * Close any currently revealed item
     */
    public void closeRevealedItem() {
        if (currentlyRevealedForeground != null) {
            animateToClosed(currentlyRevealedForeground);
            clearRevealedState();
        }
    }
    
    // ============================================================================
    // RecyclerView.OnItemTouchListener Implementation
    // ============================================================================
    
    @Override
    public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        int action = e.getActionMasked();
        
        // Only let gesture detector check for taps if we're not actively swiping
        // This prevents the gesture detector from interpreting ACTION_CANCEL as a tap
        if (currentState != SwipeState.SWIPING) {
            gestureDetector.onTouchEvent(e);
        }
        
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return handleActionDown(e);
                
            case MotionEvent.ACTION_MOVE:
                if (BuildConfig.DEBUG) android.util.Log.d(TAG, "ACTION_MOVE - state=" + currentState + ", activeViewHolder=" + (activeViewHolder != null ? "present" : "null"));
                // Only intercept if we detect a horizontal swipe
                if (currentState == SwipeState.TRACKING && activeViewHolder != null) {
                    float dx = e.getX() - initialTouchX;
                    float dy = e.getY() - initialTouchY;
                    
                    if (BuildConfig.DEBUG) android.util.Log.d(TAG, "TRACKING: dx=" + dx + ", dy=" + dy + ", touchSlop=" + touchSlop);
                    
                    // Check if this is a horizontal swipe (not vertical scroll)
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "SWIPE DETECTED! Intercepting touch events");
                        currentState = SwipeState.SWIPING;
                        recyclerView.requestDisallowInterceptTouchEvent(true);
                        return true;
                    }
                }
                break;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // Don't intercept - let onTouchEvent handle it
                break;
        }
        
        return false;
    }
    
    @Override
    public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
        if (activeViewHolder == null) {
            return;
        }
        
        int action = e.getActionMasked();
        
        switch (action) {
            case MotionEvent.ACTION_MOVE:
                handleActionMove(e);
                break;
                
            case MotionEvent.ACTION_UP:
                handleActionUp(e);
                break;
                
            case MotionEvent.ACTION_CANCEL:
                handleActionCancel();
                break;
        }
    }
    
    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // Allow parent to request we don't intercept
        // But ONLY if we're not actively swiping - if we are swiping, we WANT to intercept
        if (disallowIntercept && currentState != SwipeState.SWIPING && currentState != SwipeState.REVEALED) {
            // Cancel ongoing tracking
            handleActionCancel();
        }
    }
    
    // ============================================================================
    // Touch Event Handlers
    // ============================================================================
    
    private boolean handleActionDown(MotionEvent e) {
        // Find which item was touched
        View childView = recyclerView.findChildViewUnder(e.getX(), e.getY());
        if (childView == null) {
            return false;
        }
        
        RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(childView);
        if (!(holder instanceof ClipboardViewHolder)) {
            return false;
        }
        
        ClipboardViewHolder clipHolder = (ClipboardViewHolder) holder;
        
        // Store touch info
        initialTouchX = e.getX();
        initialTouchY = e.getY();
        lastTouchX = initialTouchX;
        activeViewHolder = clipHolder;
        
        // Initialize velocity tracker
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        } else {
            velocityTracker.clear();
        }
        velocityTracker.addMovement(e);
        
        // If an item is revealed, check if touch is on buttons
        if (currentState == SwipeState.REVEALED && clipHolder.getAdapterPosition() == currentlyRevealedPosition) {
            // Check if tap is on share or delete button
            if (isTapOnShareButton(e, clipHolder) || isTapOnDeleteButton(e, clipHolder)) {
                // Let the button handle it - don't intercept
                // Schedule close after button action completes
                if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Touch on button - letting button handle, will close after");
                scheduleCloseAfterButtonClick();
                return false;
            }
        }
        
        currentState = SwipeState.TRACKING;
        return false; // Don't intercept yet - wait to see if it's SwitcherCallbacks swipe
    }
    
    private void handleActionMove(MotionEvent e) {
        if (activeViewHolder == null || currentState != SwipeState.SWIPING) {
            return;
        }
        
        velocityTracker.addMovement(e);
        
        float currentX = e.getX();
        float dx = currentX - lastTouchX;
        float totalDx = currentX - initialTouchX;
        lastTouchX = currentX;
        
        View foreground = activeViewHolder.foreground;
        float currentTranslation = foreground.getTranslationX();
        float newTranslation = currentTranslation + dx;
        
        // If this item is already revealed, allow swiping back to close
        if (activeViewHolder.getAdapterPosition() == currentlyRevealedPosition) {
            // Revealed to the left (buttons on right), allow swiping right to close
            if (currentRevealDirection == RevealDirection.LEFT) {
                newTranslation = Math.min(0, Math.max(-revealWidthPx, newTranslation));
            }
            // Revealed to the right (buttons on left), allow swiping left to close
            else if (currentRevealDirection == RevealDirection.RIGHT) {
                newTranslation = Math.max(0, Math.min(revealWidthPx, newTranslation));
            }
        } else {
            // Not yet revealed - limit to reveal width
            // Swiping left (negative dx) reveals buttons on right
            // Swiping right (positive dx) reveals buttons on left
            // (single-sided schema: right swipes reveal nothing)
            float maxRight = singleSidedReveal ? 0 : revealWidthPx;
            newTranslation = Math.max(-revealWidthPx, Math.min(maxRight, newTranslation));
        }
        
        foreground.setTranslationX(newTranslation);
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Swipe move: translation=" + newTranslation);
    }
    
    private void handleActionUp(MotionEvent e) {
        if (activeViewHolder == null) {
            return;
        }
        
        velocityTracker.addMovement(e);
        velocityTracker.computeCurrentVelocity(1000);
        
        View foreground = activeViewHolder.foreground;
        float finalTranslation = foreground.getTranslationX();
        float velocity = velocityTracker.getXVelocity();
        
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Swipe end: translation=" + finalTranslation + ", velocity=" + velocity);
        
        // Determine if we should snap to revealed or closed
        boolean shouldReveal = false;
        RevealDirection revealDir = RevealDirection.NONE;
        
        // Check if already revealed and swiping back
        if (activeViewHolder.getAdapterPosition() == currentlyRevealedPosition) {
            if (currentRevealDirection == RevealDirection.LEFT && finalTranslation > -swipeThresholdPx) {
                // Swiped back enough to close
                shouldReveal = false;
            } else if (currentRevealDirection == RevealDirection.RIGHT && finalTranslation < swipeThresholdPx) {
                // Swiped back enough to close
                shouldReveal = false;
            } else {
                // Not swiped back enough - keep revealed
                shouldReveal = true;
                revealDir = currentRevealDirection;
            }
        } else {
            // Not yet revealed - check if should reveal
            if (Math.abs(finalTranslation) > swipeThresholdPx || Math.abs(velocity) > 1000) {
                shouldReveal = true;
                revealDir = finalTranslation < 0 ? RevealDirection.LEFT : RevealDirection.RIGHT;
            }
            if (singleSidedReveal && revealDir == RevealDirection.RIGHT) {
                shouldReveal = false;
                revealDir = RevealDirection.NONE;
            }
        }
        
        if (shouldReveal) {
            // Close any other revealed item first
            if (currentlyRevealedForeground != null && currentlyRevealedForeground != foreground) {
                animateToClosed(currentlyRevealedForeground);
            }
            
            animateToRevealed(foreground, revealDir);
            currentlyRevealedForeground = foreground;
            currentlyRevealedPosition = activeViewHolder.getAdapterPosition();
            currentRevealDirection = revealDir;
            currentState = SwipeState.REVEALED;
            
            // Make foreground non-clickable so button taps pass through
            activeViewHolder.setForegroundClickable(false);
        } else {
            animateToClosed(foreground);
            if (activeViewHolder.getAdapterPosition() == currentlyRevealedPosition) {
                clearRevealedState();
            }
            
            // Make foreground clickable for paste
            activeViewHolder.setForegroundClickable(true);
        }
        
        // Cleanup
        activeViewHolder = null;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        
        if (!shouldReveal || currentState != SwipeState.REVEALED) {
            currentState = SwipeState.IDLE;
        }
    }
    
    private void handleActionCancel() {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "handleActionCancel called - state=" + currentState);
        if (activeViewHolder != null && activeViewHolder.foreground != null) {
            animateToClosed(activeViewHolder.foreground);
            activeViewHolder.setForegroundClickable(true);
        }
        
        activeViewHolder = null;
        currentState = SwipeState.IDLE;
        
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
    
    private boolean handleTap(MotionEvent e) {
        // If an item is revealed and tap is outside it, close it
        if (currentState == SwipeState.REVEALED && currentlyRevealedPosition != RecyclerView.NO_POSITION) {
            View childView = recyclerView.findChildViewUnder(e.getX(), e.getY());
            if (childView == null) {
                // Tapped outside any item
                closeRevealedItem();
                return true;
            }
            
            RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(childView);
            if (holder != null && holder.getAdapterPosition() != currentlyRevealedPosition) {
                // Tapped on a different item
                closeRevealedItem();
                return true;
            }
        }
        
        return false;
    }
    
    // ============================================================================
    // Button Detection
    // ============================================================================
    
    private boolean isTapOnShareButton(MotionEvent e, ClipboardViewHolder holder) {
        if (holder.shareButton == null) {
            return false;
        }
        
        int[] location = new int[2];
        holder.shareButton.getLocationOnScreen(location);
        
        float rawX = e.getRawX();
        float rawY = e.getRawY();
        
        return rawX >= location[0] && 
               rawX <= location[0] + holder.shareButton.getWidth() &&
               rawY >= location[1] && 
               rawY <= location[1] + holder.shareButton.getHeight();
    }
    
    private boolean isTapOnDeleteButton(MotionEvent e, ClipboardViewHolder holder) {
        if (holder.deleteButton == null) {
            return false;
        }
        
        int[] location = new int[2];
        holder.deleteButton.getLocationOnScreen(location);
        
        float rawX = e.getRawX();
        float rawY = e.getRawY();
        
        return rawX >= location[0] && 
               rawX <= location[0] + holder.deleteButton.getWidth() &&
               rawY >= location[1] && 
               rawY <= location[1] + holder.deleteButton.getHeight();
    }
    
    // ============================================================================
    // Animation
    // ============================================================================
    
    private void animateToRevealed(View foreground, RevealDirection direction) {
        float targetTranslation = direction == RevealDirection.LEFT ? -revealWidthPx : revealWidthPx;
        
        foreground.animate()
            .translationX(targetTranslation)
            .setDuration(ANIMATION_DURATION_MS)
            .start();
        
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Animating to revealed: " + targetTranslation);
    }
    
    private void animateToClosed(View foreground) {
        foreground.animate()
            .translationX(0)
            .setDuration(ANIMATION_DURATION_MS)
            .start();
        
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Animating to closed");
    }
    
    // ============================================================================
    // State Management
    // ============================================================================
    
    private void clearRevealedState() {
        currentlyRevealedForeground = null;
        currentlyRevealedPosition = RecyclerView.NO_POSITION;
        currentRevealDirection = RevealDirection.NONE;
        currentState = SwipeState.IDLE;
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Cleared revealed state");
    }
    
    /**
     * Schedule closing the revealed item after a button click.
     * This gives the button's onClick handler time to execute first.
     */
    private void scheduleCloseAfterButtonClick() {
        recyclerView.postDelayed(() -> {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Closing revealed item after button click");
            closeRevealedItem();
        }, 50); // Small delay to let onClick execute
    }
}
