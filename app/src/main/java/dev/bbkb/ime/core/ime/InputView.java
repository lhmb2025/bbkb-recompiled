package dev.bbkb.ime.core.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.RelativeLayout;

import dev.bbkb.ime.keyboard.KeyboardColorManager;

/**
 * The IME's root input view (input_view.xml).
 *
 * <p>Audit CT-20/TD-4: this class used to carry a {@code MotionEventForwarder} hierarchy that
 * forwarded touches landing in the keyboard's top padding to the strip above it. Its only
 * surviving subclass, {@code KeyboardTopPaddingForwarder}, had been reduced to
 * {@code onInterceptTouchEvent -> return false}, so {@code InputView.onInterceptTouchEvent} could
 * never take its {@code true} branch, {@code activeForwarder} was permanently null and the whole
 * {@code onTouchEvent} override was dead — yet the per-touch geometry work in front of the closed
 * path ({@code getGlobalVisibleRect} plus two coordinate reads) still ran on every finger-down on
 * the keyboard. All of it is removed; if top-padding forwarding is wanted back it should be
 * rebuilt against {@code AuxBarView}.
 *
 * <p>The nav-bar bottom padding ({@link ImeInsets#installNavBarInsetListener}) leaves a strip
 * under the keyboard that nothing else paints, so the app behind showed through under the gesture
 * pill and the system's IME back/switcher buttons. This view fills that strip with the keyboard
 * background while IME content is shown ({@link #setNavBarFillShown}) — or, before Android 15,
 * sets the window's nav-bar color to it ({@link #setImeWindow}) — and tells the system
 * whether the background is light so those buttons are drawn dark on light themes.
 */
public final class InputView extends RelativeLayout {

    private final Paint navBarFill = new Paint();
    private boolean navBarFillShown;
    private Window imeWindow;

    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = () -> {
        applyNavBarColors();
        return kotlin.Unit.INSTANCE;
    };

    public InputView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet, 0);
        navBarFill.setColor(KeyboardColorManager.INSTANCE.getBackgroundColor());
    }

    /**
     * Before Android 15 the IME window stops at the nav bar and the system paints the area under
     * it from the window's nav-bar color, so that color has to follow the keyboard background too.
     */
    public void setImeWindow(Window window) {
        imeWindow = window;
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        applyNavBarColors();
    }

    /** Called from {@link ImeInsets#compute}: fill the nav-bar strip only while content is shown. */
    void setNavBarFillShown(boolean shown) {
        if (navBarFillShown != shown) {
            navBarFillShown = shown;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyboardColorManager.INSTANCE.addObserver(colorObserver);
        applyNavBarColors();
    }

    @Override
    protected void onDetachedFromWindow() {
        KeyboardColorManager.INSTANCE.removeObserver(colorObserver);
        super.onDetachedFromWindow();
    }

    private void applyNavBarColors() {
        int background = KeyboardColorManager.INSTANCE.getBackgroundColor();
        navBarFill.setColor(background);
        invalidate();
        if (imeWindow != null) imeWindow.setNavigationBarColor(background);
        boolean light = Color.luminance(background) > 0.5f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindowInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                        light ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View decor = getRootView();
            int flags = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light
                    ? flags | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int strip = getPaddingBottom();
        if (navBarFillShown && strip > 0) {
            canvas.drawRect(0, getHeight() - strip, getWidth(), getHeight(), navBarFill);
        }
        super.dispatchDraw(canvas);
    }
}
