package dev.bbkb.ime.core.ime;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.RelativeLayout;

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
 */
public final class InputView extends RelativeLayout {

    public InputView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet, 0);
    }
}
