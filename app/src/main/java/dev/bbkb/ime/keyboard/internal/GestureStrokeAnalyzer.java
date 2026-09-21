package dev.bbkb.ime.keyboard.internal;

import android.util.Log;

import dev.bbkb.ime.core.shared.IntArrayList;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.BuildConfig;



public final class GestureStrokeAnalyzer {

    private final int mPointerId;

    private final IntArrayList mEventTimes = new IntArrayList(128);

    private final IntArrayList mXCoordinates = new IntArrayList(128);

    private final IntArrayList mYCoordinates = new IntArrayList(128);

    /** AOSP's hard cap on stroke points -- the growth bound on the three IntArrayLists that hold
     *  the live swipe. Was a bare 800 compared with ==, so any path that overshot it would have
     *  disabled the cap entirely. */
    private static final int MAX_EVENT_COUNT = 800;

    private final GestureRecognitionParams mParams;

    private int mKeyWidth;

    private int mMinYCoordinate;

    private int mMaxYCoordinate;

    private int mDetectFastMoveSpeedThreshold;

    private int mDetectFastMoveTime;

    private int mDetectFastMoveX;

    private int mDetectFastMoveY;

    private boolean mAfterFastTyping;

    private int mGestureDynamicDistanceThresholdFrom;

    private int mGestureDynamicDistanceThresholdTo;

    private int mGestureSamplingMinimumDistance;

    private long mLastMajorEventTime;

    private int mLastMajorEventX;

    private int mLastMajorEventY;

    /** AOSP's mGestureRecognitionSpeedThreshold: assigned from mParams.mRecognitionSpeedThreshold
     *  and used as a SPEED in updateIncrementalRecognitionSize. It is not a time threshold --
     *  getGestureDynamicTimeThreshold() reads mParams.mDynamicTimeThreshold* instead. */
    private int mGestureRecognitionSpeedThreshold;

    private int mIncrementalRecognitionSize;


    
    public interface GestureStrokeTimeListener {
        void addGestureStrokeTime(int i);
    }

    public GestureStrokeAnalyzer(int i, GestureRecognitionParams c1054m) {
        this.mPointerId = i;
        this.mParams = c1054m;
    }

    public void setKeyboardGeometry(int i, int i2) {
        this.mKeyWidth = i;
        this.mMinYCoordinate = -((int) (i2 * 0.25f));
        this.mMaxYCoordinate = i2;
        float f = i;
        this.mDetectFastMoveSpeedThreshold = (int) (this.mParams.mDetectFastMoveSpeedThreshold * f);
        this.mGestureDynamicDistanceThresholdFrom = (int) (this.mParams.mDynamicDistanceThresholdFrom * f);
        this.mGestureDynamicDistanceThresholdTo = (int) (this.mParams.mDynamicDistanceThresholdTo * f);
        this.mGestureSamplingMinimumDistance = (int) (this.mParams.mSamplingMinimumDistance * f);
        this.mGestureRecognitionSpeedThreshold = (int) (f * this.mParams.mRecognitionSpeedThreshold);
    }

    public int getLength() {
        return this.mEventTimes.getLength();
    }

    public void addDownEventPoint(int i, int i2, int i3, int i4) {
        reset();
        if (i4 < this.mParams.mStaticTimeThresholdAfterFastTyping) {
            this.mAfterFastTyping = true;
        }
        addEventPoint(i, i2, i3, true, null);
    }

    private int getGestureDynamicDistanceThreshold(int i) {
        if (!this.mAfterFastTyping || i >= this.mParams.mDynamicThresholdDecayDuration) {
            return this.mGestureDynamicDistanceThresholdTo;
        }
        return this.mGestureDynamicDistanceThresholdFrom - (((this.mGestureDynamicDistanceThresholdFrom - this.mGestureDynamicDistanceThresholdTo) * i) / this.mParams.mDynamicThresholdDecayDuration);
    }

    private int getGestureDynamicTimeThreshold(int i) {
        if (!this.mAfterFastTyping || i >= this.mParams.mDynamicThresholdDecayDuration) {
            return this.mParams.mDynamicTimeThresholdTo;
        }
        return this.mParams.mDynamicTimeThresholdFrom - (((this.mParams.mDynamicTimeThresholdFrom - this.mParams.mDynamicTimeThresholdTo) * i) / this.mParams.mDynamicThresholdDecayDuration);
    }

    public boolean hasRecognitionGesturePoint() {
        int iM7383a;
        if (!hasDetectedFastMove() || (iM7383a = getLength()) <= 0) {
            return false;
        }
        int i = iM7383a - 1;
        int iM5571a = this.mEventTimes.get(i) - this.mDetectFastMoveTime;
        if (iM5571a < 0) {
            return false;
        }
        return iM5571a >= getGestureDynamicTimeThreshold(iM5571a) && getDistance(this.mXCoordinates.get(i), this.mYCoordinates.get(i), this.mDetectFastMoveX, this.mDetectFastMoveY) >= getGestureDynamicDistanceThreshold(iM5571a);
    }

