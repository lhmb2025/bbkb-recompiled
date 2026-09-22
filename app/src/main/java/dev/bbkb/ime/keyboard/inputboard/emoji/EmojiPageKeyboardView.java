package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.AudioAndHapticFeedbackManager;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.keyboard.internal.MoreKeysPanel;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.internal.KeyDetector;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.KeyboardView;
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboard;
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboardView;
import dev.bbkb.ime.keyboard.internal.PointerTracker;
import dev.bbkb.ime.keyboard.internal.BatchInputTimerHandler;
import dev.bbkb.ime.keyboard.internal.TimerHandler;
import dev.bbkb.ime.keyboard.internal.KeyPreviewChoreographer;
import dev.bbkb.ime.keyboard.internal.KeyPreviewDrawParams;
import dev.bbkb.ime.keyboard.internal.LongPressHandler;
import dev.bbkb.ime.keyboard.internal.DrawingPreviewPlacerView;

import java.util.List;

/**
 * Custom KeyboardView for rendering a single page of emoji keys.
 * 
 * Extends KeyboardView to provide:
 * - Touch event handling for emoji selection
 * - Long-press support for emoji variants (skin tones, etc.)
 * - More keys popup panel display
 * - Gesture detection (tap, long press, swipe)
 * - Key press visual feedback and animations
 * 
 * Each instance displays one page of emoji grid. Multiple instances are managed
 * by EmojiPagerAdapter within the emoji picker's ViewPager.
 */

public final class EmojiPageKeyboardView extends KeyboardView implements GestureDetector.OnGestureListener, TimerHandler.Callbacks, LongPressHandler.KeyPreviewDismisser, MoreKeysPanel.Controller, PointerTracker.KeyDrawingProxy, PointerTracker.GestureEnabledProvider {

    private static final String TAG = "EmojiPageKeyboardView";

    @Override
    protected boolean capPainterDrawsEmptyKeys() {
        // Per-style: Material floats each emoji on its own key cap (the pager behind
        // is painted the board background color by EmojiPalettesView.applyColors);
        // BB10 draws bare glyphs on the board. Pressed feedback stays either way —
        // the cap painters give pressed empty keys a state layer regardless.
        return dev.bbkb.ime.keyboard.KeyboardColorManager.styleSpec().getEmojiCaps();
    }

    private static final OnEmojiKeyListener EMPTY_LISTENER = new OnEmojiKeyListener() {
        @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
        public void onEmojiKeyPressed(Key key) {
        }

        @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
        public void onEmojiKeyReleased(Key key) {
        }
    };

    private KeyPreviewDrawParams keyboardStyleSet;

    private View moreKeysLayoutView;

    private boolean isDimmed;

    private final Paint dimPaint;

    private final int[] windowLocationArray;

    private TimerHandler keyboardHandler;

    private MoreKeysPanel moreKeysDrawingProxy;

    private final DrawingPreviewPlacerView moreKeysLayoutParams;

    private final BatchInputTimerHandler keyPreviewHandler;

    private final KeyPreviewChoreographer keyPreviewController;

    private Key currentKey;

    private OnEmojiKeyListener keyEventListener;

    private final KeyDetector keyboardActionListener;

    private final GestureDetector gestureDetector;


    
    public interface OnEmojiKeyListener {
        void onEmojiKeyPressed(Key key);

        void onEmojiKeyReleased(Key key);
    }

    @Override
    public void showGestureTrail(PointerTracker c1084t) {
    }

    @Override
    public void showSlidingKeyInputPreview(PointerTracker c1084t) {
    }
    
    @Override
    public void invalidateKeyGraphics(Key key) {
        // Default implementation - no action required
    }

    @Override
    public void onLongPress(PointerTracker c1084t) {
    }

