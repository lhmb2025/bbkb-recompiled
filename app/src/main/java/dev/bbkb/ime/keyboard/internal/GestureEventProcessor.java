package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;

import android.content.res.TypedArray;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
import dev.bbkb.ime.core.device.touch.TouchKeypadGeometry;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.SystemProps;

import java.util.ArrayList;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.shared.InputPathDebug;



public final class GestureEventProcessor extends AbstractDrawingHandler {

    private static final String TAG = "GestureEventProcessor";

    private static GestureKeyboardChecker sGestureKeyboardChecker;

    private static GestureRecognitionParams sGestureRecognitionParams;

    private static TimerCallback sTimerCallback;

    private static Params sParams;

    private static long sLastKeyEventTime;

    private static boolean sGestureHandlingActive;

    private final GesturePathTracker gesturePathTracker;

    private static KeyboardActionListenerInterface sKeyboardActionListener = KeyboardActionListenerInterface.EMPTY;

    private static boolean sInGesture = false;

    private static final PointerTrackerQueue sPointerTrackerQueue = new PointerTrackerQueue();

    private static final ArrayList<GestureEventProcessor> sTrackers = new ArrayList<>();

    
    public interface GestureKeyboardChecker {
        boolean isGestureKeyboard();
    }

    @Override
    public boolean isModifier() {
        return false;
    }

    @Override
    public boolean isOnShiftKey() {
        return false;
    }

    @Override
    public void dispatchShiftKeyTap() {
        // Capacitive strokes have no shift key.
    }

    @Override
    public boolean isInSlidingKeyInput() {
        return false;
    }

    
    static final class Params {

        public final int mTouchNoiseThresholdTime;

        public Params(TypedArray typedArray) {
            this.mTouchNoiseThresholdTime = typedArray.getInt(R.styleable.MainKeyboardView_ckbTouchNoiseThresholdTime, 0);
        }
    }

    /**
     * @param timerCallback the batch-input update timer
     * @param gestureKeyboardChecker tells whether the current keyboard takes gestures
     */
    public static void init(TypedArray typedArray, TimerCallback timerCallback, GestureKeyboardChecker gestureKeyboardChecker) {
        sGestureRecognitionParams = new GestureRecognitionParams(typedArray);
        sParams = new Params(typedArray);
        sTimerCallback = timerCallback;
        sGestureKeyboardChecker = gestureKeyboardChecker;
    }

    /** Re-installs the checker after {@link #releaseGestureKeyboardChecker} (view re-attached). */
    public static void setGestureKeyboardChecker(GestureKeyboardChecker checker) {
        sGestureKeyboardChecker = checker;
    }

    /**
     * Drops the checker if it is still {@code checker}. It is the MainKeyboardView, so keeping it
     * would leak the destroyed service and its views; a newer view's checker is left alone.
     */
    public static void releaseGestureKeyboardChecker(GestureKeyboardChecker checker) {
        if (sGestureKeyboardChecker == checker) {
            sGestureKeyboardChecker = null;
        }
    }

    /** No keyboard view attached means no gesture keyboard. */
    private static boolean isGestureKeyboard() {
        GestureKeyboardChecker checker = sGestureKeyboardChecker;
        return checker != null && checker.isGestureKeyboard();
    }

    /** @param syntheticMoveEventTime the event time */
    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    public void updateBatchInputTimer(long syntheticMoveEventTime) {
        this.gesturePathTracker.onUpdateBatchInputTimer(syntheticMoveEventTime, this);
    }

    /** @param eventTime the event time */
    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    protected void onCancelEventInternal(long eventTime) {
        cancelBatchInput();
        sPointerTrackerQueue.releaseAllPointers(eventTime);
    }

