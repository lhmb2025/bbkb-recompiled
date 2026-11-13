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

    public final void setPreviewEnabled(boolean enabled) {
        this.mPreviewEnabled = enabled;
    }

    public void setKeyboardViewGeometry(int[] originCoords, int width, int height) {
        this.mHasValidGeometry = width > 0 && height > 0;
    }
}
