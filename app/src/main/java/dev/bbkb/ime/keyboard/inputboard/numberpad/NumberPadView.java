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
 * The Number Pad board's view. Touch goes through {@link PointerTracker}, not
 * SimplifiedKeyboardView's handler, for the normal onCodeInput path and moreKeys popups.
 */
public class NumberPadView extends SimplifiedKeyboardView {

    public NumberPadView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, R.attr.mainKeyboardViewStyle);
    }

    /** @param defStyleAttr the default style attribute */
    public NumberPadView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
    }

    @Override
    protected boolean drawsFlatModernBackground() {
        // Real key surfaces, not the aux bars' flat wash; also selects standard icon tinting.
        return false;
    }

    @Override
    protected boolean shouldSkipSpacebarIcon(dev.bbkb.ime.keyboard.Key key) {
        // This board's space is a normal-width key: without the glyph it reads as blank.
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

    @Override
    @SuppressLint({"ClickableViewAccessibility"})
    public boolean onTouchEvent(MotionEvent motionEvent) {
        PointerTracker tracker = PointerTracker.getPointerTracker(
                motionEvent.getPointerId(motionEvent.getActionIndex()));
        tracker.processMotionEvent(motionEvent, this.keyboardActionListener);
        return true;
    }
}