    private void cancelBatchInput() {
        cancelAllPointerTrackers();
        this.mIsDetectingGesture = false;
        resetGestureStrokeTime();
        if (sInGesture) {
            setInGesture(false);
            this.mInGesture = false;
            if (Logger.isLoggable(TAG, Log.DEBUG)) {
                if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d] onCancelBatchInput", Integer.valueOf(this.mPointerId)));
            }
            sKeyboardActionListener.onCancelBatchInput();
            setGestureHandlingActive(false);
        }
    }

    public static void cancelAllPointerTrackers() {
        sPointerTrackerQueue.cancelAllPointerTrackers();
    }

    /** @param keyboardActionListener receives the batch-input callbacks */
    public static void setKeyboardActionListener(KeyboardActionListenerInterface keyboardActionListener) {
        sKeyboardActionListener = keyboardActionListener;
    }

    private static int getActivePointerTrackerCount() {
        return sPointerTrackerQueue.size();
    }

    /** @param pointerId the pointer id; processors are created on first use */
    public static GestureEventProcessor getGestureEventProcessor(int pointerId) {
        ArrayList<GestureEventProcessor> arrayList = sTrackers;
        for (int size = arrayList.size(); size <= pointerId; size++) {
            arrayList.add(new GestureEventProcessor(size));
        }
        return arrayList.get(pointerId);
    }

    /**
     * Capacitive keypad stroke geometry in the engine's sensor frame (not screen or VKB pixels):
     * every stroke threshold in {@link GestureStrokeAnalyzer#setKeyboardGeometry} derives from it.
     * 144 / 610 on the KEY2's pad. Independent of the {@code <ckb-y-warp>} table, so a changed warp
     * needs these revisited. Set by {@code DeviceProfile}.
     */
    private static volatile int sStrokeKeyWidth = TouchKeypadGeometry.KEY2_STROKE_KEY_WIDTH;
    private static volatile int sStrokeBoardHeight = TouchKeypadGeometry.KEY2_STROKE_BOARD_HEIGHT;

    /** @param pointerId the pointer id */
    private GestureEventProcessor(int pointerId) {
        super(pointerId);
        this.gesturePathTracker = new GesturePathTracker(pointerId, sGestureRecognitionParams);
        this.gesturePathTracker.setKeyboardGeometry(sStrokeKeyWidth, sStrokeBoardHeight);
    }

    /** Adopts the touch keypad's frame for new and existing processors; no-op when unchanged. */
    public static void setKeypadGeometry(TouchKeypadGeometry geometry) {
        if (geometry == null) return;
        final int keyWidth = geometry.strokeKeyWidth();
        final int boardHeight = geometry.strokeBoardHeight();
        if (keyWidth == sStrokeKeyWidth && boardHeight == sStrokeBoardHeight) return;
        // Statics first, so a processor created while this runs picks the new numbers up itself.
        sStrokeKeyWidth = keyWidth;
        sStrokeBoardHeight = boardHeight;
        // By index, not by iterator: this can run on the profile's loader thread while the main
        // thread adds a processor, and ArrayList.get does not throw on that.
        final ArrayList<GestureEventProcessor> trackers = sTrackers;
        for (int i = 0; i < trackers.size(); i++) {
            trackers.get(i).gesturePathTracker.setKeyboardGeometry(keyWidth, boardHeight);
        }
        android.util.Log.i("XT9KDB", "ckb stroke geometry " + keyWidth + "/" + boardHeight
                + " from " + geometry);
    }

    /** The stroke analyser's current key width (144 on the KEY2's pad). */
    public static int strokeKeyWidth() {
        return sStrokeKeyWidth;
    }

    /** The stroke analyser's current board height (610 on the KEY2's pad). */
    public static int strokeBoardHeight() {
        return sStrokeBoardHeight;
    }

    public void processMotionEvent(MotionEvent motionEvent) {
        int actionMasked = motionEvent.getActionMasked();
        long eventTime = motionEvent.getEventTime();
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.processMotionEvent: action=" + actionMasked + " sInGesture=" + sInGesture + " isInGesture=" + isInGesture() + " pointerCount=" + motionEvent.getPointerCount());
        if (actionMasked == 2) {
            int pointerCount = motionEvent.getPointerCount();
            for (int i = 0; i < pointerCount; i++) {
                GestureEventProcessor c0950aM6378a = getGestureEventProcessor(motionEvent.getPointerId(i));
                if (!sInGesture || c0950aM6378a.isInGesture()) {
                    c0950aM6378a.onMoveEvent((int) motionEvent.getX(i), (int) motionEvent.getY(i), eventTime, motionEvent);
                }
            }
        }
        if (sInGesture && !isInGesture()) {
            cancelTrackingForAction();
            return;
        }
        int actionIndex = motionEvent.getActionIndex();
        int x = (int) motionEvent.getX(actionIndex);
        int y = (int) motionEvent.getY(actionIndex);
        switch (actionMasked) {
            case 0:
            case 5:
                onDownEvent(x, y, eventTime);
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

    public static void onKeyDown(KeyEvent keyEvent) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "onKeyDown: " + keyEvent.getCharacters() + " time: " + keyEvent.getEventTime());
        }
        long downTime = PhysicalKeyboardStateTracker.isShiftKey(keyEvent.getKeyCode()) ? keyEvent.getDownTime() : keyEvent.getEventTime();
        if (sLastKeyEventTime != downTime) {
            sLastKeyEventTime = downTime;
            cancelAllTracking();
        }
    }

    public static void onKeyUp(KeyEvent keyEvent) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "onKeyUp: " + keyEvent.getCharacters() + " time: " + keyEvent.getEventTime());
        }
        sLastKeyEventTime = keyEvent.getEventTime();
        cancelAllTracking();
    }

    /**
     * Debug builds: debug.et9.ckbyscale (per-mille) scales the Y fed to the engine only; 0 or unset
     * is off. Cached at first use (set the prop, then force-stop). This ROM delivers Y in 0..450
     * while the flat KDB is 324 tall, so 720 with debug.et9.kdbvariant=default maps one to the other.
     */
    private static int sCkbYScalePerMille = -1;

    /* Piecewise sensor-to-engine Y warp from the device config's <ckb-y-warp> ("s0:a0,s1:a1,..."
     * ascending sensor values). Each sensor band maps onto one authored row of the flat KDB, the
     * space the blob's swipe decoder is tuned for. Identity when unset; engine feed only, the
     * stroke detector stays in raw sensor space. */
    private static volatile int[] sWarpSensor;   // ascending sensor breakpoints
    private static volatile int[] sWarpEngine;   // matching engine-space values

    public static void setEngineYWarp(String spec) {
        int[] sArr = null, eArr = null;
        if (spec != null && !spec.trim().isEmpty()) {
            try {
                String[] pts = spec.trim().split(",");
                sArr = new int[pts.length];
                eArr = new int[pts.length];
                for (int i = 0; i < pts.length; i++) {
                    String[] se = pts[i].split(":");
                    sArr[i] = Integer.parseInt(se[0].trim());
                    eArr[i] = Integer.parseInt(se[1].trim());
                    if (i > 0 && sArr[i] <= sArr[i - 1]) throw new IllegalArgumentException("non-ascending");
                }
            } catch (Throwable t) {
                android.util.Log.w("XT9KDB", "bad ckb-y-warp spec '" + spec + "' — warp disabled", t);
                sArr = null; eArr = null;
            }
        }
        sWarpSensor = sArr; sWarpEngine = eArr;
        android.util.Log.i("XT9KDB", "ckb-y-warp " + (sArr != null ? "set: " + spec : "cleared"));
    }

    private static int warpY(int y) {
        int[] s = sWarpSensor, e = sWarpEngine;
        if (s == null || e == null || s.length < 2) return y;
        if (y <= s[0]) return e[0] + (y - s[0]);                        // below range: translate
        for (int i = 1; i < s.length; i++) {
            if (y <= s[i]) {
                return e[i - 1] + (int) ((long) (y - s[i - 1]) * (e[i] - e[i - 1]) / (s[i] - s[i - 1]));
            }
        }
        return e[e.length - 1] + (y - s[s.length - 1]);                 // above range: translate
    }

    private static int scaleEngineY(int y) {
        // The debug override wins over the device warp.
        if (BuildConfig.DEBUG) {
            if (sCkbYScalePerMille < 0) {
                int v = 0;
                try {
                    // SystemProperties is greylist-max-o: on targetSdk 36 the reflective read may be
                    // blocked and silently return the default, leaving the scale off.
                    String s = SystemProps.get("debug.et9.ckbyscale");
                    if (s != null && !s.isEmpty()) v = Integer.parseInt(s.trim());
                } catch (Throwable ignored) { /* Unreadable prop: scale stays off. */ }
                sCkbYScalePerMille = v;
            }
            if (sCkbYScalePerMille > 0) return (y * sCkbYScalePerMille) / 1000;
        }
        return warpY(y);
    }

    /** @param metaKeyActive false ends gesture handling; true resumes it only while a gesture is live */
    public static void updateGestureHandlingState(boolean metaKeyActive) {
        if (!metaKeyActive) {
            setGestureHandlingActive(false);
        } else if (sInGesture) {
            setGestureHandlingActive(true);
        }
    }

    public static void cancelAllTracking() {
        for (int i = 0; i < sTrackers.size(); i++) {
            sTrackers.get(i).cancelTracking();
        }
    }

    /**
     * Must be called after dispatching capacitive motion events, or onDownEvent's noise filter
     * rejects real gestures against a stale key timestamp.
     *
     * @param eventTime the event time
     */
    public static void updateLastKeyTime(long eventTime) {
        sLastKeyEventTime = eventTime;
    }

    /**
     * @param x x in sensor space
     * @param y y in sensor space
     * @param eventTime the event time
     */
    private void onDownEvent(int x, int y, long eventTime) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onDownEvent:", x, y, eventTime);
        }
        long deltaSinceLastKey = eventTime - sLastKeyEventTime;
        boolean noiseFiltered = deltaSinceLastKey < sParams.mTouchNoiseThresholdTime;
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: pointer=" + this.mPointerId + " x=" + x + " y=" + y + " t=" + eventTime + " deltaSinceLastKey=" + deltaSinceLastKey + " noiseThreshold=" + sParams.mTouchNoiseThresholdTime + " filteredAsNoise=" + noiseFiltered);
        if (noiseFiltered) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: BLOCKED as physical-key noise (cancelTrackingForAction called)");
            cancelTrackingForAction();
            return;
        }
        sPointerTrackerQueue.add(this);
        boolean mkvC = isGestureKeyboard();
        boolean shouldSupport = SettingsManager.getInstance().getSettingsValues().editorCapabilities.shouldSupportGestureInput;
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mkv.isGestureKeyboard=" + mkvC + " shouldSupportGestureInput=" + shouldSupport);
        if (mkvC) {
            this.mIsTrackingCanceled = false;
            this.mIsDetectingGesture = shouldSupport;
            if (this.mIsDetectingGesture) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mIsDetectingGesture=true → initializing BatchInputTracker (gesture mode ON)");
                resetGestureStrokeTime();
                this.gesturePathTracker.addDownEventPoint(x, y, eventTime, sLastKeyEventTime, getActivePointerTrackerCount());
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: CALLING NuanceSDK.touchStart pointer=" + this.mPointerId + " x=" + x + " y=" + y + " time=" + eventTime + " mIsDetectingGesture=" + this.mIsDetectingGesture + " mInGesture=" + this.mInGesture);
                EngineTouch.start("GestureEventProcessor.onDownEvent", this.mPointerId, x, scaleEngineY(y), eventTime);
            } else {
                // The field takes no swipes: the engine gets nothing for this contact.
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mIsDetectingGesture=FALSE → BatchInputTracker NOT initialized, no NuanceSDK.touchStart (no gesture word will be produced)");
            }
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mkv.isGestureKeyboard=false → entire gesture path SKIPPED, no NuanceSDK.touchStart, no batch tracker");
        }
    }

    /**
     * @param x x, {@code y} y, {@code eventTime} event time
     * @param isMajorEvent true for the event's current sample, false for a historical one
     */
    private void onGestureMoveEvent(int x, int y, long eventTime, boolean isMajorEvent) {
        if (this.mIsDetectingGesture) {
            if (!this.gesturePathTracker.addMoveEventPoint(this.mInGesture, x, y, eventTime, isMajorEvent, this, this)) {
                cancelBatchInput();
                return;
            }
            if (!sInGesture && this.gesturePathTracker.tryStartBatchInput(this)) {
                setInGesture(true);
                this.mInGesture = true;
                sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime);
            }
            if (isInGesture()) {
                this.gesturePathTracker.updateBatchInput(eventTime, this);
            }
        }
    }

    /** @param x x, {@code y} y, {@code eventTime} event time */
    private void onMoveEvent(int x, int y, long eventTime, MotionEvent motionEvent) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onMoveEvent:", x, y, eventTime);
        }
        if (this.mIsTrackingCanceled) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onMoveEvent (onMoveEvent): BLOCKED — mIsTrackingCanceled=true for pointer=" + this.mPointerId);
            return;
        }
        if (!isGestureKeyboard() || motionEvent == null) {
            // Non-gesture keyboard: no touchStart was sent, so a touchMove would pollute the
            // engine buffer with a stroke-less sample.
            return;
        }
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onMoveEvent (onMoveEvent): pointer=" + this.mPointerId + " x=" + x + " y=" + y + " time=" + eventTime + " calling NuanceSDK.touchMove");
        EngineTouch.move("GestureEventProcessor.onMoveEvent", this.mPointerId, x, scaleEngineY(y), eventTime);
        int iFindPointerIndex = motionEvent.findPointerIndex(this.mPointerId);
        int historySize = motionEvent.getHistorySize();
        for (int i3 = 0; i3 < historySize; i3++) {
            onGestureMoveEvent((int) motionEvent.getHistoricalX(iFindPointerIndex, i3), (int) motionEvent.getHistoricalY(iFindPointerIndex, i3), motionEvent.getHistoricalEventTime(i3), false);
        }
        onGestureMoveEvent(x, y, eventTime, true);
    }

    /** @param x x, {@code y} y, {@code eventTime} event time */
    private void onUpEvent(int x, int y, long eventTime) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onUpEvent  :", x, y, eventTime);
        }
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: pointer=" + this.mPointerId + " x=" + x + " y=" + y + " mIsDetectingGesture=" + this.mIsDetectingGesture + " gestureActive(sInGesture)=" + sInGesture + " inBatchInput=" + isInGesture());
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        if (!sInGesture) {
            // A tap or stray edge touch: cancel, never touchEnd, which would register a 1-point
            // gesture in the engine buffer. Taps type through the key path.
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: sInGesture=false → touchCancel only (skip touchEnd)");
            sPointerTrackerQueue.releaseAllPointersOlderThan(this, eventTime);
            EngineTouch.cancel("GestureEventProcessor.onUpEvent/cancel", this.mPointerId);
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: calling NuanceSDK.touchEnd");
            // Deposits the gesture state on the engine thread before finishBatchInput posts the
            // prediction request, so FIFO order runs the request's guarded clear() after it.
            EngineTouch.end("GestureEventProcessor.onUpEvent/end", this.mPointerId, x, scaleEngineY(y), eventTime);
        }
        finishBatchInput(eventTime);
        sPointerTrackerQueue.remove(this);
    }

    /** @param upEventTime the event time */
    private void finishBatchInput(long upEventTime) {
        this.mIsDetectingGesture = false;
        boolean inBatch = isInGesture();
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.finishBatchInput: inBatchInput=" + inBatch + (inBatch ? " → finalizing batch via endBatchInput (triggers onEndBatchInput → requestPredictions)" : " → no batch finalization (gesture never started a word)"));
        if (inBatch) {
            this.gesturePathTracker.endBatchInput(upEventTime, this);
            setInGesture(false);
            this.mInGesture = false;
        }
    }

    @Override
    public void onStartBatchInput() {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d] onStartBatchInput", Integer.valueOf(this.mPointerId)));
        }
        sKeyboardActionListener.onStartBatchInput();
    }

    /** @param eventTime the event time */
    @Override
    public void onUpdateBatchInput(long eventTime) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d] onUpdateBatchInput", Integer.valueOf(this.mPointerId)));
        }
        sKeyboardActionListener.onUpdateBatchInput();
    }

    @Override
    public void startUpdateBatchInputTimer() {
        sTimerCallback.startUpdateBatchInputTimer(this);
    }

    /** @param upEventTime the event time */
    @Override
    public void onEndBatchInput(long upEventTime) {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onEndBatchInput: pointer=" + this.mPointerId + " cancelled(mIsTrackingCanceled)=" + this.mIsTrackingCanceled);
        sTimerCallback.cancelAllUpdateBatchInputTimers();
        if (this.mIsTrackingCanceled) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onEndBatchInput: cancelled → NOT calling onEndBatchInput");
            return;
        }
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d] onEndBatchInput", Integer.valueOf(this.mPointerId)));
        }
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onEndBatchInput: calling requestPredictions(HARDWARE)");
        sKeyboardActionListener.onEndBatchInput(InputSource.HARDWARE);
    }

    /** @param eventTime the event time */
    @Override
    public void onPhantomUpEvent(long eventTime) {
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        finishBatchInput(eventTime);
        cancelTrackingForAction();
    }

    @Override
    public void cancelTrackingForAction() {
        this.mIsTrackingCanceled = true;
    }

    /** @param inGesture whether a gesture is in progress */
    private static void setInGesture(boolean inGesture) {
        sInGesture = inGesture;
    }

    public static boolean isGestureHandlingActive() {
        return sGestureHandlingActive;
    }

    /** @param active whether gesture handling is active */
    public static void setGestureHandlingActive(boolean active) {
        sGestureHandlingActive = active;
    }
}
