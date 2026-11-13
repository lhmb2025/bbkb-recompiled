package dev.bbkb.ime.keyboard;

import android.animation.AnimatorInflater;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import dev.bbkb.ime.keyboard.internal.MoreKeysPanel;
import dev.bbkb.ime.keyboard.internal.KeyDetector;
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboard;
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboardCache;
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboardView;
import dev.bbkb.ime.keyboard.internal.PointerTracker;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.Constants;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.core.gesture.MultiPointerGestureDetector;
import dev.bbkb.ime.keyboard.internal.GestureEventProcessor;
import dev.bbkb.ime.keyboard.internal.SlidingKeyInputDrawingPreview;
import dev.bbkb.ime.keyboard.internal.BatchInputTimerHandler;
import dev.bbkb.ime.keyboard.internal.GestureTrailsDrawingPreview;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.TimerHandler;
import dev.bbkb.ime.keyboard.internal.KeyPreviewChoreographer;
import dev.bbkb.ime.keyboard.internal.KeyPreviewDrawParams;
import dev.bbkb.ime.keyboard.internal.KeyDrawParams;
import dev.bbkb.ime.keyboard.internal.LongPressHandler;
import dev.bbkb.ime.keyboard.internal.GestureInputAvailability;
import dev.bbkb.ime.keyboard.internal.DrawingPreviewPlacerView;
import dev.bbkb.ime.keyboard.slideboard.SlideboardManager;

import java.util.HashSet;
import java.util.Iterator;
import dev.bbkb.ime.BuildConfig;


public final class MainKeyboardView extends KeyboardView implements GestureEventProcessor.GestureKeyboardChecker, TimerHandler.Callbacks, LongPressHandler.KeyPreviewDismisser, MoreKeysPanel.Controller, PointerTracker.KeyDrawingProxy, PointerTracker.GestureEnabledProvider, PointerTracker.SlidingPanel {

    private static final String TAG = "MainKeyboardView";

    /** Bold marker emitted by the prediction engine; never rendered on the spacebar. */
    private static final java.util.regex.Pattern PERCENT_B_MARKER = java.util.regex.Pattern.compile(
            "%B", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.LITERAL);

    private final ObjectAnimator mAltCodeKeyWhileTypingFadeinAnimator;

    private int mAltCodeKeyWhileTypingAnimAlpha;

    private final DrawingPreviewPlacerView mDrawingPreviewPlacerView;

    private final int[] mOriginCoords;

    private final GestureTrailsDrawingPreview mGestureTrailsDrawingPreview;

    private final SlidingKeyInputDrawingPreview mGestureFloatingPreviewText;

    private final KeyPreviewDrawParams mKeyPreviewChoreographer;

    private final KeyPreviewChoreographer mKeyPreviewController;

    private final Paint mBackgroundDimPaint;

    private boolean mNeedsToDimEntireKeyboard;

    private final View mMoreKeysKeyboardContainer;

    private final View mMoreKeysKeyboardForActionContainer;

    private final MoreKeysKeyboardCache mMoreKeysKeyboardCache;

    private final boolean mConfigShowMoreKeysKeyboardAtTouchedPoint;

    private MoreKeysPanel mMoreKeysPanel;

    private final KeyDetector mKeyDetector;

    private final TimerHandler mKeyTimerHandler;

    private final BatchInputTimerHandler mBatchUpdateTimerHandler;

    private final int mLanguageOnSpacebarHorizontalMargin;

    private final LongPressHandler mKeyPreviewDismissHandler;

    private final GestureInputAvailability mGestureInputAvailability;

    private final SettingsManager mSettings;


    private final HashSet<Key> mPressedKeys;

    private MultiPointerGestureDetector mGestureDetector;

    private SlideboardManager mSlideboardManager;

    private String mAutoCorrectionText;

    private KeyboardActionListenerInterface mKeyboardActionListener;

    private boolean mShiftKeyState;

    private boolean mShiftKeyLocked;

    /** The ?123 key ({@code key_switch_alpha_symbol}), which doubles as the on-screen Ctrl key. */
    private static final int TO_SYMBOL_KEY_CODE = -3;

    /** What ?123 reads while on-screen Ctrl is armed. A key cap, not prose: not translated. */
    private static final String CTRL_LABEL = "CTRL";

    private boolean mCtrlLabelActive;

    /** Shift-state indicator bar paint; reused across repaints, its colour is set per draw. */
    private final Paint mShiftIndicatorPaint = newShiftIndicatorPaint();

    private Key mShiftKey;

    private Drawable[] mShiftKeyIcons;

    private Key mAltKey;

    private Drawable[] mAltKeyIcons;

    private Key mSpaceKey;

    private final float mAutoCorrectionSpacebarTextRatio;

    private float mAutoCorrectionSpacebarTextSize;

    private final int mAutoCorrectionSpacebarTextColor;

    private final float mMinimumScaleOfSpacebarText;

    private final ObjectAnimator mAltCodeKeyWhileTypingFadeoutAnimator;

    /**
     * E-ink screen (DeviceProfile): every repaint is visible and slow, so skip pressed highlights,
     * fades, animated previews and the gesture trail. Read on each keyboard set.
     */
    private boolean mEinkDisplay;

    /** Whether the user turned on-screen swipe typing on; the trail also needs !mEinkDisplay. */
    private boolean mVkbGesturePreviewRequested;

