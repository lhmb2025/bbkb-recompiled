package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;


public final class GestureTrailsDrawingPreview extends AbstractDrawingPreview implements Runnable {

    private final GestureTrailDrawingParams mDrawingParams;

    private final Paint mGesturePaint;

    private int mOffscreenWidth;

    private int mOffscreenHeight;

    private int mOffscreenOffsetY;

    private Bitmap mOffscreenBuffer;

    private final SparseArray<GestureTrailDrawingPoints> mGestureTrails = new SparseArray<>();

    private final Canvas mOffscreenCanvas = new Canvas();

    private final Rect mDirtyRect = new Rect();

    private final Rect mOffscreenDirtyRect = new Rect();

    private final Rect mGestureTrailBoundsRect = new Rect();

    private final Handler mDrawingHandler = new Handler(Looper.getMainLooper());

    public GestureTrailsDrawingPreview(TypedArray typedArray) {
        this.mDrawingParams = new GestureTrailDrawingParams(typedArray);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        this.mGesturePaint = paint;
    }

    @Override
    public void setKeyboardViewGeometry(int[] originCoords, int width, int height) {
        super.setKeyboardViewGeometry(originCoords, width, height);
        this.mOffscreenOffsetY = (int) (height * 0.25f);
        this.mOffscreenWidth = width;
        this.mOffscreenHeight = this.mOffscreenOffsetY + height;
    }

    @Override
    public void onDeallocateMemory() {
        // Cancel the self-scheduled invalidate first: on detach a pending run() would let a
        // racing drawPreview allocate a full-size buffer nobody can see.
        this.mDrawingHandler.removeCallbacks(this);
        synchronized (this.mGestureTrails) {
            this.mGestureTrails.clear();
        }
        freeOffscreenBuffer();
    }

    private void freeOffscreenBuffer() {
        this.mOffscreenCanvas.setBitmap(null);
        this.mOffscreenCanvas.setMatrix(null);
        Bitmap bitmap = this.mOffscreenBuffer;
        if (bitmap != null) {
            bitmap.recycle();
            this.mOffscreenBuffer = null;
        }
    }

    private void mayAllocateOffscreenBuffer() {
        Bitmap bitmap = this.mOffscreenBuffer;
        if (bitmap != null && bitmap.getWidth() == this.mOffscreenWidth && this.mOffscreenBuffer.getHeight() == this.mOffscreenHeight) {
            return;
        }
        freeOffscreenBuffer();
        this.mOffscreenBuffer = Bitmap.createBitmap(this.mOffscreenWidth, this.mOffscreenHeight, Bitmap.Config.ARGB_8888);
        this.mOffscreenCanvas.setBitmap(this.mOffscreenBuffer);
        this.mOffscreenCanvas.translate(0.0f, this.mOffscreenOffsetY);
    }

    private boolean drawGestureTrails(Canvas canvas, Paint paint, Rect rect) {
        boolean zM7399a;
        if (!rect.isEmpty()) {
            paint.setColor(0);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(rect, paint);
        }
        rect.setEmpty();
        synchronized (this.mGestureTrails) {
            int size = this.mGestureTrails.size();
            zM7399a = false;
            for (int i = 0; i < size; i++) {
                zM7399a |= this.mGestureTrails.valueAt(i).drawGestureTrail(canvas, paint, this.mGestureTrailBoundsRect, this.mDrawingParams);
                rect.union(this.mGestureTrailBoundsRect);
            }
        }
        return zM7399a;
    }

    @Override
    public void run() {
        invalidateDrawingView();
    }

    /**
     * Drops any trail and frees its buffer when the keyboard hides or the trail is switched off.
     * Separate from {@link #onDeallocateMemory}, which is the placer's detach contract.
     */
    public void releaseOffscreenBuffer() {
        if (this.mOffscreenBuffer == null) {
            return;
        }
        onDeallocateMemory();
        this.mOffscreenDirtyRect.setEmpty();
    }

    @Override
    public void drawPreview(Canvas canvas) {
        if (isPreviewEnabled()) {
            // The placer also draws for key previews; only a trail needs the buffer (2.7 MB portrait).
            if (this.mOffscreenBuffer == null) {
                synchronized (this.mGestureTrails) {
                    if (this.mGestureTrails.size() == 0) {
                        return;
                    }
                }
            }
            mayAllocateOffscreenBuffer();
            if (drawGestureTrails(this.mOffscreenCanvas, this.mGesturePaint, this.mOffscreenDirtyRect)) {
                this.mDrawingHandler.removeCallbacks(this);
                this.mDrawingHandler.postDelayed(this, this.mDrawingParams.mUpdateInterval);
            }
            if (this.mOffscreenDirtyRect.isEmpty()) {
                return;
            }
            this.mDirtyRect.set(this.mOffscreenDirtyRect);
            this.mDirtyRect.offset(0, this.mOffscreenOffsetY);
            canvas.drawBitmap(this.mOffscreenBuffer, this.mDirtyRect, this.mOffscreenDirtyRect, (Paint) null);
        }
    }

    /** @param pointerTracker the tracker whose stroke to draw */
    public void setGestureStroke(PointerTracker pointerTracker) {
        GestureTrailDrawingPoints c1057p;
        if (isPreviewEnabled()) {
            synchronized (this.mGestureTrails) {
                c1057p = this.mGestureTrails.get(pointerTracker.mPointerId);
                if (c1057p == null) {
                    c1057p = new GestureTrailDrawingPoints();
                    this.mGestureTrails.put(pointerTracker.mPointerId, c1057p);
                }
            }
            c1057p.addStroke(pointerTracker.getGestureStrokeRecognizer(), pointerTracker.getDownTime());
            invalidateDrawingView();
        }
    }
}
