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

    /** Hard cap on stroke points held for the live swipe; compared with >= so it cannot be overshot. */
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

    /** A speed (px per second), used in updateIncrementalRecognitionSize; not a time threshold. */
    private int mGestureRecognitionSpeedThreshold;

    private int mIncrementalRecognitionSize;


    
    public interface GestureStrokeTimeListener {
        void addGestureStrokeTime(int distance);
    }

    /**
     * @param pointerId pointer id
     * @param gestureRecognitionParams recognition thresholds
     */
    public GestureStrokeAnalyzer(int pointerId, GestureRecognitionParams gestureRecognitionParams) {
        this.mPointerId = pointerId;
        this.mParams = gestureRecognitionParams;
    }

    /**
     * @param keyWidth most common key width
     * @param keyboardHeight keyboard height
     */
    public void setKeyboardGeometry(int keyWidth, int keyboardHeight) {
        this.mKeyWidth = keyWidth;
        this.mMinYCoordinate = -((int) (keyboardHeight * 0.25f));
        this.mMaxYCoordinate = keyboardHeight;
        float f = keyWidth;
        this.mDetectFastMoveSpeedThreshold = (int) (this.mParams.mDetectFastMoveSpeedThreshold * f);
        this.mGestureDynamicDistanceThresholdFrom = (int) (this.mParams.mDynamicDistanceThresholdFrom * f);
        this.mGestureDynamicDistanceThresholdTo = (int) (this.mParams.mDynamicDistanceThresholdTo * f);
        this.mGestureSamplingMinimumDistance = (int) (this.mParams.mSamplingMinimumDistance * f);
        this.mGestureRecognitionSpeedThreshold = (int) (f * this.mParams.mRecognitionSpeedThreshold);
    }

    public int getLength() {
        return this.mEventTimes.getLength();
    }

    /**
     * @param x x
     * @param y y
     * @param elapsedTimeSinceFirstDown elapsed gesture time
     * @param elapsedTimeSinceLastTyping time since the last letter was typed
     */
    public void addDownEventPoint(int x, int y, int elapsedTimeSinceFirstDown, int elapsedTimeSinceLastTyping) {
        reset();
        if (elapsedTimeSinceLastTyping < this.mParams.mStaticTimeThresholdAfterFastTyping) {
            this.mAfterFastTyping = true;
        }
        addEventPoint(x, y, elapsedTimeSinceFirstDown, true, null);
    }

    private int getGestureDynamicDistanceThreshold(int deltaTime) {
        if (!this.mAfterFastTyping || deltaTime >= this.mParams.mDynamicThresholdDecayDuration) {
            return this.mGestureDynamicDistanceThresholdTo;
        }
        return this.mGestureDynamicDistanceThresholdFrom - (((this.mGestureDynamicDistanceThresholdFrom - this.mGestureDynamicDistanceThresholdTo) * deltaTime) / this.mParams.mDynamicThresholdDecayDuration);
    }

    private int getGestureDynamicTimeThreshold(int deltaTime) {
        if (!this.mAfterFastTyping || deltaTime >= this.mParams.mDynamicThresholdDecayDuration) {
            return this.mParams.mDynamicTimeThresholdTo;
        }
        return this.mParams.mDynamicTimeThresholdFrom - (((this.mParams.mDynamicTimeThresholdFrom - this.mParams.mDynamicTimeThresholdTo) * deltaTime) / this.mParams.mDynamicThresholdDecayDuration);
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

    /** @param time elapsed gesture time for the copied point */
    public void duplicateLastPoint(int time) {
        int iM7383a = getLength() - 1;
        if (iM7383a >= 0) {
            int iM5571a = this.mXCoordinates.get(iM7383a);
            int iM5571a2 = this.mYCoordinates.get(iM7383a);
            appendPoint(iM5571a, iM5571a2, time);
            updateIncrementalRecognitionSize(iM5571a, iM5571a2, time);
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

    private void appendPoint(int x, int y, int time) {
        int iM7383a = getLength();
        int i4 = iM7383a - 1;
        if ((i4 >= 0 && this.mEventTimes.get(i4) > time) || iM7383a >= MAX_EVENT_COUNT) {
            if (Logger.isLoggable("StrokeRecPoints", Log.DEBUG)) {
                if (BuildConfig.DEBUG) Log.d("StrokeRecPoints", String.format("[%d] drop stale event: %d,%d|%d last: %d,%d|%d", Integer.valueOf(this.mPointerId), Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(time), Integer.valueOf(this.mXCoordinates.get(i4)), Integer.valueOf(this.mYCoordinates.get(i4)), Integer.valueOf(this.mEventTimes.get(i4))));
            }
        } else {
            this.mEventTimes.add(time);
            this.mXCoordinates.add(x);
            this.mYCoordinates.add(y);
        }
    }

    private void setLastMajorEvent(int x, int y, int time) {
        this.mLastMajorEventTime = time;
        this.mLastMajorEventX = x;
        this.mLastMajorEventY = y;
    }

    private final boolean hasDetectedFastMove() {
        return this.mDetectFastMoveTime > 0;
    }

    private int detectFastMove(int x, int y, int time) {
        int iM7383a = getLength() - 1;
        int iM7376b = getDistance(this.mXCoordinates.get(iM7383a), this.mYCoordinates.get(iM7383a), x, y);
        int iM5571a = time - this.mEventTimes.get(iM7383a);
        if (iM5571a > 0) {
            int i4 = iM7376b * 1000;
            if (!hasDetectedFastMove() && i4 > this.mDetectFastMoveSpeedThreshold * iM5571a) {
                this.mDetectFastMoveTime = time;
                this.mDetectFastMoveX = x;
                this.mDetectFastMoveY = y;
            }
        }
        return iM7376b;
    }

    /**
     * Returns whether the point is within the keyboard's vertical gesture bounds.
     * @param x x
     * @param y y
     * @param time elapsed gesture time
     * @param isMajorEvent true for a major event (not a historical sample)
     * @param gestureStrokeTimeListener receives the distance moved, or null
     */
    public boolean addEventPoint(int x, int y, int time, boolean isMajorEvent, GestureStrokeTimeListener gestureStrokeTimeListener) {
        if (getLength() <= 0) {
            appendPoint(x, y, time);
            setLastMajorEvent(x, y, time);
        } else {
            int iM7379c = detectFastMove(x, y, time);
            if (gestureStrokeTimeListener != null) {
                gestureStrokeTimeListener.addGestureStrokeTime(iM7379c);
            }
            if (iM7379c > this.mGestureSamplingMinimumDistance) {
                appendPoint(x, y, time);
            }
        }
        if (isMajorEvent) {
            updateIncrementalRecognitionSize(x, y, time);
            setLastMajorEvent(x, y, time);
        }
        return y >= this.mMinYCoordinate && y < this.mMaxYCoordinate;
    }

    private void updateIncrementalRecognitionSize(int x, int y, int time) {
        int i4 = (int) (time - this.mLastMajorEventTime);
        if (i4 > 0 && getDistance(this.mLastMajorEventX, this.mLastMajorEventY, x, y) * 1000 < this.mGestureRecognitionSpeedThreshold * i4) {
            this.mIncrementalRecognitionSize = getLength();
        }
    }

    /** Callers store this in mLastRecognitionPointSize, which nothing reads yet. */
    public int getIncrementalRecognitionSize() {
        return this.mIncrementalRecognitionSize;
    }

    /** Not Math.hypot: far slower, and its overflow safety is useless for pixel deltas. */
    private static int getDistance(int x1, int y1, int x2, int y2) {
        final int dx = x1 - x2;
        final int dy = y1 - y2;
        return (int) Math.sqrt((double) ((dx * dx) + (dy * dy)));
    }
}
