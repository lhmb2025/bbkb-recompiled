package dev.bbkb.ime.core.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Window;
import android.widget.RelativeLayout;

import androidx.core.view.WindowCompat;

import dev.bbkb.ime.keyboard.KeyboardColorManager;

/**
 * The IME's root input view (input_view.xml). Paints the nav-bar padding strip
 * ({@link ImeInsets#installNavBarInsetListener}) with the keyboard background while content is
 * shown, or before Android 15 sets the window's nav-bar color, and marks light backgrounds so the
 * system's IME buttons draw dark.
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

    /** Before Android 15 the system paints under the nav bar from the window's nav-bar color, so it follows the keyboard. */
    public void setImeWindow(Window window) {
        imeWindow = window;
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
        }
        applyNavBarColors();
    }

    /** From {@link ImeInsets#compute}: fill the strip only while content is shown. */
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
        // No window yet: setImeWindow() comes straight after the view is installed and applies this.
        if (imeWindow == null) return;
        imeWindow.setNavigationBarColor(background);
        WindowCompat.getInsetsController(imeWindow, this)
                .setAppearanceLightNavigationBars(Color.luminance(background) > 0.5f);
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
