package dev.bbkb.ime.core.textinput;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.CursorAnchorInfo;

import dev.bbkb.ime.R;
import dev.bbkb.ime.BuildConfig;



public class CursorTracker {

    private static final String TAG = "CursorTracker";

    /** Only for a null container, which BlackBerryIME never passes. TYPE_TOAST is refused on API 26+. */
    private final WindowManager.LayoutParams windowLayoutParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);

    private final ViewGroup.LayoutParams containerLayoutParams = new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

    private WindowManager windowManager;

    private ViewGroup containerView;

    private View trackerView;

    private final Context context;

    public CursorTracker(Context context) {
        // TOP|START so the window branch's x/y are plain screen coordinates.
        this.windowLayoutParams.gravity = Gravity.START | Gravity.TOP;
        this.context = context;
    }

    private void positionTrackerView(CursorAnchorInfo cursorAnchorInfo) {
        // The teardrop's tip (top center) sits on the marker's bottom, like the platform handle.
        float[] fArr = {cursorAnchorInfo.getInsertionMarkerHorizontal(), cursorAnchorInfo.getInsertionMarkerBottom()};
        cursorAnchorInfo.getMatrix().mapPoints(fArr);
        fArr[0] = fArr[0] - (this.trackerView.getMeasuredWidth() / 2);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Positioning cursor tracker view for info: " + cursorAnchorInfo + ", at x=" + fArr[0] + ", y=" + fArr[1]);
        }
        if (this.containerView != null) {
            // The matrix gives screen coordinates; translation is in the container's space,
            // which does not start at the screen origin (off by about the status bar height).
            int[] containerOnScreen = new int[2];
            this.containerView.getLocationOnScreen(containerOnScreen);
            this.trackerView.setTranslationX(fArr[0] - containerOnScreen[0]);
            this.trackerView.setTranslationY(fArr[1] - containerOnScreen[1]);
        } else {
            WindowManager.LayoutParams layoutParams = this.windowLayoutParams;
            layoutParams.x = (int) fArr[0];
            layoutParams.y = (int) fArr[1];
        }
    }

    public void show(CursorAnchorInfo cursorAnchorInfo, ViewGroup viewGroup) {
        if (isShowing()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Show requested when already visible");
            hide();
        }
        this.containerView = viewGroup;
        if (this.containerView == null) {
            this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        }
        this.trackerView = inflateTrackerView();
        positionTrackerView(cursorAnchorInfo);
        ViewGroup viewGroup2 = this.containerView;
        if (viewGroup2 != null) {
            viewGroup2.addView(this.trackerView, 0, this.containerLayoutParams);
        } else {
            this.windowManager.addView(this.trackerView, this.windowLayoutParams);
        }
    }

    public void updatePosition(CursorAnchorInfo cursorAnchorInfo) {
        positionTrackerView(cursorAnchorInfo);
        if (this.containerView == null) {
            this.windowManager.updateViewLayout(this.trackerView, this.windowLayoutParams);
        }
    }

    private View inflateTrackerView() {
        View viewInflate = LayoutInflater.from(this.context).inflate(R.layout.cursor_tracker, (ViewGroup) null);
        // Accent tint (in the dynamic theme, the system accent the platform handle uses).
        if (viewInflate instanceof android.widget.ImageView) {
            dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE.tint(
                    (android.widget.ImageView) viewInflate,
                    dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE.getAccentColor());
        }
        viewInflate.measure(0, 0);
        return viewInflate;
    }

    public void hide() {
        if (isShowing()) {
            ViewGroup viewGroup = this.containerView;
            if (viewGroup != null) {
                viewGroup.removeView(this.trackerView);
            } else {
                this.windowManager.removeView(this.trackerView);
            }
        }
        this.containerView = null;
        this.trackerView = null;
        this.windowManager = null;
    }

    public boolean isShowing() {
        View view;
        return ((this.windowManager == null && this.containerView == null) || (view = this.trackerView) == null || view.getParent() == null) ? false : true;
    }
}
