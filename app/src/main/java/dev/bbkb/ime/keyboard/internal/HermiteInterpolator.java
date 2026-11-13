package dev.bbkb.ime.keyboard.internal;



public final class HermiteInterpolator {

    public int mP1X;

    public int mP1Y;

    public int mP2X;

    public int mP2Y;

    public float mSlope1X;

    public float mSlope1Y;

    public float mSlope2X;

    public float mSlope2Y;

    public float mInterpolatedX;

    public float mInterpolatedY;

    private int[] mXCoords;

    private int[] mYCoords;

    private int mMinPos;

    private int mMaxPos;

    /**
     * @param xCoords x coordinates
     * @param yCoords y coordinates
     * @param minPos first valid index
     * @param maxPos end index (exclusive)
     */
    public void reset(int[] xCoords, int[] yCoords, int minPos, int maxPos) {
        this.mXCoords = xCoords;
        this.mYCoords = yCoords;
        this.mMinPos = minPos;
        this.mMaxPos = maxPos;
    }

    /**
     * Sets the segment P1 to P2 with neighbors P0 and P3, given as indices.
     * @param p0 P0
     * @param p1 P1
     * @param p2 P2
     * @param p3 P3
     */
    public void setInterval(int p0, int p1, int p2, int p3) {
        int[] iArr = this.mXCoords;
        this.mP1X = iArr[p1];
        int[] iArr2 = this.mYCoords;
        this.mP1Y = iArr2[p1];
        this.mP2X = iArr[p2];
        this.mP2Y = iArr2[p2];
        int i5 = this.mP2X - this.mP1X;
        int i6 = this.mP2Y - this.mP1Y;
        if (p0 >= this.mMinPos) {
            // Tangent at P1 is half of the vector P0 -> P2 (the endpoint, not the delta i5).
            this.mSlope1X = (this.mP2X - iArr[p0]) / 2.0f;
            this.mSlope1Y = (this.mP2Y - iArr2[p0]) / 2.0f;
        } else if (p3 < this.mMaxPos) {
            // No P0: mirror the tangent at P2 (half of the vector P1 -> P3) about P1 -> P2.
            float f = (iArr[p3] - this.mP1X) / 2.0f;
            float f2 = (iArr2[p3] - this.mP1Y) / 2.0f;
            float f3 = i5;
            float f4 = i6;
            float f5 = (f3 * f2) - (f4 * f);
            float f6 = (f * f3) + (f2 * f4);
            float f7 = (1.0f / ((i5 * i5) + (i6 * i6))) / 2.0f;
            this.mSlope1X = ((f6 * f3) + (f5 * f4)) * f7;
            this.mSlope1Y = f7 * ((f6 * f4) - (f5 * f3));
        } else {
            this.mSlope1X = i5;
            this.mSlope1Y = i6;
        }
        if (p3 < this.mMaxPos) {
            this.mSlope2X = (this.mXCoords[p3] - this.mP1X) / 2.0f;
            this.mSlope2Y = (this.mYCoords[p3] - this.mP1Y) / 2.0f;
            return;
        }
        if (p0 >= this.mMinPos) {
            float f8 = (this.mP2X - this.mXCoords[p0]) / 2.0f;
            float f9 = (this.mP2Y - this.mYCoords[p0]) / 2.0f;
            float f10 = i5;
            float f11 = i6;
            float f12 = (f10 * f9) - (f11 * f8);
            float f13 = (f8 * f10) + (f9 * f11);
            float f14 = (1.0f / ((i5 * i5) + (i6 * i6))) / 2.0f;
            this.mSlope2X = ((f13 * f10) + (f12 * f11)) * f14;
            this.mSlope2Y = f14 * ((f13 * f11) - (f12 * f10));
            return;
        }
        this.mSlope2X = i5;
        this.mSlope2Y = i6;
    }

    /** @param t position on the segment, 0 at P1 to 1 at P2 */
    public void interpolate(float t) {
        float f2 = 1.0f - t;
        float f3 = 2.0f * t;
        float f4 = 1.0f + f3;
        float f5 = 3.0f - f3;
        float f6 = f2 * f2;
        float f7 = t * t;
        this.mInterpolatedX = (((this.mP1X * f4) + (this.mSlope1X * t)) * f6) + (((this.mP2X * f5) - (this.mSlope2X * f2)) * f7);
        this.mInterpolatedY = (((f4 * this.mP1Y) + (t * this.mSlope1Y)) * f6) + (((f5 * this.mP2Y) - (f2 * this.mSlope2Y)) * f7);
    }
}
