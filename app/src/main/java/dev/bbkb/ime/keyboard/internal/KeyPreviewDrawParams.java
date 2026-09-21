package dev.bbkb.ime.keyboard.internal;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Property;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import dev.bbkb.ime.R;



public final class KeyPreviewDrawParams {

    private static final AccelerateInterpolator ACCELERATE_INTERPOLATOR = new AccelerateInterpolator();

    private static final DecelerateInterpolator DECELERATE_INTERPOLATOR = new DecelerateInterpolator();

    public final int mPreviewOffset;

    public final int mPreviewHeight;


    private final int mShowUpAnimatorResId;

    private final int mDismissAnimatorResId;

    private boolean mHasCustomAnimationParams;

    private int mShowUpDuration;

    private int mDismissDuration;

    private float mShowUpStartScaleX;

    private float mShowUpStartScaleY;

    private float mDismissEndScaleX;

    private float mDismissEndScaleY;

    private int mLingerTimeout;

    private boolean mShowPopup = true;

    private int mPreviewVisibleWidth;

    private int mPreviewVisibleHeight;

    private int mPreviewVisibleOffset;

    public KeyPreviewDrawParams(TypedArray typedArray) {
        this.mPreviewOffset = typedArray.getDimensionPixelOffset(R.styleable.MainKeyboardView_keyPreviewOffset, 0);
        this.mPreviewHeight = typedArray.getDimensionPixelSize(R.styleable.MainKeyboardView_keyPreviewHeight, 0);
        this.mLingerTimeout = typedArray.getInt(R.styleable.MainKeyboardView_keyPreviewLingerTimeout, 0);
        this.mShowUpAnimatorResId = typedArray.getResourceId(R.styleable.MainKeyboardView_keyPreviewShowUpAnimator, 0);
        this.mDismissAnimatorResId = typedArray.getResourceId(R.styleable.MainKeyboardView_keyPreviewDismissAnimator, 0);
    }

    public void setVisibleOffset(int i) {
        this.mPreviewVisibleOffset = i;
    }

    public int getVisibleOffset() {
        return this.mPreviewVisibleOffset;
    }

    public void setGeometry(View view) {
        int measuredWidth = view.getMeasuredWidth();
        int i = this.mPreviewHeight;
        this.mPreviewVisibleWidth = (measuredWidth - view.getPaddingLeft()) - view.getPaddingRight();
        this.mPreviewVisibleHeight = (i - view.getPaddingTop()) - view.getPaddingBottom();
        setVisibleOffset(this.mPreviewOffset - view.getPaddingBottom());
    }

    public int getVisibleWidth() {
        return this.mPreviewVisibleWidth;
    }

    public int getVisibleHeight() {
        return this.mPreviewVisibleHeight;
    }

    public void setPopupEnabled(boolean z, int i) {
        this.mShowPopup = z;
        this.mLingerTimeout = i;
    }

    public boolean isPopupEnabled() {
        return this.mShowPopup;
    }

    public int getLingerTimeout() {
        return this.mLingerTimeout;
    }

    public void setAnimationParams(boolean z, float f, float f2, int i, float f3, float f4, int i2) {
        this.mHasCustomAnimationParams = z;
        this.mShowUpStartScaleX = f;
        this.mShowUpStartScaleY = f2;
        this.mShowUpDuration = i;
        this.mDismissEndScaleX = f3;
        this.mDismissEndScaleY = f4;
        this.mDismissDuration = i2;
    }

    public Animator createShowUpAnimator(View view) throws Resources.NotFoundException {
        if (this.mHasCustomAnimationParams) {
            ObjectAnimator objectAnimatorOfFloat = ObjectAnimator.ofFloat(view, (Property<View, Float>) View.SCALE_X, this.mShowUpStartScaleX, 1.0f);
            ObjectAnimator objectAnimatorOfFloat2 = ObjectAnimator.ofFloat(view, (Property<View, Float>) View.SCALE_Y, this.mShowUpStartScaleY, 1.0f);
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.play(objectAnimatorOfFloat).with(objectAnimatorOfFloat2);
            animatorSet.setDuration(this.mShowUpDuration);
            animatorSet.setInterpolator(DECELERATE_INTERPOLATOR);
            return animatorSet;
        }
        Animator animatorLoadAnimator = AnimatorInflater.loadAnimator(view.getContext(), this.mShowUpAnimatorResId);
        animatorLoadAnimator.setTarget(view);
        animatorLoadAnimator.setInterpolator(DECELERATE_INTERPOLATOR);
        return animatorLoadAnimator;
    }

    public Animator createDismissAnimator(View view) throws Resources.NotFoundException {
        if (this.mHasCustomAnimationParams) {
            ObjectAnimator objectAnimatorOfFloat = ObjectAnimator.ofFloat(view, (Property<View, Float>) View.SCALE_X, this.mDismissEndScaleX);
            ObjectAnimator objectAnimatorOfFloat2 = ObjectAnimator.ofFloat(view, (Property<View, Float>) View.SCALE_Y, this.mDismissEndScaleY);
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.play(objectAnimatorOfFloat).with(objectAnimatorOfFloat2);
            animatorSet.setDuration(Math.min(this.mDismissDuration, this.mLingerTimeout));
            animatorSet.setInterpolator(ACCELERATE_INTERPOLATOR);
            return animatorSet;
        }
        Animator animatorLoadAnimator = AnimatorInflater.loadAnimator(view.getContext(), this.mDismissAnimatorResId);
        animatorLoadAnimator.setTarget(view);
        animatorLoadAnimator.setInterpolator(ACCELERATE_INTERPOLATOR);
        animatorLoadAnimator.setDuration(this.mLingerTimeout);
        return animatorLoadAnimator;
    }
}
