package dev.bbkb.ime.keyboard.internal;

import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;



public final class RoundedLine {

    private final RectF mArc1 = new RectF();

    private final RectF mArc2 = new RectF();

    private final Path mPath = new Path();

    public Path makeRoundedLine(float p1x, float p1y, float r1, float p2x, float p2y, float r2) {
        this.mPath.rewind();
        double d = p2x - p1x;
        double d2 = p2y - p1y;
        double dHypot = Math.hypot(d, d2);
        if (Double.compare(0.0d, dHypot) == 0) {
            return this.mPath;
        }
        double dAtan2 = Math.atan2(d2, d);
        double dAsin = Math.asin((r2 - r1) / dHypot);
        double d3 = 1.5707963267948966d + dAsin;
        double d4 = dAtan2 - d3;
        double d5 = dAtan2 + d3;
        float fCos = (float) Math.cos(d4);
        float fSin = (float) Math.sin(d4);
        float fCos2 = (float) Math.cos(d5);
        float fSin2 = (float) Math.sin(d5);
        float f7 = (r1 * fCos) + p1x;
        float f8 = (r1 * fSin) + p1y;
        float f9 = (fSin * r2) + p2y;
        float f10 = (float) (d4 * 57.29577951308232d);
        float f11 = (float) (dAsin * 2.0d * 57.29577951308232d);
        this.mArc1.set(p1x, p1y, p1x, p1y);
        float f12 = -r1;
        this.mArc1.inset(f12, f12);
        this.mArc2.set(p2x, p2y, p2x, p2y);
        float f13 = -r2;
        this.mArc2.inset(f13, f13);
        this.mPath.moveTo(p1x, p1y);
        this.mPath.arcTo(this.mArc1, f10, (-180.0f) + f11);
        this.mPath.moveTo(p2x, p2y);
        this.mPath.arcTo(this.mArc2, f10, f11 + 180.0f);
        this.mPath.moveTo(f7, f8);
        this.mPath.lineTo(p1x, p1y);
        this.mPath.lineTo((r1 * fCos2) + p1x, (r1 * fSin2) + p1y);
        this.mPath.lineTo((fCos2 * r2) + p2x, (fSin2 * r2) + p2y);
        this.mPath.lineTo(p2x, p2y);
        this.mPath.lineTo((fCos * r2) + p2x, f9);
        this.mPath.close();
        return this.mPath;
    }

    public void getBounds(Rect rect) {
        this.mPath.computeBounds(this.mArc1, true);
        this.mArc1.roundOut(rect);
    }
}
