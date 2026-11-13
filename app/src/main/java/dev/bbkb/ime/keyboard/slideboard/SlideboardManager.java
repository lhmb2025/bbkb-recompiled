package dev.bbkb.ime.keyboard.slideboard;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.MainKeyboardView;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager;



public class SlideboardManager implements SettingsManager.OnSettingsChangeListener, SlideboardComponent.Listener {

    private final Context themedContext;

    private final BlackBerryIME ime;

    private QuickPhrasesController quickPhrasesController;

    private NumericSubpanelController numericSubpanelController;

    private ImageView dividerView;

    private SlideboardComponent leftComponent;

    private SlideboardComponent rightComponent;

    private FrameLayout keyboardFrame;

    private NumericSubpanelKeyboardView numericSubpanelView;

    private QuickPhrasesView quickPhrasesView;

    private int screenWidth;

    private int maxTranslation;

    private float translationX = 0.0f;

    private final KeyboardSwitcher keyboardSwitcher = KeyboardSwitcher.getInstance();

    /** The snap currently running, so teardown can stop it driving a detached view. */
    private SnapAnimation activeSnap;

    public SlideboardManager(Context context, View view, BlackBerryIME blackBerryIME) {
        this.themedContext = new ContextThemeWrapper(context, dev.bbkb.ime.R.style.KeyboardTheme_LXX);
        this.ime = blackBerryIME;
        this.quickPhrasesController = this.ime.getQuickPhrasesController();
        this.numericSubpanelController = this.ime.getNumericSubpanelController();
        SettingsManager.getInstance().addOnSettingsChangeListener(this);
        initialize(view);
    }

    public void initialize(View view) {
        this.dividerView = (ImageView) view.findViewById(R.id.subpanel_divider_vertical);
        applyDividerFill();
        this.keyboardFrame = (FrameLayout) view.findViewById(R.id.keyboard_frame);
        this.screenWidth = ResourceConfigManager.getScreenWidthPixels(this.themedContext.getResources());
        this.maxTranslation = this.screenWidth / 2;
    }

    /** Width of the shadow the keyboard casts on an open panel, in dp. */
    private static final float EDGE_SHADOW_WIDTH_DP = 12f;

