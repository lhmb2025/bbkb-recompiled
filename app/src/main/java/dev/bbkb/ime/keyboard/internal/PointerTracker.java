package dev.bbkb.ime.keyboard.internal;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.Constants;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import com.blackberry.nuanceshim.NuanceSDK;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;

import java.util.ArrayList;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.shared.InputPathDebug;



public final class PointerTracker extends AbstractDrawingHandler {

    private static TypingTimeRecorder sGestureStrokeParams = null;

    private static boolean sSuppressSlideboard = false;

    private static final String TAG = "PointerTracker";

    private static GestureRecognitionParams sGestureRecognitionParams;

    private static TimerCallback sTimerCallback;

    private static PointerTrackerParams sParams;

    private static GestureStrokeDrawingParams sGestureStroke;

    private static KeyDrawingProxy sDrawingProxy;

    private static SlidingPanel sSlidingPanel;

    private static TimerProxy sKeyboardActionCallback;

    private static GestureEnabledProvider sGestureEnabledProvider;

    private Keyboard mKeyboard;

    private long mDownTime;

    private int[] mDownCoordinates;

    private long mLastUpTime;

    private long mKeyDownTime;

    private Key mCurrentKey;

    private int mKeyX;

    private int mKeyY;

    private float mSlideboardStartRawX;

    private long mDownEventNanoTime;

    private int mLastX;

    private int mLastY;

    private boolean mIgnoreModifierKey;

    private MoreKeysPanel mDrawingProxy;

    private boolean mIsAllowedDraggingFinger;

    private int mRepeatingKeyCode;

    private final GestureStrokeRecognizer mGestureStrokeRecognizer;

    private boolean mSlideboardActive;

    private float mSlideboardBaseTranslation;

    int mSecondPointerDeltaX;

    boolean mIsInSlidingKeyInput;

    private final GesturePathTracker mGesturePathTracker;

    private KeyDetector mKeyDetector;

    private static KeyboardActionListenerInterface sKeyboardActionListener = KeyboardActionListenerInterface.EMPTY;

    private static boolean sInGesture = false;

    private static final PointerTrackerQueue sPointerTrackerQueue = new PointerTrackerQueue();

    private static final ArrayList<PointerTracker> sTrackers = new ArrayList<>();

    private static boolean sInMoreKeysPanel = false;

    
    public interface KeyDrawingProxy {
        void showGestureTrail(PointerTracker c1084t);

        void invalidateKeyGraphics(Key key);

        void showSlidingKeyInputPreview(PointerTracker c1084t);

        void showKeyPreview(Key key);

        void dismissKeyPreview(Key key);

        void dismissGestureTrail();
    }

    
    public interface GestureEnabledProvider {
        boolean isGestureInputEnabled();
    }

    
    public interface SlidingPanel {

        void setSlideTranslation(float f, boolean z);

        float getTranslationX();

        void onSlidingFinished();
    }

    
    static final class PointerTrackerParams {

        public final int mTouchNoiseThresholdTime;

        public final int mTouchNoiseThresholdDistance;

        public final int mSuppressKeyPreviewAfterBatchInputDuration;

        public final int mKeyRepeatStartTimeout;

        public final int mKeyRepeatInterval;

        public final int mLongPressShiftLockTimeout;

        public final int mLockFocusToFirstOnDownKeyDuration;

        public PointerTrackerParams(TypedArray typedArray) {
            this.mTouchNoiseThresholdTime = typedArray.getInt(R.styleable.MainKeyboardView_touchNoiseThresholdTime, 0);
            this.mTouchNoiseThresholdDistance = typedArray.getDimensionPixelSize(R.styleable.MainKeyboardView_touchNoiseThresholdDistance, 0);
            this.mSuppressKeyPreviewAfterBatchInputDuration = typedArray.getInt(R.styleable.MainKeyboardView_suppressKeyPreviewAfterBatchInputDuration, 0);
            this.mKeyRepeatStartTimeout = typedArray.getInt(R.styleable.MainKeyboardView_keyRepeatStartTimeout, 0);
            this.mKeyRepeatInterval = typedArray.getInt(R.styleable.MainKeyboardView_keyRepeatInterval, 0);
            this.mLongPressShiftLockTimeout = typedArray.getInt(R.styleable.MainKeyboardView_longPressShiftLockTimeout, 0);
            this.mLockFocusToFirstOnDownKeyDuration = typedArray.getInt(R.styleable.MainKeyboardView_lockFocusToFirstOnDownKeyDuration, 0);
        }
    }

    public static void init(TypedArray typedArray, TimerProxy interfaceC1020ac, TimerCallback interfaceC1046e, KeyDrawingProxy aVar, GestureEnabledProvider bVar, SlidingPanel cVar) throws Resources.NotFoundException {
        sParams = new PointerTrackerParams(typedArray);
        sGestureRecognitionParams = new GestureRecognitionParams(typedArray);
        sGestureStroke = new GestureStrokeDrawingParams(typedArray);
        sGestureStrokeParams = new TypingTimeRecorder(sGestureRecognitionParams.mStaticTimeThresholdAfterFastTyping, sParams.mSuppressKeyPreviewAfterBatchInputDuration);
        setViewProxies(interfaceC1020ac, interfaceC1046e, aVar, bVar, cVar);
    }

