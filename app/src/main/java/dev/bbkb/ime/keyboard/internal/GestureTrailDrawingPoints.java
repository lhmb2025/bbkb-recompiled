package dev.bbkb.ime.keyboard.internal;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;

import dev.bbkb.ime.core.shared.IntArrayList;



final class GestureTrailDrawingPoints {

    private long mCurrentTimeBase;

    private int mTrailStartIndex;

    private int mLastInterpolatedDrawIndex;

    private final IntArrayList mXCoordinates = new IntArrayList(256);

    private final IntArrayList mYCoordinates = new IntArrayList(256);

    private final IntArrayList mEventTimes = new IntArrayList(256);

    private int mCurrentStrokeId = -1;

    private final RoundedLine mRoundedLine = new RoundedLine();

    private final Rect mRoundedLineBounds = new Rect();

    private static int markAsDownEvent(int xCoord) {
        return (-128) - xCoord;
    }

    private static boolean isDownEventXCoord(int xCoordOrMark) {
        return xCoordOrMark <= -128;
    }

    GestureTrailDrawingPoints() {
        // Fields start at their defaults.
    }

    private static int getXCoordValue(int xCoordOrMark) {
        return isDownEventXCoord(xCoordOrMark) ? (-128) - xCoordOrMark : xCoordOrMark;
    }

    /**
     * @param gestureStrokeRecognizer the stroke to copy new points from
     * @param downTime down time of the stroke
     */
    public void addStroke(GestureStrokeRecognizer gestureStrokeRecognizer, long downTime) {
        synchronized (this.mEventTimes) {
            addStrokeInternal(gestureStrokeRecognizer, downTime);
        }
    }

    private void addStrokeInternal(GestureStrokeRecognizer gestureStrokeRecognizer, long downTime) {
        int iM5570a = this.mEventTimes.getLength();
        gestureStrokeRecognizer.appendIncrementalBatchPoints(this.mEventTimes, this.mXCoordinates, this.mYCoordinates);
        if (this.mEventTimes.getLength() == iM5570a) {
            return;
        }
        int[] iArrM5577b = this.mEventTimes.getPrimitiveArray();
        int iM7369a = gestureStrokeRecognizer.getGestureStrokeId();
        this.mLastInterpolatedDrawIndex = gestureStrokeRecognizer.appendAllBatchPoints(iM7369a == this.mCurrentStrokeId ? this.mLastInterpolatedDrawIndex : iM5570a, this.mEventTimes, this.mXCoordinates, this.mYCoordinates);
        if (iM7369a != this.mCurrentStrokeId) {
            int i = (int) (downTime - this.mCurrentTimeBase);
            for (int i2 = this.mTrailStartIndex; i2 < iM5570a; i2++) {
                iArrM5577b[i2] = iArrM5577b[i2] - i;
            }
            int[] iArrM5577b2 = this.mXCoordinates.getPrimitiveArray();
            iArrM5577b2[iM5570a] = markAsDownEvent(iArrM5577b2[iM5570a]);
            this.mCurrentTimeBase = downTime - iArrM5577b[iM5570a];
            this.mCurrentStrokeId = iM7369a;
        }
    }

    private static int getAlpha(int elapsedTime, GestureTrailDrawingParams gestureTrailDrawingParams) {
        if (elapsedTime < gestureTrailDrawingParams.mFadeoutStartDelay) {
            return 255;
        }
        return 255 - (((elapsedTime - gestureTrailDrawingParams.mFadeoutStartDelay) * 255) / gestureTrailDrawingParams.mFadeoutDuration);
    }

    private static float getWidth(int elapsedTime, GestureTrailDrawingParams gestureTrailDrawingParams) {
        return gestureTrailDrawingParams.mTrailStartWidth - (((gestureTrailDrawingParams.mTrailStartWidth - gestureTrailDrawingParams.mTrailEndWidth) * elapsedTime) / gestureTrailDrawingParams.mTrailLingerDuration);
    }

    /**
     * @param rect receives the drawn bounds
     * @param gestureTrailDrawingParams trail drawing params
     */
    public boolean drawGestureTrail(Canvas canvas, Paint paint, Rect rect, GestureTrailDrawingParams gestureTrailDrawingParams) {
        boolean zM7396b;
        synchronized (this.mEventTimes) {
            zM7396b = drawGestureTrailInternal(canvas, paint, rect, gestureTrailDrawingParams);
        }
        return zM7396b;
    }