    /** Posted placer removal; see {@link #onDetachedFromWindow}. */
    private final Runnable mRemovePlacerIfDetached = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) {
                removePlacerFromParent();
            }
        }
    };

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    public SettingsManager getmSettings() {
        return this.mSettings;
    }

    public MainKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    /** @param defStyleAttr default style attribute */
    public MainKeyboardView(Context context, AttributeSet attributeSet, int defStyleAttr) throws Resources.NotFoundException {
        super(context, attributeSet, defStyleAttr);
        this.mAutoCorrectionText = "";
        this.mAltCodeKeyWhileTypingAnimAlpha = 255;
        this.mOriginCoords = CoordinateUtils.newCoordinateArray();
        this.mBackgroundDimPaint = new Paint();
        this.mMoreKeysKeyboardCache = new MoreKeysKeyboardCache();
        this.mKeyPreviewDismissHandler = new LongPressHandler(this);
        this.mGestureInputAvailability = new GestureInputAvailability();
        this.mPressedKeys = new HashSet<>();
        this.mDrawingPreviewPlacerView = new DrawingPreviewPlacerView(context, attributeSet);
        final int resourceId2;
        final int resourceId3;
        final int resourceId4;
        final int resourceId5;
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.MainKeyboardView, defStyleAttr, R.style.MainKeyboardView);
        try {
        int i2 = typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0);
        int i3 = typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_gestureRecognitionUpdateTime, 0);
        this.mKeyTimerHandler = new TimerHandler(this, i2);
        this.mBatchUpdateTimerHandler = new BatchInputTimerHandler(i3);
        this.mKeyDetector = new KeyDetector(typedArrayObtainStyledAttributes.getDimension(R.styleable.MainKeyboardView_keyHysteresisDistance, 0.0f), typedArrayObtainStyledAttributes.getDimension(R.styleable.MainKeyboardView_keyHysteresisDistanceForSlidingModifier, 0.0f));
        PointerTracker.init(typedArrayObtainStyledAttributes, this.mKeyTimerHandler, this.mBatchUpdateTimerHandler, this, this, this);
        GestureEventProcessor.init(typedArrayObtainStyledAttributes, this.mBatchUpdateTimerHandler, this);
        int i4 = typedArrayObtainStyledAttributes.getInt(R.styleable.MainKeyboardView_backgroundDimAlpha, 0);
        this.mBackgroundDimPaint.setColor(-16777216);
        this.mBackgroundDimPaint.setAlpha(i4);
        this.mAutoCorrectionSpacebarTextRatio = typedArrayObtainStyledAttributes.getFraction(R.styleable.MainKeyboardView_autoCorrectionOnSpacebarTextRatio, 1, 1, 1.0f);
        this.mAutoCorrectionSpacebarTextColor = typedArrayObtainStyledAttributes.getColor(R.styleable.MainKeyboardView_autoCorrectionOnSpacebarTextColor, 0);
        this.mMinimumScaleOfSpacebarText = typedArrayObtainStyledAttributes.getFraction(R.styleable.MainKeyboardView_minimumScaleOfSpacebarText, 1, 1, 0.5f);
        resourceId2 = typedArrayObtainStyledAttributes.getResourceId(R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeoutAnimator, 0);
        resourceId3 = typedArrayObtainStyledAttributes.getResourceId(R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeinAnimator, 0);
        this.mKeyPreviewChoreographer = new KeyPreviewDrawParams(typedArrayObtainStyledAttributes);
        this.mKeyPreviewController = new KeyPreviewChoreographer(this.mKeyPreviewChoreographer);
        resourceId4 = typedArrayObtainStyledAttributes.getResourceId(R.styleable.MainKeyboardView_moreKeysKeyboardLayout, 0);
        resourceId5 = typedArrayObtainStyledAttributes.getResourceId(R.styleable.MainKeyboardView_moreKeysKeyboardForActionLayout, resourceId4);
        this.mConfigShowMoreKeysKeyboardAtTouchedPoint = typedArrayObtainStyledAttributes.getBoolean(R.styleable.MainKeyboardView_showMoreKeysKeyboardAtTouchedPoint, false);
        this.mGestureTrailsDrawingPreview = new GestureTrailsDrawingPreview(typedArrayObtainStyledAttributes);
        this.mGestureTrailsDrawingPreview.setDrawingView(this.mDrawingPreviewPlacerView);
        this.mGestureFloatingPreviewText = new SlidingKeyInputDrawingPreview(typedArrayObtainStyledAttributes);
        this.mGestureFloatingPreviewText.setDrawingView(this.mDrawingPreviewPlacerView);
        } finally {
            typedArrayObtainStyledAttributes.recycle();
        }
        this.mSettings = SettingsManager.getInstance();
        LayoutInflater layoutInflaterFrom = LayoutInflater.from(getContext());
        this.mMoreKeysKeyboardContainer = layoutInflaterFrom.inflate(resourceId4, (ViewGroup) null);
        this.mMoreKeysKeyboardForActionContainer = layoutInflaterFrom.inflate(resourceId5, (ViewGroup) null);
        this.mAltCodeKeyWhileTypingFadeoutAnimator = loadObjectAnimator(resourceId2, this);
        this.mAltCodeKeyWhileTypingFadeinAnimator = loadObjectAnimator(resourceId3, this);
        this.mKeyboardActionListener = KeyboardActionListenerInterface.EMPTY;
        this.mLanguageOnSpacebarHorizontalMargin = (int) getResources().getDimension(R.dimen.config_language_on_spacebar_horizontal_margin);
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void setHardwareAcceleratedDrawingEnabled(boolean enabled) {
        super.setHardwareAcceleratedDrawingEnabled(enabled);
        this.mDrawingPreviewPlacerView.setHardwareAcceleratedDrawingEnabled(enabled);
    }

    /**
     * @param resId animator resource id, 0 for none
     * @param target the animation target
     */
    private ObjectAnimator loadObjectAnimator(int resId, Object target) {
        if (resId == 0) {
            return null;
        }
        ObjectAnimator objectAnimator = (ObjectAnimator) AnimatorInflater.loadAnimator(getContext(), resId);
        if (objectAnimator != null) {
            objectAnimator.setTarget(target);
        }
        return objectAnimator;
    }

    private static void cancelAndStartAnimators(ObjectAnimator objectAnimator, ObjectAnimator animatorToStart) {
        if (objectAnimator == null || animatorToStart == null) {
            return;
        }
        float animatedFraction = 0.0f;
        if (objectAnimator.isStarted()) {
            objectAnimator.cancel();
            animatedFraction = 1.0f - objectAnimator.getAnimatedFraction();
        }
        animatorToStart.start();
        animatorToStart.setCurrentPlayTime((long) (animatorToStart.getDuration() * animatedFraction));
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerHandler
    public void startWhileTypingFadeinAnimation() {
        if (skipAltCodeWhileTypingAnimation()) {
            return;
        }
        cancelAndStartAnimators(this.mAltCodeKeyWhileTypingFadeoutAnimator, this.mAltCodeKeyWhileTypingFadeinAnimator);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerHandler
    public void startWhileTypingFadeoutAnimation() {
        if (skipAltCodeWhileTypingAnimation()) {
            return;
        }
        cancelAndStartAnimators(this.mAltCodeKeyWhileTypingFadeinAnimator, this.mAltCodeKeyWhileTypingFadeoutAnimator);
    }

    /**
     * Only PC-QWERTY has alt-code-while-typing keys; elsewhere the fades would tick every frame for
     * nothing. Skipped there with the alpha left at rest so a later PC-QWERTY board starts full.
     */
    private boolean skipAltCodeWhileTypingAnimation() {
        Keyboard keyboard = getKeyboard();
        if (!this.mEinkDisplay && keyboard != null && !keyboard.mAltCodeKeysWhileTyping.isEmpty()) {
            return false;
        }
        if (this.mAltCodeKeyWhileTypingFadeinAnimator != null) {
            this.mAltCodeKeyWhileTypingFadeinAnimator.cancel();
        }
        if (this.mAltCodeKeyWhileTypingFadeoutAnimator != null) {
            this.mAltCodeKeyWhileTypingFadeoutAnimator.cancel();
        }
        this.mAltCodeKeyWhileTypingAnimAlpha = 255;
        return true;
    }

    /**
     * Animation target for {@code res/anim/alt_code_key_while_typing_fade{in,out}.xml}. Not unused:
     * ObjectAnimator finds it by reflection; R8 keeps it via the View {@code set*} rule in proguard-rules.pro.
     */
    public void setAltCodeKeyWhileTypingAnimAlpha(int alpha) {
        if (this.mAltCodeKeyWhileTypingAnimAlpha == alpha) {
            return;
        }
        this.mAltCodeKeyWhileTypingAnimAlpha = alpha;
        Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }
        // Only the alt-code keys change appearance, so repaint those rather than the whole view.
        for (Key key : keyboard.mAltCodeKeysWhileTyping) {
            invalidateKey(key);
        }
    }

    /** @param listener the key action listener */
    public void setKeyboardActionListener(KeyboardActionListenerInterface listener) {
        this.mKeyboardActionListener = listener;
        PointerTracker.setKeyboardActionListener(listener);
        GestureEventProcessor.setKeyboardActionListener(listener);
    }

    /** @param multiPointerGestureDetector the multi-pointer gesture detector */
    public void setGestureDetector(MultiPointerGestureDetector multiPointerGestureDetector) {
        this.mGestureDetector = multiPointerGestureDetector;
    }

    /** @param x touch x coordinate */
    public int getKeyX(int x) {
        return Constants.isValidCoordinate(x) ? this.mKeyDetector.getTouchX(x) : x;
    }

    /** @param y touch y coordinate */
    public int getKeyY(int y) {
        return Constants.isValidCoordinate(y) ? this.mKeyDetector.getTouchY(y) : y;
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void setKeyboard(Keyboard keyboard) {
        this.mKeyTimerHandler.cancelAllLongPressTimers();
        super.setKeyboard(keyboard);
        this.mKeyDetector.setKeyboard(keyboard, -getPaddingLeft(), (-getPaddingTop()) + getVerticalCorrection());
        PointerTracker.setKeyDetector(this.mKeyDetector);
        if (keyboard != null) {
            this.mGestureInputAvailability.setValidKeyboardLayout(keyboard.mId.passwordInput());
        }
        // Popups are cached per parent keyboard (MoreKeysKeyboardCache), so a swap keeps them.
        updateEinkDisplay();
        releaseCurrentlyPressedKeys();
        this.mSpaceKey = keyboard.getKeyByCode(32);
        float f = keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap;
        this.mAutoCorrectionSpacebarTextSize = f * this.mAutoCorrectionSpacebarTextRatio;
        this.mShiftKey = keyboard.getKeyByCode(-1);
        if (this.mShiftKey != null) {
            this.mShiftKeyIcons = new Drawable[10];
            for (int i = 0; i < 10; i++) {
                this.mShiftKeyIcons[i] = this.mShiftKey.getBackgroundIcon(keyboard.mIconsSet, 255, i);
            }
        }
        this.mAltKey = keyboard.getKeyByCode(-16);
        if (this.mAltKey != null) {
            this.mAltKeyIcons = new Drawable[10];
            for (int i2 = 0; i2 < 10; i2++) {
                this.mAltKeyIcons[i2] = this.mAltKey.getBackgroundIcon(keyboard.mIconsSet, 255, i2);
            }
        }
    }

    /**
     * @param previewEnabled true to show key previews
     * @param lingerTimeout preview linger timeout in ms
     */
    public void setKeyPreviewPopupEnabled(boolean previewEnabled, int lingerTimeout) {
        this.mKeyPreviewChoreographer.setPopupEnabled(previewEnabled, lingerTimeout);
    }

    /**
     * @param hasCustomAnimationParams true to use these custom params
     * @param showUpStartXScale show-up start scale x
     * @param showUpStartYScale show-up start scale y
     * @param showUpDuration show-up duration in ms
     * @param dismissEndXScale dismiss end scale x
     * @param dismissEndYScale dismiss end scale y
     * @param dismissDuration dismiss duration in ms
     */
    public void setKeyPreviewAnimationParams(boolean hasCustomAnimationParams, float showUpStartXScale, float showUpStartYScale, int showUpDuration, float dismissEndXScale, float dismissEndYScale, int dismissDuration) {
        this.mKeyPreviewChoreographer.setAnimationParams(hasCustomAnimationParams, showUpStartXScale, showUpStartYScale, showUpDuration, dismissEndXScale, dismissEndYScale, dismissDuration);
    }

    private void locatePreviewPlacerView() {
        getLocationInWindow(this.mOriginCoords);
        this.mDrawingPreviewPlacerView.setKeyboardViewGeometry(this.mOriginCoords, getWidth(), getHeight());
    }

    private void installPreviewPlacerView() {
        View rootView = getRootView();
        if (rootView == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Cannot find root view");
            return;
        }
        ViewGroup viewGroup = (ViewGroup) rootView.findViewById(android.R.id.content);
        if (viewGroup == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView");
        } else {
            sMainHandler.removeCallbacks(this.mRemovePlacerIfDetached);
            if (this.mDrawingPreviewPlacerView.getParent() == viewGroup) {
                return;
            }
            removePlacerFromParent();
            viewGroup.addView(this.mDrawingPreviewPlacerView);
        }
    }

    private void removePlacerFromParent() {
        android.view.ViewParent parent = this.mDrawingPreviewPlacerView.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(this.mDrawingPreviewPlacerView);
        }
    }

    private void updateEinkDisplay() {
        boolean eink = DeviceProfile.current().isEinkDisplay();
        if (eink == this.mEinkDisplay) {
            return;
        }
        this.mEinkDisplay = eink;
        setVkbGesturePreviewMode(this.mVkbGesturePreviewRequested);
        invalidateAllKeys();
    }

    @Override // dev.bbkb.ime.keyboard.internal.LongPressHandler
    public void dismissAllKeyPreviews() {
        this.mKeyPreviewController.dismissAllKeyPreviews();
        PointerTracker.setReleasedKeyGraphicsToAllKeys();
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void showKeyPreview(Key key) {
        Keyboard keyboard;
        if (key == null || key.isNoKeyPreview() || (keyboard = getKeyboard()) == null) {
            return;
        }
        KeyPreviewDrawParams c1063v = this.mKeyPreviewChoreographer;
        if (!c1063v.isPopupEnabled()) {
            c1063v.setVisibleOffset(-keyboard.mVerticalGap);
            return;
        }
        locatePreviewPlacerView();
        getLocationInWindow(this.mOriginCoords);
        // No show/dismiss animation on e-ink: each animation frame would be a panel refresh.
        this.mKeyPreviewController.placeAndShowKeyPreview(key, keyboard.mIconsSet, this.textFormatter, getWidth(), this.mOriginCoords, this.mDrawingPreviewPlacerView, isHardwareAccelerated() && !this.mEinkDisplay);
    }

    @Override // dev.bbkb.ime.keyboard.internal.LongPressHandler
    public void dismissKeyPreviewWithoutDelay(Key key) {
        this.mKeyPreviewController.dismissKeyPreview(key, false);
        invalidateKeyState(key);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void dismissKeyPreview(Key key) {
        if (!isHardwareAccelerated()) {
            this.mKeyPreviewDismissHandler.postKeyPreviewDismiss(this.mKeyPreviewChoreographer.getLingerTimeout(), key);
        } else {
            this.mKeyPreviewController.dismissKeyPreview(key, true);
        }
    }

    /** @param enabled true to show the sliding key input preview */
    public void setSlidingKeyInputPreviewEnabled(boolean enabled) {
        this.mGestureFloatingPreviewText.setPreviewEnabled(enabled);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void invalidateKeyGraphics(Key key) {
        // PointerTracker's press/release: only the key's pressed look changed.
        if (this.mEinkDisplay) {
            return;
        }
        invalidateKeyState(key);
    }
    
    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void showGestureTrail(PointerTracker pointerTracker) {
        locatePreviewPlacerView();
        this.mGestureFloatingPreviewText.showGestureTrail(pointerTracker);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void dismissGestureTrail() {
        this.mGestureFloatingPreviewText.dismissGestureTrail();
    }

    /** @param gesturePreviewRequested true if the gesture trail is requested */
    private void setVkbGesturePreviewMode(boolean gesturePreviewRequested) {
        this.mVkbGesturePreviewRequested = gesturePreviewRequested;
        // No gesture trail on e-ink: it would refresh the panel every frame of a swipe.
        boolean enabled = gesturePreviewRequested && !this.mEinkDisplay;
        this.mGestureTrailsDrawingPreview.setPreviewEnabled(enabled);
        if (!enabled) {
            this.mGestureTrailsDrawingPreview.releaseOffscreenBuffer();
        }
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void showSlidingKeyInputPreview(PointerTracker pointerTracker) {
        locatePreviewPlacerView();
        this.mGestureTrailsDrawingPreview.setGestureStroke(pointerTracker);
    }

    /** @param enabledByUser true if on-screen swipe typing is on */
    public void setVkbGestureHandlingEnabledByUser(boolean enabledByUser) {
        this.mGestureInputAvailability.setVkbGestureEnabledByUser(enabledByUser);
        setVkbGesturePreviewMode(enabledByUser);
    }

    /** @param enabledByUser true if capacitive gestures are on */
    public void setCkbGestureHandlingEnabledByUser(boolean enabledByUser) {
        this.mGestureInputAvailability.setCkbGestureEnabledByUser(enabledByUser);
    }

    /** @param mainDictionaryAvailable true if the main dictionary is available */
    public void setMainDictionaryAvailability(boolean mainDictionaryAvailable) {
        this.mGestureInputAvailability.setMainDictionaryAvailable(mainDictionaryAvailable);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public boolean isGestureInputEnabled() {
        return this.mGestureInputAvailability.isVkbGestureInputEnabled();
    }

    @Override // dev.bbkb.ime.keyboard.KeyDetector
    public boolean isGestureKeyboard() {
        return this.mGestureInputAvailability.isCkbGestureInputEnabled();
    }

    @Override // android.view.View
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Re-install the static collaborators dropped by onDetachedFromWindow's
        // PointerTracker.release(); the constructor's init() only runs once per view.
        PointerTracker.setViewProxies(this.mKeyTimerHandler, this.mBatchUpdateTimerHandler, this, this, this);
        PointerTracker.setKeyboardActionListener(this.mKeyboardActionListener);
        GestureEventProcessor.setGestureKeyboardChecker(this);
        installPreviewPlacerView();
        updateEinkDisplay();
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDetachedFromWindow() {
        // Before super: a pending long-press would otherwise show a more-keys panel on a detached
        // view, leaving mMoreKeysPanel non-null and undismissable.
        cancelAllOngoingEvents();
        super.onDetachedFromWindow();
        this.mDrawingPreviewPlacerView.removeAllViews();
        // The placer lives in the window's content view; left there, each input view recreation
        // would stack another full-window placer. Removed one message later because the window's
        // detach may be iterating the content view's children; its own detach frees the previews.
        sMainHandler.post(this.mRemovePlacerIfDetached);
        // PointerTracker holds this view in static fields; drop them so a detached input view
        // (and its themed Context) is not retained for the process lifetime.
        PointerTracker.release();
        GestureEventProcessor.releaseGestureKeyboardChecker(this);
    }

    /**
     * The keyboard the long-pressed key belongs to. Input boards (the number pad) route through this
     * view but are not getKeyboard(), which on the PKB is a zero-height element whose geometry would
     * make the popup's ProximityGrid divide by zero.
     */
    private Keyboard moreKeysSourceKeyboardFor(Key key) {
        Keyboard main = getKeyboard();
        if (main != null && main.hasKey(key)) {
            return main;
        }
        dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadView numberPad =
                KeyboardSwitcher.getInstance().peekNumberPadView();
        if (numberPad != null && numberPad.getKeyboard() != null && numberPad.getKeyboard().hasKey(key)) {
            return numberPad.getKeyboard();
        }
        return main;
    }

    private MoreKeysPanel onCreateMoreKeysKeyboard(Key key, Context context) {
        MoreKeySpec[] c1034aqArrM6245i = key.getMoreKeys();
        if (c1034aqArrM6245i == null) {
            return null;
        }
        Keyboard parent = moreKeysSourceKeyboardFor(key);
        KeyPreviewDrawParams previewParams = this.mKeyPreviewChoreographer;
        // The popup's size depends on the key preview's geometry, so a change there drops them.
        int previewGeometry = java.util.Arrays.hashCode(new int[] {
                previewParams.isPopupEnabled() ? 1 : 0, previewParams.getVisibleWidth(), previewParams.getVisibleHeight()});
        Keyboard c0965eMo5338b = this.mMoreKeysKeyboardCache.get(parent, key, previewGeometry);
        if (c0965eMo5338b == null) {
            c0965eMo5338b = new MoreKeysKeyboard.Builder(context, key, parent, previewParams.isPopupEnabled() && !key.isNoKeyPreview() && c1034aqArrM6245i.length == 1 && previewParams.getVisibleWidth() > 0, previewParams.getVisibleWidth(), previewParams.getVisibleHeight(), createPaintForKey(key)).build();
            this.mMoreKeysKeyboardCache.put(parent, key, previewGeometry, c0965eMo5338b);
        }
        View view = key.isFunctionalKey() ? this.mMoreKeysKeyboardForActionContainer : this.mMoreKeysKeyboardContainer;
        MoreKeysKeyboardView moreKeysKeyboardView = (MoreKeysKeyboardView) view.findViewById(R.id.more_keys_keyboard_view);
        moreKeysKeyboardView.setKeyboard(c0965eMo5338b);
        view.measure(-2, -2);
        return moreKeysKeyboardView;
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerHandler
    public void onLongPress(PointerTracker pointerTracker) {
        Key keyM7652o;
        if (isShowingMoreKeysPanel() || (keyM7652o = pointerTracker.getKey()) == null) {
            return;
        }
        KeyboardActionListenerInterface interfaceC0976f = this.mKeyboardActionListener;
        if (keyM7652o.hasBackgroundTypeAction()) {
            int iB = keyM7652o.getMoreKeys()[0].getCode();
            pointerTracker.cancelTrackingAndReleaseKey();
            interfaceC0976f.onPressKey(iB, 0, true);
            interfaceC0976f.onCodeInput(iB, -1, -1, pointerTracker.getLongPressTimeoutTime(keyM7652o.getCode()), false);
            interfaceC0976f.onReleaseKey(iB, false);
            return;
        }
        int iM6232c = keyM7652o.getCode();
        if ((iM6232c == -10 || iM6232c == 32) && interfaceC0976f.onCustomRequest(1)) {
            pointerTracker.cancelTrackingAndReleaseKey();
            interfaceC0976f.onReleaseKey(iM6232c, false);
        } else {
            if (keyM7652o.hasLongPressKey()) {
                if (keyM7652o.getLongPressCode() != -34) {
                    pointerTracker.cancelTrackingAndReleaseKey();
                }
                int iM6235d = keyM7652o.getLongPressCode();
                interfaceC0976f.onPressKey(iM6235d, 0, true);
                interfaceC0976f.onCodeInput(iM6235d, -1, -1, pointerTracker.getLongPressTimeoutTime(keyM7652o.getCode()), false);
                interfaceC0976f.onReleaseKey(iM6235d, false);
                return;
            }
            openMoreKeysPanel(keyM7652o, pointerTracker);
        }
    }

    /** @param pointerTracker the pointer that long-pressed */
    private void openMoreKeysPanel(Key key, PointerTracker pointerTracker) {
        int iMo6216ab;
        MoreKeysPanel interfaceC1071mM6289a = onCreateMoreKeysKeyboard(key, getContext());
        if (interfaceC1071mM6289a == null) {
            return;
        }
        int[] iArrM5687a = CoordinateUtils.newCoordinateArray();
        pointerTracker.getLastCoordinates(iArrM5687a);
        boolean z = this.mKeyPreviewChoreographer.isPopupEnabled() && !key.isNoKeyPreview();
        if (this.mConfigShowMoreKeysKeyboardAtTouchedPoint && !z) {
            iMo6216ab = CoordinateUtils.x(iArrM5687a);
        } else {
            iMo6216ab = key.getX() + (key.getWidth() / 2);
        }
        interfaceC1071mM6289a.showMoreKeysPanel(this, this, iMo6216ab, key.getY() + this.mKeyPreviewChoreographer.getVisibleOffset(), this.mKeyboardActionListener);
        pointerTracker.startMoreKeysPanel(interfaceC1071mM6289a);
        dismissKeyPreviewWithoutDelay(key);
    }

    public boolean isInDraggingFinger() {
        if (isShowingMoreKeysPanel()) {
            return true;
        }
        return PointerTracker.isAnyInSlidingKeyInput();
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onShowMoreKeysPanel(MoreKeysPanel moreKeysPanel) {
        locatePreviewPlacerView();
        moreKeysPanel.showInParent(this.mDrawingPreviewPlacerView);
        this.mMoreKeysPanel = moreKeysPanel;
        dimEntireKeyboard(true);
    }

    public boolean isShowingMoreKeysPanel() {
        MoreKeysPanel interfaceC1071m = this.mMoreKeysPanel;
        return interfaceC1071m != null && interfaceC1071m.isShowingInParent();
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onDismissMoreKeysPanel() {
        PointerTracker.dismissAllMoreKeysPanels();
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onCancelMoreKeysPanel() {
        dimEntireKeyboard(false);
        if (isShowingMoreKeysPanel()) {
            this.mMoreKeysPanel.removeFromParent();
            this.mMoreKeysPanel = null;
        }
    }

    public void startDoubleTapShiftKeyTimer() {
        this.mKeyTimerHandler.startDoubleTapShiftKeyTimer();
    }

    public void cancelDoubleTapShiftKeyTimer() {
        this.mKeyTimerHandler.cancelDoubleTapShiftKeyTimer();
    }

    public void cancelLongPressShiftKeyTimer() {
        this.mKeyTimerHandler.cancelLongPressShiftKeyTimer();
    }

    public boolean isInDoubleTapShiftKeyTimeout() {
        return this.mKeyTimerHandler.isInDoubleTapShiftKeyTimeout();
    }

    @Override // android.view.View
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        if (getKeyboard() == null) {
            return false;
        }
        return processMotionEvent(motionEvent);
    }

    public void cancelAllGestureTracking() {
        GestureEventProcessor.cancelAllTracking();
    }

    public void onPhysicalKeyDown(KeyEvent keyEvent) {
        GestureEventProcessor.onKeyDown(keyEvent);
    }

    public void onPhysicalKeyUp(KeyEvent keyEvent) {
        GestureEventProcessor.onKeyUp(keyEvent);
    }

    /** @param metaKeyActive true if gesture handling is active */
    public void updateGestureHandlingState(boolean metaKeyActive) {
        GestureEventProcessor.updateGestureHandlingState(metaKeyActive);
    }

    public boolean isGestureHandlingActive() {
        return GestureEventProcessor.isGestureHandlingActive();
    }

    /** @param active true to mark gesture handling active */
    public void setGestureInputStartsBeforeRunningShift(boolean active) {
        GestureEventProcessor.setGestureHandlingActive(active);
    }

    /** @param slideboardManager the slide board manager */
    public void setSlideBoardViewManager(SlideboardManager slideboardManager) {
        this.mSlideboardManager = slideboardManager;
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void setSlideTranslation(float translationX, boolean animate) {
        SlideboardManager c1083e = this.mSlideboardManager;
        if (c1083e != null) {
            c1083e.setTranslation(translationX, animate);
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "no input board bar");
        }
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void onSlidingFinished() {
        MultiPointerGestureDetector c0924a = this.mGestureDetector;
        if (c0924a != null) {
            c0924a.cancel();
        }
        SlideboardManager c1083e = this.mSlideboardManager;
        if (c1083e != null) {
            c1083e.reset();
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "no input board bar");
        }
    }

    public boolean processMotionEvent(MotionEvent motionEvent) {
        MultiPointerGestureDetector c0924a;
        int pointerId = motionEvent.getPointerId(motionEvent.getActionIndex());
        PointerTracker c1084tM7596a = PointerTracker.getPointerTracker(pointerId);
        if ((!this.mGestureInputAvailability.isVkbGestureInputEnabled() || this.mSettings.getSettingsValues().isVkbSwipeGesturesEnabled) && !isShowingMoreKeysPanel() && !c1084tM7596a.isSlideboardDragging() && (c0924a = this.mGestureDetector) != null && c0924a.onTouchEvent(motionEvent)) {
            dev.bbkb.ime.keyboard.internal.EngineTouch.cancel("MainKeyboardView.multiPointer", pointerId);
            c1084tM7596a.cancelTracking();
            return true;
        }
        c1084tM7596a.processMotionEvent(motionEvent, this.mKeyDetector);
        return true;
    }

    public void cancelAllOngoingEvents() {
        this.mKeyTimerHandler.cancelAllTimers();
        this.mBatchUpdateTimerHandler.cancelAllUpdateBatchInputTimers();
        this.mKeyPreviewDismissHandler.cancelDismiss();
        dismissAllKeyPreviews();
        dismissGestureTrail();
        PointerTracker.dismissAllMoreKeysPanels();
        PointerTracker.cancelAllPointerTrackers();
        releaseCurrentlyPressedKeys();
    }

    public void closing() {
        cancelAllOngoingEvents();
        // The popup cache survives (popups belong to parent keyboards, not the field); the trail
        // buffer (2.7 MB portrait) need not outlive the keyboard.
        this.mGestureTrailsDrawingPreview.releaseOffscreenBuffer();
    }

    public void onHideWindow() {
        onCancelMoreKeysPanel();
        this.mGestureTrailsDrawingPreview.releaseOffscreenBuffer();
    }

    /**
     * KeyboardState calls both setters at the end of every shift update, changed or not.
     * @param isShifted true if shifted
     */
    public void setShiftKeyState(boolean isShifted) {
        if (this.mShiftKeyState == isShifted) {
            return;
        }
        this.mShiftKeyState = isShifted;
        invalidateKey(this.mShiftKey);
    }

    public void setShiftLocked(boolean locked) {
        if (this.mShiftKeyLocked == locked) {
            return;
        }
        this.mShiftKeyLocked = locked;
        invalidateKey(this.mShiftKey);
    }

    /**
     * On-screen Ctrl is armed (the long-press of ?123 fired, or Ctrl is latched for the next key):
     * the ?123 key reads {@link #CTRL_LABEL} until it is not. See ControlModeController.
     */
    public void setCtrlLabelActive(boolean active) {
        if (this.mCtrlLabelActive == active) {
            return;
        }
        this.mCtrlLabelActive = active;
        Keyboard keyboard = getKeyboard();
        Key key = keyboard == null ? null : keyboard.getKeyByCode(TO_SYMBOL_KEY_CODE);
        if (key != null) {
            invalidateKey(key);
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected String labelFor(Key key) {
        if (this.mCtrlLabelActive && key.getCode() == TO_SYMBOL_KEY_CODE) {
            return CTRL_LABEL;
        }
        return super.labelFor(key);
    }

    /** @param enabled true to enable the code -7 key */
    public void setFunctionalKeyEnabled(boolean enabled) {
        Key keyM6604b;
        Keyboard keyboard = getKeyboard();
        if (keyboard == null || (keyM6604b = keyboard.getKeyByCode(-7)) == null) {
            return;
        }
        // Called on every keyboard set (KeyboardSwitcher.setKeyboard); usually unchanged.
        if (keyM6604b.isActive() == enabled) {
            return;
        }
        keyM6604b.setActive(enabled);
        invalidateKey(keyM6604b);
    }

    /**
     * Strips the "%B" bold marker once per prediction change, not on every spacebar repaint.
     * @param text the prediction text, may hold %B markers
     */
    public void setAutoCorrectionText(String text) {
        String stripped = text == null ? "" : PERCENT_B_MARKER.matcher(text).replaceAll("");
        if (this.mAutoCorrectionText.equals(stripped)) {
            return;
        }
        this.mAutoCorrectionText = stripped;
        // Per-tap content: only the spacebar's layer is re-recorded (KeyboardView.invalidateKeyState).
        invalidateKeyState(this.mSpaceKey);
    }

    /** @param dim true to dim */
    private void dimEntireKeyboard(boolean dim) {
        boolean z2 = this.mNeedsToDimEntireKeyboard != dim;
        this.mNeedsToDimEntireKeyboard = dim;
        if (z2) {
            invalidateAllKeys();
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (this.mNeedsToDimEntireKeyboard) {
            canvas.drawRect(0.0f, 0.0f, getWidth(), getHeight(), this.mBackgroundDimPaint);
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected void requestKeyLayerFrame() {
        locatePreviewPlacerView();
        if (!this.mDrawingPreviewPlacerView.requestFrameWithoutDamage()) {
            super.requestKeyLayerFrame();
        }
    }

    /** Pressed keys and the spacebar's word go in per-key layers (KeyboardView.keyLayers). */
    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected boolean usesKeyLayers() {
        return true;
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected boolean hasPerTapContent(Key key) {
        if (key == this.mSpaceKey && this.mAutoCorrectionText.length() > 0) {
            return true;
        }
        return super.hasPerTapContent(key);
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected boolean suppressesPressedState() {
        return this.mEinkDisplay;
    }

    /** The main board (alphabet + symbol pages) carries the style's fret bars. */
    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected boolean drawsFrets() {
        return true;
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected void drawKeyContent(Key key, Canvas canvas, Paint paint, KeyDrawParams formatter, boolean suppressLabel) {
        boolean autoCorrectionDrawn;
        if (key.isAltCodeWhileTyping() && key.isActive()) {
            formatter.animAlpha = this.mAltCodeKeyWhileTypingAnimAlpha;
        }
        int iM6232c = key.getCode();
        boolean spacebarAutoCorrectionDrawn = false;
        if (iM6232c == 32) {
            if (this.mAutoCorrectionText.length() > 0 && !isDrawingRestingKeys()) {
                spacebarAutoCorrectionDrawn = true;
                drawAutoCorrectionOnSpacebar(key, canvas, paint);
            }
            autoCorrectionDrawn = spacebarAutoCorrectionDrawn;
        } else {
            if (iM6232c == -10) {
                drawPopupHintLetter(key, canvas, paint, formatter);
            } else if (iM6232c == -1) {
                drawShiftKeyIcon(key, canvas);
            } else if (iM6232c == -16) {
                drawAltCodeKeyIcon(key, canvas);
            }
            autoCorrectionDrawn = false;
        }
        // The autocorrect word has already been painted over the spacebar, so the base class
        // must not paint the key's own label (or the spacebar icon) on top of it.
        super.drawKeyContent(key, canvas, paint, formatter, autoCorrectionDrawn);
        // Shift indicator bar from mShiftKeyState: on the VKB backgroundType does not change with shift.
        if (iM6232c == -1 && KeyboardColorManager.INSTANCE.isInitialized()) {
            float density = getDensity();
            Paint indicatorPaint = this.mShiftIndicatorPaint;
            if (this.mShiftKeyLocked) {
                indicatorPaint.setColor(KeyboardColorManager.INSTANCE.getTextColor());
            } else {
                indicatorPaint.setColor(KeyboardColorManager.INSTANCE.getKeyColor());
            }
            StyleSpec spec = KeyboardColorManager.styleSpec();
            boolean insetCaps = spec.getKeyCap() != KeyCap.FLAT;
            float indicatorHeight = (insetCaps ? 3 : 2) * density;
            float indicatorWidth = 18 * density;
            int keyWidth = key.getWidth();
            // The bar sits keyInsetVDp + 4dp below the key top, inside the cap (above the arrow icon).
            float indicatorY = (spec.getKeyInsetVDp() + 4) * density;
            float indicatorX = (keyWidth - indicatorWidth) / 2.0f;
            if (insetCaps) {
                float cornerRadius = indicatorHeight / 2.0f;
                canvas.drawRoundRect(indicatorX, indicatorY, indicatorX + indicatorWidth,
                        indicatorY + indicatorHeight, cornerRadius, cornerRadius, indicatorPaint);
            } else {
                canvas.drawRect(indicatorX, indicatorY, indicatorX + indicatorWidth,
                        indicatorY + indicatorHeight, indicatorPaint);
            }
        }
    }

    /** Shift indicator paint, created once rather than per repaint. */
    static Paint newShiftIndicatorPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    /** @param width the spacebar width */
    private String getAutoCorrectionText(Paint paint, int width) {
        return SpacebarTextUtils.truncateTextToWidth(width - (this.mLanguageOnSpacebarHorizontalMargin * 2), this.mAutoCorrectionText, this.mAutoCorrectionSpacebarTextSize, this.mMinimumScaleOfSpacebarText, paint);
    }

    private void drawAutoCorrectionOnSpacebar(Key key, Canvas canvas, Paint paint) {
        int iM6204Z = key.getWidth();
        int iM6215aa = key.getHeight();
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(this.mAutoCorrectionSpacebarTextSize);
        String strM6290a = getAutoCorrectionText(paint, iM6204Z);
        float fDescent = paint.descent();
        float f = (iM6215aa / 2) + (((-paint.ascent()) + fDescent) / 2.0f);
        // Accent word read at draw when the spec asks; else the static autoCorrectionOnSpacebarTextColor.
        paint.setColor(KeyboardColorManager.styleSpec().getAccentSpacebarPrediction()
                ? KeyboardColorManager.INSTANCE.getAccentColor()
                : this.mAutoCorrectionSpacebarTextColor);
        canvas.drawText(strM6290a, iM6204Z / 2, f - fDescent, paint);
        paint.setTextSize(this.mAutoCorrectionSpacebarTextSize);
    }

    private void drawShiftKeyIcon(Key key, Canvas canvas) {
        Drawable drawable = this.mShiftKeyState ? this.mShiftKeyIcons[3] : this.mShiftKeyIcons[1];
        if (drawable != null) {
            // Tint here: this overlay draws before the base class re-tints the shared drawable.
            if (KeyboardColorManager.INSTANCE.isInitialized()) {
                applyIconTint(key, drawable);
            }
            drawDrawable(canvas, drawable, (key.getWidth() - drawable.getIntrinsicWidth()) / 2, (key.getHeight() - drawable.getIntrinsicHeight()) / 2, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
    }

    private void drawAltCodeKeyIcon(Key key, Canvas canvas) {
        Drawable drawable = this.mAltKeyIcons[4];
        if (drawable != null) {
            if (KeyboardColorManager.INSTANCE.isInitialized()) {
                applyIconTint(key, drawable);
            }
            drawDrawable(canvas, drawable, (key.getWidth() - drawable.getIntrinsicWidth()) / 2, (key.getHeight() / 3) - (drawable.getIntrinsicHeight() / 2), drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    public void cleanup() {
        super.cleanup();
        this.mDrawingPreviewPlacerView.removeAllDrawingPreviews();
    }

    public void deallocateMemory() {
        cancelAllOngoingEvents();
        cleanup();
        setKeyboardActionListener(KeyboardActionListenerInterface.EMPTY);
        this.mGestureDetector = null;
        PointerTracker.release();
        GestureEventProcessor.releaseGestureKeyboardChecker(this);
    }

    /** @param pressed true if pressed */
    public void setKeyPressed(Key key, boolean pressed) {
        synchronized (this.mPressedKeys) {
            if (pressed) {
                key.onPressed();
                this.mPressedKeys.add(key);
            } else {
                key.onReleased();
                this.mPressedKeys.remove(key);
            }
            if (!this.mEinkDisplay) {
                invalidateKeyState(key);
            }
        }
    }

    public void releaseCurrentlyPressedKeys() {
        synchronized (this.mPressedKeys) {
            Iterator<Key> it = this.mPressedKeys.iterator();
            while (it.hasNext()) {
                Key next = it.next();
                if (!PointerTracker.isAnyPointerOnKey(next)) {
                    next.onReleased();
                    // Its layer would otherwise keep showing it pressed until the next redraw.
                    invalidateKeyState(next);
                }
            }
            this.mPressedKeys.clear();
        }
    }
}
