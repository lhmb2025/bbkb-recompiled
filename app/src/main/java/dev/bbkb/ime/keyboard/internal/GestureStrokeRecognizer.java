package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.core.shared.IntArrayList;



public final class GestureStrokeRecognizer {

    private final GestureStrokeDrawingParams mGestureStroke;

    private int mGestureStrokeId;

    private int mLastIncrementalBatchSize;

    private int mLastInterpolatedDrawIndex;

    private int mLastX;

    private int mLastY;

    private double mDistanceFromLastSampledPoint;

    private final IntArrayList mEventTimes = new IntArrayList(256);

    private final IntArrayList mXCoordinates = new IntArrayList(256);

    private final IntArrayList mYCoordinates = new IntArrayList(256);

    private final HermiteInterpolator mInterpolator = new HermiteInterpolator();

    private static double angularDiff(double d, double d2) {
        double d3 = d - d2;
        while (d3 > 3.141592653589793d) {
            d3 -= 6.283185307179586d;
        }
        while (d3 < -3.141592653589793d) {
            d3 += 6.283185307179586d;
        }
        return d3;
    }

    public GestureStrokeRecognizer(GestureStrokeDrawingParams c1052k) {
        this.mGestureStroke = c1052k;
    }

    private void reset() {
        this.mGestureStrokeId++;
        this.mLastIncrementalBatchSize = 0;
        this.mLastInterpolatedDrawIndex = 0;
        this.mEventTimes.setLength(0);
        this.mXCoordinates.setLength(0);
        this.mYCoordinates.setLength(0);
    }

    public int getGestureStrokeId() {
        return this.mGestureStrokeId;
    }

    public void onDownEvent(int i, int i2, int i3) {
        reset();
        onMoveEvent(i, i2, i3);
    }

    private boolean needsSampling(int i, int i2) {
        this.mDistanceFromLastSampledPoint += Math.hypot(i - this.mLastX, i2 - this.mLastY);
        this.mLastX = i;
        this.mLastY = i2;
        boolean z = this.mEventTimes.getLength() == 0;
        if (this.mDistanceFromLastSampledPoint < this.mGestureStroke.mMinSamplingDistance && !z) {
            return false;
        }
        this.mDistanceFromLastSampledPoint = 0.0d;
        return true;
    }

    public void onMoveEvent(int i, int i2, int i3) {
        if (needsSampling(i, i2)) {
            this.mEventTimes.add(i3);
            this.mXCoordinates.add(i);
            this.mYCoordinates.add(i2);
        }
    }

    public void appendIncrementalBatchPoints(IntArrayList c0865af, IntArrayList c0865af2, IntArrayList c0865af3) {
        int iM5570a = this.mEventTimes.getLength();
        int i = this.mLastIncrementalBatchSize;
        int i2 = iM5570a - i;
        if (i2 <= 0) {
            return;
        }
        c0865af.append(this.mEventTimes, i, i2);
        c0865af2.append(this.mXCoordinates, this.mLastIncrementalBatchSize, i2);
        c0865af3.append(this.mYCoordinates, this.mLastIncrementalBatchSize, i2);
        this.mLastIncrementalBatchSize = this.mEventTimes.getLength();
    }

    public int appendAllBatchPoints(int i, IntArrayList c0865af, IntArrayList c0865af2, IntArrayList c0865af3) {
        int iM5570a = this.mEventTimes.getLength();
        int[] iArrM5577b = this.mEventTimes.getPrimitiveArray();
        int[] iArrM5577b2 = this.mXCoordinates.getPrimitiveArray();
        int[] iArrM5577b3 = this.mYCoordinates.getPrimitiveArray();
        this.mInterpolator.reset(iArrM5577b2, iArrM5577b3, 0, iM5570a);
        int i2 = this.mLastInterpolatedDrawIndex + 1;
        int i3 = i;
        int i4 = i3;
        while (i2 < iM5570a) {
            int i5 = i2 - 1;
            int i6 = i2 + 1;
            this.mLastInterpolatedDrawIndex = i5;
            this.mInterpolator.setInterval(i5 - 1, i5, i2, i6);
            int iMin = Math.min(this.mGestureStroke.mMaxInterpolationSegments, Math.max((int) Math.ceil(Math.abs(angularDiff(Math.atan2(this.mInterpolator.mSlope2Y, this.mInterpolator.mSlope2X), Math.atan2(this.mInterpolator.mSlope1Y, this.mInterpolator.mSlope1X))) / this.mGestureStroke.mMaxInterpolationAngularThreshold), (int) Math.ceil(Math.hypot(this.mInterpolator.mP1X - this.mInterpolator.mP2X, this.mInterpolator.mP1Y - this.mInterpolator.mP2Y) / this.mGestureStroke.mMaxInterpolationDistanceThreshold)));
            int iM5571a = c0865af.get(i4);
            int i7 = iArrM5577b[i2] - iArrM5577b[i5];
            int i8 = i4 + 1;
            int i9 = 1;
            while (i9 < iMin) {
                float f = i9 / iMin;
                this.mInterpolator.interpolate(f);
                c0865af.addAt(i8, ((int) (i7 * f)) + iM5571a);
                c0865af2.addAt(i8, (int) this.mInterpolator.mInterpolatedX);
                c0865af3.addAt(i8, (int) this.mInterpolator.mInterpolatedY);
                i8++;
                i9++;
            }
            c0865af.addAt(i8, iArrM5577b[i2]);
            c0865af2.addAt(i8, iArrM5577b2[i2]);
            c0865af3.addAt(i8, iArrM5577b3[i2]);
            i3 = i4;
            i4 = i8;
            i2 = i6;
        }
        return i3;
    }
}
