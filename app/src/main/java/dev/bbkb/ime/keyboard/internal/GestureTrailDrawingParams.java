package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;

import dev.bbkb.ime.R;



final class GestureTrailDrawingParams {

    public final float mTrailStartWidth;

    public final float mTrailEndWidth;

    public final float mTrailBodyRatio;

    public boolean mTrailShadowEnabled;

    public final float mTrailShadowRatio;

    public final int mFadeoutStartDelay;

    public final int mFadeoutDuration;

    public final int mUpdateInterval;

    public final int mTrailLingerDuration;

    public GestureTrailDrawingParams(TypedArray typedArray) {
        this.mTrailStartWidth = typedArray.getDimension(R.styleable.MainKeyboardView_gestureTrailStartWidth, 0.0f);
        this.mTrailEndWidth = typedArray.getDimension(R.styleable.MainKeyboardView_gestureTrailEndWidth, 0.0f);
        this.mTrailBodyRatio = typedArray.getInt(R.styleable.MainKeyboardView_gestureTrailBodyRatio, 100) / 100.0f;
        int i = typedArray.getInt(R.styleable.MainKeyboardView_gestureTrailShadowRatio, 0);
        this.mTrailShadowEnabled = i > 0;
        this.mTrailShadowRatio = i / 100.0f;
        this.mFadeoutStartDelay = typedArray.getInt(R.styleable.MainKeyboardView_gestureTrailFadeoutStartDelay, 0);
        this.mFadeoutDuration = typedArray.getInt(R.styleable.MainKeyboardView_gestureTrailFadeoutDuration, 0);
        this.mTrailLingerDuration = this.mFadeoutStartDelay + this.mFadeoutDuration;
        this.mUpdateInterval = typedArray.getInt(R.styleable.MainKeyboardView_gestureTrailUpdateInterval, 0);
    }
}
