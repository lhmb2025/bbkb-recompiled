package dev.bbkb.ime.core.shared;

import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;



public final class ViewLayoutUtils {
    public static ViewGroup.MarginLayoutParams newLayoutParam(ViewGroup viewGroup, int i, int i2) {
        if (viewGroup instanceof FrameLayout) {
            return new FrameLayout.LayoutParams(i, i2);
        }
        if (viewGroup instanceof RelativeLayout) {
            return new RelativeLayout.LayoutParams(i, i2);
        }
        if (viewGroup == null) {
            throw new NullPointerException("placer is null");
        }
        throw new IllegalArgumentException("placer is neither FrameLayout nor RelativeLayout: " + viewGroup.getClass().getName());
    }

    public static void placeViewAt(View view, int i, int i2, int i3, int i4) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
            marginLayoutParams.width = i3;
            marginLayoutParams.height = i4;
            marginLayoutParams.setMargins(i, i2, 0, 0);
        }
    }

    public static void updateLayoutHeightOf(Window window, int i) {
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.height != i) {
            attributes.height = i;
            window.setAttributes(attributes);
        }
    }

    public static void updateLayoutHeightOf(View view, int i) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams.height != i) {
            layoutParams.height = i;
            view.setLayoutParams(layoutParams);
        }
    }

    public static void updateLayoutGravityOf(View view, int i) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams layoutParams2 = (LinearLayout.LayoutParams) layoutParams;
            if (layoutParams2.gravity != i) {
                layoutParams2.gravity = i;
                view.setLayoutParams(layoutParams2);
                return;
            }
            return;
        }
        if (layoutParams instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams layoutParams3 = (FrameLayout.LayoutParams) layoutParams;
            if (layoutParams3.gravity != i) {
                layoutParams3.gravity = i;
                view.setLayoutParams(layoutParams3);
                return;
            }
            return;
        }
        throw new IllegalArgumentException("Layout parameter doesn't have gravity: " + layoutParams.getClass().getName());
    }
}
