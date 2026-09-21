package dev.bbkb.ime.keyboard.inputboard.numberpad;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.keyboard.internal.PointerTracker;

/**
 * View for the Number Pad input board (a 7x4 math/numeric grid shown in place of the
 * main keyboard, toggled from the UIM bar).
 *
 * Like {@link dev.bbkb.ime.keyboard.slideboard.NumericSubpanelKeyboardView},
 * touch is routed through {@link PointerTracker} rather than SimplifiedKeyboardView's own
 * handler: the tracker's static action listener (installed by MainKeyboardView) commits
 * codes through the IME's normal onCodeInput path and provides moreKeys long-press popups,
 * neither of which the simplified handler supports.
 */
public class NumberPadView extends SimplifiedKeyboardView {

    public NumberPadView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    public NumberPadView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView
    protected boolean drawsFlatModernBackground() {
        // Real key surfaces, not the aux bars' flat wash: keyColor for the digits
        // (backgroundType normal), keyColorAlt for the symbol keys (functional).
        // Also selects KeyboardView's standard icon tinting (enter arrow, delete).
        return false;
    }

    @Override // dev.bbkb.ime.keyboard.KeyboardView
    protected boolean shouldSkipSpacebarIcon(dev.bbkb.ime.keyboard.Key key) {
        // Cap themes suppress the main spacebar's bar glyph, but this board's space is a
        // normal-width key in the utility column — without the glyph it reads as blank.
        return false;
    }

    public boolean isShowing() {
        return getVisibility() == View.VISIBLE;
    }

    public void show() {
        setVisibility(View.VISIBLE);
        bringToFront();
    }

    public void hide() {
        setVisibility(View.GONE);
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView, android.view.View
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        PointerTracker tracker = PointerTracker.getPointerTracker(
                motionEvent.getPointerId(motionEvent.getActionIndex()));
        tracker.processMotionEvent(motionEvent, this.keyboardActionListener);
        return true;
    }
}
