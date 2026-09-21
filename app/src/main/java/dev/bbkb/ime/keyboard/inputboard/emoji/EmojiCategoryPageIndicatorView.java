package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Custom view that displays page indicators for emoji keyboard pages.
 * 
 * Renders a row of circular dots showing:
 * - Total number of pages in the current category
 * - Which page is currently active (highlighted)
 * - Smooth scrolling animation between pages
 * 
 * The indicators are drawn with configurable colors, sizes, and spacing ratios.
 * Updates dynamically as the user swipes between emoji pages.
 */

public final class EmojiCategoryPageIndicatorView extends View {

    private final Paint activePaint;

    private final Paint inactivePaint;

    private float indicatorRadiusRatio;

    private float indicatorGapRatio;

    private int pageCount;

    private int currentPage;

    private float pageOffset;

    public EmojiCategoryPageIndicatorView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public EmojiCategoryPageIndicatorView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.activePaint = new Paint();
        this.inactivePaint = new Paint();
        this.pageCount = 0;
        this.currentPage = 0;
        this.pageOffset = 0.0f;
    }

    public void setColors(int i, int i2, int i3) {
        this.activePaint.setColor(i);
        this.inactivePaint.setColor(i2);
        setBackgroundColor(i3);
    }

    public void setIndicatorRatios(float f, float f2) {
        this.indicatorRadiusRatio = f;
        this.indicatorGapRatio = f2;
    }

    public void setPageInfo(int i, int i2, float f) {
        this.pageCount = i;
        this.currentPage = i2;
        this.pageOffset = f;
        invalidate();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        float f;
        if (this.pageCount <= 1) {
            canvas.drawColor(0);
            return;
        }
        float width = getWidth();
        float height = (getHeight() / 2) * this.indicatorRadiusRatio;
        float f2 = this.indicatorGapRatio * width;
        int i = this.pageCount;
        if (i % 2 == 0) {
            float f3 = f2 / 2.0f;
            f = (width / 2.0f) - (((i * height) + (i * f3)) + f3);
        } else {
            f = (width / 2.0f) - (((i * height) + ((i / 2) * f2)) + f2);
        }
        float f4 = f + f2 + height;
        float height2 = getHeight() / 2.0f;
        for (int i2 = 0; i2 < this.pageCount; i2++) {
            if (i2 == this.currentPage) {
                canvas.drawCircle(f4, height2, height, this.activePaint);
            } else {
                canvas.drawCircle(f4, height2, height, this.inactivePaint);
            }
            f4 = f4 + (height * 2.0f) + f2;
        }
    }
}