    /**
     * Install (or re-install) the per-input-view collaborators. Split out of {@link #init} so that
     * {@link #release()} can drop them on detach and the owning view can restore them when it is
     * re-attached without re-parsing its styled attributes.
     */
    public static void setViewProxies(TimerProxy interfaceC1020ac, TimerCallback interfaceC1046e, KeyDrawingProxy aVar, GestureEnabledProvider bVar, SlidingPanel cVar) {
        sKeyboardActionCallback = interfaceC1020ac;
        sTimerCallback = interfaceC1046e;
        sDrawingProxy = aVar;
        sGestureEnabledProvider = bVar;
        sSlidingPanel = cVar;
    }

    /**
     * Drop every static reference this class holds to the input view that installed it.
     *
     * <p>{@link #init} stores the {@code MainKeyboardView} (as the drawing proxy, the sliding panel
     * and the gesture-enabled provider) plus its timer handlers in static fields. A new input view
     * is built on every rotation, theme change and {@code forceRecreateInputView()}; without this
     * the previous View, its themed Context and its whole inflated hierarchy stay strongly
     * reachable for the process lifetime, and a stale tracker can still draw into a detached view.
     */
    public static void release() {
        sDrawingProxy = null;
        sSlidingPanel = null;
        sKeyboardActionCallback = null;
        sTimerCallback = null;
        sGestureEnabledProvider = null;
        sKeyboardActionListener = KeyboardActionListenerInterface.EMPTY;
        sTrackers.clear();
    }

    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    public void updateBatchInputTimer(long j) {
        this.mGesturePathTracker.onUpdateBatchInputTimer(j, this);
    }

    public static void cancelAllPointerTrackers() {
        sPointerTrackerQueue.cancelAllPointerTrackers();
    }

    public static void setKeyboardActionListener(KeyboardActionListenerInterface interfaceC0976f) {
        sKeyboardActionListener = interfaceC0976f;
    }

    private static int getActivePointerTrackerCount() {
        return sPointerTrackerQueue.size();
    }

    public static PointerTracker getPointerTracker(int i) {
        ArrayList<PointerTracker> arrayList = sTrackers;
        for (int size = arrayList.size(); size <= i; size++) {
            arrayList.add(new PointerTracker(size));
        }
        return arrayList.get(i);
    }

    public static boolean isAnyInSlidingKeyInput() {
        return sPointerTrackerQueue.isAnyInSlidingKeyInput();
    }

    public static void setKeyDetector(KeyDetector c0964d) {
        if (c0964d.getKeyboard() == null) {
            return;
        }
        int size = sTrackers.size();
        for (int i = 0; i < size; i++) {
            sTrackers.get(i).setKeyDetectorInner(c0964d);
        }
    }

    public static void setReleasedKeyGraphicsToAllKeys() {
        int size = sTrackers.size();
        for (int i = 0; i < size; i++) {
            PointerTracker c1084t = sTrackers.get(i);
            c1084t.setReleasedKeyGraphics(c1084t.getKey());
        }
    }

    public static void dismissAllMoreKeysPanels() {
        int size = sTrackers.size();
        for (int i = 0; i < size; i++) {
            sTrackers.get(i).dismissMoreKeysPanel();
        }
    }

    public static boolean isAnyPointerOnKey(Key key) {
        int size = sTrackers.size();
        for (int i = 0; i < size; i++) {
            if (key.equals(sTrackers.get(i).getKey())) {
                return true;
            }
        }
        return false;
    }

    private PointerTracker(int i) {
        super(i);
        this.mSecondPointerDeltaX = 0;
        this.mKeyDetector = new KeyDetector();
        this.mDownCoordinates = CoordinateUtils.newCoordinateArray();
        this.mCurrentKey = null;
        this.mRepeatingKeyCode = -1;
        this.mGesturePathTracker = new GesturePathTracker(i, sGestureRecognitionParams);
        this.mGestureStrokeRecognizer = new GestureStrokeRecognizer(sGestureStroke);
    }

