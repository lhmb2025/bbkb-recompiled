package dev.bbkb.ime.keyboard.internal;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;



public final class RoundedLine {

    private final RectF mArc1 = new RectF();

    private final RectF mArc2 = new RectF();

    private final Path mPath = new Path();

    public Path makeRoundedLine(float f, float f2, float f3, float f4, float f5, float f6) {
        this.mPath.rewind();
        double d = f4 - f;
        double d2 = f5 - f2;
        double dHypot = Math.hypot(d, d2);
        if (Double.compare(0.0d, dHypot) == 0) {
            return this.mPath;
        }
        double dAtan2 = Math.atan2(d2, d);
        double dAsin = Math.asin((f6 - f3) / dHypot);
        double d3 = 1.5707963267948966d + dAsin;
        double d4 = dAtan2 - d3;
        double d5 = dAtan2 + d3;
        float fCos = (float) Math.cos(d4);
        float fSin = (float) Math.sin(d4);
        float fCos2 = (float) Math.cos(d5);
        float fSin2 = (float) Math.sin(d5);
        float f7 = (f3 * fCos) + f;
        float f8 = (f3 * fSin) + f2;
        float f9 = (fSin * f6) + f5;
        float f10 = (float) (d4 * 57.29577951308232d);
        float f11 = (float) (dAsin * 2.0d * 57.29577951308232d);
        this.mArc1.set(f, f2, f, f2);
        float f12 = -f3;
        this.mArc1.inset(f12, f12);
        this.mArc2.set(f4, f5, f4, f5);
        float f13 = -f6;
        this.mArc2.inset(f13, f13);
        this.mPath.moveTo(f, f2);
        this.mPath.arcTo(this.mArc1, f10, (-180.0f) + f11);
        this.mPath.moveTo(f4, f5);
        this.mPath.arcTo(this.mArc2, f10, f11 + 180.0f);
        this.mPath.moveTo(f7, f8);
        this.mPath.lineTo(f, f2);
        this.mPath.lineTo((f3 * fCos2) + f, (f3 * fSin2) + f2);
        this.mPath.lineTo((fCos2 * f6) + f4, (fSin2 * f6) + f5);
        this.mPath.lineTo(f4, f5);
        this.mPath.lineTo((fCos * f6) + f4, f9);
        this.mPath.close();
        return this.mPath;
    }

    public void getBounds(Rect rect) {
        this.mPath.computeBounds(this.mArc1, true);
        this.mArc1.roundOut(rect);
    }
}
