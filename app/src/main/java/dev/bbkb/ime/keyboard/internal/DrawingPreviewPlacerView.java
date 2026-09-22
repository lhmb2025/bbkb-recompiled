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

    public DrawingPreviewPlacerView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.keyboardViewOrigin = CoordinateUtils.newCoordinateArray();
        this.previews = new ArrayList<>();
        setWillNotDraw(false);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    }

    public void setHardwareAcceleratedDrawingEnabled(boolean z) {
        if (z) {
            Paint paint = new Paint();
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            setLayerType(View.LAYER_TYPE_HARDWARE, paint);
        }
    }

    public void setDrawingPreview(AbstractDrawingPreview preview) {
        if (this.previews.indexOf(preview) < 0) {
            this.previews.add(preview);
        }
    }

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

    @Override // android.view.ViewGroup, android.view.View
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeAllDrawingPreviews();
    }

    @Override // android.view.View
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
