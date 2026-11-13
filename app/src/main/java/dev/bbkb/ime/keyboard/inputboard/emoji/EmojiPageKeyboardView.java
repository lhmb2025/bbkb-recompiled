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
import dev.bbkb.ime.core.settings.PrefsManager;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.keyboard.internal.MoreKeysPanel;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.internal.KeyDetector;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.keyboard.KeyboardView;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
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
 * One page of the emoji grid: tap to type, long-press for the skin-tone popup. Pages are made
 * by EmojiPagerAdapter for the board's ViewPager2 and by the search overlay.
 */

public final class EmojiPageKeyboardView extends KeyboardView implements GestureDetector.OnGestureListener, TimerHandler.Callbacks, LongPressHandler.KeyPreviewDismisser, MoreKeysPanel.Controller, PointerTracker.KeyDrawingProxy, PointerTracker.GestureEnabledProvider {

    private static final String TAG = "EmojiPageKeyboardView";

    @Override
    protected boolean capPainterDrawsEmptyKeys() {
        // Material draws a cap per emoji; BB10 draws bare glyphs. Pressed keys get a state
        // layer either way.
        return dev.bbkb.ime.keyboard.KeyboardColorManager.styleSpec().getEmojiCaps();
    }

    private static final OnEmojiKeyListener EMPTY_LISTENER = new OnEmojiKeyListener() {
        @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
        public void onEmojiKeyPressed(Key key) {
            // No listener set yet.
        }

        @Override // dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPageKeyboardView.OnEmojiKeyListener
        public void onEmojiKeyReleased(Key key) {
            // No listener set yet.
        }
    };

    private KeyPreviewDrawParams keyboardStyleSet;

    /** The skin-tone popup's layout, inflated on the first long-press (see {@link #moreKeysLayoutView()}). */
    private final int moreKeysLayoutResId;

    /** This page's own popup view, when no {@link SharedMoreKeysPanel} was given. */
    private View moreKeysLayoutView;

    private SharedMoreKeysPanel sharedMoreKeysPanel;

    private boolean isDimmed;

    private final Paint dimPaint;

    private final int[] windowLocationArray;

    private TimerHandler keyboardHandler;

    private MoreKeysPanel moreKeysDrawingProxy;

    private final DrawingPreviewPlacerView moreKeysLayoutParams;

    /** The window content view the placer was added to, kept for its removal. */
    private ViewGroup placerHost;

    private final BatchInputTimerHandler keyPreviewHandler;

    private final KeyPreviewChoreographer keyPreviewController;

    private Key currentKey;

    private OnEmojiKeyListener keyEventListener;

    private final KeyDetector keyboardActionListener;

    private final GestureDetector gestureDetector;


    
    public interface OnEmojiKeyListener {
        void onEmojiKeyPressed(Key key);

        void onEmojiKeyReleased(Key key);

        /** The skin-tone popup changed the remembered tone of {@code emoji}'s base; redraw its keys. */
        default void onToneMemoryChanged(String emoji) {
            // Optional for listeners.
        }

        /** An emoji was picked from the skin-tone popup: it counts as used, like a tapped key. */
        default void onPopupEmojiPicked(String emoji) {
            // Optional for listeners.
        }
    }

    /** @param c1084t the pointer tracker */
    @Override
    public void showGestureTrail(PointerTracker c1084t) {
        // No gesture typing on emoji pages.
    }

    /** @param c1084t the pointer tracker */
    @Override
    public void showSlidingKeyInputPreview(PointerTracker c1084t) {
        // No sliding key input on emoji pages.
    }
    
    @Override
    public void invalidateKeyGraphics(Key key) {
        // Keys are redrawn through invalidateKey.
    }

    /** @param c1084t the pointer tracker */
    @Override
    public void onLongPress(PointerTracker c1084t) {
        // Long-press is handled by the GestureDetector (onLongPress(MotionEvent)).
    }