    /**
     * The shadow the keyboard edge casts on an open panel (re-applied on settings changes so a theme
     * switch leaves no stale color; mirrored for the left panel). The ImageView measures from the
     * intrinsic width. The intrinsic height must stay 1px: in the wrap_content keyboard_frame a tall
     * first-pass height lifts the keyboard to the top of the screen during a slide; fitXY stretches it.
     */
    private void applyDividerFill() {
        if (this.dividerView == null) {
            return;
        }
        final float density = this.dividerView.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable shadow = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE.getSlideEdgeShadowColor(),
                        android.graphics.Color.TRANSPARENT,
                });
        shadow.setSize(Math.max(1, Math.round(EDGE_SHADOW_WIDTH_DP * density)), 1);
        this.dividerView.setImageDrawable(shadow);
    }

    public void hide() {
        SnapAnimation snap = this.activeSnap;
        this.activeSnap = null;
        if (snap != null) {
            snap.cancel();
        }
        SettingsManager.getInstance().removeOnSettingsChangeListener(this);
        SlideboardComponent interfaceC1082d = this.leftComponent;
        if (interfaceC1082d != null) {
            interfaceC1082d.setListener(null);
            this.leftComponent = null;
        }
        SlideboardComponent interfaceC1082d2 = this.rightComponent;
        if (interfaceC1082d2 != null) {
            interfaceC1082d2.setListener(null);
            this.rightComponent = null;
        }
        this.numericSubpanelView = null;
        this.quickPhrasesView = null;
    }

    /** @param c0965e the keyboard now shown */
    public void setKeyboard(Keyboard c0965e) {
        if (c0965e != null && c0965e.isSlideboardEligible()) {
            SlideboardComponent interfaceC1082d = this.leftComponent;
            if (interfaceC1082d == null || interfaceC1082d.isShowing()) {
                return;
            }
            setTranslation(0.0f, false);
            return;
        }
        animateTranslation(0.0f, true, true, true);
    }

    /** @param c0804d the new settings */
    @Override
    public void onSettingsValuesChanged(SettingsValues c0804d) {
        applyDividerFill();
        applySettings(c0804d);
        setTranslation(this.translationX, false);
        this.quickPhrasesController.loadPhrases(c0804d);
        this.numericSubpanelController.applySettings(c0804d);
    }

    /** @param c0804d the settings that place the numeric panel left or right */
    private void applySettings(SettingsValues c0804d) {
        if (c0804d.slideboardNumericLocation == 0) {
            this.leftComponent = this.numericSubpanelView;
            this.rightComponent = this.quickPhrasesView;
        } else {
            this.rightComponent = this.numericSubpanelView;
            this.leftComponent = this.quickPhrasesView;
        }
    }

    /** @param interfaceC1082d the numeric or quick-phrases panel to attach */
    public void setSlideboardComponent(SlideboardComponent interfaceC1082d) {
        if (interfaceC1082d instanceof NumericSubpanelKeyboardView) {
            this.numericSubpanelView = (NumericSubpanelKeyboardView) interfaceC1082d;
            this.numericSubpanelView.setGestureDetector(this.ime.getOrCreateGestureDetector());
            this.numericSubpanelController.setView(this.numericSubpanelView);
        } else if (interfaceC1082d instanceof QuickPhrasesView) {
            this.quickPhrasesView = (QuickPhrasesView) interfaceC1082d;
            this.quickPhrasesController.setView(this.quickPhrasesView);
        }
        this.keyboardFrame.addView((View) interfaceC1082d, 0);
        interfaceC1082d.setListener(this);
        applySettings(SettingsManager.getInstance().getSettingsValues());
    }

    /** Collapse the slideboard ("show" the keyboard): both panels hidden, no divider. */
    public void show() {
        SlideboardComponent interfaceC1082d = this.leftComponent;
        if (interfaceC1082d != null) {
            interfaceC1082d.hide();
        }
        SlideboardComponent interfaceC1082d2 = this.rightComponent;
        if (interfaceC1082d2 != null) {
            interfaceC1082d2.hide();
        }
        ImageView imageView = this.dividerView;
        if (imageView != null) {
            imageView.setVisibility(View.GONE);
        }
    }

    public void showNumericPanel() {
        SlideboardComponent interfaceC1082d = this.leftComponent;
        if (interfaceC1082d != null) {
            interfaceC1082d.show();
        }
        SlideboardComponent interfaceC1082d2 = this.rightComponent;
        if (interfaceC1082d2 != null) {
            interfaceC1082d2.show();
        }
        // The edge shadow keeps its intrinsic width; stretching it would tint the whole panel.
    }

    public boolean isShowing() {
        SlideboardComponent interfaceC1082d = this.leftComponent;
        if (interfaceC1082d != null && interfaceC1082d.isShowing()) {
            return true;
        }
        SlideboardComponent interfaceC1082d2 = this.rightComponent;
        return interfaceC1082d2 != null && interfaceC1082d2.isShowing();
    }

    public void reset() {
        int i = this.screenWidth / 4;
        float f = this.translationX;
        float f2 = i;
        if (f < f2 && f > (-i)) {
            setTranslation(0.0f, true);
            return;
        }
        float f3 = this.translationX;
        if (f3 >= f2) {
            setTranslation(this.maxTranslation, true);
        } else if (f3 <= (-i)) {
            setTranslation(-this.maxTranslation, true);
        }
    }

    /**
     * @param f the keyboard's target x translation
     * @param z true to animate
     */
    public void setTranslation(float f, boolean z) {
        animateTranslation(f, z, false, false);
    }

    /**
     * @param f the keyboard's target x translation
     * @param z true to animate (a snap), false to set it directly
     * @param z2 true to move even if the keyboard is not slideboard-eligible
     * @param z3 true to collapse the panels when the snap ends
     */
    public void animateTranslation(float f, boolean z, boolean z2, boolean z3) {
        if (f > this.maxTranslation || f < (-this.maxTranslation) || f == this.translationX) {
            return;
        }
        MainKeyboardView mainKeyboardViewM6790T = this.keyboardSwitcher.getMainKeyboardView();
        if (z2 || (mainKeyboardViewM6790T.getKeyboard() != null && mainKeyboardViewM6790T.getKeyboard().isSlideboardEligible())) {
            if (this.translationX == 0.0f && f != 0.0f) {
                this.ime.hideFlickSuggestions();
            }
            this.translationX = f;
            // Fully open (either side) or closed: the states that change the UIM's alphabet key.
            if (this.translationX == 0.0f || Math.abs(this.translationX) == this.maxTranslation) {
                UnifiedInputBoardManager boardManager = this.keyboardSwitcher.getUnifiedInputBoardManager();
                if (boardManager != null) {
                    boardManager.updateAlphabetKeyForSlideboard();
                }
            }
            if (!z) {
                mainKeyboardViewM6790T.setTranslationX(f);
                if (this.leftComponent != null) {
                    if (SettingsManager.getInstance().getSettingsValues().slideboardStillBoardsEnabled) {
                        this.leftComponent.setXTranslation(0.0f);
                    } else {
                        this.leftComponent.setXTranslation(f - this.leftComponent.getSlideBoardWidth());
                    }
                }
                if (this.rightComponent != null) {
                    if (SettingsManager.getInstance().getSettingsValues().slideboardStillBoardsEnabled) {
                        this.rightComponent.setXTranslation(this.screenWidth / 2.0f);
                    } else {
                        this.rightComponent.setXTranslation(this.screenWidth + f);
                    }
                }
                if (f < 0.0f) {
                    this.dividerView.setVisibility(View.VISIBLE);
                    this.dividerView.setScaleX(1.0f);
                    this.dividerView.setTranslationX(f + this.screenWidth);
                    return;
                } else {
                    if (f > 0.0f) {
                        this.dividerView.setVisibility(View.VISIBLE);
                        this.dividerView.setScaleX(-1.0f);
                        this.dividerView.setTranslationX(f - this.dividerView.getWidth());
                        return;
                    }
                    this.dividerView.setVisibility(View.GONE);
                    return;
                }
            }
            int leftWidth = this.leftComponent != null ? this.leftComponent.getSlideBoardWidth() : 0;
            SnapAnimation snap = new SnapAnimation(this.leftComponent, mainKeyboardViewM6790T,
                    this.rightComponent, f, leftWidth, this.screenWidth, z3);
            this.activeSnap = snap;
            snap.start();
        }
    }

    public float getTranslationX() {
        return this.translationX;
    }

    @Override
    public void onSlideComplete() {
        setTranslation(0.0f, true);
    }

    
    private class SnapAnimation implements Animator.AnimatorListener, ValueAnimator.AnimatorUpdateListener {

        private ValueAnimator animator;

        private View keyboardView;

        private SlideboardComponent animRightComponent;

        private SlideboardComponent animLeftComponent;

        private DecelerateInterpolator interpolator = new DecelerateInterpolator(2.0f);

        private int leftComponentWidth;

        private int animScreenWidth;

        private float targetTranslation;

        private boolean showOnEnd;

        /** Read once in {@link #start()}: it cannot change mid-snap. */
        private boolean stillBoardsEnabled;

        private boolean cancelled;

        @Override
        public void onAnimationCancel(Animator animator) {
            // onAnimationEnd follows and checks the cancelled flag.
        }

        @Override
        public void onAnimationRepeat(Animator animator) {
            // The snap does not repeat.
        }

        @Override
        public void onAnimationStart(Animator animator) {
            // Nothing to set up; the update listener drives the views.
        }

        /**
         * @param interfaceC1082d the left panel
         * @param interfaceC1082d2 the right panel
         * @param f the keyboard's target x translation
         * @param i the left panel's width
         * @param i2 the screen width
         * @param z true to collapse the panels when the snap ends
         */
        SnapAnimation(SlideboardComponent interfaceC1082d, View view, SlideboardComponent interfaceC1082d2, float f, int i, int i2, boolean z) {
            this.animLeftComponent = interfaceC1082d;
            this.keyboardView = view;
            this.animRightComponent = interfaceC1082d2;
            this.targetTranslation = f;
            this.leftComponentWidth = i;
            this.animScreenWidth = i2;
            this.showOnEnd = z;
        }

        void cancel() {
            this.cancelled = true;
            if (this.animator != null) {
                this.animator.cancel();
            }
        }

        void start() {
            this.stillBoardsEnabled =
                    SettingsManager.getInstance().getSettingsValues().slideboardStillBoardsEnabled;
            this.animator = ValueAnimator.ofFloat(this.keyboardView.getTranslationX(), this.targetTranslation);
            this.animator.setInterpolator(this.interpolator);
            this.animator.setDuration(SlideboardManager.this.themedContext.getResources().getInteger(R.integer.config_slideboard_snap_animation));
            this.animator.addUpdateListener(this);
            this.animator.addListener(this);
            this.animator.start();
        }

        @Override
        public void onAnimationUpdate(ValueAnimator valueAnimator) {
            float fFloatValue = ((Float) valueAnimator.getAnimatedValue()).floatValue();
            this.keyboardView.setTranslationX(fFloatValue);
            if (this.animLeftComponent != null) {
                if (this.stillBoardsEnabled) {
                    this.animLeftComponent.setXTranslation(0.0f);
                } else {
                    this.animLeftComponent.setXTranslation(fFloatValue - this.leftComponentWidth);
                }
            }
            if (this.animRightComponent != null) {
                if (this.stillBoardsEnabled) {
                    this.animRightComponent.setXTranslation(this.animScreenWidth / 2.0f);
                } else {
                    this.animRightComponent.setXTranslation(this.animScreenWidth + fFloatValue);
                }
            }
            if (fFloatValue < 0.0f) {
                SlideboardManager.this.dividerView.setScaleX(1.0f);
                SlideboardManager.this.dividerView.setTranslationX(fFloatValue + this.animScreenWidth);
            } else if (fFloatValue > 0.0f) {
                SlideboardManager.this.dividerView.setScaleX(-1.0f);
                SlideboardManager.this.dividerView.setTranslationX(fFloatValue - SlideboardManager.this.dividerView.getWidth());
            } else {
                SlideboardManager.this.dividerView.setVisibility(View.GONE);
            }
        }

        @Override
        public void onAnimationEnd(Animator animator) {
            if (SlideboardManager.this.activeSnap == this) {
                SlideboardManager.this.activeSnap = null;
            }
            if (this.cancelled) {
                // Cancelled by hide(): what this snap captured belongs to a torn-down input view.
                return;
            }
            if (this.showOnEnd) {
                SlideboardManager.this.show();
            }
            if (this.targetTranslation == 0.0f) {
                SlideboardManager.this.ime.updateFlickMetrics();
            }
        }
    }
}
