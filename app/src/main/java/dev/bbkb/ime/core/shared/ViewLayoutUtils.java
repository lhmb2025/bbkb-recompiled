package dev.bbkb.ime.core.shared;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;



public final class ViewLayoutUtils {
    public static ViewGroup.MarginLayoutParams newLayoutParam(ViewGroup viewGroup, int width, int height) {
        if (viewGroup instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(width, height);
        }
        if (viewGroup instanceof RelativeLayout) {
            return new RelativeLayout.LayoutParams(width, height);
        }
        if (viewGroup == null) {
            throw new NullPointerException("placer is null");
        }
        throw new IllegalArgumentException("placer is neither FrameLayout nor RelativeLayout: " + viewGroup.getClass().getName());
    }

    public static void placeViewAt(View view, int x, int y, int width, int height) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
            marginLayoutParams.width = width;
            marginLayoutParams.height = height;
            marginLayoutParams.setMargins(x, y, 0, 0);
        }
    }

    public static void updateLayoutHeightOf(Window window, int layoutHeight) {
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.height != layoutHeight) {
            attributes.height = layoutHeight;
            window.setAttributes(attributes);
        }
    }

    public static void updateLayoutHeightOf(View view, int layoutHeight) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams.height != layoutHeight) {
            layoutParams.height = layoutHeight;
            view.setLayoutParams(layoutParams);
        }
    }

    public static void updateLayoutGravityOf(View view, int layoutGravity) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams2 = (LinearLayout.LayoutParams) layoutParams;
            if (layoutParams2.gravity != layoutGravity) {
                layoutParams2.gravity = layoutGravity;
                view.setLayoutParams(layoutParams2);
                return;
            }
            return;
        }
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams layoutParams3 = (FrameLayout.LayoutParams) layoutParams;
            if (layoutParams3.gravity != layoutGravity) {
                layoutParams3.gravity = layoutGravity;
                view.setLayoutParams(layoutParams3);
                return;
            }
            return;
        }
        throw new IllegalArgumentException("Layout parameter doesn't have gravity: " + layoutParams.getClass().getName());
    }
}
