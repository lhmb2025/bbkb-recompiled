package dev.bbkb.ime.keyboard.internal;

import android.os.SystemClock;
import android.util.Log;

import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.BuildConfig;


public abstract class AbstractDrawingHandler implements PointerTrackerQueue.Element, GesturePathTracker.BatchInputListener, GestureStrokeAnalyzer.GestureStrokeTimeListener {

    private static final String TAG = "AbstractDrawingHandler";

    protected boolean mIsDetectingGesture = false;

    protected boolean mInGesture = false;

    protected boolean mIsTrackingCanceled;

    protected long mAccumulatedGestureTime;

    public final int mPointerId;

    public abstract void updateBatchInputTimer(long j);

    protected abstract void onCancelEventInternal(long j);

    public AbstractDrawingHandler(int i) {
        this.mPointerId = i;
    }

    public boolean isInGesture() {
        return this.mInGesture;
    }

    protected void onCancelEvent(int i, int i2, long j) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onCancelEvt:", i, i2, j);
        }
        onCancelEventInternal(j);
    }

    protected void resetGestureStrokeTime() {
        this.mAccumulatedGestureTime = 0L;
    }

    @Override
    public void addGestureStrokeTime(int i) {
        this.mAccumulatedGestureTime += i;
    }

    protected void printTouchEvent(String str, int i, int i2, long j) {
        if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d]%s %4d %4d %5d", Integer.valueOf(this.mPointerId), str, Integer.valueOf(i), Integer.valueOf(i2), Long.valueOf(j)));
    }

    public void cancelTracking() {
        onCancelEventInternal(SystemClock.uptimeMillis());
    }
}
