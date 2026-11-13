package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.shared.InputPathDebug;


public class GesturePathTracker {

    private long mGestureStartTime;

    private int mLastRecognitionPointSize = 0;

    private long mLastRecognitionTime = 0;

    private final GestureStrokeAnalyzer mGestureStrokeAnalyzer;

    
    public interface BatchInputListener {
        void onStartBatchInput();

        void onUpdateBatchInput(long eventTime);

        void startUpdateBatchInputTimer();

        void onEndBatchInput(long upEventTime);
    }

    public GesturePathTracker(int pointerId, GestureRecognitionParams gestureRecognitionParams) {
        this.mGestureStrokeAnalyzer = new GestureStrokeAnalyzer(pointerId, gestureRecognitionParams);
    }

    public void setKeyboardGeometry(int keyWidth, int keyboardHeight) {
        this.mGestureStrokeAnalyzer.setKeyboardGeometry(keyWidth, keyboardHeight);
    }

    public int getGestureElapsedTime(long eventTime) {
        return (int) (eventTime - this.mGestureStartTime);
    }

    public void addDownEventPoint(int x, int y, long downEventTime, long lastKeyEventTime, int activePointerCount) {
        if (activePointerCount == 1) {
            this.mGestureStartTime = downEventTime;
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "BatchInputTracker.onStartBatchInput: x=" + x + " y=" + y + " time=" + downEventTime);
        }
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "BatchInputTracker.onUpdateBatchInput: x=" + x + " y=" + y + " elapsed=" + getGestureElapsedTime(downEventTime) + " dt=" + (downEventTime - lastKeyEventTime));
        this.mGestureStrokeAnalyzer.addDownEventPoint(x, y, getGestureElapsedTime(downEventTime), (int) (downEventTime - lastKeyEventTime));
    }

    public boolean addMoveEventPoint(boolean inGesture, int x, int y, long eventTime, boolean isMajorEvent, BatchInputListener listener, GestureStrokeAnalyzer.GestureStrokeTimeListener strokeTimeListener) {
        int iM7383a = this.mGestureStrokeAnalyzer.getLength();
        boolean zM7387a = this.mGestureStrokeAnalyzer.addEventPoint(x, y, getGestureElapsedTime(eventTime), isMajorEvent, strokeTimeListener);
        if (inGesture && this.mGestureStrokeAnalyzer.getLength() > iM7383a) {
            listener.startUpdateBatchInputTimer();
        }
        return zM7387a;
    }

    public boolean tryStartBatchInput(BatchInputListener batchInputListener) {
        if (!this.mGestureStrokeAnalyzer.hasRecognitionGesturePoint()) {
            return false;
        }
        this.mLastRecognitionPointSize = 0;
        this.mLastRecognitionTime = 0L;
        batchInputListener.onStartBatchInput();
        return true;
    }

    public void onUpdateBatchInputTimer(long eventTime, BatchInputListener batchInputListener) {
        this.mGestureStrokeAnalyzer.duplicateLastPoint(getGestureElapsedTime(eventTime));
        updateBatchInput(eventTime, batchInputListener);
    }

    public void updateBatchInput(long moveEventTime, BatchInputListener batchInputListener) {
        int iM7389c = this.mGestureStrokeAnalyzer.getIncrementalRecognitionSize();
        batchInputListener.onUpdateBatchInput(moveEventTime);
        batchInputListener.startUpdateBatchInputTimer();
        this.mLastRecognitionPointSize = iM7389c;
        this.mLastRecognitionTime = moveEventTime;
    }

    public void endBatchInput(long upEventTime, BatchInputListener batchInputListener) {
        batchInputListener.onEndBatchInput(upEventTime);
        this.mLastRecognitionPointSize = this.mGestureStrokeAnalyzer.getIncrementalRecognitionSize();
        this.mLastRecognitionTime = upEventTime;
    }
}
