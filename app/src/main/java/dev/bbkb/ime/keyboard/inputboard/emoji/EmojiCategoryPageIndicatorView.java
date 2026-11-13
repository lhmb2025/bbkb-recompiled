package dev.bbkb.ime.keyboard.inputboard.emoji;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** A row of dots for the current category's pages, the active page highlighted; hidden for one page. */

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

    /** @param defStyleAttr the default style attribute */
    public EmojiCategoryPageIndicatorView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        this.activePaint = new Paint();
        this.inactivePaint = new Paint();
        this.pageCount = 0;
        this.currentPage = 0;
        this.pageOffset = 0.0f;
    }

    /**
     * @param activeColor the active dot color
     * @param inactiveColor the inactive dot color
     * @param backgroundColor the background color
     */
    public void setColors(int activeColor, int inactiveColor, int backgroundColor) {
        this.activePaint.setColor(activeColor);
        this.inactivePaint.setColor(inactiveColor);
        setBackgroundColor(backgroundColor);
    }

    /**
     * @param radiusRatio the dot radius as a fraction of half the view height
     * @param gapRatio the gap between dots as a fraction of the view width
     */
    public void setIndicatorRatios(float radiusRatio, float gapRatio) {
        this.indicatorRadiusRatio = radiusRatio;
        this.indicatorGapRatio = gapRatio;
    }

    /**
     * @param pageCount the page count
     * @param currentPage the current page
     * @param pageOffset the scroll offset toward the next page (stored, not drawn)
     */
    public void setPageInfo(int pageCount, int currentPage, float pageOffset) {
        this.pageCount = pageCount;
        this.currentPage = currentPage;
        this.pageOffset = pageOffset;
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