    private boolean callListenerOnPressAndCheckKeyboardLayoutChange(Key key, int i, long j) {
        if (sInGesture || this.mIsDetectingGesture || this.mIsTrackingCanceled) {
            return false;
        }
        boolean z = this.mIsInSlidingKeyInput && key.isModifierKey();
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) {
            logD("onPress    : %s%s%s%s", key == null ? "none" : Constants.printableCode(key.getCode()),
                    z ? " ignoreModifier" : "", key == null ? "none" : key.isActive() ? "" : " disabled",
                    i > 0 ? " repeatCount=" + i : "");
        }
        if (z || key == null || !key.isActive()) {
            return false;
        }
        sKeyboardActionListener.onPressKey(key.getCode(), i, getActivePointerTrackerCount() == 1);
        boolean z2 = this.mIgnoreModifierKey;
        this.mIgnoreModifierKey = false;
        sKeyboardActionCallback.startTypingStateTimer(key);
        return z2;
    }

    private void callListenerOnCodeInput(Key key, int i, int i2, int i3, long j, boolean z) {
        boolean z2 = this.mIsInSlidingKeyInput && key.isModifierKey();
        boolean z3 = key.isAltCodeWhileTyping() && sKeyboardActionCallback.isTypingState();
        int iM6199U = z3 ? key.getLongPressActionCode() : i;
        if (z2) {
            return;
        }
        if (key.isActive() || z3) {
            sGestureStrokeParams.onCodeInput(iM6199U, j);
            if (iM6199U == -4) {
                sKeyboardActionListener.onTextInput(key.getKeySpecOutputText(), j);
            } else if (iM6199U != -21) {
                if (this.mKeyboard.allowsGestureForCode(iM6199U)) {
                    sKeyboardActionListener.onCodeInput(iM6199U, i2, i3, j, z);
                } else {
                    sKeyboardActionListener.onCodeInput(iM6199U, -1, -1, j, z);
                }
            }
        }
    }

    private void callListenerOnRelease(Key key, int i, boolean z) {
        if (!key.isControlKey()) {
            if (sInGesture || this.mIsDetectingGesture) {
                return;
            }
            if (this.mIsTrackingCanceled && !key.isShiftKey()) {
                return;
            }
        }
        boolean z2 = this.mIsInSlidingKeyInput && key.isModifierKey();
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) {
            logD("onRelease  : %s%s%s%s", Constants.printableCode(i), z ? " sliding" : "",
                    z2 ? " ignoreModifier" : "", key.isActive() ? "" : " disabled");
        }
        if (!z2 && key.isActive()) {
            sKeyboardActionListener.onReleaseKey(i, z);
        }
    }

    private void callListenerOnCancelInput() {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onCancelInput");
        sKeyboardActionListener.onCancelInput();
    }

    private void setKeyDetectorInner(KeyDetector c0964d) {
        Keyboard c0965eM6591a = c0964d.getKeyboard();
        if (c0965eM6591a == null) {
            return;
        }
        if (c0964d == this.mKeyDetector && c0965eM6591a == this.mKeyboard) {
            return;
        }
        this.mKeyDetector = c0964d;
        this.mKeyboard = c0965eM6591a;
        this.mIgnoreModifierKey = true;
        int i = this.mKeyboard.mMostCommonKeyWidth;
        int i2 = this.mKeyboard.mMostCommonKeyHeight;
        this.mGesturePathTracker.setKeyboardGeometry(i, this.mKeyboard.mOccupiedHeight);
    }

    @Override
    public boolean isModifier() {
        Key key = this.mCurrentKey;
        return key != null && key.isModifierKey();
    }

    @Override
    public boolean isOnShiftKey() {
        Key key = this.mCurrentKey;
        return key != null && key.isShiftKey();
    }

    @Override
    public void dispatchShiftKeyTap() {
        cancelTrackingAndReleaseKey();
        sKeyboardActionListener.onPressKey(-2, 0, true);
        sKeyboardActionListener.onCodeInput(-2, -1, -1, SystemClock.uptimeMillis(), false);
        sKeyboardActionListener.onReleaseKey(-2, false);
    }

    @Override
    public boolean isInSlidingKeyInput() {
        return this.mIsInSlidingKeyInput;
    }

    @Override
    public void onPhantomUpEvent(long j) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onPhntEvent:", this.mLastX, this.mLastY, j);
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        onUpEventInternal(this.mLastX, this.mLastY, j);
        cancelTrackingForAction();
    }

    @Override
    public void cancelTrackingForAction() {
        if (isShowingMoreKeysPanel()) {
            return;
        }
        // FIX-L8: null when the engine failed to load. The keyboard must still type.
        final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("PointerTracker.cancelTrackingForAction");
        if (sdk != null) sdk.touchCancel(this.mPointerId);
        this.mIsTrackingCanceled = true;
    }

    public Key getKey() {
        return this.mCurrentKey;
    }

    public Key getKeyOn(int i, int i2) {
        return this.mKeyDetector.detectHitKey(i, i2);
    }

    private void setReleasedKeyGraphics(Key key) {
        sDrawingProxy.dismissKeyPreview(key);
        if (key == null) {
            return;
        }
        onKeyReleased(key);
        if (key.isShiftKey()) {
            for (Key key2 : this.mKeyboard.mShiftKeys) {
                if (key2 != key) {
                    onKeyReleased(key2);
                }
            }
        }
        if (key.isAltCodeWhileTyping()) {
            int iM6199U = key.getLongPressActionCode();
            Key keyM6604b = this.mKeyboard.getKeyByCode(iM6199U);
            if (keyM6604b != null) {
                onKeyReleased(keyM6604b);
            }
            for (Key key3 : this.mKeyboard.mAltCodeKeysWhileTyping) {
                if (key3 != key && key3.getLongPressActionCode() == iM6199U) {
                    onKeyReleased(key3);
                }
            }
        }
    }

    private static boolean needsToSuppressKeyPreviewPopup(long j) {
        if (sGestureEnabledProvider.isGestureInputEnabled()) {
            return sGestureStrokeParams.needsUpdateBatchInput(j);
        }
        return false;
    }

    private void setPressedKeyGraphics(Key key, long j) {
        if (key == null) {
            return;
        }
        boolean z = true;
        boolean z2 = key.isAltCodeWhileTyping() && sKeyboardActionCallback.isTypingState();
        if (!key.isActive() && !z2) {
            z = false;
        }
        if (z) {
            if (!key.isNoKeyPreview() && !sInGesture && !needsToSuppressKeyPreviewPopup(j)) {
                sDrawingProxy.showKeyPreview(key);
            }
            onKeyPressed(key);
            if (key.isShiftKey()) {
                for (Key key2 : this.mKeyboard.mShiftKeys) {
                    if (key2 != key) {
                        onKeyPressed(key2);
                    }
                }
            }
            if (z2) {
                int iM6199U = key.getLongPressActionCode();
                Key keyM6604b = this.mKeyboard.getKeyByCode(iM6199U);
                if (keyM6604b != null) {
                    onKeyPressed(keyM6604b);
                }
                for (Key key3 : this.mKeyboard.mAltCodeKeysWhileTyping) {
                    if (key3 != key && key3.getLongPressActionCode() == iM6199U) {
                        onKeyPressed(key3);
                    }
                }
            }
        }
    }

    private static void onKeyReleased(Key key) {
        key.onReleased();
        sDrawingProxy.invalidateKeyGraphics(key);
    }

    private static void onKeyPressed(Key key) {
        key.onPressed();
        sDrawingProxy.invalidateKeyGraphics(key);
    }

    public GestureStrokeRecognizer getGestureStrokeRecognizer() {
        return this.mGestureStrokeRecognizer;
    }

    public void getLastCoordinates(int[] iArr) {
        CoordinateUtils.set(iArr, this.mLastX, this.mLastY);
    }

    public long getDownTime() {
        return this.mDownTime;
    }

    public void getDownCoordinates(int[] iArr) {
        CoordinateUtils.copy(iArr, this.mDownCoordinates);
    }

    private Key onDownKey(int i, int i2, long j) {
        this.mDownTime = j;
        CoordinateUtils.set(this.mDownCoordinates, i, i2);
        return onMoveToNewKey(onMoveKey(i, i2), i, i2);
    }

    /**
     * Euclidean distance between two on-screen points. Deliberately not {@link Math#hypot}: hypot
     * pays for overflow/underflow correctness that integer pixel deltas bounded by the screen can
     * never need, and it is roughly an order of magnitude slower. Called on every ACTION_MOVE
     * sample and on every historical sample replayed with it.
     */
    private static int getDistance(int i, int i2, int i3, int i4) {
        final int dx = i - i3;
        final int dy = i2 - i4;
        return (int) Math.sqrt((double) ((dx * dx) + (dy * dy)));
    }

    private Key onMoveKey(int i, int i2) {
        this.mLastX = i;
        this.mLastY = i2;
        return this.mKeyDetector.detectHitKey(i, i2);
    }

    private Key onMoveToNewKey(Key key, int i, int i2) {
        this.mCurrentKey = key;
        this.mKeyX = i;
        this.mKeyY = i2;
        return key;
    }

    private void updateSlidingKeyInputPreview() {
        if (this.mIsTrackingCanceled) {
            return;
        }
        sDrawingProxy.showSlidingKeyInputPreview(this);
    }

    @Override
    public void onStartBatchInput() {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onStartBatchInput");
        sKeyboardActionListener.onStartBatchInput();
        dismissAllMoreKeysPanels();
        sKeyboardActionCallback.cancelLongPressTimers(this);
        sPointerTrackerQueue.dispatchHeldShiftKey();
    }

    @Override
    public void onUpdateBatchInput(long j) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onUpdateBatchInput");
        sKeyboardActionListener.onUpdateBatchInput();
    }

    @Override
    public void startUpdateBatchInputTimer() {
        sTimerCallback.startUpdateBatchInputTimer(this);
    }

    @Override
    public void onEndBatchInput(long j) {
        sGestureStrokeParams.onEndBatchInput(j);
        sTimerCallback.cancelAllUpdateBatchInputTimers();
        if (this.mIsTrackingCanceled) {
            return;
        }
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onEndBatchInput");
        sKeyboardActionListener.onEndBatchInput(InputSource.SOFTWARE);
    }

    public void processMotionEvent(MotionEvent motionEvent, KeyDetector c0964d) {
        int actionMasked = motionEvent.getActionMasked();
        long eventTime = motionEvent.getEventTime();
        if (actionMasked == 0) {
            this.mSecondPointerDeltaX = 0;
        }
        if (actionMasked == 2) {
            int pointerCount = motionEvent.getPointerCount();
            for (int i = 0; i < pointerCount; i++) {
                PointerTracker c1084tM7596a = getPointerTracker(motionEvent.getPointerId(i));
                if ((!sInMoreKeysPanel || c1084tM7596a.isShowingMoreKeysPanel()) && (!sInGesture || c1084tM7596a.isInGesture())) {
                    c1084tM7596a.onMoveEvent((int) motionEvent.getX(i), (int) motionEvent.getY(i), eventTime, motionEvent);
                }
            }
        }
        if ((sInMoreKeysPanel && !isShowingMoreKeysPanel()) || (sInGesture && !isInGesture())) {
            cancelTrackingForAction();
            return;
        }
        int actionIndex = motionEvent.getActionIndex();
        int x = (int) motionEvent.getX(actionIndex);
        int y = (int) motionEvent.getY(actionIndex);
        switch (actionMasked) {
            case 0:
            case 5:
                this.mSlideboardStartRawX = motionEvent.getRawX();
                this.mDownEventNanoTime = System.nanoTime();
                this.mSlideboardActive = false;
                setSuppressSlideboard(false);
                onDownEvent(x, y, eventTime, c0964d);
                break;
            case 1:
            case 6:
                onUpEvent(x, y, eventTime);
                break;
            case 3:
                onCancelEvent(x, y, eventTime);
                break;
        }
    }

    private void onDownEvent(int i, int i2, long j, KeyDetector c0964d) {
        int iM7594a;
        setKeyDetectorInner(c0964d);
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onDownEvent:", i, i2, j);
        long j2 = j - this.mLastUpTime;
        if (j2 < sParams.mTouchNoiseThresholdTime && (iM7594a = getDistance(i, i2, this.mLastX, this.mLastY)) < sParams.mTouchNoiseThresholdDistance) {
            if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onDownEvent: ignore potential noise: time=%d distance=%d", j2, iM7594a);
            cancelTrackingForAction();
            return;
        }
        Key keyM7645a = getKeyOn(i, i2);
        if (keyM7645a != null && keyM7645a.isModifierKey()) {
            sPointerTrackerQueue.releaseAllPointers(j);
        }
        sPointerTrackerQueue.add(this);
        onDownEventInternal(i, i2, j);
        if (sGestureEnabledProvider.isGestureInputEnabled()) {
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchStart: pointerId=" + this.mPointerId + " x=" + i + " y=" + i2 + " time=" + j + " isOnKey=" + keyM7645a);
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("PointerTracker.onDownEvent");
            if (sdk != null) sdk.touchStart(this.mPointerId, i, i2, j);
            Keyboard c0965e = this.mKeyboard;
            this.mIsDetectingGesture = (c0965e == null || !c0965e.mId.isAlphabetKeyboard() || !SettingsManager.getInstance().getSettingsValues().editorCapabilities.shouldSupportGestureInput || keyM7645a == null || keyM7645a.isModifierKey()) ? false : true;
            if (this.mIsDetectingGesture) {
                resetGestureStrokeTime();
                this.mGesturePathTracker.addDownEventPoint(i, i2, j, sGestureStrokeParams.getLastLetterTypingTime(), getActivePointerTrackerCount());
                this.mGestureStrokeRecognizer.onDownEvent(i, i2, this.mGesturePathTracker.getGestureElapsedTime(j));
            }
        }
    }

    boolean isShowingMoreKeysPanel() {
        return this.mDrawingProxy != null;
    }

    private void dismissMoreKeysPanel() {
        if (isShowingMoreKeysPanel()) {
            this.mDrawingProxy.dismissMoreKeysPanel();
            this.mDrawingProxy = null;
            setInMoreKeysPanel(false);
        }
    }

    private void onDownEventInternal(int i, int i2, long j) {
        Key keyM7614b = onDownKey(i, i2, j);
        this.mIgnoreModifierKey = false;
        this.mIsTrackingCanceled = false;
        resetSlidingKeyInput();
        if (keyM7614b != null) {
            if (callListenerOnPressAndCheckKeyboardLayoutChange(keyM7614b, 0, j)) {
                keyM7614b = onDownKey(i, i2, j);
            }
            startKeyRepeatIfRepeatable(keyM7614b);
            startMoreKeysPanelTimer(keyM7614b, j);
            startLockFocusTimer();
            setPressedKeyGraphics(keyM7614b, j);
        }
    }

    private void startSlidingKeyInput(Key key) {
        this.mIsInSlidingKeyInput = true;
    }

    // AOSP also tracks "sliding key input started on a modifier" (longer long-press timeout,
    // modifier hysteresis, gesture trail, onFinishSlidingInput on up). Nothing here ever entered
    // that state — the original BlackBerry build only ever cleared its flag — so it is not modelled.
    private void resetSlidingKeyInput() {
        this.mIsInSlidingKeyInput = false;
        sDrawingProxy.dismissGestureTrail();
    }

    private void onGestureMoveEvent(int i, int i2, long j, boolean z, Key key) {
        if (this.mIsDetectingGesture) {
            if (!this.mGesturePathTracker.addMoveEventPoint(this.mInGesture, i, i2, j, z, this, this)) {
                cancelBatchInput();
                return;
            }
            this.mGestureStrokeRecognizer.onMoveEvent(i, i2, this.mGesturePathTracker.getGestureElapsedTime(j));
            if (isShowingMoreKeysPanel()) {
                return;
            }
            if (!sInGesture && key != null && Character.isLetter(key.getCode()) && this.mGesturePathTracker.tryStartBatchInput(this)) {
                setInGesture(true);
                this.mInGesture = true;
                sPointerTrackerQueue.releaseAllPointersExcept(this, j);
            }
            if (isInGesture()) {
                if (key != null) {
                    this.mGesturePathTracker.updateBatchInput(j, this);
                }
                updateSlidingKeyInputPreview();
            }
        }
    }

    private void onMoveEvent(int i, int i2, long j, MotionEvent motionEvent) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onMoveEvent:", i, i2, j);
        if (this.mIsTrackingCanceled) {
            return;
        }
        if (sGestureEnabledProvider.isGestureInputEnabled() && motionEvent != null) {
            int iFindPointerIndex = motionEvent.findPointerIndex(this.mPointerId);
            int historySize = motionEvent.getHistorySize();
            for (int i3 = 0; i3 < historySize; i3++) {
                onGestureMoveEvent((int) motionEvent.getHistoricalX(iFindPointerIndex, i3), (int) motionEvent.getHistoricalY(iFindPointerIndex, i3), motionEvent.getHistoricalEventTime(i3), false, null);
            }
        }
        if (isShowingMoreKeysPanel()) {
            this.mDrawingProxy.onMoveEvent(this.mDrawingProxy.translateX(i), this.mDrawingProxy.translateY(i2), this.mPointerId, j);
            onMoveKey(i, i2);
            return;
        }
        onMoveEventInternal(i, i2, j, motionEvent);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchMove: pointerId=" + this.mPointerId + " x=" + i + " y=" + i2 + " time=" + j);
        final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("PointerTracker.onMoveEvent");
        if (sdk != null) sdk.touchMove(this.mPointerId, i, i2, j);
    }

    private void startTrackingNewKey(Key key, int i, int i2, long j) {
        if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0, j)) {
            key = onMoveKey(i, i2);
        }
        onMoveToNewKey(key, i, i2);
        if (this.mIsTrackingCanceled) {
            return;
        }
        startMoreKeysPanelTimer(key, j);
        setPressedKeyGraphics(key, j);
    }

    private void processSlidingKeyInput(Key key) {
        setReleasedKeyGraphics(key);
        callListenerOnRelease(key, key.getCode(), true);
        startSlidingKeyInput(key);
        sKeyboardActionCallback.cancelKeyTimers(this);
    }

    // Key selection by dragging a finger is always on here (config_key_selection_by_dragging_finger
    // is true, pinned by PointerTrackerCallbackStreamTest), so AOSP's "cancel on slide" and
    // "sliding finger while multi touching" arms are gone from these two methods.
    private void onMoveToNewKeyInternal(Key key, int i, int i2, long j, Key key2) {
        if (this.mIsAllowedDraggingFinger) {
            processSlidingKeyInput(key2);
            startKeyRepeatIfRepeatable(key);
            startTrackingNewKey(key, i, i2, j);
        }
    }

    private void slideOutFromOldKey(Key key, int i, int i2) {
        processSlidingKeyInput(key);
        onMoveToNewKey((Key) null, i, i2);
    }

    public boolean isSlideboardDragging() {
        return this.mSlideboardActive;
    }

    private float getSlideboardDistanceX(int x) {
        return Math.abs(x - this.mSlideboardStartRawX);
    }

    private void onMoveEventInternal(int i, int i2, long j, MotionEvent motionEvent) {
        if (processSlideboardMove(i, i2, j, motionEvent)) {
            return;
        }
        Key key = this.mCurrentKey;
        Key keyM7631d = onMoveKey(i, i2);
        if (sGestureEnabledProvider.isGestureInputEnabled()) {
            onGestureMoveEvent(i, i2, j, true, keyM7631d);
            if (sInGesture) {
                this.mCurrentKey = null;
                setReleasedKeyGraphics(key);
                return;
            }
        }
        if (keyM7631d != null) {
            if (key != null && isMajorEnoughMoveToBeOnNewKey(i, i2, j, keyM7631d)) {
                onMoveToNewKeyInternal(keyM7631d, i, i2, j, key);
            } else if (key == null) {
                startTrackingNewKey(keyM7631d, i, i2, j);
            }
        } else if (key != null && isMajorEnoughMoveToBeOnNewKey(i, i2, j, (Key) null)) {
            slideOutFromOldKey(key, i, i2);
        }
    }

    private boolean processSlideboardMove(int i, int i2, long j, MotionEvent motionEvent) {
        long jNanoTime = System.nanoTime() - this.mDownEventNanoTime;
        // One snapshot per sample: this runs on every ACTION_MOVE, and re-reading mid-expression
        // can observe two different SettingsValues if a settings reload lands between the reads.
        final SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (!sv.isSlideboardEnabled || sSuppressSlideboard || ((!this.mSlideboardActive && getSlideboardDistanceX(i) <= 150f) || jNanoTime <= 200000000)) {
            return false;
        }
        if (!this.mSlideboardActive && isSlideboardGesture(motionEvent)) {
            this.mSlideboardActive = true;
            this.mSlideboardBaseTranslation = sSlidingPanel.getTranslationX();
            setInGesture(true);
            this.mInGesture = true;
            sPointerTrackerQueue.releaseAllPointersExcept(this, j);
            cancelOwnLongPressTimers();
            cancelKeyRepeatTimerSelf();
            setReleasedKeyGraphics(this.mCurrentKey);
        }
        float rawX = (int) (motionEvent.getRawX() - this.mSlideboardStartRawX);
        if (isSlideboardGesture(motionEvent)) {
            sSlidingPanel.setSlideTranslation(this.mSlideboardBaseTranslation + rawX, false);
        }
        return true;
    }

    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    protected void onCancelEvent(int i, int i2, long j) {
        super.onCancelEvent(i, i2, j);
        if (this.mSlideboardActive) {
            sSlidingPanel.onSlidingFinished();
        }
    }

    private void onUpEvent(int i, int i2, long j) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onUpEvent  :", i, i2, j);
        this.mLastUpTime = j;
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        if (this.mSlideboardActive) {
            sSlidingPanel.onSlidingFinished();
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchCancel: pointerId=" + this.mPointerId + " isGesture=" + this.mSlideboardActive);
            final NuanceSDK slideSdk = NuanceSDKManager.sdkOrWarn("PointerTracker.onUpEvent/slideboard");
            if (slideSdk != null) slideSdk.touchCancel(this.mPointerId);
            cancelTracking();
        }
        if (!sInGesture) {
            Key key = this.mCurrentKey;
            if (key != null && key.isModifierKey()) {
                sPointerTrackerQueue.releaseAllPointersExcept(this, j);
            } else {
                sPointerTrackerQueue.releaseAllPointersOlderThan(this, j);
            }
        }
        onUpEventInternal(i, i2, j);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchEnd: pointerId=" + this.mPointerId + " x=" + i + " y=" + i2 + " time=" + j);
        // Same race protection as GestureEventProcessor.touchEnd: deposit + flag under
        // the gesture lock so the suggestion worker's defensive clear() can't wipe the
        // flow trace before the gesture commit reads it. This path (VKB batch input)
        // was missed by the original fix, which made VKB swipe words vanish: the trace
        // reached the engine but gesturePending was never set, so the next prediction
        // request cleared the results and no commit ever ran.
        synchronized (NuanceSDKManager.getGestureLock()) {
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("PointerTracker.onUpEvent/end");
            // No engine means no deposit and nothing to commit: gestureAccepted stays false,
            // which is the same outcome the engine reports for a gesture it did not take.
            boolean gestureAccepted = sdk != null && sdk.touchEnd(this.mPointerId, i, i2, j);
            if (gestureAccepted) {
                NuanceSDKManager.noteGestureDeposit();
                if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchEnd: gestureAccepted → setGesturePending(true)");
            }
        }
        sPointerTrackerQueue.remove(this);
    }

    private void onUpEventInternal(int i, int i2, long j) {
        sKeyboardActionCallback.cancelKeyTimers(this);
        boolean z = this.mIsInSlidingKeyInput;
        resetSlidingKeyInput();
        this.mIsDetectingGesture = false;
        Key key = this.mCurrentKey;
        this.mCurrentKey = null;
        int i3 = this.mRepeatingKeyCode;
        this.mRepeatingKeyCode = -1;
        setReleasedKeyGraphics(key);
        if (isShowingMoreKeysPanel()) {
            if (!this.mIsTrackingCanceled) {
                int iMo6364c = this.mDrawingProxy.translateX(i);
                int iMo6367d = this.mDrawingProxy.translateY(i2);
                final NuanceSDK panelSdk = NuanceSDKManager.sdkOrWarn("PointerTracker.onUpEventInternal/panel");
                if (panelSdk != null) panelSdk.touchCancel(this.mPointerId);
                this.mDrawingProxy.onUpEvent(iMo6364c, iMo6367d, this.mPointerId, j);
            }
            dismissMoreKeysPanel();
            return;
        }
        if (sInGesture) {
            if (key != null) {
                callListenerOnRelease(key, key.getCode(), true);
            }
            if (isInGesture()) {
                this.mGesturePathTracker.endBatchInput(j, this);
                setInGesture(false);
                this.mInGesture = false;
            }
            updateSlidingKeyInputPreview();
            return;
        }
        if (this.mIsTrackingCanceled) {
            if (key == null) {
                return;
            }
            if (!key.isShiftKey() && !key.isControlKey()) {
                return;
            }
        }
        if (key == null || !key.isRepeatable() || key.getCode() != i3 || z) {
            if (sInMoreKeysPanel) {
                if (key != null) {
                    callListenerOnRelease(key, key.getCode(), true);
                }
            } else {
                final NuanceSDK tapSdk = NuanceSDKManager.sdkOrWarn("PointerTracker.onUpEventInternal/tap");
                if (tapSdk != null) tapSdk.touchCancel(this.mPointerId);
                detectAndSendKey(key, this.mKeyX, this.mKeyY, j);
            }
        }
    }

    public void startMoreKeysPanel(MoreKeysPanel interfaceC1071m) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        setReleasedKeyGraphics(this.mCurrentKey);
        interfaceC1071m.onDownEvent(interfaceC1071m.translateX(this.mLastX), interfaceC1071m.translateY(this.mLastY), this.mPointerId, jUptimeMillis);
        this.mDrawingProxy = interfaceC1071m;
        setInMoreKeysPanel(isShowingMoreKeysPanel());
        sPointerTrackerQueue.releaseAllPointersExcept(this, jUptimeMillis);
        setSuppressSlideboard(true);
    }

    public void cancelOwnLongPressTimers() {
        sKeyboardActionCallback.cancelLongPressTimers(this);
    }

    public void cancelTrackingAndReleaseKey() {
        resetSlidingKeyInput();
        cancelTrackingForAction();
        setReleasedKeyGraphics(this.mCurrentKey);
        sPointerTrackerQueue.remove(this);
    }

    private void cancelBatchInput() {
        cancelAllPointerTrackers();
        this.mIsDetectingGesture = false;
        if (sInGesture) {
            setInGesture(false);
            this.mInGesture = false;
            if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onCancelBatchInput");
            sKeyboardActionListener.onCancelBatchInput();
        }
    }

    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    protected void onCancelEventInternal(long j) {
        cancelBatchInput();
        cancelAllPointerTrackers();
        sPointerTrackerQueue.releaseAllPointers(j);
        resetTrackingState();
    }

    private void resetTrackingState() {
        sKeyboardActionCallback.cancelKeyTimers(this);
        setReleasedKeyGraphics(this.mCurrentKey);
        resetSlidingKeyInput();
        dismissMoreKeysPanel();
    }

    private boolean isMajorEnoughMoveToBeOnNewKey(int i, int i2, long j, Key key) {
        Key key2 = this.mCurrentKey;
        if (key == key2) {
            return false;
        }
        if (key2 == null) {
            return true;
        }
        int iM6589a = this.mKeyDetector.getKeyHysteresisDistanceSquared(false);
        int iM6225b = key2.squaredDistanceToEdge(i, i2);
        if (iM6225b >= iM6589a) {
            if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("isMajorEnoughMoveToBeOnNewKey: %.2f key width from key edge", ((float) Math.sqrt(iM6225b)) / this.mKeyboard.mMostCommonKeyWidth);
            return true;
        }
        return false;
    }

    private void startMoreKeysPanelTimer(Key key, long j) {
        int iM7634e;
        this.mKeyDownTime = j;
        sKeyboardActionCallback.cancelLongPressShiftKeyTimer();
        if (sInGesture || key == null || !key.hasMoreKeys()) {
            return;
        }
        if (!(this.mIsInSlidingKeyInput && key.getMoreKeys() == null) && (iM7634e = getKeyLongPressTimeout(key.getCode())) > 0) {
            sKeyboardActionCallback.startLongPressTimer(this, iM7634e);
        }
    }

    public long getLongPressTimeoutTime(int i) {
        return this.mKeyDownTime + getKeyLongPressTimeout(i);
    }

    private int getKeyLongPressTimeout(int i) {
        if (i == -1) {
            return sParams.mLongPressShiftLockTimeout;
        }
        final SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        int i2 = sv.keyLongpressTimeoutMs;
        return sv.isSlideboardEnabled ? sv.slideboardKeyLongpressTimeout : i2;
    }

    private void detectAndSendKey(Key key, int i, int i2, long j) {
        if (key == null) {
            callListenerOnCancelInput();
            return;
        }
        int iM6232c = key.getCode();
        callListenerOnCodeInput(key, iM6232c, i, i2, j, false);
        callListenerOnRelease(key, iM6232c, false);
    }

    public void onLockFocusTimerExpired() {
        this.mIsAllowedDraggingFinger = true;
    }

    public void cancelKeyRepeatTimerSelf() {
        sKeyboardActionCallback.cancelKeyRepeatTimer(this);
    }

    private void startKeyRepeatIfRepeatable(Key key) {
        if (sInGesture || key == null || !key.isRepeatable() || this.mIsInSlidingKeyInput) {
            return;
        }
        startKeyRepeatTimer(1);
    }

    public void onKeyRepeat(int i, int i2) {
        setSuppressSlideboard(true);
        Key keyM7652o = getKey();
        if (keyM7652o == null || keyM7652o.getCode() != i) {
            this.mRepeatingKeyCode = -1;
            return;
        }
        this.mRepeatingKeyCode = i;
        this.mIsDetectingGesture = false;
        startKeyRepeatTimer(i2 + 1);
        callListenerOnPressAndCheckKeyboardLayoutChange(keyM7652o, i2, SystemClock.uptimeMillis());
        callListenerOnCodeInput(keyM7652o, i, this.mKeyX, this.mKeyY, SystemClock.uptimeMillis(), true);
    }

    private int getKeyRepeatStartTimeout() {
        final SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (sv.isSlideboardEnabled) {
            return sv.slideboardKeyLongpressTimeout;
        }
        return sParams.mKeyRepeatStartTimeout;
    }

    private void startKeyRepeatTimer(int i) {
        sKeyboardActionCallback.startKeyRepeatTimer(this, i, i == 1 ? getKeyRepeatStartTimeout() : sParams.mKeyRepeatInterval);
    }

    private void startLockFocusTimer() {
        this.mIsAllowedDraggingFinger = false;
        sKeyboardActionCallback.startLockFocusTimer(this, sParams.mLockFocusToFirstOnDownKeyDuration);
    }

    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    protected void printTouchEvent(String str, int i, int i2, long j) {
        // Whole body under the compile-time gate: this is called per down/move/up sample, and the
        // Object[7] plus five boxed values were being built before the test.
        if (!BuildConfig.DEBUG) {
            return;
        }
        Key key = this.mKeyDetector.detectHitKey(i, i2);
        String printableCode = key == null ? "none" : Constants.printableCode(key.getCode());
        Log.d(TAG, String.format("[%d]%s%s %4d %4d %5d %s", this.mPointerId, this.mIsTrackingCanceled ? "-" : " ", str, i, i2, j, printableCode));
    }

    /**
     * Debug line {@code "[pointerId] " + fmt}. Every caller guards with
     * {@code BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)} so release builds fold the
     * call, its varargs array and the boxing away.
     */
    private void logD(String fmt, Object... args) {
        final Object[] withId = new Object[args.length + 1];
        withId[0] = this.mPointerId;
        System.arraycopy(args, 0, withId, 1, args.length);
        Log.d(TAG, String.format("[%d] " + fmt, withId));
    }

    private static void setInGesture(boolean z) {
        sInGesture = z;
    }

    private static void setInMoreKeysPanel(boolean z) {
        sInMoreKeysPanel = z;
    }

    private static void setSuppressSlideboard(boolean z) {
        sSuppressSlideboard = z;
    }

    private boolean isSlideboardGesture(MotionEvent motionEvent) {
        int historySize = motionEvent.getHistorySize();
        if (motionEvent.getPointerCount() > 1 && historySize > 0) {
            this.mSecondPointerDeltaX = (int) (motionEvent.getX(1) - ((int) motionEvent.getHistoricalX(1, 0)));
        }
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        return (!c0804dM5050c.isVkbGestureInputEnabled && motionEvent.getPointerCount() == 1 && this.mSecondPointerDeltaX == 0) || (c0804dM5050c.isVkbGestureInputEnabled && motionEvent.getPointerCount() == 2);
    }

}
