package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;

import dev.bbkb.ime.R;



public final class GestureStrokeDrawingParams {

    public final double mMinSamplingDistance;

    public final double mMaxInterpolationAngularThreshold;

    public final double mMaxInterpolationDistanceThreshold;

    public final int mMaxInterpolationSegments;

    public GestureStrokeDrawingParams(TypedArray typedArray) {
        double radians;
        this.mMinSamplingDistance = typedArray.getDimension(R.styleable.MainKeyboardView_gestureTrailMinSamplingDistance, 0.0f);
        int integer = typedArray.getInteger(R.styleable.MainKeyboardView_gestureTrailMaxInterpolationAngularThreshold, 0);
        if (integer <= 0) {
            radians = Math.toRadians(15.0d);
        } else {
            radians = Math.toRadians(integer);
        }
        this.mMaxInterpolationAngularThreshold = radians;
        this.mMaxInterpolationDistanceThreshold = typedArray.getDimension(R.styleable.MainKeyboardView_gestureTrailMaxInterpolationDistanceThreshold, 0.0f);
        this.mMaxInterpolationSegments = typedArray.getInteger(R.styleable.MainKeyboardView_gestureTrailMaxInterpolationSegments, 4);
    }
}
