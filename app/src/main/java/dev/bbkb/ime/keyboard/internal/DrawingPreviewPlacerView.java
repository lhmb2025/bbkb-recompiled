package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RelativeLayout;

import dev.bbkb.ime.core.shared.CoordinateUtils;

import java.util.ArrayList;



public final class DrawingPreviewPlacerView extends RelativeLayout {

    private final int[] keyboardViewOrigin;

    private final ArrayList<AbstractDrawingPreview> previews;

    private View frameTrigger;

    public DrawingPreviewPlacerView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.keyboardViewOrigin = CoordinateUtils.newCoordinateArray();
        this.previews = new ArrayList<>();
        setWillNotDraw(false);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    }

    /** @param z true to draw previews on a hardware layer */
    public void setHardwareAcceleratedDrawingEnabled(boolean z) {
        if (z) {
            Paint paint = new Paint();
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        }
    }

    /**
     * Requests a frame that damages almost nothing by invalidating an empty 1x1 child, so the
     * keyboard view can show a re-recorded per-key layer without re-recording itself.
     * @return false when not attached; the caller must invalidate
     */
    public boolean requestFrameWithoutDamage() {
        if (!isAttachedToWindow()) {
            return false;
        }
        View trigger = this.frameTrigger;
        if (trigger == null) {
            trigger = new View(getContext());
            trigger.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            this.frameTrigger = trigger;
        }
        if (trigger.getParent() != this) {
            // First use, or removeAllViews() dropped it; adding it re-records this view once.
            if (trigger.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) trigger.getParent()).removeView(trigger);
            }
            // Over the keyboard's corner, so any damage it counts stays inside the keyboard.
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(1, 1);
            params.leftMargin = Math.max(0, CoordinateUtils.x(this.keyboardViewOrigin));
            params.topMargin = Math.max(0, CoordinateUtils.y(this.keyboardViewOrigin));
            addView(trigger, 0, params);
        }
        trigger.invalidate();
        return true;
    }

    public void setDrawingPreview(AbstractDrawingPreview preview) {
        if (this.previews.indexOf(preview) < 0) {
            this.previews.add(preview);
        }
    }

    /**
     * @param iArr keyboard view origin in this view
     * @param i keyboard width
     * @param i2 keyboard height
     */
    public void setKeyboardViewGeometry(int[] iArr, int i, int i2) {
        CoordinateUtils.copy(this.keyboardViewOrigin, iArr);
        int size = this.previews.size();
        for (int i3 = 0; i3 < size; i3++) {
            this.previews.get(i3).setKeyboardViewGeometry(iArr, i, i2);
        }
    }

    public void removeAllDrawingPreviews() {
        int size = this.previews.size();
        for (int i = 0; i < size; i++) {
            this.previews.get(i).onDeallocateMemory();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeAllDrawingPreviews();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float xTranslate = CoordinateUtils.x(this.keyboardViewOrigin);
        float yTranslate = CoordinateUtils.y(this.keyboardViewOrigin);
        canvas.translate(xTranslate, yTranslate);
        int size = this.previews.size();
        for (int i = 0; i < size; i++) {
            this.previews.get(i).drawPreview(canvas);
        }
        canvas.translate(-xTranslate, -yTranslate);
    }
}
