package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;

import androidx.core.content.ContextCompat;

import dev.bbkb.ime.R;



public final class TextDecoratorUi implements TextDecoratorUiOperator {

    private final RelativeLayout mContentView;

    private final DrawingView mDrawingView;

    private final PopupWindow mPopupWindow;

    private final View mParentView;

    private final float mDensity;

    private final RectF mBounds;

    public TextDecoratorUi(Context context, View view) {
        Resources resources = context.getResources();
        this.mDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, resources.getInteger(R.integer.text_decorator_hit_area_margin_in_dp), resources.getDisplayMetrics());
        DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        this.mBounds = new RectF(0.0f, 0.0f, displayMetrics.widthPixels, displayMetrics.heightPixels);
        this.mContentView = new RelativeLayout(context);
        this.mContentView.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
        this.mContentView.setBackground(new ColorDrawable(0));
        ViewGroup viewGroupM7503a = findContentParent(view);
        this.mDrawingView = new DrawingView(context);
        this.mContentView.addView(this.mDrawingView);
        if (viewGroupM7503a != null) {
            viewGroupM7503a.addView(this.mContentView);
        }
        this.mPopupWindow = new PopupWindow(context);
        // PopupWindow.setBackgroundDrawable is NOT deprecated (unlike View.setBackgroundDrawable)
        // and the null is deliberate: a null background is what suppresses this window's
        // outside-touch dismissal on some ROMs, which the panel relies on. Do not "modernise".
        this.mPopupWindow.setBackgroundDrawable(null);
        this.mParentView = new View(context);
        this.mPopupWindow.setContentView(this.mParentView);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TextDecoratorUiOperator
    public void cancel() {
        RelativeLayout relativeLayout = this.mContentView;
        if (relativeLayout != null) {
            ViewParent parent = relativeLayout.getParent();
            if (parent != null && (parent instanceof ViewGroup)) {
                ((ViewGroup) parent).removeView(this.mContentView);
            }
            this.mContentView.removeAllViews();
        }
        PopupWindow popupWindow = this.mPopupWindow;
        if (popupWindow != null) {
            popupWindow.dismiss();
        }
    }

    @Override // dev.bbkb.ime.keyboard.internal.TextDecoratorUiOperator
    public void hide() {
        this.mDrawingView.setVisibility(View.GONE);
        this.mPopupWindow.dismiss();
    }

    private static final RectF transformRect(Matrix matrix, RectF rectF, boolean z) {
        RectF rectF2;
        float fHeight = rectF.height();
        if (z) {
            rectF2 = new RectF(rectF.left - fHeight, rectF.top, rectF.left, rectF.top + fHeight);
        } else {
            rectF2 = new RectF(rectF.right, rectF.top, rectF.right + fHeight, rectF.top + fHeight);
        }
        matrix.mapRect(rectF2);
        return rectF2;
    }

    @Override // dev.bbkb.ime.keyboard.internal.TextDecoratorUiOperator
    public void showIndicator(Matrix matrix, RectF rectF, boolean z) {
        RectF rectFM7504b = transformRect(matrix, rectF, z);
        if (rectFM7504b.left < this.mBounds.left || this.mBounds.right < rectFM7504b.right) {
            rectFM7504b = transformRect(matrix, rectF, !z);
        }
        this.mDrawingView.setBounds(rectFM7504b);
        RectF rectF2 = new RectF();
        matrix.mapRect(rectF2, rectF);
        rectF2.union(rectFM7504b);
        float f = this.mDensity;
        rectF2.inset(-f, -f);
        int[] iArr = new int[2];
        this.mContentView.getLocationOnScreen(iArr);
        int i = iArr[0];
        int i2 = iArr[1];
        this.mDrawingView.setX(rectFM7504b.left - i);
        this.mDrawingView.setY(rectFM7504b.top - i2);
        this.mDrawingView.setVisibility(View.VISIBLE);
        if (this.mPopupWindow.isShowing()) {
            this.mPopupWindow.update(((int) rectF2.left) - i, ((int) rectF2.top) - i2, (int) rectF2.width(), (int) rectF2.height());
            return;
        }
        this.mPopupWindow.setWidth((int) rectF2.width());
        this.mPopupWindow.setHeight((int) rectF2.height());
        this.mPopupWindow.showAtLocation(this.mContentView, 0, ((int) rectF2.left) - i, ((int) rectF2.top) - i2);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TextDecoratorUiOperator
    public void setOnClickHandler(final Runnable runnable) {
        this.mParentView.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                runnable.run();
            }
        });
    }

    
    private static class IndicatorView extends View {

        private final Path mHighlightPath1;

        private final Path mHighlightPath2;

        private final Paint mPaint;

        private final Matrix scaleMatrix;

        private final int backgroundColor;

        private final int indicatorColor;

        private final RectF bounds;

        public IndicatorView(Context context, int i, int i2, int i3, int i4) {
            super(context);
            this.mHighlightPath2 = new Path();
            this.mPaint = new Paint(1);
            this.scaleMatrix = new Matrix();
            this.bounds = new RectF();
            Resources resources = context.getResources();
            this.mHighlightPath1 = loadIndicatorPath(resources, i, i2);
            this.backgroundColor = ContextCompat.getColor(context, i3);
            this.indicatorColor = ContextCompat.getColor(context, i4);
        }

        public void setBounds(RectF rectF) {
            this.bounds.set(rectF);
        }

        @Override // android.view.View
        protected void onDraw(Canvas canvas) {
            this.mPaint.setColor(this.backgroundColor);
            this.mPaint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0.0f, 0.0f, this.bounds.width(), this.bounds.height(), this.mPaint);
            this.scaleMatrix.reset();
            this.scaleMatrix.postScale(this.bounds.width(), this.bounds.height());
            this.mHighlightPath1.transform(this.scaleMatrix, this.mHighlightPath2);
            this.mPaint.setColor(this.indicatorColor);
            canvas.drawPath(this.mHighlightPath2, this.mPaint);
        }

        private static Path loadIndicatorPath(Resources resources, int i, int i2) throws Resources.NotFoundException {
            float integer = 1.0f / resources.getInteger(i2);
            int[] intArray = resources.getIntArray(i);
            Path path = new Path();
            for (int i3 = 0; i3 < intArray.length; i3 += 2) {
                if (i3 == 0) {
                    path.moveTo(intArray[i3] * integer, intArray[i3 + 1] * integer);
                } else {
                    path.lineTo(intArray[i3] * integer, intArray[i3 + 1] * integer);
                }
            }
            path.close();
            return path;
        }
    }

    private static ViewGroup findContentParent(View view) {
        ViewGroup viewGroup;
        View rootView = view.getRootView();
        if (rootView == null || (viewGroup = (ViewGroup) rootView.findViewById(android.R.id.content)) == null) {
            return null;
        }
        return viewGroup;
    }

    
    private static final class DrawingView extends IndicatorView {
        public DrawingView(Context context) {
            super(context, R.array.text_decorator_add_to_dictionary_indicator_path, R.integer.text_decorator_add_to_dictionary_indicator_path_size, R.color.text_decorator_add_to_dictionary_indicator_background_color, R.color.text_decorator_add_to_dictionary_indicator_foreground_color);
        }
    }
}
