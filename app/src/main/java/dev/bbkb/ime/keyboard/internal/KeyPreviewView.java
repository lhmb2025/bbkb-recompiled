package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

import androidx.appcompat.widget.AppCompatTextView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import java.util.HashSet;
import dev.bbkb.ime.core.shared.InputPathDebug;



public class KeyPreviewView extends AppCompatTextView {

    private static final String TAG = "KeyPreviewView";

    /** Labels known to fit without X-scaling; per view so it does not grow for the process lifetime. */
    private final HashSet<String> mNoScaleXTextSet = new HashSet<>();

    private static final int[][][] KEY_PREVIEW_BACKGROUND_STATE_SETS = {new int[][]{new int[0], new int[]{R.attr.state_has_morekeys}}, new int[][]{new int[]{R.attr.state_left_edge}, new int[]{R.attr.state_left_edge, R.attr.state_has_morekeys}}, new int[][]{new int[]{R.attr.state_right_edge}, new int[]{R.attr.state_right_edge, R.attr.state_has_morekeys}}};

    private final Rect mBackgroundPadding;
    
    private int desiredWidth = -1;
    private int desiredHeight = -1;

    /** Cached backgrounds, rebuilt only when the theme changes. */
    private GradientDrawable mRoundedBackground;
    private int mRoundedBackgroundColor;
    private float mRoundedBackgroundRadiusDp = Float.NaN;
    private Drawable mFlatBackground;
    private int mFlatBackgroundColor;