    @Override // android.view.View
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        return true;
    }

    @Override
    public void startWhileTypingFadeinAnimation() {
    }

    @Override
    public void startWhileTypingFadeoutAnimation() {
    }

    @Override
    public void dismissGestureTrail() {
    }

    @Override
    public boolean isGestureInputEnabled() {
        return false;
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
        return false;
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public boolean onScroll(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
        return false;
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public void onShowPress(MotionEvent motionEvent) {
    }

    @Override
    public void onShowMoreKeysPanel(MoreKeysPanel interfaceC1071m) {
        updateMoreKeysPosition();
        interfaceC1071m.showInParent(this.moreKeysLayoutParams);
        this.moreKeysDrawingProxy = interfaceC1071m;
        setDimmed(true);
    }

    public boolean isShowingMoreKeys() {
        MoreKeysPanel interfaceC1071m = this.moreKeysDrawingProxy;
        return interfaceC1071m != null && interfaceC1071m.isShowingInParent();
    }

    @Override
    public void onCancelMoreKeysPanel() {
        setDimmed(false);
        if (isShowingMoreKeys()) {
            this.moreKeysDrawingProxy.removeFromParent();
            this.moreKeysDrawingProxy = null;
        }
    }

    @Override
    public void onDismissMoreKeysPanel() {
        PointerTracker.dismissAllMoreKeysPanels();
    }

    @Override
    public void dismissKeyPreviewWithoutDelay(Key key) {
        this.keyPreviewController.dismissKeyPreview(key, false);
        invalidateKey(key);
    }

    @Override
    public void dismissAllKeyPreviews() {
        this.keyPreviewController.dismissAllKeyPreviews();
        PointerTracker.setReleasedKeyGraphicsToAllKeys();
    }

    @Override
    public void showKeyPreview(Key key) {
        Keyboard keyboard;
        if (key == null || key.isNoKeyPreview() || (keyboard = getKeyboard()) == null) {
            return;
        }
        KeyPreviewDrawParams c1063v = this.keyboardStyleSet;
        if (!c1063v.isPopupEnabled()) {
            c1063v.setVisibleOffset(-keyboard.mVerticalGap);
            return;
        }
        updateMoreKeysPosition();
        getLocationInWindow(this.windowLocationArray);
        this.keyPreviewController.placeAndShowKeyPreview(key, keyboard.mIconsSet, this.textFormatter, getWidth(), this.windowLocationArray, this.moreKeysLayoutParams, isHardwareAccelerated());
    }

    @Override
    public void dismissKeyPreview(Key key) {
        this.keyPreviewController.dismissKeyPreview(key, true);
    }

    public EmojiPageKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.keyboardViewStyle);
    }

    public EmojiPageKeyboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.keyboardStyleSet = null;
        this.moreKeysLayoutView = null;
        this.dimPaint = new Paint();
        this.windowLocationArray = CoordinateUtils.newCoordinateArray();
        this.keyEventListener = EMPTY_LISTENER;
        this.keyboardActionListener = new KeyDetector();
        this.gestureDetector = new GestureDetector(context, this);
        this.gestureDetector.setIsLongpressEnabled(true);
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.MainKeyboardView, i, R.style.MainKeyboardView);
        this.keyboardStyleSet = new KeyPreviewDrawParams(typedArrayObtainStyledAttributes);
        this.moreKeysLayoutView = LayoutInflater.from(getContext()).inflate(typedArrayObtainStyledAttributes.getResourceId(R.styleable.MainKeyboardView_moreKeysKeyboardLayout, 0), (ViewGroup) null);
        this.moreKeysLayoutParams = new DrawingPreviewPlacerView(context, attributeSet);
        int i2 = typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_backgroundDimAlpha, 0);
        this.dimPaint.setColor(-16777216);
        this.keyboardHandler = new TimerHandler(this, typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0));
        this.dimPaint.setAlpha(i2);
        this.keyPreviewController = new KeyPreviewChoreographer(this.keyboardStyleSet);
        this.keyPreviewHandler = new BatchInputTimerHandler(typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_gestureRecognitionUpdateTime, 0));
        typedArrayObtainStyledAttributes.recycle();
    }

    private void updateMoreKeysPosition() {
        getLocationInWindow(this.windowLocationArray);
        this.moreKeysLayoutParams.setKeyboardViewGeometry(this.windowLocationArray, getWidth(), getHeight());
    }

    @Override // android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachMoreKeysToWindow();
    }

    private ViewGroup getWindowContentView() {
        View rootView = getRootView();
        if (rootView != null) {
            return (ViewGroup) rootView.findViewById(android.R.id.content);
        }
        return null;
    }

    /**
     * Attaches the more-keys placer to the window content view, mirroring
     * {@code MainKeyboardView.installPreviewPlacerView()}.
     *
     * <p>Audit IB-5: the guard used to be inverted - the placer was added only when a
     * view carrying the placer's own id was <em>already</em> in the content view. The
     * placer inherits its id from the AttributeSet it is constructed with, so down the
     * XML path it passed by accident (findViewById found the page view itself), while
     * the programmatic path used by {@code EmojiSearchOverlayView}
     * ({@code new EmojiPageKeyboardView(context, null)}) has {@code NO_ID}, so
     * {@code findViewById(-1)} returned null and the placer was never attached - which
     * silently killed skin-tone long-press popups on every emoji shown through the
     * search/recents overlay.</p>
     */
    private void attachMoreKeysToWindow() {
        ViewGroup windowContentView = getWindowContentView();
        if (windowContentView == null) {
            return;
        }
        if (this.moreKeysLayoutParams.getParent() == null) {
            windowContentView.addView(this.moreKeysLayoutParams);
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ViewGroup windowContentView = getWindowContentView();
        if (windowContentView != null) {
            windowContentView.removeView(this.moreKeysLayoutParams);
        }
        this.moreKeysLayoutParams.removeAllViews();
        cleanup();
    }

    private void cancelAllPendingEvents() {
        this.keyboardHandler.cancelAllTimers();
        this.keyPreviewHandler.cancelAllUpdateBatchInputTimers();
        dismissAllKeyPreviews();
        dismissGestureTrail();
        PointerTracker.dismissAllMoreKeysPanels();
        PointerTracker.cancelAllPointerTrackers();
        this.keyboardHandler.removeCallbacksAndMessages(null);
        this.keyPreviewHandler.removeCallbacksAndMessages(null);
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void cleanup() {
        cancelAllPendingEvents();
        super.cleanup();
        this.moreKeysLayoutParams.removeAllDrawingPreviews();
    }

    public void setOnKeyEventListener(OnEmojiKeyListener interfaceC0967a) {
        this.keyEventListener = interfaceC0967a;
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void setKeyboard(Keyboard keyboard) {
        this.keyboardHandler.cancelAllLongPressTimers();
        super.setKeyboard(keyboard);
        this.keyboardActionListener.setKeyboard(keyboard, 0.0f, 0.0f);
    }

    @Override // android.view.View
    public boolean onTouchEvent(MotionEvent motionEvent) {
        if (isShown()) {
            Key keyM6620b = getKeyFromMotionEvent(motionEvent);
            switch (motionEvent.getActionMasked()) {
                case 0:
                    this.currentKey = keyM6620b;
                    break;
                case 1:
                    releaseAllKeys();
                    break;
                case 2:
                    Key key = this.currentKey;
                    if (key == null || !key.equals(keyM6620b)) {
                        releaseAllKeys();
                        break;
                    }
                    break;
            }
        }
        // Route all events through processPointerEvents, which is the single
        // dispatcher: it forwards to the GestureDetector and, when the gesture is
        // consumed (e.g. a tap), terminates the PointerTracker via cancelTracking(). Calling
        // gestureDetector.onTouchEvent() here and returning early on a consumed UP
        // would bypass that cleanup, leaving the long-press timer armed on DOWN to
        // fire and open the dimmed MoreKeys overlay, which swallows all touch input.
        return processPointerEvents(motionEvent);
    }

    private boolean processPointerEvents(MotionEvent motionEvent) {
        GestureDetector gestureDetector;
        PointerTracker c1084tM7596a = PointerTracker.getPointerTracker(motionEvent.getPointerId(motionEvent.getActionIndex()));
        if (!isShowingMoreKeys() && (gestureDetector = this.gestureDetector) != null && gestureDetector.onTouchEvent(motionEvent)) {
            c1084tM7596a.cancelTracking();
        } else {
            if (isShowingMoreKeys()) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            c1084tM7596a.processMotionEvent(motionEvent, this.keyboardActionListener);
        }
        return true;
    }

    private Key getKeyFromMotionEvent(MotionEvent motionEvent) {
        int actionIndex = motionEvent.getActionIndex();
        return this.keyboardActionListener.detectHitKey((int) motionEvent.getX(actionIndex), (int) motionEvent.getY(actionIndex));
    }

    @Override // android.view.View
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Fix for multi-row emoji display:
        // Calculate proper height dynamically from actual key positions
        // This ensures correct display on any screen size
        Keyboard keyboard = getKeyboard();
        if (keyboard != null && keyboard instanceof EmojiKeyboard) {
            int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
            
            // Get all keys to calculate actual required height
            List<Key> keys = keyboard.getKeys();
            int keyCount = keys.size();
            
            if (keyCount > 0) {
                // Find the bottom-most key to determine required height
                // This works for any grid layout (7 cols, 10 cols, etc.) on any screen size
                int maxBottom = 0;
                for (Key key : keys) {
                    // Use the key's actual hit box rectangle for accurate positioning
                    int keyBottom = key.getHitBox().bottom;  // Rect.bottom
                    if (keyBottom > maxBottom) {
                        maxBottom = keyBottom;
                    }
                }
                
                // Add padding to the calculated height
                int calculatedHeight = maxBottom + getPaddingTop() + getPaddingBottom();

                // Log for debugging (helps verify correct height on different devices)
                // Log.d(TAG, "EmojiPage onMeasure: keyCount=" + keyCount +
                //           ", maxBottom=" + maxBottom +
                //           ", calculatedHeight=" + calculatedHeight +
                //           ", paddingTop=" + getPaddingTop() +
                //           ", paddingBottom=" + getPaddingBottom());
                
                setMeasuredDimension(width, calculatedHeight);
                return;
            }
        }
        // Fallback to parent implementation
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
    
    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (this.isDimmed) {
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.dimPaint);
        }
    }

    private void setDimmed(boolean z) {
        boolean z2 = this.isDimmed != z;
        this.isDimmed = z;
        if (z2) {
            invalidateAllKeys();
        }
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public boolean onDown(MotionEvent motionEvent) {
        Key keyM6620b = getKeyFromMotionEvent(motionEvent);
        if (keyM6620b == null) {
            return false;
        }
        // Skip blank placeholder keys (code -21) which fill empty slots in recents
        if (keyM6620b.getCode() == -21) {
            return false;
        }
        this.currentKey = keyM6620b;
        keyM6620b.onPressed();
        invalidateKey(keyM6620b);
        AudioAndHapticFeedbackManager.getInstance().performAudioAndHapticFeedback(keyM6620b.getCode(), this);
        this.keyEventListener.onEmojiKeyPressed(keyM6620b);
        return false;
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public boolean onSingleTapUp(MotionEvent motionEvent) {
        Key keyM6620b = getKeyFromMotionEvent(motionEvent);
        if (keyM6620b == null) {
            return false;
        }
        // Skip blank placeholder keys (code -21) which fill empty slots in recents
        int keyCode = keyM6620b.getCode();
        if (keyCode == -21) {
            return false;
        }
        keyM6620b.onReleased();
        invalidateKey(keyM6620b);
        this.keyEventListener.onEmojiKeyReleased(keyM6620b);
        releaseAllKeys();
        return true;
    }

    public void releaseAllKeys() {
        Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            List<Key> listMo6608c = keyboard.getKeys();
            for (int i = 0; i < listMo6608c.size(); i++) {
                listMo6608c.get(i).onReleased();
            }
            invalidateAllKeys();
        }
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public void onLongPress(MotionEvent motionEvent) {
        Key keyM6620b = getKeyFromMotionEvent(motionEvent);
        if (keyM6620b == null) {
            return;
        }
        // Skip blank placeholder keys (code -21) which fill empty slots in recents
        if (keyM6620b.getCode() == -21) {
            return;
        }
        MoreKeysKeyboard moreKeysKeyboardMo5338b = new MoreKeysKeyboard.Builder(getContext(), keyM6620b, getKeyboard(), false, this.keyboardStyleSet.getVisibleWidth(), this.keyboardStyleSet.getVisibleHeight(), createPaintForKey(keyM6620b)).build();
        View view = this.moreKeysLayoutView;
        MoreKeysKeyboardView moreKeysKeyboardView = (MoreKeysKeyboardView) view.findViewById(R.id.more_keys_keyboard_view);
        moreKeysKeyboardView.setKeyboard(moreKeysKeyboardMo5338b);
        view.measure(-2, -2);
        moreKeysKeyboardView.showMoreKeysPanel(this, this, keyM6620b.getX() + (keyM6620b.getWidth() / 2), keyM6620b.getY(), KeyboardSwitcher.getInstance().getKeyboardActionListener());
        PointerTracker.getPointerTracker(motionEvent.getPointerId(motionEvent.getActionIndex())).startMoreKeysPanel(moreKeysKeyboardView);
        dismissKeyPreviewWithoutDelay(keyM6620b);
    }
}
