package dev.bbkb.ime.core.ime;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.appcompat.widget.AppCompatImageView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.ResourceConfigManager;

/** The swipe-to-delete icon overlay ({@code delete_animation_view}); the icon comes from the layout's {@code android:src}. */
public class SwipeToDeleteAnimatorView extends AppCompatImageView implements Animator.AnimatorListener {

    private static final String TAG = "SwipeToDeleteAnimatorView";

    private final float minIconScale;

    private final float maxIconScale;

    private final int maxAlpha;

    private final int minAlpha;

    private final float fadeInRatio;

    private final int maxDurationMs;

    private final int minDurationMs;

    private final int suggestionsStripHeight;

    private AnimatorSet animatorSet;

    private float startX;

    private float startY;

    private float endX;

    private int keyboardHeight;

    private int keyboardWidth;

    private float viewHeight;

    private boolean suggestionStripShown;

    @Override
    public void onAnimationCancel(Animator animator) {
    }

    @Override
    public void onAnimationRepeat(Animator animator) {
    }

    public SwipeToDeleteAnimatorView(Context context) {
        this(context, null);
    }

    public SwipeToDeleteAnimatorView(Context context, AttributeSet attributeSet) {
        super(new androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_BlackberryKeyboard_IME), attributeSet);
        setVisibility(View.INVISIBLE);
        Resources resources = context.getResources();
        this.fadeInRatio = resources.getFraction(R.fraction.config_delete_animation_fade_in_ratio, 1, 1);
        this.maxDurationMs = resources.getInteger(R.integer.config_delete_animation_max_duration_ms);
        this.minDurationMs = resources.getInteger(R.integer.config_delete_animation_min_duration_ms);
        this.minIconScale = resources.getFraction(R.fraction.config_delete_animation_min_icon_scale, 1, 1);
        this.maxIconScale = resources.getFraction(R.fraction.config_delete_animation_max_icon_scale, 1, 1);
        this.maxAlpha = (int) resources.getFraction(R.fraction.config_delete_animation_max_alpha, 255, 255);
        this.minAlpha = (int) resources.getFraction(R.fraction.config_delete_animation_min_alpha, 255, 255);
        this.suggestionsStripHeight = ResourceConfigManager.getSuggestionsStripHeight(resources);
    }

    /**
     * @param startX start x of the sweep
     * @param startY start y of the touch
     * @param endX end x of the sweep
     */
    public void startSwipeAnimation(float startX, float startY, float endX) {
        float f4;
        if (this.keyboardHeight > this.suggestionsStripHeight || this.suggestionStripShown) {
            AnimatorSet animatorSet = this.animatorSet;
            if (animatorSet != null) {
                animatorSet.cancel();
            }
            this.animatorSet = new AnimatorSet();
            this.animatorSet.addListener(this);
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.viewHeight = getHeight();
            int iAbs = (int) (this.maxDurationMs * (Math.abs(this.endX - this.startX) / this.keyboardWidth));
            int i = this.minDurationMs;
            if (iAbs < i) {
                iAbs = i;
            }
            if (this.keyboardHeight <= this.suggestionsStripHeight && this.suggestionStripShown) {
                                int parentHeight = getParent() instanceof View ? ((View) getParent()).getHeight() : (int) this.viewHeight;
                f4 = (float) (((parentHeight - this.viewHeight) / 2.0d) * (-1.0d));
            } else {
                float f5 = this.startY;
                float f6 = this.viewHeight;
                if (f5 <= f6) {
                    f4 = f6 - this.keyboardHeight;
                } else {
                    f4 = f5 - this.keyboardHeight;
                }
            }
            ObjectAnimator objectAnimatorOfInt = ObjectAnimator.ofFloat(this, "alpha", this.minAlpha / 255.0f, this.maxAlpha / 255.0f);
            objectAnimatorOfInt.setInterpolator(new FadeInOutInterpolator(this.fadeInRatio));
            ObjectAnimator objectAnimatorOfFloat = ObjectAnimator.ofFloat(this, "translationX", this.startX, this.endX);
            objectAnimatorOfFloat.setInterpolator(new AccelerateDecelerateInterpolator());
            this.animatorSet.setDuration(iAbs).playTogether(objectAnimatorOfInt, objectAnimatorOfFloat, ObjectAnimator.ofFloat(this, "scaleX", this.minIconScale, this.maxIconScale), ObjectAnimator.ofFloat(this, "scaleY", this.minIconScale, this.maxIconScale), ObjectAnimator.ofFloat(this, "translationY", f4, f4));
            this.animatorSet.start();
        }
    }

    public boolean isAnimating() {
        AnimatorSet animatorSet = this.animatorSet;
        if (animatorSet != null) {
            return animatorSet.isStarted();
        }
        return false;
    }

    public void endAnimation() {
        AnimatorSet animatorSet = this.animatorSet;
        if (animatorSet != null) {
            animatorSet.end();
        }
    }

    /** @param height keyboard height in px */
    public void setCurrentKeyboardHeight(int height) {
        this.keyboardHeight = height;
    }

    /** @param shown true if the suggestion strip is shown */
    public void setSuggestionStripShown(boolean shown) {
        this.suggestionStripShown = shown;
    }

    /** @param width keyboard width in px */
    public void setCurrentKeyboardWidth(int width) {
        this.keyboardWidth = width;
    }

    @Override
    public void onAnimationStart(Animator animator) {
        setVisibility(View.VISIBLE);
    }

    @Override
    public void onAnimationEnd(Animator animator) {
        setVisibility(View.INVISIBLE);
        AnimatorSet animatorSet = this.animatorSet;
        if (animatorSet != null) {
            animatorSet.removeAllListeners();
            this.animatorSet = null;
        }
    }

    
    class FadeInOutInterpolator implements Interpolator {

        final float fadeInSlope;

        final float fadeOutSlope;

        final float fadeInEndFraction;

        /** @param fadeInEndFraction fraction of the animation spent fading in */
        FadeInOutInterpolator(float fadeInEndFraction) {
            this.fadeInEndFraction = fadeInEndFraction;
            this.fadeInSlope = 1.0f / fadeInEndFraction;
            this.fadeOutSlope = 1.0f / (1.0f - SwipeToDeleteAnimatorView.this.fadeInRatio);
        }

        @Override
        public float getInterpolation(float input) {
            float f2 = this.fadeInEndFraction;
            if (f2 >= 1.0f || f2 == 0.0f) {
                return 1.0f;
            }
            if (input <= f2) {
                return input * this.fadeInSlope;
            }
            float f3 = this.fadeOutSlope;
            return ((-f3) * input) + f3;
        }
    }
}
