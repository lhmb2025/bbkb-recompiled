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

    public abstract void updateBatchInputTimer(long syntheticMoveEventTime);

    protected abstract void onCancelEventInternal(long eventTime);

    public AbstractDrawingHandler(int pointerId) {
        this.mPointerId = pointerId;
    }

    public boolean isInGesture() {
        return this.mInGesture;
    }

    protected void onCancelEvent(int x, int y, long eventTime) {
        if (Logger.isLoggable(TAG, Log.DEBUG)) {
            printTouchEvent("onCancelEvt:", x, y, eventTime);
        }
        onCancelEventInternal(eventTime);
    }

    protected void resetGestureStrokeTime() {
        this.mAccumulatedGestureTime = 0L;
    }

    @Override
    public void addGestureStrokeTime(int distance) {
        this.mAccumulatedGestureTime += distance;
    }

    protected void printTouchEvent(String title, int x, int y, long eventTime) {
        if (BuildConfig.DEBUG) Log.d(TAG, String.format("[%d]%s %4d %4d %5d", Integer.valueOf(this.mPointerId), title, Integer.valueOf(x), Integer.valueOf(y), Long.valueOf(eventTime)));
    }

    public void cancelTracking() {
        onCancelEventInternal(SystemClock.uptimeMillis());
    }
}
