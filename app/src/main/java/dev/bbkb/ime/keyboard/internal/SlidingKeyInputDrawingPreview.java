package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.CoordinateUtils;


public final class SlidingKeyInputDrawingPreview extends AbstractDrawingPreview {

    private final float mCircleRadius;

    private final float mShadowRadius;

    private boolean mShowsGestureTrail;

    private final int[] mPreviousCoords = CoordinateUtils.newCoordinateArray();

    private final int[] mCurrentCoords = CoordinateUtils.newCoordinateArray();

    private final RoundedLine mRoundedLine = new RoundedLine();

    private final Paint mPaint = new Paint();

    @Override // dev.bbkb.ime.keyboard.internal.AbstractDrawingPreview
    public void onDeallocateMemory() {
    }

    public SlidingKeyInputDrawingPreview(TypedArray typedArray) {
        float dimension = typedArray.getDimension(R.styleable.MainKeyboardView_slidingKeyInputPreviewWidth, 0.0f) / 2.0f;
        this.mCircleRadius = (typedArray.getInt(R.styleable.MainKeyboardView_slidingKeyInputPreviewBodyRatio, 100) / 100.0f) * dimension;
        int i = typedArray.getInt(R.styleable.MainKeyboardView_slidingKeyInputPreviewShadowRatio, 0);
        this.mShadowRadius = i > 0 ? dimension * (i / 100.0f) : 0.0f;
    }

    public void dismissGestureTrail() {
        this.mShowsGestureTrail = false;
        invalidateDrawingView();
    }

    @Override // dev.bbkb.ime.keyboard.internal.AbstractDrawingPreview
    public void drawPreview(Canvas canvas) {
        if (isPreviewEnabled() && this.mShowsGestureTrail) {
            // Pull-at-draw: accent at preview alpha (was the slidingKeyInputPreviewColor attr).
            int color = dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE.getSlidingPreviewColor();
            this.mPaint.setColor(color);
            if (this.mShadowRadius > 0.0f) {
                this.mPaint.setShadowLayer(this.mShadowRadius, 0.0f, 0.0f, color);
            }
            float f = this.mCircleRadius;
            canvas.drawPath(this.mRoundedLine.makeRoundedLine(CoordinateUtils.x(this.mPreviousCoords), CoordinateUtils.y(this.mPreviousCoords), f, CoordinateUtils.x(this.mCurrentCoords), CoordinateUtils.y(this.mCurrentCoords), f), this.mPaint);
        }
    }

    public void showGestureTrail(PointerTracker c1084t) {
        c1084t.getDownCoordinates(this.mPreviousCoords);
        c1084t.getLastCoordinates(this.mCurrentCoords);
        this.mShowsGestureTrail = true;
        invalidateDrawingView();
    }
}
