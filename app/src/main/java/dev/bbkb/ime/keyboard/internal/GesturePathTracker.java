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

        void onUpdateBatchInput(long j);

        void startUpdateBatchInputTimer();

        void onEndBatchInput(long j);
    }

    public GesturePathTracker(int i, GestureRecognitionParams c1054m) {
        this.mGestureStrokeAnalyzer = new GestureStrokeAnalyzer(i, c1054m);
    }

    public void setKeyboardGeometry(int i, int i2) {
        this.mGestureStrokeAnalyzer.setKeyboardGeometry(i, i2);
    }

    public int getGestureElapsedTime(long j) {
        return (int) (j - this.mGestureStartTime);
    }

    public void addDownEventPoint(int i, int i2, long j, long j2, int i3) {
        if (i3 == 1) {
            this.mGestureStartTime = j;
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "BatchInputTracker.onStartBatchInput: x=" + i + " y=" + i2 + " time=" + j);
        }
        if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "BatchInputTracker.onUpdateBatchInput: x=" + i + " y=" + i2 + " elapsed=" + getGestureElapsedTime(j) + " dt=" + (j - j2));
        this.mGestureStrokeAnalyzer.addDownEventPoint(i, i2, getGestureElapsedTime(j), (int) (j - j2));
    }

    public boolean addMoveEventPoint(boolean z, int i, int i2, long j, boolean z2, BatchInputListener aVar, GestureStrokeAnalyzer.GestureStrokeTimeListener aVar2) {
        int iM7383a = this.mGestureStrokeAnalyzer.getLength();
        boolean zM7387a = this.mGestureStrokeAnalyzer.addEventPoint(i, i2, getGestureElapsedTime(j), z2, aVar2);
        if (z && this.mGestureStrokeAnalyzer.getLength() > iM7383a) {
            aVar.startUpdateBatchInputTimer();
        }
        return zM7387a;
    }

    public boolean tryStartBatchInput(BatchInputListener aVar) {
        if (!this.mGestureStrokeAnalyzer.hasRecognitionGesturePoint()) {
            return false;
        }
        this.mLastRecognitionPointSize = 0;
        this.mLastRecognitionTime = 0L;
        aVar.onStartBatchInput();
        return true;
    }

    public void onUpdateBatchInputTimer(long j, BatchInputListener aVar) {
        this.mGestureStrokeAnalyzer.duplicateLastPoint(getGestureElapsedTime(j));
        updateBatchInput(j, aVar);
    }

    public void updateBatchInput(long j, BatchInputListener aVar) {
        int iM7389c = this.mGestureStrokeAnalyzer.getIncrementalRecognitionSize();
        aVar.onUpdateBatchInput(j);
        aVar.startUpdateBatchInputTimer();
        this.mLastRecognitionPointSize = iM7389c;
        this.mLastRecognitionTime = j;
    }

    public void endBatchInput(long j, BatchInputListener aVar) {
        aVar.onEndBatchInput(j);
        this.mLastRecognitionPointSize = this.mGestureStrokeAnalyzer.getIncrementalRecognitionSize();
        this.mLastRecognitionTime = j;
    }
}