    private boolean drawGestureTrailInternal(Canvas canvas, Paint paint, Rect rect, GestureTrailDrawingParams gestureTrailDrawingParams) {
        boolean z;
        int i;
        int i2;
        int i3;
        rect.setEmpty();
        int iM5570a = this.mEventTimes.getLength();
        if (iM5570a == 0) {
            return false;
        }
        int[] iArrM5577b = this.mEventTimes.getPrimitiveArray();
        int[] iArrM5577b2 = this.mXCoordinates.getPrimitiveArray();
        int[] iArrM5577b3 = this.mYCoordinates.getPrimitiveArray();
        int iUptimeMillis = (int) (SystemClock.uptimeMillis() - this.mCurrentTimeBase);
        int i4 = this.mTrailStartIndex;
        while (i4 < iM5570a && iUptimeMillis - iArrM5577b[i4] >= gestureTrailDrawingParams.mTrailLingerDuration) {
            i4++;
        }
        this.mTrailStartIndex = i4;
        if (i4 < iM5570a) {
            // Read at draw time so the trail follows the active palette.
            int trailColor = dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE.getAccentColor();
            paint.setColor(trailColor);
            paint.setStyle(Paint.Style.FILL);
            RoundedLine c1038au = this.mRoundedLine;
            int iM7397c = getXCoordValue(iArrM5577b2[i4]);
            int i5 = iArrM5577b3[i4];
            float fM7393b = getWidth(iUptimeMillis - iArrM5577b[i4], gestureTrailDrawingParams) / 2.0f;
            int i6 = i4 + 1;
            while (i6 < iM5570a) {
                int i7 = iUptimeMillis - iArrM5577b[i6];
                int iM7397c2 = getXCoordValue(iArrM5577b2[i6]);
                int i8 = iUptimeMillis;
                int i9 = iArrM5577b3[i6];
                float fM7393b2 = getWidth(i7, gestureTrailDrawingParams) / 2.0f;
                if (isDownEventXCoord(iArrM5577b2[i6])) {
                    i = iM7397c2;
                    i2 = i9;
                    i3 = i6;
                } else {
                    i = iM7397c2;
                    i2 = i9;
                    i3 = i6;
                    Path pathM7298a = c1038au.makeRoundedLine(iM7397c, i5, gestureTrailDrawingParams.mTrailBodyRatio * fM7393b, iM7397c2, i9, fM7393b2 * gestureTrailDrawingParams.mTrailBodyRatio);
                    if (!pathM7298a.isEmpty()) {
                        c1038au.getBounds(this.mRoundedLineBounds);
                        if (gestureTrailDrawingParams.mTrailShadowEnabled) {
                            float f = gestureTrailDrawingParams.mTrailShadowRatio * fM7393b2;
                            paint.setShadowLayer(f, 0.0f, 0.0f, trailColor);
                            int i10 = -((int) Math.ceil(f));
                            this.mRoundedLineBounds.inset(i10, i10);
                        }
                        rect.union(this.mRoundedLineBounds);
                        paint.setAlpha(getAlpha(i7, gestureTrailDrawingParams));
                        canvas.drawPath(pathM7298a, paint);
                    }
                }
                i6 = i3 + 1;
                iUptimeMillis = i8;
                fM7393b = fM7393b2;
                iM7397c = i;
                i5 = i2;
            }
        }
        int i11 = iM5570a - i4;
        if (i11 < i4) {
            z = false;
            this.mTrailStartIndex = 0;
            if (i11 > 0) {
                System.arraycopy(iArrM5577b, i4, iArrM5577b, 0, i11);
                System.arraycopy(iArrM5577b2, i4, iArrM5577b2, 0, i11);
                System.arraycopy(iArrM5577b3, i4, iArrM5577b3, 0, i11);
            }
            this.mEventTimes.setLength(i11);
            this.mXCoordinates.setLength(i11);
            this.mYCoordinates.setLength(i11);
            this.mLastInterpolatedDrawIndex = Math.max(this.mLastInterpolatedDrawIndex - i4, 0);
        } else {
            z = false;
        }
        if (i11 > 0) {
            return true;
        }
        return z;
    }
}
