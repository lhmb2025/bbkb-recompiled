package dev.bbkb.ime.keyboard.auxbar.suggestions;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;

import dev.bbkb.ime.R;



public final class FlickSuggestionAnimationView extends AppCompatTextView implements Animator.AnimatorListener {

    private AnimatorSet mAnimatorSet;

    private final float mAnimateDistance;

    private float mStartY;

    private float mEndY;

    private final float mShadowRadius;

    private final int mShadowColor;

    public FlickSuggestionAnimationView(Context context, AttributeSet attributeSet) {
        super(new android.view.ContextThemeWrapper(context, R.style.Theme_BlackberryKeyboard_IME), attributeSet);
        Resources resources = getContext().getResources();
        this.mAnimateDistance = resources.getDimension(R.dimen.config_suggestion_animate_distance);
        this.mShadowRadius = resources.getDimension(R.dimen.config_flick_prediction_shadow_radius);
        this.mShadowColor = ContextCompat.getColor(getContext(), R.color.flick_suggestion_shadow_color);
    }

    @Override // android.animation.Animator.AnimatorListener
    public void onAnimationCancel(Animator animator) {
    }

    @Override // android.animation.Animator.AnimatorListener
    public void onAnimationRepeat(Animator animator) {
    }

    public void animateSelection(TextView textView, int i, ViewParent viewParent) {
        if (viewParent == null) {
            return;
        }
        AnimatorSet animatorSet = this.mAnimatorSet;
        if (animatorSet != null) {
            animatorSet.cancel();
        }
        this.mAnimatorSet = new AnimatorSet();
        this.mAnimatorSet.addListener(this);
        int[] locationInWindow = new int[2];
        textView.getLocationInWindow(locationInWindow);
        setX(locationInWindow[0]);
        this.mStartY = locationInWindow[1];
        this.mEndY = locationInWindow[1] + this.mAnimateDistance;
        setLayoutParams(new ViewGroup.LayoutParams(textView.getWidth(), textView.getHeight()));
        setText(textView.getText());
        setTextSize(0, textView.getTextSize());
        setTextScaleX(textView.getTextScaleX());
        setTextColor(i);
        setShadowLayer(this.mShadowRadius, 0.0f, 0.0f, this.mShadowColor);
        setGravity(17);
        float alpha = textView.getAlpha();
        this.mAnimatorSet.setDuration(1000L).playTogether(ObjectAnimator.ofFloat(this, "alpha", alpha, alpha * 0.5f), ObjectAnimator.ofFloat(this, "translationY", this.mStartY, this.mEndY));
        this.mAnimatorSet.start();
        ViewGroup viewGroup = (ViewGroup) viewParent;
        viewGroup.removeView(this);
        viewGroup.addView(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        // Without this the running AnimatorSet keeps driving (and holding) a detached view.
        stop();
        super.onDetachedFromWindow();
    }

    protected void removeFromParent() {
        ViewGroup viewGroup = (ViewGroup) getParent();
        if (viewGroup != null) {
            viewGroup.removeView(this);
        }
    }

    protected boolean isRunning() {
        AnimatorSet animatorSet = this.mAnimatorSet;
        if (animatorSet != null) {
            return animatorSet.isStarted();
        }
        return false;
    }

    protected void stop() {
        AnimatorSet animatorSet = this.mAnimatorSet;
        if (animatorSet != null) {
            animatorSet.end();
        }
    }

    @Override // android.animation.Animator.AnimatorListener
    public void onAnimationStart(Animator animator) {
        setVisibility(View.VISIBLE);
    }

    @Override // android.animation.Animator.AnimatorListener
    public void onAnimationEnd(Animator animator) {
        setVisibility(View.GONE);
        AnimatorSet animatorSet = this.mAnimatorSet;
        if (animatorSet != null) {
            animatorSet.removeAllListeners();
            this.mAnimatorSet = null;
        }
        removeFromParent();
    }
}