    @Override // android.view.View
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
        return true;
    }

    @Override
    public void startWhileTypingFadeinAnimation() {
        // No while-typing animation on emoji pages.
    }

    @Override
    public void startWhileTypingFadeoutAnimation() {
        // No while-typing animation on emoji pages.
    }

    @Override
    public void dismissGestureTrail() {
        // No gesture trail to dismiss.
    }

    @Override
    public boolean isGestureInputEnabled() {
        return false;
    }

    /**
     * @param f horizontal velocity
     * @param f2 vertical velocity
     */
    @Override // android.view.GestureDetector.OnGestureListener
    public boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
        return false;
    }

    /**
     * @param f horizontal scroll distance
     * @param f2 vertical scroll distance
     */
    @Override // android.view.GestureDetector.OnGestureListener
    public boolean onScroll(MotionEvent motionEvent, MotionEvent motionEvent2, float f, float f2) {
        return false;
    }

    @Override // android.view.GestureDetector.OnGestureListener
    public void onShowPress(MotionEvent motionEvent) {
        // Pressed state is set in onDown.
    }

    /** @param interfaceC1071m the popup panel being shown */
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

    /** @param i the default style attribute */
    public EmojiPageKeyboardView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.keyboardStyleSet = null;
        this.dimPaint = new Paint();
        this.windowLocationArray = CoordinateUtils.newCoordinateArray();
        this.keyEventListener = EMPTY_LISTENER;
        this.keyboardActionListener = new KeyDetector();
        this.gestureDetector = new GestureDetector(context, this);
        this.gestureDetector.setIsLongpressEnabled(true);
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.MainKeyboardView, i, R.style.MainKeyboardView);
        this.keyboardStyleSet = new KeyPreviewDrawParams(typedArrayObtainStyledAttributes);
        // Inflated lazily: most pages are never long-pressed.
        this.moreKeysLayoutResId = typedArrayObtainStyledAttributes.getResourceId(R.styleable.MainKeyboardView_moreKeysKeyboardLayout, 0);
        this.moreKeysLayoutParams = new DrawingPreviewPlacerView(context, attributeSet);
        int i2 = typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_backgroundDimAlpha, 0);
        this.dimPaint.setColor(-16777216);
        this.keyboardHandler = new TimerHandler(this, typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0));
        this.dimPaint.setAlpha(i2);
        this.keyPreviewController = new KeyPreviewChoreographer(this.keyboardStyleSet);
        this.keyPreviewHandler = new BatchInputTimerHandler(typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_gestureRecognitionUpdateTime, 0));
        typedArrayObtainStyledAttributes.recycle();
    }

    /** Shares one popup view among the pages of a board instead of each page inflating its own. */
    void setSharedMoreKeysPanel(SharedMoreKeysPanel panel) {
        this.sharedMoreKeysPanel = panel;
    }

    private View moreKeysLayoutView() {
        if (this.sharedMoreKeysPanel != null) {
            return this.sharedMoreKeysPanel.get(getContext(), this.moreKeysLayoutResId);
        }
        if (this.moreKeysLayoutView == null) {
            this.moreKeysLayoutView = LayoutInflater.from(getContext()).inflate(this.moreKeysLayoutResId, (ViewGroup) null);
        }
        return this.moreKeysLayoutView;
    }

    /**
     * The skin-tone popup view shared by every page of one board, inflated on the first long-press.
     * Only one popup is up at a time; showing it detaches it from the page that held it last.
     */

    static final class SharedMoreKeysPanel {
        private View view;
        private int layoutResId;

        View get(Context context, int resId) {
            if (this.view == null || this.layoutResId != resId) {
                this.view = LayoutInflater.from(context).inflate(resId, (ViewGroup) null);
                this.layoutResId = resId;
            }
            return this.view;
        }

        /** The popup view if a long-press has inflated it, else null. */
        View peek() {
            return this.view;
        }
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
     * Attaches the more-keys placer to the window content view when it has no parent. Do not
     * test by view id: pages built in code (the search overlay) have NO_ID.
     */
    private void attachMoreKeysToWindow() {
        ViewGroup windowContentView = getWindowContentView();
        if (windowContentView == null) {
            return;
        }
        if (this.moreKeysLayoutParams.getParent() == null) {
            windowContentView.addView(this.moreKeysLayoutParams);
        }
        this.placerHost = windowContentView;
    }

    /**
     * Removes the more-keys placer, posted when the host is mid-layout: RecyclerView detaches pages
     * inside the content frame's layoutChildren, and removing a child then throws an NPE. Uses the
     * host remembered on attach, since a detached page cannot reach the content view.
     */
    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        final ViewGroup host = this.placerHost;
        final DrawingPreviewPlacerView placer = this.moreKeysLayoutParams;
        if (host != null) {
            if (host.isInLayout()) {
                host.post(() -> {
                    if (!isAttachedToWindow() && placer.getParent() == host) {
                        host.removeView(placer);
                    }
                });
            } else {
                host.removeView(placer);
            }
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

    /** @param interfaceC0967a receives emoji key presses and releases */
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
        // Always go through processPointerEvents: on a consumed gesture it cancels the tracker.
        // Returning early here leaves the long-press timer armed and opens a dimmed popup that
        // swallows all touches.
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
        // Height comes from the bottom-most key, not the keyboard's declared height.
        Keyboard keyboard = getKeyboard();
        if (keyboard != null && keyboard instanceof EmojiKeyboard) {
            int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();

            List<Key> keys = keyboard.getKeys();
            int keyCount = keys.size();
            
            if (keyCount > 0) {
                int maxBottom = 0;
                for (Key key : keys) {
                    int keyBottom = key.getHitBox().bottom;
                    if (keyBottom > maxBottom) {
                        maxBottom = keyBottom;
                    }
                }

                int calculatedHeight = maxBottom + getPaddingTop() + getPaddingBottom();

                setMeasuredDimension(width, calculatedHeight);
                return;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
    
    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (this.isDimmed) {
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.dimPaint);
        }
    }

    /** @param z true to dim the page behind an open popup */
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
        // -21 is a blank placeholder key filling empty recents slots.
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
        if (keyM6620b.getCode() == -21) {
            return;
        }
        MoreKeysKeyboard moreKeysKeyboardMo5338b = new MoreKeysKeyboard.Builder(getContext(), keyM6620b, getKeyboard(), false, this.keyboardStyleSet.getVisibleWidth(), this.keyboardStyleSet.getVisibleHeight(), createPaintForKey(keyM6620b)).build();
        View view = moreKeysLayoutView();
        MoreKeysKeyboardView moreKeysKeyboardView = (MoreKeysKeyboardView) view.findViewById(R.id.more_keys_keyboard_view);
        moreKeysKeyboardView.setKeyboard(moreKeysKeyboardMo5338b);
        view.measure(-2, -2);
        moreKeysKeyboardView.showMoreKeysPanel(this, this, keyM6620b.getX() + (keyM6620b.getWidth() / 2), keyM6620b.getY(),
                new ToneRecordingListener(KeyboardSwitcher.getInstance().getKeyboardActionListener(), getContext(), this.keyEventListener));
        PointerTracker.getPointerTracker(motionEvent.getPointerId(motionEvent.getActionIndex())).startMoreKeysPanel(moreKeysKeyboardView);
        dismissKeyPreviewWithoutDelay(keyM6620b);
        holdPagerForTones(keyM6620b);
    }

    /**
     * Keeps the pager from stealing the finger once a popup with tones opens. The popup opens
     * between touch events, so waiting for the next move is too late: RecyclerView would already
     * intercept a diagonal slide. A base-only popup has nothing to slide to, so paging still works.
     */
    void holdPagerForTones(Key key) {
        MoreKeySpec[] popup = key.getMoreKeys();
        if (popup != null && popup.length > 1 && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
    }

    /**
     * Wraps the IME listener for the skin-tone popup: a picked tone is remembered for its base
     * ({@link EmojiToneMemory}) and the board redraws that key. Nothing is remembered in an
     * incognito field.
     */
    static final class ToneRecordingListener implements KeyboardActionListenerInterface {
        private final KeyboardActionListenerInterface delegate;
        private final Context context;
        private final OnEmojiKeyListener board;

        ToneRecordingListener(KeyboardActionListenerInterface delegate, Context context, OnEmojiKeyListener board) {
            this.delegate = delegate;
            this.context = context.getApplicationContext();
            this.board = board;
        }

        @Override
        public void onMoreKeysKeyTyped() {
            delegate.onMoreKeysKeyTyped();
        }

        @Override
        public void onCodeInput(int code, int x, int y, long time, boolean fromMoreKeys) {
            if (code > 0) {
                remember(new String(Character.toChars(code)));
            }
            delegate.onCodeInput(code, x, y, time, fromMoreKeys);
        }

        @Override
        public void onReleaseKey(int code, boolean withSliding) {
            delegate.onReleaseKey(code, withSliding);
        }

        @Override
        public void onTextInput(String text, long time) {
            remember(text);
            delegate.onTextInput(text, time);
        }

        @Override
        public void onPressKey(int code, int repeatCount, boolean isSinglePointer) {
            delegate.onPressKey(code, repeatCount, isSinglePointer);
        }

        @Override
        public boolean onCustomRequest(int requestCode) {
            return delegate.onCustomRequest(requestCode);
        }

        @Override
        public void onCancelInput() {
            delegate.onCancelInput();
        }

        private void remember(String emoji) {
            if (KeyboardSwitcher.getInstance().isNoPersonalizedLearning()) {
                return;
            }
            // A popup pick counts for recents too.
            board.onPopupEmojiPicked(emoji);

            if (EmojiToneMemory.onEmojiPicked(PrefsManager.INSTANCE.getPrefs(context), emoji)) {
                board.onToneMemoryChanged(emoji);
            }
        }
    }
}
