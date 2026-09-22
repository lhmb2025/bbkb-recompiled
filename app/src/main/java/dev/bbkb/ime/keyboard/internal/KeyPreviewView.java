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

    /** Labels already known to fit without X-scaling. Per view, so it dies with the view
     *  instead of growing for the process lifetime across locale and theme changes. */
    private final HashSet<String> mNoScaleXTextSet = new HashSet<>();

    private static final int[][][] KEY_PREVIEW_BACKGROUND_STATE_SETS = {new int[][]{new int[0], new int[]{R.attr.state_has_morekeys}}, new int[][]{new int[]{R.attr.state_left_edge}, new int[]{R.attr.state_left_edge, R.attr.state_has_morekeys}}, new int[][]{new int[]{R.attr.state_right_edge}, new int[]{R.attr.state_right_edge, R.attr.state_has_morekeys}}};

    private final Rect mBackgroundPadding;
    
    private int desiredWidth = -1;
    private int desiredHeight = -1;

    /** Cached backgrounds, rebuilt only when the theme actually changes. See applyThemedBackground. */
    private GradientDrawable mRoundedBackground;
    private int mRoundedBackgroundColor;
    private float mRoundedBackgroundRadiusDp = Float.NaN;
    private Drawable mFlatBackground;
    private int mFlatBackgroundColor;

    public KeyPreviewView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public KeyPreviewView(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
        this.mBackgroundPadding = new Rect();
        // Position text at top, centered horizontally
        setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        // Remove font padding to properly position text
        setIncludeFontPadding(false);
        // Add padding - more on top for the letter
        int horizontalPadding = (int) (8 * getResources().getDisplayMetrics().density);
        int topPadding = (int) (12 * getResources().getDisplayMetrics().density);
        int bottomPadding = (int) (4 * getResources().getDisplayMetrics().density);
        setPadding(horizontalPadding, topPadding, horizontalPadding, bottomPadding);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] onMeasure called");
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Current text: '" + getText() + "', length: " + (getText() != null ? getText().length() : "null"));
        
        // If we have desired dimensions from the key, use them
        if (desiredWidth > 0 && desiredHeight > 0) {
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Using desired dimensions - width: " + desiredWidth + ", height: " + desiredHeight);
            int widthSpec = MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY);
            int heightSpec = MeasureSpec.makeMeasureSpec(desiredHeight, View.MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec, heightSpec);
        } else {
            // Fallback to default measurement
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
        
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] After measure - measuredWidth: " + getMeasuredWidth() + ", measuredHeight: " + getMeasuredHeight());
    }
    
    public void setPreviewDimensions(int width, int height) {
        this.desiredWidth = width;
        this.desiredHeight = height;
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Set preview dimensions - width: " + width + ", height: " + height);
    }

    public void setPreviewVisual(Key key, KeyboardIconSet c1024ag, KeyDrawParams c1061t) {
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] setPreviewVisual called - key code: " + key.getCode());
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] key.getIconId() (icon code): " + key.getIconId());
        
        // Apply themed colors and square background
        applyThemedBackground();
        
        if (key.getIconId() != 0) {
            Drawable iconDrawable = key.getPreviewIcon(c1024ag);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Using icon drawable: " + iconDrawable);
            setCompoundDrawables(null, null, null, iconDrawable);
            setText((CharSequence) null);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Set icon, cleared text");
            return;
        }
        
        setCompoundDrawables(null, null, null, null);
        // Use themed text color instead of formatter color
        setTextColor(KeyboardColorManager.INSTANCE.getTextColor());
        setTextSize(0, key.getLetterSize(c1061t));
        // Match the key-face weight so the bubble doesn't look lighter than the key
        setTypeface(KeyboardColorManager.styleSpec().getMediumKeyTypeface()
                ? dev.bbkb.ime.keyboard.KeyboardView.mediumWeightTypeface(key.getLetterTypeface(c1061t))
                : key.getLetterTypeface(c1061t));
        
        String keyLabel = key.getVisibleLabel();
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] key.getVisibleLabel() (label): '" + keyLabel + "', length: " + (keyLabel != null ? keyLabel.length() : "null"));
        setTextAndScaleX(keyLabel);
    }
    
    private void applyThemedBackground() {
        int color = KeyboardColorManager.INSTANCE.getKeyColorPressed();
        dev.bbkb.ime.keyboard.StyleSpec spec = KeyboardColorManager.styleSpec();
        float radiusDp = spec.getPreviewRadiusDp();
        if (radiusDp > 0) {
            // K3: rounded, elevated preview bubble instead of the flat square fill.
            // The GradientDrawable's rounded outline also shapes the elevation shadow.
            float density = getResources().getDisplayMetrics().density;
            // Reuse the drawable across keystrokes. GradientDrawable.setColor/setCornerRadius
            // mutate in place, and View.setBackground short-circuits when handed the instance it
            // already holds, so the repeat case costs no allocation and no relayout.
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

    private void setTextAndScaleX(String str) {
        Drawable background;
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] setTextAndScaleX called with: '" + str + "'");
        setTextScaleX(1.0f);
        setText(str);
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] After setText - current text: '" + getText() + "'");
        
        if (mNoScaleXTextSet.contains(str) || (background = getBackground()) == null) {
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Skipping scale calculation - cached or no background");
            return;
        }
        background.getPadding(this.mBackgroundPadding);
        int intrinsicWidth = (background.getIntrinsicWidth() - this.mBackgroundPadding.left) - this.mBackgroundPadding.right;
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Background intrinsic width: " + intrinsicWidth);
        
        // FIX: If background has no intrinsic width (returns -1), don't scale
        if (intrinsicWidth <= 0) {
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Background has no intrinsic width, using default scale");
            mNoScaleXTextSet.add(str);
            return;
        }
        
        float fM7441a = getTextWidth(str, getPaint());
        if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Text width: " + fM7441a);
        
        float f = intrinsicWidth;
        if (fM7441a <= f) {
            mNoScaleXTextSet.add(str);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Text fits, no scaling needed");
        } else {
            setTextScaleX(f / fM7441a);
            if (InputPathDebug.on()) Log.d(TAG, "[KEY_PREVIEW] Text too wide, scaling to: " + (f / fM7441a));
        }
    }

    private static float getTextWidth(String str, TextPaint textPaint) {
        // measureText sums the same advances without the per-call float[].
        return TextUtils.isEmpty(str) ? 0.0f : textPaint.measureText(str);
    }

    public void setPreviewBackground(boolean z, int i) {
        Drawable background = getBackground();
        if (background == null) {
            return;
        }
        background.setState(KEY_PREVIEW_BACKGROUND_STATE_SETS[i][z ? 1 : 0]);
    }
}
