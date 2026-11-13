package dev.bbkb.ime.keyboard.internal;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.Constants;
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
        void showGestureTrail(PointerTracker pointerTracker);

        void invalidateKeyGraphics(Key key);

        void showSlidingKeyInputPreview(PointerTracker pointerTracker);

        void showKeyPreview(Key key);

        void dismissKeyPreview(Key key);

        void dismissGestureTrail();
    }

    
    public interface GestureEnabledProvider {
        boolean isGestureInputEnabled();
    }

    
    public interface SlidingPanel {

        void setSlideTranslation(float translationX, boolean animate);

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

    public static void init(TypedArray typedArray, TimerProxy timerProxy, TimerCallback timerCallback, KeyDrawingProxy keyDrawingProxy, GestureEnabledProvider gestureEnabledProvider, SlidingPanel slidingPanel) throws Resources.NotFoundException {
        sParams = new PointerTrackerParams(typedArray);
        sGestureRecognitionParams = new GestureRecognitionParams(typedArray);
        sGestureStroke = new GestureStrokeDrawingParams(typedArray);
        sGestureStrokeParams = new TypingTimeRecorder(sGestureRecognitionParams.mStaticTimeThresholdAfterFastTyping, sParams.mSuppressKeyPreviewAfterBatchInputDuration);
        setViewProxies(timerProxy, timerCallback, keyDrawingProxy, gestureEnabledProvider, slidingPanel);
    }

    /** Installs the per-input-view collaborators; separate from {@link #init} so a re-attached view can restore them after {@link #release()}. */
    public static void setViewProxies(TimerProxy timerProxy, TimerCallback timerCallback, KeyDrawingProxy keyDrawingProxy, GestureEnabledProvider gestureEnabledProvider, SlidingPanel slidingPanel) {
        sKeyboardActionCallback = timerProxy;
        sTimerCallback = timerCallback;
        sDrawingProxy = keyDrawingProxy;
        sGestureEnabledProvider = gestureEnabledProvider;
        sSlidingPanel = slidingPanel;
    }

    /**
     * Drops every static reference to the input view that installed it. The view is rebuilt on
     * rotation, theme change and recreate; without this the old view hierarchy leaks for the
     * process lifetime and a stale tracker can draw into a detached view.
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

    /** @param syntheticMoveEventTime event time */
    @Override
    public void updateBatchInputTimer(long syntheticMoveEventTime) {
        this.mGesturePathTracker.onUpdateBatchInputTimer(syntheticMoveEventTime, this);
    }

    public static void cancelAllPointerTrackers() {
        sPointerTrackerQueue.cancelAllPointerTrackers();
    }

    public static void setKeyboardActionListener(KeyboardActionListenerInterface listener) {
        sKeyboardActionListener = listener;
    }

    private static int getActivePointerTrackerCount() {
        return sPointerTrackerQueue.size();
    }

    /** @param pointerId pointer id; trackers are created on demand up to it */
    public static PointerTracker getPointerTracker(int pointerId) {
        ArrayList<PointerTracker> arrayList = sTrackers;
        for (int size = arrayList.size(); size <= pointerId; size++) {
            arrayList.add(new PointerTracker(size));
        }
        return arrayList.get(pointerId);
    }

    public static boolean isAnyInSlidingKeyInput() {
        return sPointerTrackerQueue.isAnyInSlidingKeyInput();
    }

    /** @param keyDetector the detector for the current keyboard; ignored if it has no keyboard */
    public static void setKeyDetector(KeyDetector keyDetector) {
        if (keyDetector.getKeyboard() == null) {
            return;
        }
        int size = sTrackers.size();
        for (int i = 0; i < size; i++) {
            sTrackers.get(i).setKeyDetectorInner(keyDetector);
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

    private PointerTracker(int pointerId) {
        super(pointerId);
        this.mSecondPointerDeltaX = 0;
        this.mKeyDetector = new KeyDetector();
        this.mDownCoordinates = CoordinateUtils.newCoordinateArray();
        this.mCurrentKey = null;
        this.mRepeatingKeyCode = -1;
        this.mGesturePathTracker = new GesturePathTracker(pointerId, sGestureRecognitionParams);
        this.mGestureStrokeRecognizer = new GestureStrokeRecognizer(sGestureStroke);
    }

    private boolean callListenerOnPressAndCheckKeyboardLayoutChange(Key key, int repeatCount, long eventTime) {
        if (sInGesture || this.mIsDetectingGesture || this.mIsTrackingCanceled) {
            return false;
        }
        boolean z = this.mIsInSlidingKeyInput && key.isModifierKey();
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) {
            logD("onPress    : %s%s%s%s", key == null ? "none" : Constants.printableCode(key.getCode()),
                    z ? " ignoreModifier" : "", key == null ? "none" : key.isActive() ? "" : " disabled",
                    repeatCount > 0 ? " repeatCount=" + repeatCount : "");
        }
        if (z || key == null || !key.isActive()) {
            return false;
        }
        sKeyboardActionListener.onPressKey(key.getCode(), repeatCount, getActivePointerTrackerCount() == 1);
        boolean z2 = this.mIgnoreModifierKey;
        this.mIgnoreModifierKey = false;
        sKeyboardActionCallback.startTypingStateTimer(key);
        return z2;
    }

    private void callListenerOnCodeInput(Key key, int primaryCode, int x, int y, long eventTime, boolean isKeyRepeat) {
        boolean z2 = this.mIsInSlidingKeyInput && key.isModifierKey();
        boolean z3 = key.isAltCodeWhileTyping() && sKeyboardActionCallback.isTypingState();
        int iM6199U = z3 ? key.getLongPressActionCode() : primaryCode;
        if (z2) {
            return;
        }
        if (key.isActive() || z3) {
            sGestureStrokeParams.onCodeInput(iM6199U, eventTime);
            if (iM6199U == -4) {
                sKeyboardActionListener.onTextInput(key.getKeySpecOutputText(), eventTime);
            } else if (iM6199U != -21) {
                if (this.mKeyboard.allowsGestureForCode(iM6199U)) {
                    sKeyboardActionListener.onCodeInput(iM6199U, x, y, eventTime, isKeyRepeat);
                } else {
                    sKeyboardActionListener.onCodeInput(iM6199U, -1, -1, eventTime, isKeyRepeat);
                }
            }
        }
    }

    private void callListenerOnRelease(Key key, int primaryCode, boolean withSliding) {
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
            logD("onRelease  : %s%s%s%s", Constants.printableCode(primaryCode), withSliding ? " sliding" : "",
                    z2 ? " ignoreModifier" : "", key.isActive() ? "" : " disabled");
        }
        if (!z2 && key.isActive()) {
            sKeyboardActionListener.onReleaseKey(primaryCode, withSliding);
        }
    }

    private void callListenerOnCancelInput() {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onCancelInput");
        sKeyboardActionListener.onCancelInput();
    }

    private void setKeyDetectorInner(KeyDetector keyDetector) {
        Keyboard c0965eM6591a = keyDetector.getKeyboard();
        if (c0965eM6591a == null) {
            return;
        }
        if (keyDetector == this.mKeyDetector && c0965eM6591a == this.mKeyboard) {
            return;
        }
        this.mKeyDetector = keyDetector;
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
    public void onPhantomUpEvent(long eventTime) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onPhntEvent:", this.mLastX, this.mLastY, eventTime);
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        onUpEventInternal(this.mLastX, this.mLastY, eventTime);
        cancelTrackingForAction();
    }

    @Override
    public void cancelTrackingForAction() {
        if (isShowingMoreKeysPanel()) {
            return;
        }
        // Must not throw when the engine failed to load: the keyboard must still type.
        EngineTouch.cancel("PointerTracker.cancelTrackingForAction", this.mPointerId);
        this.mIsTrackingCanceled = true;
    }

    public Key getKey() {
        return this.mCurrentKey;
    }

    public Key getKeyOn(int x, int y) {
        return this.mKeyDetector.detectHitKey(x, y);
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

    private static boolean needsToSuppressKeyPreviewPopup(long eventTime) {
        if (sGestureEnabledProvider.isGestureInputEnabled()) {
            return sGestureStrokeParams.needsUpdateBatchInput(eventTime);
        }
        return false;
    }

    private void setPressedKeyGraphics(Key key, long eventTime) {
        if (key == null) {
            return;
        }
        boolean z = true;
        boolean z2 = key.isAltCodeWhileTyping() && sKeyboardActionCallback.isTypingState();
        if (!key.isActive() && !z2) {
            z = false;
        }
        if (z) {
            if (!key.isNoKeyPreview() && !sInGesture && !needsToSuppressKeyPreviewPopup(eventTime)) {
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

    public void getLastCoordinates(int[] outCoords) {
        CoordinateUtils.set(outCoords, this.mLastX, this.mLastY);
    }

    public long getDownTime() {
        return this.mDownTime;
    }

    public void getDownCoordinates(int[] outCoords) {
        CoordinateUtils.copy(outCoords, this.mDownCoordinates);
    }

    private Key onDownKey(int x, int y, long eventTime) {
        this.mDownTime = eventTime;
        CoordinateUtils.set(this.mDownCoordinates, x, y);
        return onMoveToNewKey(onMoveKey(x, y), x, y);
    }

    /** Not {@link Math#hypot}: that is far slower and its overflow safety is useless for pixel deltas; runs on every move sample. */
    private static int getDistance(int x1, int y1, int x2, int y2) {
        final int dx = x1 - x2;
        final int dy = y1 - y2;
        return (int) Math.sqrt((double) ((dx * dx) + (dy * dy)));
    }

    private Key onMoveKey(int x, int y) {
        this.mLastX = x;
        this.mLastY = y;
        return this.mKeyDetector.detectHitKey(x, y);
    }

    private Key onMoveToNewKey(Key key, int x, int y) {
        this.mCurrentKey = key;
        this.mKeyX = x;
        this.mKeyY = y;
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
    public void onUpdateBatchInput(long eventTime) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onUpdateBatchInput");
        sKeyboardActionListener.onUpdateBatchInput();
    }

    @Override
    public void startUpdateBatchInputTimer() {
        sTimerCallback.startUpdateBatchInputTimer(this);
    }

    @Override
    public void onEndBatchInput(long upEventTime) {
        sGestureStrokeParams.onEndBatchInput(upEventTime);
        sTimerCallback.cancelAllUpdateBatchInputTimers();
        if (this.mIsTrackingCanceled) {
            return;
        }
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onEndBatchInput");
        sKeyboardActionListener.onEndBatchInput(InputSource.SOFTWARE);
    }

    public void processMotionEvent(MotionEvent motionEvent, KeyDetector keyDetector) {
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
                onDownEvent(x, y, eventTime, keyDetector);
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

    private void onDownEvent(int x, int y, long eventTime, KeyDetector keyDetector) {
        int iM7594a;
        setKeyDetectorInner(keyDetector);
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onDownEvent:", x, y, eventTime);
        long j2 = eventTime - this.mLastUpTime;
        if (j2 < sParams.mTouchNoiseThresholdTime && (iM7594a = getDistance(x, y, this.mLastX, this.mLastY)) < sParams.mTouchNoiseThresholdDistance) {
            if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("onDownEvent: ignore potential noise: time=%d distance=%d", j2, iM7594a);
            cancelTrackingForAction();
            return;
        }
        Key keyM7645a = getKeyOn(x, y);
        if (keyM7645a != null && keyM7645a.isModifierKey()) {
            sPointerTrackerQueue.releaseAllPointers(eventTime);
        }
        sPointerTrackerQueue.add(this);
        onDownEventInternal(x, y, eventTime);
        if (sGestureEnabledProvider.isGestureInputEnabled()) {
            Keyboard c0965e = this.mKeyboard;
            this.mIsDetectingGesture = (c0965e == null || !c0965e.mId.isAlphabetKeyboard() || !SettingsManager.getInstance().getSettingsValues().editorCapabilities.shouldSupportGestureInput || keyM7645a == null || keyM7645a.isModifierKey()) ? false : true;
            if (this.mIsDetectingGesture) {
                // Only a contact that can become a swipe goes to the engine; any other start
                // would take the session from a finger that is swiping.
                if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchStart: pointerId=" + this.mPointerId + " x=" + x + " y=" + y + " time=" + eventTime + " isOnKey=" + keyM7645a);
                EngineTouch.start("PointerTracker.onDownEvent", this.mPointerId, x, y, eventTime);
                resetGestureStrokeTime();
                this.mGesturePathTracker.addDownEventPoint(x, y, eventTime, sGestureStrokeParams.getLastLetterTypingTime(), getActivePointerTrackerCount());
                this.mGestureStrokeRecognizer.onDownEvent(x, y, this.mGesturePathTracker.getGestureElapsedTime(eventTime));
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

    private void onDownEventInternal(int x, int y, long eventTime) {
        Key keyM7614b = onDownKey(x, y, eventTime);
        this.mIgnoreModifierKey = false;
        this.mIsTrackingCanceled = false;
        resetSlidingKeyInput();
        if (keyM7614b != null) {
            if (callListenerOnPressAndCheckKeyboardLayoutChange(keyM7614b, 0, eventTime)) {
                keyM7614b = onDownKey(x, y, eventTime);
            }
            startKeyRepeatIfRepeatable(keyM7614b);
            startMoreKeysPanelTimer(keyM7614b, eventTime);
            startLockFocusTimer();
            setPressedKeyGraphics(keyM7614b, eventTime);
        }
    }

    private void startSlidingKeyInput(Key key) {
        this.mIsInSlidingKeyInput = true;
    }

    private void resetSlidingKeyInput() {
        this.mIsInSlidingKeyInput = false;
        sDrawingProxy.dismissGestureTrail();
    }

    private void onGestureMoveEvent(int x, int y, long eventTime, boolean isMajorEvent, Key key) {
        if (this.mIsDetectingGesture) {
            if (!this.mGesturePathTracker.addMoveEventPoint(this.mInGesture, x, y, eventTime, isMajorEvent, this, this)) {
                cancelBatchInput();
                return;
            }
            this.mGestureStrokeRecognizer.onMoveEvent(x, y, this.mGesturePathTracker.getGestureElapsedTime(eventTime));
            if (isShowingMoreKeysPanel()) {
                return;
            }
            if (!sInGesture && key != null && Character.isLetter(key.getCode()) && this.mGesturePathTracker.tryStartBatchInput(this)) {
                setInGesture(true);
                this.mInGesture = true;
                sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime);
            }
            if (isInGesture()) {
                if (key != null) {
                    this.mGesturePathTracker.updateBatchInput(eventTime, this);
                }
                updateSlidingKeyInputPreview();
            }
        }
    }

    private void onMoveEvent(int x, int y, long eventTime, MotionEvent motionEvent) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onMoveEvent:", x, y, eventTime);
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
            this.mDrawingProxy.onMoveEvent(this.mDrawingProxy.translateX(x), this.mDrawingProxy.translateY(y), this.mPointerId, eventTime);
            onMoveKey(x, y);
            return;
        }
        onMoveEventInternal(x, y, eventTime, motionEvent);
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchMove: pointerId=" + this.mPointerId + " x=" + x + " y=" + y + " time=" + eventTime);
        EngineTouch.move("PointerTracker.onMoveEvent", this.mPointerId, x, y, eventTime);
    }

    private void startTrackingNewKey(Key key, int x, int y, long eventTime) {
        if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0, eventTime)) {
            key = onMoveKey(x, y);
        }
        onMoveToNewKey(key, x, y);
        if (this.mIsTrackingCanceled) {
            return;
        }
        startMoreKeysPanelTimer(key, eventTime);
        setPressedKeyGraphics(key, eventTime);
    }

    private void processSlidingKeyInput(Key key) {
        setReleasedKeyGraphics(key);
        callListenerOnRelease(key, key.getCode(), true);
        startSlidingKeyInput(key);
        sKeyboardActionCallback.cancelKeyTimers(this);
    }

    // Key selection by dragging a finger is always on (pinned by PointerTrackerCallbackStreamTest),
    // so AOSP's "cancel on slide" and multi-touch sliding arms are not here.
    private void onMoveToNewKeyInternal(Key key, int x, int y, long eventTime, Key oldKey) {
        if (this.mIsAllowedDraggingFinger) {
            processSlidingKeyInput(oldKey);
            startKeyRepeatIfRepeatable(key);
            startTrackingNewKey(key, x, y, eventTime);
        }
    }

    private void slideOutFromOldKey(Key key, int x, int y) {
        processSlidingKeyInput(key);
        onMoveToNewKey((Key) null, x, y);
    }

    public boolean isSlideboardDragging() {
        return this.mSlideboardActive;
    }

    private float getSlideboardDistanceX(MotionEvent motionEvent) {
        // Raw against raw: the start is a raw x, and the view-local x shifts with the keyboard's
        // translation once the slideboard is open, making a held key look like a long drag.
        return Math.abs(motionEvent.getRawX() - this.mSlideboardStartRawX);
    }

    private void onMoveEventInternal(int x, int y, long eventTime, MotionEvent motionEvent) {
        if (processSlideboardMove(x, y, eventTime, motionEvent)) {
            return;
        }
        Key key = this.mCurrentKey;
        Key keyM7631d = onMoveKey(x, y);
        if (sGestureEnabledProvider.isGestureInputEnabled()) {
            onGestureMoveEvent(x, y, eventTime, true, keyM7631d);
            if (sInGesture) {
                this.mCurrentKey = null;
                setReleasedKeyGraphics(key);
                return;
            }
        }
        if (keyM7631d != null) {
            if (key != null && isMajorEnoughMoveToBeOnNewKey(x, y, eventTime, keyM7631d)) {
                onMoveToNewKeyInternal(keyM7631d, x, y, eventTime, key);
            } else if (key == null) {
                startTrackingNewKey(keyM7631d, x, y, eventTime);
            }
        } else if (key != null && isMajorEnoughMoveToBeOnNewKey(x, y, eventTime, (Key) null)) {
            slideOutFromOldKey(key, x, y);
        }
    }

    private boolean processSlideboardMove(int x, int y, long eventTime, MotionEvent motionEvent) {
        long jNanoTime = System.nanoTime() - this.mDownEventNanoTime;
        // One snapshot per sample: a settings reload between reads would mix two SettingsValues.
        final SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (!sv.isSlideboardEnabled || sSuppressSlideboard || ((!this.mSlideboardActive && getSlideboardDistanceX(motionEvent) <= 150f) || jNanoTime <= 200000000)) {
            return false;
        }
        if (!this.mSlideboardActive && isSlideboardGesture(motionEvent)) {
            this.mSlideboardActive = true;
            this.mSlideboardBaseTranslation = sSlidingPanel.getTranslationX();
            setInGesture(true);
            this.mInGesture = true;
            sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime);
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

    @Override
    protected void onCancelEvent(int x, int y, long eventTime) {
        super.onCancelEvent(x, y, eventTime);
        if (this.mSlideboardActive) {
            sSlidingPanel.onSlidingFinished();
        }
    }

    private void onUpEvent(int x, int y, long eventTime) {
        if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) printTouchEvent("onUpEvent  :", x, y, eventTime);
        this.mLastUpTime = eventTime;
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        if (this.mSlideboardActive) {
            sSlidingPanel.onSlidingFinished();
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchCancel: pointerId=" + this.mPointerId + " isGesture=" + this.mSlideboardActive);
            EngineTouch.cancel("PointerTracker.onUpEvent/slideboard", this.mPointerId);
            cancelTracking();
        }
        // As in GestureEventProcessor.touchEnd, the touchEnd op raises the pending seq itself so
        // the suggestion request's clear() cannot wipe the trace before the gesture commit reads it.
        // Ordering is FIFO on the engine thread: post touchEnd before onUpEventInternal, which
        // posts the prediction request that consumes it. A lift that ends no swipe is only
        // cancelled, never decoded (a held key's jiggle would decode as a stray swipe).
        final boolean endsSwipe = sInGesture && !isShowingMoreKeysPanel();
        if (endsSwipe) postTouchEnd(x, y, eventTime);
        if (!sInGesture) {
            Key key = this.mCurrentKey;
            if (key != null && key.isModifierKey()) {
                sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime);
            } else {
                sPointerTrackerQueue.releaseAllPointersOlderThan(this, eventTime);
            }
        }
        onUpEventInternal(x, y, eventTime);
        if (!endsSwipe) EngineTouch.cancel("PointerTracker.onUpEvent/notSwipe", this.mPointerId);
        sPointerTrackerQueue.remove(this);
    }

    private void postTouchEnd(int x, int y, long eventTime) {
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "PointerTracker.touchEnd: pointerId=" + this.mPointerId + " x=" + x + " y=" + y + " time=" + eventTime);
        EngineTouch.end("PointerTracker.onUpEvent/end", this.mPointerId, x, y, eventTime);
    }

    private void onUpEventInternal(int x, int y, long eventTime) {
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
                int iMo6364c = this.mDrawingProxy.translateX(x);
                int iMo6367d = this.mDrawingProxy.translateY(y);
                EngineTouch.cancel("PointerTracker.onUpEventInternal/panel", this.mPointerId);
                this.mDrawingProxy.onUpEvent(iMo6364c, iMo6367d, this.mPointerId, eventTime);
            }
            dismissMoreKeysPanel();
            return;
        }
        if (sInGesture) {
            if (key != null) {
                callListenerOnRelease(key, key.getCode(), true);
            }
            if (isInGesture()) {
                this.mGesturePathTracker.endBatchInput(eventTime, this);
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
                EngineTouch.cancel("PointerTracker.onUpEventInternal/tap", this.mPointerId);
                detectAndSendKey(key, this.mKeyX, this.mKeyY, eventTime);
            }
        }
    }

    public void startMoreKeysPanel(MoreKeysPanel moreKeysPanel) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        setReleasedKeyGraphics(this.mCurrentKey);
        moreKeysPanel.onDownEvent(moreKeysPanel.translateX(this.mLastX), moreKeysPanel.translateY(this.mLastY), this.mPointerId, jUptimeMillis);
        this.mDrawingProxy = moreKeysPanel;
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

    @Override
    protected void onCancelEventInternal(long eventTime) {
        cancelBatchInput();
        cancelAllPointerTrackers();
        sPointerTrackerQueue.releaseAllPointers(eventTime);
        resetTrackingState();
    }

    private void resetTrackingState() {
        sKeyboardActionCallback.cancelKeyTimers(this);
        setReleasedKeyGraphics(this.mCurrentKey);
        resetSlidingKeyInput();
        dismissMoreKeysPanel();
    }

    private boolean isMajorEnoughMoveToBeOnNewKey(int x, int y, long eventTime, Key key) {
        Key key2 = this.mCurrentKey;
        if (key == key2) {
            return false;
        }
        if (key2 == null) {
            return true;
        }
        int iM6589a = this.mKeyDetector.getKeyHysteresisDistanceSquared(false);
        int iM6225b = key2.squaredDistanceToEdge(x, y);
        if (iM6225b >= iM6589a) {
            if (BuildConfig.DEBUG && Logger.isLoggable(TAG, Log.DEBUG)) logD("isMajorEnoughMoveToBeOnNewKey: %.2f key width from key edge", ((float) Math.sqrt(iM6225b)) / this.mKeyboard.mMostCommonKeyWidth);
            return true;
        }
        return false;
    }

    private void startMoreKeysPanelTimer(Key key, long eventTime) {
        int iM7634e;
        this.mKeyDownTime = eventTime;
        sKeyboardActionCallback.cancelLongPressShiftKeyTimer();
        if (sInGesture || key == null || !key.hasMoreKeys()) {
            return;
        }
        if (!(this.mIsInSlidingKeyInput && key.getMoreKeys() == null) && (iM7634e = getKeyLongPressTimeout(key.getCode())) > 0) {
            sKeyboardActionCallback.startLongPressTimer(this, iM7634e);
        }
    }

    /** @param code key code */
    public long getLongPressTimeoutTime(int code) {
        return this.mKeyDownTime + getKeyLongPressTimeout(code);
    }

    private int getKeyLongPressTimeout(int code) {
        if (code == -1) {
            return sParams.mLongPressShiftLockTimeout;
        }
        // One delay for every key, slideboard included.
        return SettingsManager.getInstance().getSettingsValues().keyLongpressTimeoutMs;
    }

    private void detectAndSendKey(Key key, int x, int y, long eventTime) {
        if (key == null) {
            callListenerOnCancelInput();
            return;
        }
        int iM6232c = key.getCode();
        callListenerOnCodeInput(key, iM6232c, x, y, eventTime, false);
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

    /**
     * @param code key code of the repeating key
     * @param repeatCount repeat count so far
     */
    public void onKeyRepeat(int code, int repeatCount) {
        setSuppressSlideboard(true);
        Key keyM7652o = getKey();
        if (keyM7652o == null || keyM7652o.getCode() != code) {
            this.mRepeatingKeyCode = -1;
            return;
        }
        this.mRepeatingKeyCode = code;
        this.mIsDetectingGesture = false;
        startKeyRepeatTimer(repeatCount + 1);
        callListenerOnPressAndCheckKeyboardLayoutChange(keyM7652o, repeatCount, SystemClock.uptimeMillis());
        callListenerOnCodeInput(keyM7652o, code, this.mKeyX, this.mKeyY, SystemClock.uptimeMillis(), true);
    }

    private int getKeyRepeatStartTimeout() {
        final SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (sv.isSlideboardEnabled) {
            // The slideboard's repeating keys start after the long-press delay.
            return sv.keyLongpressTimeoutMs;
        }
        return sParams.mKeyRepeatStartTimeout;
    }

    private void startKeyRepeatTimer(int repeatCount) {
        sKeyboardActionCallback.startKeyRepeatTimer(this, repeatCount, repeatCount == 1 ? getKeyRepeatStartTimeout() : sParams.mKeyRepeatInterval);
    }

    private void startLockFocusTimer() {
        this.mIsAllowedDraggingFinger = false;
        sKeyboardActionCallback.startLockFocusTimer(this, sParams.mLockFocusToFirstOnDownKeyDuration);
    }

    @Override
    protected void printTouchEvent(String title, int x, int y, long eventTime) {
        // Whole body under the compile-time gate: runs per sample, so skip the varargs boxing.
        if (!BuildConfig.DEBUG) {
            return;
        }
        Key key = this.mKeyDetector.detectHitKey(x, y);
        String printableCode = key == null ? "none" : Constants.printableCode(key.getCode());
        Log.d(TAG, String.format("[%d]%s%s %4d %4d %5d %s", this.mPointerId, this.mIsTrackingCanceled ? "-" : " ", title, x, y, eventTime, printableCode));
    }

    /** Debug line prefixed with the pointer id; callers guard with BuildConfig.DEBUG so release builds drop the call. */
    private void logD(String fmt, Object... args) {
        final Object[] withId = new Object[args.length + 1];
        withId[0] = this.mPointerId;
        System.arraycopy(args, 0, withId, 1, args.length);
        Log.d(TAG, String.format("[%d] " + fmt, withId));
    }

    private static void setInGesture(boolean inGesture) {
        sInGesture = inGesture;
    }

    private static void setInMoreKeysPanel(boolean inMoreKeysPanel) {
        sInMoreKeysPanel = inMoreKeysPanel;
    }

    private static void setSuppressSlideboard(boolean suppress) {
        sSuppressSlideboard = suppress;
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