    public void duplicateLastPoint(int i) {
        int iM7383a = getLength() - 1;
        if (iM7383a >= 0) {
            int iM5571a = this.mXCoordinates.get(iM7383a);
            int iM5571a2 = this.mYCoordinates.get(iM7383a);
            appendPoint(iM5571a, iM5571a2, i);
            updateIncrementalRecognitionSize(iM5571a, iM5571a2, i);
        }
    }

    private void reset() {
        this.mIncrementalRecognitionSize = 0;
        this.mEventTimes.setLength(0);
        this.mXCoordinates.setLength(0);
        this.mYCoordinates.setLength(0);
        this.mLastMajorEventTime = 0L;
        this.mDetectFastMoveTime = 0;
        this.mAfterFastTyping = false;
    }

    private void appendPoint(int i, int i2, int i3) {
        int iM7383a = getLength();
        int i4 = iM7383a - 1;
        if ((i4 >= 0 && this.mEventTimes.get(i4) > i3) || iM7383a >= MAX_EVENT_COUNT) {
            if (Logger.isLoggable("StrokeRecPoints", Log.DEBUG)) {
                if (BuildConfig.DEBUG) Log.d("StrokeRecPoints", String.format("[%d] drop stale event: %d,%d|%d last: %d,%d|%d", Integer.valueOf(this.mPointerId), Integer.valueOf(i), Integer.valueOf(i2), Integer.valueOf(i3), Integer.valueOf(this.mXCoordinates.get(i4)), Integer.valueOf(this.mYCoordinates.get(i4)), Integer.valueOf(this.mEventTimes.get(i4))));
            }
        } else {
            this.mEventTimes.add(i3);
            this.mXCoordinates.add(i);
            this.mYCoordinates.add(i2);
        }
    }

    private void setLastMajorEvent(int i, int i2, int i3) {
        this.mLastMajorEventTime = i3;
        this.mLastMajorEventX = i;
        this.mLastMajorEventY = i2;
    }

    private final boolean hasDetectedFastMove() {
        return this.mDetectFastMoveTime > 0;
    }

    private int detectFastMove(int i, int i2, int i3) {
        int iM7383a = getLength() - 1;
        int iM7376b = getDistance(this.mXCoordinates.get(iM7383a), this.mYCoordinates.get(iM7383a), i, i2);
        int iM5571a = i3 - this.mEventTimes.get(iM7383a);
        if (iM5571a > 0) {
            int i4 = iM7376b * 1000;
            if (!hasDetectedFastMove() && i4 > this.mDetectFastMoveSpeedThreshold * iM5571a) {
                this.mDetectFastMoveTime = i3;
                this.mDetectFastMoveX = i;
                this.mDetectFastMoveY = i2;
            }
        }
        return iM7376b;
    }

    public boolean addEventPoint(int i, int i2, int i3, boolean z, GestureStrokeTimeListener aVar) {
        if (getLength() <= 0) {
            appendPoint(i, i2, i3);
            setLastMajorEvent(i, i2, i3);
        } else {
            int iM7379c = detectFastMove(i, i2, i3);
            if (aVar != null) {
                aVar.addGestureStrokeTime(iM7379c);
            }
            if (iM7379c > this.mGestureSamplingMinimumDistance) {
                appendPoint(i, i2, i3);
            }
        }
        if (z) {
            updateIncrementalRecognitionSize(i, i2, i3);
            setLastMajorEvent(i, i2, i3);
        }
        return i2 >= this.mMinYCoordinate && i2 < this.mMaxYCoordinate;
    }

    private void updateIncrementalRecognitionSize(int i, int i2, int i3) {
        int i4 = (int) (i3 - this.mLastMajorEventTime);
        if (i4 > 0 && getDistance(this.mLastMajorEventX, this.mLastMajorEventY, i, i2) * 1000 < this.mGestureRecognitionSpeedThreshold * i4) {
            this.mIncrementalRecognitionSize = getLength();
        }
    }

    /**
     * NOTE: the two callers in GesturePathTracker store this into mLastRecognitionPointSize,
     * which nothing reads -- the incremental-recognition sizing path is currently inert. This
     * used to return a separate field assigned only to 0 in reset(), i.e. a constant zero;
     * returning the value the class actually computes is behaviour-neutral today and is what
     * the path needs if it is ever wired up.
     */
    public int getIncrementalRecognitionSize() {
        return this.mIncrementalRecognitionSize;
    }

    /** Not Math.hypot: its overflow/underflow correctness is irrelevant for on-screen integer
     *  deltas and costs roughly an order of magnitude, on a per-stroke-point path. */
    private static int getDistance(int i, int i2, int i3, int i4) {
        final int dx = i - i3;
        final int dy = i2 - i4;
        return (int) Math.sqrt((double) ((dx * dx) + (dy * dy)));
    }
}
