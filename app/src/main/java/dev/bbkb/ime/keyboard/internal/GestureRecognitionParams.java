package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.ResourceConfigManager;



public final class GestureRecognitionParams {

    public static final GestureRecognitionParams EMPTY = new GestureRecognitionParams();

    public final int mStaticTimeThresholdAfterFastTyping;

    public final float mDetectFastMoveSpeedThreshold;

    public final int mDynamicThresholdDecayDuration;

    public final int mDynamicTimeThresholdFrom;

    public final int mDynamicTimeThresholdTo;

    public final float mDynamicDistanceThresholdFrom;

    public final float mDynamicDistanceThresholdTo;

    public final float mSamplingMinimumDistance;

    public final int mRecognitionMinimumTime;

    public final float mRecognitionSpeedThreshold;

    private GestureRecognitionParams() {
        this.mStaticTimeThresholdAfterFastTyping = 350;
        this.mDetectFastMoveSpeedThreshold = 1.5f;
        this.mDynamicThresholdDecayDuration = 450;
        this.mDynamicTimeThresholdFrom = 300;
        this.mDynamicTimeThresholdTo = 20;
        this.mDynamicDistanceThresholdFrom = 6.0f;
        this.mDynamicDistanceThresholdTo = 0.35f;
        this.mSamplingMinimumDistance = 0.16666667f;
        this.mRecognitionMinimumTime = 100;
        this.mRecognitionSpeedThreshold = 5.5f;
    }

    public GestureRecognitionParams(TypedArray typedArray) {
        this.mStaticTimeThresholdAfterFastTyping = typedArray.getInt(R.styleable.MainKeyboardView_gestureStaticTimeThresholdAfterFastTyping, EMPTY.mStaticTimeThresholdAfterFastTyping);
        this.mDetectFastMoveSpeedThreshold = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.MainKeyboardView_gestureDetectFastMoveSpeedThreshold, EMPTY.mDetectFastMoveSpeedThreshold);
        this.mDynamicThresholdDecayDuration = typedArray.getInt(R.styleable.MainKeyboardView_gestureDynamicThresholdDecayDuration, EMPTY.mDynamicThresholdDecayDuration);
        this.mDynamicTimeThresholdFrom = typedArray.getInt(R.styleable.MainKeyboardView_gestureDynamicTimeThresholdFrom, EMPTY.mDynamicTimeThresholdFrom);
        this.mDynamicTimeThresholdTo = typedArray.getInt(R.styleable.MainKeyboardView_gestureDynamicTimeThresholdTo, EMPTY.mDynamicTimeThresholdTo);
        this.mDynamicDistanceThresholdFrom = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.MainKeyboardView_gestureDynamicDistanceThresholdFrom, EMPTY.mDynamicDistanceThresholdFrom);
        this.mDynamicDistanceThresholdTo = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.MainKeyboardView_gestureDynamicDistanceThresholdTo, EMPTY.mDynamicDistanceThresholdTo);
        this.mSamplingMinimumDistance = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.MainKeyboardView_gestureSamplingMinimumDistance, EMPTY.mSamplingMinimumDistance);
        this.mRecognitionMinimumTime = typedArray.getInt(R.styleable.MainKeyboardView_gestureRecognitionMinimumTime, EMPTY.mRecognitionMinimumTime);
        this.mRecognitionSpeedThreshold = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.MainKeyboardView_gestureRecognitionSpeedThreshold, EMPTY.mRecognitionSpeedThreshold);
    }
}