    public KeyPreviewView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public KeyPreviewView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        this.mBackgroundPadding = new Rect();
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        setIncludeFontPadding(false);
        int horizontalPadding = (int) (8 * getResources().getDisplayMetrics().density);
        int topPadding = (int) (12 * getResources().getDisplayMetrics().density);
        int bottomPadding = (int) (4 * getResources().getDisplayMetrics().density);
        setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding);
    }

    /**
     * Shows or hides the bubble by alpha, not visibility: alpha damages only this view, while a
     * visibility change makes the placer re-record. A hidden bubble leaves the accessibility tree.
     */
    public void setPreviewShown(boolean shown) {
        setAlpha(shown ? 1.0f : 0.0f);
        setImportantForAccessibility(shown ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    public boolean isPreviewShown() {
        return getAlpha() > 0.0f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] onMeasure called");
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Current text: '" + getText() + "', length: " + (getText() != null ? getText().length() : "null"));

        if (desiredWidth > 0 && desiredHeight > 0) {
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Using desired dimensions - width: " + desiredWidth + ", height: " + desiredHeight);
            int widthSpec = MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY);
            int heightSpec = MeasureSpec.makeMeasureSpec(desiredHeight, View.MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec, heightSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
        
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] After measure - measuredWidth: " + getMeasuredWidth() + ", measuredHeight: " + getMeasuredHeight());
    }
    
    public void setPreviewDimensions(int width, int height) {
        this.desiredWidth = width;
        this.desiredHeight = height;
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Set preview dimensions - width: " + width + ", height: " + height);
    }

    public void setPreviewVisual(Key key, KeyboardIconSet keyboardIconSet, KeyDrawParams keyDrawParams) {
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] setPreviewVisual called - key code: " + key.getCode());
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] key.getIconId() (icon code): " + key.getIconId());

        applyThemedBackground();
        
        if (key.getIconId() != 0) {
            Drawable iconDrawable = key.getPreviewIcon(keyboardIconSet);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Using icon drawable: " + iconDrawable);
            setBottomDrawableIfChanged(iconDrawable);
            setText((CharSequence) null);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Set icon, cleared text");
            return;
        }
        
        setBottomDrawableIfChanged(null);
        setTextColor(KeyboardColorManager.INSTANCE.getTextColor());
        setTextSize(0, key.getLetterSize(keyDrawParams));
        setTypeface(KeyboardColorManager.styleSpec().getMediumKeyTypeface()
                ? dev.bbkb.ime.keyboard.KeyboardView.mediumWeightTypeface(key.getLetterTypeface(keyDrawParams))
                : key.getLetterTypeface(keyDrawParams));
        
        String keyLabel = key.getVisibleLabel();
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] key.getVisibleLabel() (label): '" + keyLabel + "', length: " + (keyLabel != null ? keyLabel.length() : "null"));
        setTextAndScaleX(keyLabel);
    }

    /** setCompoundDrawables always requests a whole-window layout; skip it when nothing changes. */
    private void setBottomDrawableIfChanged(Drawable bottom) {
        Drawable[] current = getCompoundDrawables();
        if (current[0] == null && current[1] == null && current[2] == null && current[3] == bottom) {
            return;
        }
        setCompoundDrawables(null, null, null, bottom);
    }

    private void applyThemedBackground() {
        int color = KeyboardColorManager.INSTANCE.getKeyColorPressed();
        dev.bbkb.ime.keyboard.StyleSpec spec = KeyboardColorManager.styleSpec();
        float radiusDp = spec.getPreviewRadiusDp();
        if (radiusDp > 0) {
            // The rounded outline also shapes the elevation shadow.
            float density = getResources().getDisplayMetrics().density;
            // Reused: setColor/setCornerRadius mutate in place and setBackground short-circuits
            // on the same instance, so a repeat costs no allocation or relayout.
            GradientDrawable bg = this.mRoundedBackground;
            if (bg == null) {
                bg = new GradientDrawable();
                this.mRoundedBackground = bg;
                this.mRoundedBackgroundColor = ~color;
                this.mRoundedBackgroundRadiusDp = Float.NaN;
            }
            if (this.mRoundedBackgroundColor != color) {
                this.mRoundedBackgroundColor = color;
                bg.setColor(color);
            }
            if (this.mRoundedBackgroundRadiusDp != radiusDp) {
                this.mRoundedBackgroundRadiusDp = radiusDp;
                bg.setCornerRadius(radiusDp * density);
            }
            setBackground(bg);
            setElevation(spec.getPreviewElevationDp() * density);
        } else {
            if (this.mFlatBackground == null || this.mFlatBackgroundColor != color) {
                this.mFlatBackground = KeyboardColorManager.fill(color);
                this.mFlatBackgroundColor = color;
            }
            setBackground(this.mFlatBackground);
            setElevation(0);
        }
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Applied themed background with color: " + Integer.toHexString(color));
    }

    private void setTextAndScaleX(String text) {
        Drawable background;
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] setTextAndScaleX called with: '" + text + "'");
        setTextScaleX(1.0f);
        setText(text);
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] After setText - current text: '" + getText() + "'");
        
        if (mNoScaleXTextSet.contains(text) || (background = getBackground()) == null) {
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Skipping scale calculation - cached or no background");
            return;
        }
        background.getPadding(this.mBackgroundPadding);
        int intrinsicWidth = (background.getIntrinsicWidth() - this.mBackgroundPadding.left) - this.mBackgroundPadding.right;
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Background intrinsic width: " + intrinsicWidth);

        // No intrinsic width (-1): don't scale.
        if (intrinsicWidth <= 0) {
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Background has no intrinsic width, using default scale");
            mNoScaleXTextSet.add(text);
            return;
        }
        
        float fM7441a = getTextWidth(text, getPaint());
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Text width: " + fM7441a);
        
        float f = intrinsicWidth;
        if (fM7441a <= f) {
            mNoScaleXTextSet.add(text);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Text fits, no scaling needed");
        } else {
            setTextScaleX(f / fM7441a);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Text too wide, scaling to: " + (f / fM7441a));
        }
    }

    private static float getTextWidth(String text, TextPaint textPaint) {
        return TextUtils.isEmpty(text) ? 0.0f : textPaint.measureText(text);
    }

    /**
     * @param hasMoreKeys true if the key has more keys
     * @param position edge position: 0 none, 1 left edge, 2 right edge
     */
    public void setPreviewBackground(boolean hasMoreKeys, int position) {
        Drawable background = getBackground();
        if (background == null) {
            return;
        }
        background.setState(KEY_PREVIEW_BACKGROUND_STATE_SETS[position][hasMoreKeys ? 1 : 0]);
    }
}
