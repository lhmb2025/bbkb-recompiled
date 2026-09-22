package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;

import android.content.res.TypedArray;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import com.blackberry.nuanceshim.NuanceSDK;
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker;
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

    public static void init(TypedArray typedArray, TimerCallback interfaceC1046e, GestureKeyboardChecker aVar) {
        sGestureRecognitionParams = new GestureRecognitionParams(typedArray);
        sParams = new Params(typedArray);
        sTimerCallback = interfaceC1046e;
        sGestureKeyboardChecker = aVar;
    }

    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    public void updateBatchInputTimer(long j) {
        this.gesturePathTracker.onUpdateBatchInputTimer(j, this);
    }

    @Override // dev.bbkb.ime.keyboard.AbstractDrawingHandler
    protected void onCancelEventInternal(long j) {
        cancelBatchInput();
        sPointerTrackerQueue.releaseAllPointers(j);
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

    public static void setKeyboardActionListener(KeyboardActionListenerInterface interfaceC0976f) {
        sKeyboardActionListener = interfaceC0976f;
    }

    private static int getActivePointerTrackerCount() {
        return sPointerTrackerQueue.size();
    }

    public static GestureEventProcessor getGestureEventProcessor(int i) {
        ArrayList<GestureEventProcessor> arrayList = sTrackers;
        for (int size = arrayList.size(); size <= i; size++) {
            arrayList.add(new GestureEventProcessor(size));
        }
        return arrayList.get(i);
    }

    /**
     * CKB (capacitive physical keypad) stroke geometry, in the engine's own sensor coordinate
     * frame — NOT screen pixels and NOT the VKB frame. Every CKB stroke threshold is derived
     * from these two numbers in {@link GestureStrokeAnalyzer#setKeyboardGeometry}: the fast-move
     * speed threshold, both dynamic distance thresholds, the sampling minimum distance, the
     * recognition speed threshold, and the min/max Y band that decides whether a batch is
     * cancelled. They were baked into the original app as bare literals with no device
     * qualifier; they do NOT track the per-device {@code <ckb-y-warp>} table used by
     * {@link #warpY}, so a device whose warp table changes needs these revisited too.
     */
    private static final int CKB_KEY_WIDTH = 144;
    private static final int CKB_BOARD_HEIGHT = 610;

    private GestureEventProcessor(int i) {
        super(i);
        this.gesturePathTracker = new GesturePathTracker(i, sGestureRecognitionParams);
        this.gesturePathTracker.setKeyboardGeometry(CKB_KEY_WIDTH, CKB_BOARD_HEIGHT);
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
     * debug.et9.ckbyscale (per-mille): scales the Y fed to the ENGINE only (stroke detector
     * stays raw). 0/unset = off. Cached at first use — set the prop, then force-stop.
     * Stock KEY2 evidently delivered capacitive Y in the flat KDB's 324-space; this ROM
     * delivers 0..450, so 720 (=324/450) + debug.et9.kdbvariant=default reproduces the
     * original shipping stack for A/B. See remediation log §8.
     */
    private static int sCkbYScalePerMille = -1;

    /* RETAIL piecewise sensor->engine Y warp (§8.7.12): breakpoints from the device config's
     * <ckb-y-warp> ("s0:a0,s1:a1,..." ascending). Each measured sensor band maps exactly onto
     * one authored row of the flat KDB, reproducing the stock stack the blob's swipe decoder is
     * tuned for (S5 evidence, log §8.6). Identity when unset. Applies to the ENGINE feed only —
     * the stroke detector stays in raw sensor space. */
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
        // Debug override first (experiments; per-mille linear; cached; debug builds only) …
        if (BuildConfig.DEBUG) {
            if (sCkbYScalePerMille < 0) {
                int v = 0;
                try {
                    // UNVERIFIED ON DEVICE: android.os.SystemProperties is greylist-max-o, so on
                    // targetSdk 36 the reflective lookup inside SystemProps is blocked outright on
                    // many builds. SystemProps then returns the default and the scale silently
                    // stays at 0, which makes the A/B knob report "off" even when the prop is set.
                    // If debug.et9.ckbyscale stops taking effect on the KEY2, this is why -- move
                    // it to a debug SharedPreferences key via SettingsManager rather than
                    // debugging the prop.
                    String s = SystemProps.get("debug.et9.ckbyscale");
                    if (s != null && !s.isEmpty()) v = Integer.parseInt(s.trim());
                } catch (Throwable ignored) { }
                sCkbYScalePerMille = v;
            }
            if (sCkbYScalePerMille > 0) return (y * sCkbYScalePerMille) / 1000;
        }
        // … else the RETAIL piecewise warp from the device config (all build types).
        return warpY(y);
    }

    public static void updateGestureHandlingState(boolean z) {
        if (!z) {
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
     * Updates the timestamp of the last key event.
     * This MUST be called after dispatching motion events from the capacitive keyboard
     * so that noise filtering in onDownEvent does not incorrectly reject legitimate
     * gesture input based on stale hardware key timestamps.
     *
     * Corresponds to original app: invoke-static {}, Ldev/bbkb/ime/keyboard/a;->q()V
     */
    public static void updateLastKeyTime(long j) {
        sLastKeyEventTime = j;
    }

    private void onDownEvent(int i, int i2, long j) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onDownEvent:", i, i2, j);
        }
        long deltaSinceLastKey = j - sLastKeyEventTime;
        boolean noiseFiltered = deltaSinceLastKey < sParams.mTouchNoiseThresholdTime;
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: pointer=" + this.mPointerId + " x=" + i + " y=" + i2 + " t=" + j + " deltaSinceLastKey=" + deltaSinceLastKey + " noiseThreshold=" + sParams.mTouchNoiseThresholdTime + " filteredAsNoise=" + noiseFiltered);
        if (noiseFiltered) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: BLOCKED as physical-key noise (cancelTrackingForAction called)");
            cancelTrackingForAction();
            return;
        }
        sPointerTrackerQueue.add(this);
        boolean mkvC = sGestureKeyboardChecker.isGestureKeyboard();
        boolean shouldSupport = SettingsManager.getInstance().getSettingsValues().editorCapabilities.shouldSupportGestureInput;
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mkv.isGestureKeyboard=" + mkvC + " shouldSupportGestureInput=" + shouldSupport);
        if (mkvC) {
            this.mIsTrackingCanceled = false;
            this.mIsDetectingGesture = shouldSupport;
            if (this.mIsDetectingGesture) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mIsDetectingGesture=true → initializing BatchInputTracker (gesture mode ON)");
                resetGestureStrokeTime();
                this.gesturePathTracker.addDownEventPoint(i, i2, j, sLastKeyEventTime, getActivePointerTrackerCount());
            } else {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mIsDetectingGesture=FALSE → BatchInputTracker NOT initialized (no gesture word will be produced)");
            }
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: CALLING NuanceSDK.touchStart pointer=" + this.mPointerId + " x=" + i + " y=" + i2 + " time=" + j + " mIsDetectingGesture=" + this.mIsDetectingGesture + " mInGesture=" + this.mInGesture);
            // FIX-L8: null when the engine failed to load. The keyboard must still type.
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("GestureEventProcessor.onDownEvent");
            if (sdk != null) sdk.touchStart(this.mPointerId, i, scaleEngineY(i2), j);
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onDownEvent: mkv.isGestureKeyboard=false → entire gesture path SKIPPED, no NuanceSDK.touchStart, no batch tracker");
        }
    }

    private void onGestureMoveEvent(int i, int i2, long j, boolean z) {
        if (this.mIsDetectingGesture) {
            if (!this.gesturePathTracker.addMoveEventPoint(this.mInGesture, i, i2, j, z, this, this)) {
                cancelBatchInput();
                return;
            }
            if (!sInGesture && this.gesturePathTracker.tryStartBatchInput(this)) {
                setInGesture(true);
                this.mInGesture = true;
                sPointerTrackerQueue.releaseAllPointersExcept(this, j);
            }
            if (isInGesture()) {
                this.gesturePathTracker.updateBatchInput(j, this);
            }
        }
    }

    private void onMoveEvent(int i, int i2, long j, MotionEvent motionEvent) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onMoveEvent:", i, i2, j);
        }
        if (this.mIsTrackingCanceled) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onMoveEvent (onMoveEvent): BLOCKED — mIsTrackingCanceled=true for pointer=" + this.mPointerId);
            return;
        }
        if (!sGestureKeyboardChecker.isGestureKeyboard() || motionEvent == null) {
            // Non-gesture keyboard (symbol pages, number pad, UIM): onDownEvent dispatched no
            // touchStart, so a touchMove here is a stray sample with no stroke behind it. That is
            // the same engine-buffer pollution route already closed for touchEnd in onUpEvent.
            return;
        }
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onMoveEvent (onMoveEvent): pointer=" + this.mPointerId + " x=" + i + " y=" + i2 + " time=" + j + " calling NuanceSDK.touchMove");
        final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("GestureEventProcessor.onMoveEvent");
        if (sdk != null) sdk.touchMove(this.mPointerId, i, scaleEngineY(i2), j);
        int iFindPointerIndex = motionEvent.findPointerIndex(this.mPointerId);
        int historySize = motionEvent.getHistorySize();
        for (int i3 = 0; i3 < historySize; i3++) {
            onGestureMoveEvent((int) motionEvent.getHistoricalX(iFindPointerIndex, i3), (int) motionEvent.getHistoricalY(iFindPointerIndex, i3), motionEvent.getHistoricalEventTime(i3), false);
        }
        onGestureMoveEvent(i, i2, j, true);
    }

    private void onUpEvent(int i, int i2, long j) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onUpEvent  :", i, i2, j);
        }
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: pointer=" + this.mPointerId + " x=" + i + " y=" + i2 + " mIsDetectingGesture=" + this.mIsDetectingGesture + " gestureActive(sInGesture)=" + sInGesture + " inBatchInput=" + isInGesture());
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        boolean gestureAccepted = false;
        if (!sInGesture) {
            // Not an active gesture (tap or stray capacitive edge-touch): cancel and do
            // NOT send touchEnd. Sending touchEnd here registered a 1-point "gesture"
            // that polluted the engine buffer (the m48=1 records at x=0/1080, y>grid).
            // Tap characters are handled via the normal key path, not the gesture path.
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: sInGesture=false → touchCancel only (skip touchEnd)");
            sPointerTrackerQueue.releaseAllPointersOlderThan(this, j);
            final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("GestureEventProcessor.onUpEvent/cancel");
            if (sdk != null) sdk.touchCancel(this.mPointerId);
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: calling NuanceSDK.touchEnd");
            // Deposit the gesture's native state and raise the pending flag atomically w.r.t.
            // the suggestion worker's guarded clear() (NuanceSDKManager.getGestureLock).
            synchronized (NuanceSDKManager.getGestureLock()) {
                final NuanceSDK sdk = NuanceSDKManager.sdkOrWarn("GestureEventProcessor.onUpEvent/end");
                // No engine means no deposit and nothing to commit: gestureAccepted stays false,
                // which is the same outcome the engine reports for a gesture it did not take.
                gestureAccepted = sdk != null && sdk.touchEnd(this.mPointerId, i, scaleEngineY(i2), j);
                if (gestureAccepted) {
                    NuanceSDKManager.noteGestureDeposit();
                }
            }
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.onUpEvent: touchEnd returned gestureAccepted=" + gestureAccepted);
        }
        finishBatchInput(j);
        sPointerTrackerQueue.remove(this);
    }

    private void finishBatchInput(long j) {
        this.mIsDetectingGesture = false;
        boolean inBatch = isInGesture();
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyDetector.finishBatchInput: inBatchInput=" + inBatch + (inBatch ? " → finalizing batch via endBatchInput (triggers onEndBatchInput → requestPredictions)" : " → no batch finalization (gesture never started a word)"));
        if (inBatch) {
            this.gesturePathTracker.endBatchInput(j, this);
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

    @Override
    public void onUpdateBatchInput(long j) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d] onUpdateBatchInput", Integer.valueOf(this.mPointerId)));
        }
        sKeyboardActionListener.onUpdateBatchInput();
    }

    @Override
    public void startUpdateBatchInputTimer() {
        sTimerCallback.startUpdateBatchInputTimer(this);
    }

    @Override
    public void onEndBatchInput(long j) {
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

    @Override
    public void onPhantomUpEvent(long j) {
        sTimerCallback.cancelUpdateBatchInputTimer(this);
        finishBatchInput(j);
        cancelTrackingForAction();
    }

    @Override
    public void cancelTrackingForAction() {
        this.mIsTrackingCanceled = true;
    }

    private static void setInGesture(boolean z) {
        sInGesture = z;
    }

    public static boolean isGestureHandlingActive() {
        return sGestureHandlingActive;
    }

    public static void setGestureHandlingActive(boolean z) {
        sGestureHandlingActive = z;
    }
}
