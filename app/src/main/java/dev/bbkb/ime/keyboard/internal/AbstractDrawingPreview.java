package dev.bbkb.ime.keyboard.internal;

import android.graphics.Canvas;
import android.view.View;



public abstract class AbstractDrawingPreview {

    private View mDrawingView;

    private boolean mPreviewEnabled;

    private boolean mHasValidGeometry;

    public abstract void drawPreview(Canvas canvas);

    public abstract void onDeallocateMemory();

    public void setDrawingView(DrawingPreviewPlacerView placerView) {
        this.mDrawingView = placerView;
        placerView.setDrawingPreview(this);
    }

    protected void invalidateDrawingView() {
        View view = this.mDrawingView;
        if (view != null) {
            view.invalidate();
        }
    }

    protected final boolean isPreviewEnabled() {
        return this.mPreviewEnabled && this.mHasValidGeometry;
    }

    public final void setPreviewEnabled(boolean z) {
        this.mPreviewEnabled = z;
    }

    public void setKeyboardViewGeometry(int[] iArr, int i, int i2) {
        this.mHasValidGeometry = i > 0 && i2 > 0;
    }
}
