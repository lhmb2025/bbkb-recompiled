package dev.bbkb.ime.keyboard.internal;

import android.graphics.Matrix;
import android.graphics.RectF;



public interface TextDecoratorUiOperator {
    void cancel();

    void showIndicator(Matrix matrix, RectF rectF, boolean showAtLeftSide);

    void setOnClickHandler(Runnable runnable);

    void hide();
}
