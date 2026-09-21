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

    public void reset(int[] iArr, int[] iArr2, int i, int i2) {
        this.mXCoords = iArr;
        this.mYCoords = iArr2;
        this.mMinPos = i;
        this.mMaxPos = i2;
    }

    public void setInterval(int i, int i2, int i3, int i4) {
        int[] iArr = this.mXCoords;
        this.mP1X = iArr[i2];
        int[] iArr2 = this.mYCoords;
        this.mP1Y = iArr2[i2];
        this.mP2X = iArr[i3];
        this.mP2Y = iArr2[i3];
        int i5 = this.mP2X - this.mP1X;
        int i6 = this.mP2Y - this.mP1Y;
        if (i >= this.mMinPos) {
            // Tangent at P1 is half of the vector P0 -> P2 (mirrors the P2 branch at the
            // bottom of this method). The decompiled source substituted the delta local
            // (i5 = P2X - P1X) where the endpoint P2X belongs, which scaled the tangent by
            // -mP1X / -mP1Y and made the drawn gesture trail diverge from the finger the
            // further right / further down the keyboard the stroke ran.
            this.mSlope1X = (this.mP2X - iArr[i]) / 2.0f;
            this.mSlope1Y = (this.mP2Y - iArr2[i]) / 2.0f;
        } else if (i4 < this.mMaxPos) {
            // No P0: mirror the tangent at P2 (half of the vector P1 -> P3) about P1 -> P2.
            float f = (iArr[i4] - this.mP1X) / 2.0f;
            float f2 = (iArr2[i4] - this.mP1Y) / 2.0f;
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
        if (i4 < this.mMaxPos) {
            this.mSlope2X = (this.mXCoords[i4] - this.mP1X) / 2.0f;
            this.mSlope2Y = (this.mYCoords[i4] - this.mP1Y) / 2.0f;
            return;
        }
        if (i >= this.mMinPos) {
            float f8 = (this.mP2X - this.mXCoords[i]) / 2.0f;
            float f9 = (this.mP2Y - this.mYCoords[i]) / 2.0f;
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

    public void interpolate(float f) {
        float f2 = 1.0f - f;
        float f3 = 2.0f * f;
        float f4 = 1.0f + f3;
        float f5 = 3.0f - f3;
        float f6 = f2 * f2;
        float f7 = f * f;
        this.mInterpolatedX = (((this.mP1X * f4) + (this.mSlope1X * f)) * f6) + (((this.mP2X * f5) - (this.mSlope2X * f2)) * f7);
        this.mInterpolatedY = (((f4 * this.mP1Y) + (f * this.mSlope1Y)) * f6) + (((f5 * this.mP2Y) - (f2 * this.mSlope2Y)) * f7);
    }
}
