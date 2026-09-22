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
import dev.bbkb.ime.keyboard.internal.MoreKeysKeyboardView;
import dev.bbkb.ime.keyboard.internal.PointerTracker;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.Constants;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
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
import java.util.WeakHashMap;
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

    private final WeakHashMap<Key, Keyboard> mMoreKeysKeyboardCache;

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

    public SettingsManager getmSettings() {
        return this.mSettings;
    }

    public MainKeyboardView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    public MainKeyboardView(Context context, AttributeSet attributeSet, int i) throws Resources.NotFoundException {
        super(context, attributeSet, i);
        this.mAutoCorrectionText = "";
        this.mAltCodeKeyWhileTypingAnimAlpha = 255;
        this.mOriginCoords = CoordinateUtils.newCoordinateArray();
        this.mBackgroundDimPaint = new Paint();
        this.mMoreKeysKeyboardCache = new WeakHashMap<>();
        this.mKeyPreviewDismissHandler = new LongPressHandler(this);
        this.mGestureInputAvailability = new GestureInputAvailability();
        this.mPressedKeys = new HashSet<>();
        this.mDrawingPreviewPlacerView = new DrawingPreviewPlacerView(context, attributeSet);
        final int resourceId2;
        final int resourceId3;
        final int resourceId4;
        final int resourceId5;
        TypedArray typedArrayObtainStyledAttributes = context.obtainStyledAttributes(attributeSet, R.styleable.MainKeyboardView, i, R.style.MainKeyboardView);
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

    private ObjectAnimator loadObjectAnimator(int i, Object obj) {
        if (i == 0) {
            return null;
        }
        ObjectAnimator objectAnimator = (ObjectAnimator) AnimatorInflater.loadAnimator(getContext(), i);
        if (objectAnimator != null) {
            objectAnimator.setTarget(obj);
        }
        return objectAnimator;
    }

    private static void cancelAndStartAnimators(ObjectAnimator objectAnimator, ObjectAnimator objectAnimator2) {
        if (objectAnimator == null || objectAnimator2 == null) {
            return;
        }
        float animatedFraction = 0.0f;
        if (objectAnimator.isStarted()) {
            objectAnimator.cancel();
            animatedFraction = 1.0f - objectAnimator.getAnimatedFraction();
        }
        objectAnimator2.start();
        objectAnimator2.setCurrentPlayTime((long) (objectAnimator2.getDuration() * animatedFraction));
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerHandler
    public void startWhileTypingFadeinAnimation() {
        cancelAndStartAnimators(this.mAltCodeKeyWhileTypingFadeoutAnimator, this.mAltCodeKeyWhileTypingFadeinAnimator);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerHandler
    public void startWhileTypingFadeoutAnimation() {
        cancelAndStartAnimators(this.mAltCodeKeyWhileTypingFadeinAnimator, this.mAltCodeKeyWhileTypingFadeoutAnimator);
    }

    /**
     * Animation target for {@code res/anim/alt_code_key_while_typing_fade{in,out}.xml}.
     *
     * DO NOT DELETE AS UNUSED. ObjectAnimator resolves this by name through reflection, so it has
     * no static callers and looks dead to a call-graph sweep. R8 keeps it via the
     * `public void set*` rule on Views in proguard-rules.pro; only a source-level sweep can
     * break it again.
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

    public void setKeyboardActionListener(KeyboardActionListenerInterface interfaceC0976f) {
        this.mKeyboardActionListener = interfaceC0976f;
        PointerTracker.setKeyboardActionListener(interfaceC0976f);
        GestureEventProcessor.setKeyboardActionListener(interfaceC0976f);
    }

    public void setGestureDetector(MultiPointerGestureDetector c0924a) {
        this.mGestureDetector = c0924a;
    }

    public int getKeyX(int i) {
        return Constants.isValidCoordinate(i) ? this.mKeyDetector.getTouchX(i) : i;
    }

    public int getKeyY(int i) {
        return Constants.isValidCoordinate(i) ? this.mKeyDetector.getTouchY(i) : i;
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
        this.mMoreKeysKeyboardCache.clear();
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

    public void setKeyPreviewPopupEnabled(boolean z, int i) {
        this.mKeyPreviewChoreographer.setPopupEnabled(z, i);
    }

    public void setKeyPreviewAnimationParams(boolean z, float f, float f2, int i, float f3, float f4, int i2) {
        this.mKeyPreviewChoreographer.setAnimationParams(z, f, f2, i, f3, f4, i2);
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
            viewGroup.addView(this.mDrawingPreviewPlacerView);
        }
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
        this.mKeyPreviewController.placeAndShowKeyPreview(key, keyboard.mIconsSet, this.textFormatter, getWidth(), this.mOriginCoords, this.mDrawingPreviewPlacerView, isHardwareAccelerated());
    }

    @Override // dev.bbkb.ime.keyboard.internal.LongPressHandler
    public void dismissKeyPreviewWithoutDelay(Key key) {
        this.mKeyPreviewController.dismissKeyPreview(key, false);
        invalidateKey(key);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void dismissKeyPreview(Key key) {
        if (!isHardwareAccelerated()) {
            this.mKeyPreviewDismissHandler.postKeyPreviewDismiss(this.mKeyPreviewChoreographer.getLingerTimeout(), key);
        } else {
            this.mKeyPreviewController.dismissKeyPreview(key, true);
        }
    }

    public void setSlidingKeyInputPreviewEnabled(boolean z) {
        this.mGestureFloatingPreviewText.setPreviewEnabled(z);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void invalidateKeyGraphics(Key key) {
        invalidateKey(key);
    }
    
    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void showGestureTrail(PointerTracker c1084t) {
        locatePreviewPlacerView();
        this.mGestureFloatingPreviewText.showGestureTrail(c1084t);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void dismissGestureTrail() {
        this.mGestureFloatingPreviewText.dismissGestureTrail();
    }

    private void setVkbGesturePreviewMode(boolean z) {
        this.mGestureTrailsDrawingPreview.setPreviewEnabled(z);
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void showSlidingKeyInputPreview(PointerTracker c1084t) {
        locatePreviewPlacerView();
        this.mGestureTrailsDrawingPreview.setGestureStroke(c1084t);
    }

    public void setVkbGestureHandlingEnabledByUser(boolean z) {
        this.mGestureInputAvailability.setVkbGestureEnabledByUser(z);
        setVkbGesturePreviewMode(z);
    }

    public void setCkbGestureHandlingEnabledByUser(boolean z) {
        this.mGestureInputAvailability.setCkbGestureEnabledByUser(z);
    }

    public void setMainDictionaryAvailability(boolean z) {
        this.mGestureInputAvailability.setMainDictionaryAvailable(z);
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
        installPreviewPlacerView();
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView, android.view.View
    protected void onDetachedFromWindow() {
        // Before super: a pending long-press or preview-dismiss message would otherwise fire
        // onLongPress -> showMoreKeysPanel on a view that is no longer in the window, leaving
        // mMoreKeysPanel non-null and undismissable.
        cancelAllOngoingEvents();
        super.onDetachedFromWindow();
        this.mDrawingPreviewPlacerView.removeAllViews();
        // KI2-17/KT-11: PointerTracker holds this view in static fields; drop them so a detached
        // input view (and its themed Context) is not retained for the process lifetime.
        PointerTracker.release();
    }

    /**
     * The keyboard the long-pressed key actually belongs to. PointerTracker routes input
     * boards (the number pad) through this view too, but their keys are NOT part of
     * getKeyboard() — and on the physical pad the main keyboard is the collapsed
     * zero-height element, whose mMostCommonKeyHeight of 0 drives the MoreKeysKeyboard
     * geometry negative (ArithmeticException in ProximityGrid). Deriving the popup
     * from the key's own keyboard gives correct sizing on every surface.
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
        Keyboard c0965eMo5338b = this.mMoreKeysKeyboardCache.get(key);
        if (c0965eMo5338b == null) {
            c0965eMo5338b = new MoreKeysKeyboard.Builder(context, key, moreKeysSourceKeyboardFor(key), this.mKeyPreviewChoreographer.isPopupEnabled() && !key.isNoKeyPreview() && c1034aqArrM6245i.length == 1 && this.mKeyPreviewChoreographer.getVisibleWidth() > 0, this.mKeyPreviewChoreographer.getVisibleWidth(), this.mKeyPreviewChoreographer.getVisibleHeight(), createPaintForKey(key)).build();
            this.mMoreKeysKeyboardCache.put(key, c0965eMo5338b);
        }
        View view = key.isFunctionalKey() ? this.mMoreKeysKeyboardForActionContainer : this.mMoreKeysKeyboardContainer;
        MoreKeysKeyboardView moreKeysKeyboardView = (MoreKeysKeyboardView) view.findViewById(R.id.more_keys_keyboard_view);
        moreKeysKeyboardView.setKeyboard(c0965eMo5338b);
        view.measure(-2, -2);
        return moreKeysKeyboardView;
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerHandler
    public void onLongPress(PointerTracker c1084t) {
        Key keyM7652o;
        if (isShowingMoreKeysPanel() || (keyM7652o = c1084t.getKey()) == null) {
            return;
        }
        KeyboardActionListenerInterface interfaceC0976f = this.mKeyboardActionListener;
        if (keyM7652o.hasBackgroundTypeAction()) {
            int iB = keyM7652o.getMoreKeys()[0].getCode();
            c1084t.cancelTrackingAndReleaseKey();
            interfaceC0976f.onPressKey(iB, 0, true);
            interfaceC0976f.onCodeInput(iB, -1, -1, c1084t.getLongPressTimeoutTime(keyM7652o.getCode()), false);
            interfaceC0976f.onReleaseKey(iB, false);
            return;
        }
        int iM6232c = keyM7652o.getCode();
        if ((iM6232c == -10 || iM6232c == 32) && interfaceC0976f.onCustomRequest(1)) {
            c1084t.cancelTrackingAndReleaseKey();
            interfaceC0976f.onReleaseKey(iM6232c, false);
        } else {
            if (keyM7652o.hasLongPressKey()) {
                if (keyM7652o.getLongPressCode() != -34) {
                    c1084t.cancelTrackingAndReleaseKey();
                }
                int iM6235d = keyM7652o.getLongPressCode();
                interfaceC0976f.onPressKey(iM6235d, 0, true);
                interfaceC0976f.onCodeInput(iM6235d, -1, -1, c1084t.getLongPressTimeoutTime(keyM7652o.getCode()), false);
                interfaceC0976f.onReleaseKey(iM6235d, false);
                return;
            }
            openMoreKeysPanel(keyM7652o, c1084t);
        }
    }

    private void openMoreKeysPanel(Key key, PointerTracker c1084t) {
        int iMo6216ab;
        MoreKeysPanel interfaceC1071mM6289a = onCreateMoreKeysKeyboard(key, getContext());
        if (interfaceC1071mM6289a == null) {
            return;
        }
        int[] iArrM5687a = CoordinateUtils.newCoordinateArray();
        c1084t.getLastCoordinates(iArrM5687a);
        boolean z = this.mKeyPreviewChoreographer.isPopupEnabled() && !key.isNoKeyPreview();
        if (this.mConfigShowMoreKeysKeyboardAtTouchedPoint && !z) {
            iMo6216ab = CoordinateUtils.x(iArrM5687a);
        } else {
            iMo6216ab = key.getX() + (key.getWidth() / 2);
        }
        interfaceC1071mM6289a.showMoreKeysPanel(this, this, iMo6216ab, key.getY() + this.mKeyPreviewChoreographer.getVisibleOffset(), this.mKeyboardActionListener);
        c1084t.startMoreKeysPanel(interfaceC1071mM6289a);
        dismissKeyPreviewWithoutDelay(key);
    }

    public boolean isInDraggingFinger() {
        if (isShowingMoreKeysPanel()) {
            return true;
        }
        return PointerTracker.isAnyInSlidingKeyInput();
    }

    @Override // dev.bbkb.ime.keyboard.internal.MoreKeysPanel
    public void onShowMoreKeysPanel(MoreKeysPanel interfaceC1071m) {
        locatePreviewPlacerView();
        interfaceC1071m.showInParent(this.mDrawingPreviewPlacerView);
        this.mMoreKeysPanel = interfaceC1071m;
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

    public void updateGestureHandlingState(boolean z) {
        GestureEventProcessor.updateGestureHandlingState(z);
    }

    public boolean isGestureHandlingActive() {
        return GestureEventProcessor.isGestureHandlingActive();
    }

    public void setGestureInputStartsBeforeRunningShift(boolean z) {
        GestureEventProcessor.setGestureHandlingActive(z);
    }

    public void setSlideBoardViewManager(SlideboardManager c1083e) {
        this.mSlideboardManager = c1083e;
    }

    @Override // dev.bbkb.ime.keyboard.PointerTracker
    public void setSlideTranslation(float f, boolean z) {
        SlideboardManager c1083e = this.mSlideboardManager;
        if (c1083e != null) {
            c1083e.setTranslation(f, z);
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
            NuanceSDKManager.getInstance().touchCancel(pointerId);
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
        this.mMoreKeysKeyboardCache.clear();
    }

    public void onHideWindow() {
        onCancelMoreKeysPanel();
    }

    public void setShiftKeyState(boolean z) {
        this.mShiftKeyState = z;
        invalidateKey(this.mShiftKey);
    }

    public void setShiftLocked(boolean locked) {
        this.mShiftKeyLocked = locked;
        invalidateKey(this.mShiftKey);
    }

    public void setFunctionalKeyEnabled(boolean z) {
        Key keyM6604b;
        Keyboard keyboard = getKeyboard();
        if (keyboard == null || (keyM6604b = keyboard.getKeyByCode(-7)) == null) {
            return;
        }
        keyM6604b.setActive(z);
        invalidateKey(keyM6604b);
    }

    /**
     * The "%B" bold marker is stripped HERE, once per prediction change, rather than by a
     * freshly compiled regex on every spacebar repaint (the spacebar repaints continuously
     * while a prediction is showing).
     */
    public void setAutoCorrectionText(String str) {
        String stripped = str == null ? "" : PERCENT_B_MARKER.matcher(str).replaceAll("");
        if (this.mAutoCorrectionText.equals(stripped)) {
            return;
        }
        this.mAutoCorrectionText = stripped;
        invalidateKey(this.mSpaceKey);
    }

    private void dimEntireKeyboard(boolean z) {
        boolean z2 = this.mNeedsToDimEntireKeyboard != z;
        this.mNeedsToDimEntireKeyboard = z;
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
            if (this.mAutoCorrectionText.length() > 0) {
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
        // Draw shift state indicator bar based on actual shift state flag (mShiftKeyState)
        // This works on VKB where backgroundType doesn't change between shift states
        if (iM6232c == -1 && KeyboardColorManager.INSTANCE.isInitialized()) {
            float density = getResources().getDisplayMetrics().density;
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
            // The bar sits inset from the TOP of the key (above the arrow icon).
            // Cap styles: the cap top sits keyInsetVDp below the key bounds, so clear
            // that plus 4dp of padding to keep the bar inside the cap. Full-bleed
            // styles (inset 0) get the same 4dp visual clearance from the key top.
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

    /** Same settings the draw path used to allocate on every shift-key repaint. */
    static Paint newShiftIndicatorPaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    private String getAutoCorrectionText(Paint paint, int i) {
        return SpacebarTextUtils.truncateTextToWidth(i - (this.mLanguageOnSpacebarHorizontalMargin * 2), this.mAutoCorrectionText, this.mAutoCorrectionSpacebarTextSize, this.mMinimumScaleOfSpacebarText, paint);
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
        // Pull-at-draw when the spec asks for the accent word (BB10's blue spacebar
        // prediction); other styles keep the static autoCorrectionOnSpacebarTextColor attr.
        paint.setColor(KeyboardColorManager.styleSpec().getAccentSpacebarPrediction()
                ? KeyboardColorManager.INSTANCE.getAccentColor()
                : this.mAutoCorrectionSpacebarTextColor);
        canvas.drawText(strM6290a, iM6204Z / 2, f - fDescent, paint);
        paint.setTextSize(this.mAutoCorrectionSpacebarTextSize);
    }

    private void drawShiftKeyIcon(Key key, Canvas canvas) {
        Drawable drawable = this.mShiftKeyState ? this.mShiftKeyIcons[3] : this.mShiftKeyIcons[1];
        if (drawable != null) {
            drawDrawable(canvas, drawable, (key.getWidth() - drawable.getIntrinsicWidth()) / 2, (key.getHeight() - drawable.getIntrinsicHeight()) / 2, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        }
    }

    private void drawAltCodeKeyIcon(Key key, Canvas canvas) {
        Drawable drawable = this.mAltKeyIcons[4];
        if (drawable != null) {
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
    }

    public void setKeyPressed(Key key, boolean z) {
        synchronized (this.mPressedKeys) {
            if (z) {
                key.onPressed();
                this.mPressedKeys.add(key);
            } else {
                key.onReleased();
                this.mPressedKeys.remove(key);
            }
            invalidateKey(key);
        }
    }

    public void releaseCurrentlyPressedKeys() {
        synchronized (this.mPressedKeys) {
            Iterator<Key> it = this.mPressedKeys.iterator();
            while (it.hasNext()) {
                Key next = it.next();
                if (!PointerTracker.isAnyPointerOnKey(next)) {
                    next.onReleased();
                }
            }
            this.mPressedKeys.clear();
        }
    }
}
