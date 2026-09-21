package dev.bbkb.ime.core.shared;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Spannable;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.widget.TextView;



public final class CursorAnchorInfoUtils {
    private static boolean isPositionVisible(View view, float f, float f2) {
        float[] fArr = {f, f2};
        View view2 = view;
        while (view2 != null) {
            if (view2 != view) {
                fArr[0] = fArr[0] - view2.getScrollX();
                fArr[1] = fArr[1] - view2.getScrollY();
            }
            if (fArr[0] < 0.0f || fArr[1] < 0.0f || fArr[0] > view2.getWidth() || fArr[1] > view2.getHeight()) {
                return false;
            }
            if (!view2.getMatrix().isIdentity()) {
                view2.getMatrix().mapPoints(fArr);
            }
            fArr[0] = fArr[0] + view2.getLeft();
            fArr[1] = fArr[1] + view2.getTop();
            Object parent = view2.getParent();
            view2 = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    public static CursorAnchorInfo getCursorAnchorInfo(TextView textView) {
        int i;
        float f;
        Layout layout = textView.getLayout();
        if (layout == null) {
            return null;
        }
        CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        int selectionStart = textView.getSelectionStart();
        builder.setSelectionRange(selectionStart, textView.getSelectionEnd());
        Matrix matrix = new Matrix(textView.getMatrix());
        int[] locationOnScreen = new int[2];
        textView.getLocationOnScreen(locationOnScreen);
        int i2 = 1;
        matrix.postTranslate(locationOnScreen[0], locationOnScreen[1]);
        builder.setMatrix(matrix);
        if (layout.getLineCount() == 0) {
            return null;
        }
        Rect rect = new Rect();
        Rect rect2 = new Rect();
        layout.getLineBounds(0, rect);
        textView.getLineBounds(0, rect2);
        float scrollX = (rect2.left - rect.left) - textView.getScrollX();
        float scrollY = (rect2.top - rect.top) - textView.getScrollY();
        CharSequence text = textView.getText();
        if (text instanceof Spannable) {
            int length = text.length();
            Spannable spannable = (Spannable) text;
            int iMin = length;
            int iMax = 0;
            for (Object obj : spannable.getSpans(0, text.length(), Object.class)) {
                if ((spannable.getSpanFlags(obj) & 256) != 0) {
                    iMin = Math.min(iMin, spannable.getSpanStart(obj));
                    iMax = Math.max(iMax, spannable.getSpanEnd(obj));
                }
            }
            if (iMin >= 0 && iMin < iMax) {
                builder.setComposingText(iMin, text.subSequence(iMin, iMax));
                int lineForOffset = layout.getLineForOffset(iMin);
                int lineForOffset2 = layout.getLineForOffset(iMax - 1);
                int i3 = lineForOffset;
                while (i3 <= lineForOffset2) {
                    int lineStart = layout.getLineStart(i3);
                    int lineEnd = layout.getLineEnd(i3);
                    int iMax2 = Math.max(lineStart, iMin);
                    int iMin2 = Math.min(lineEnd, iMax);
                    boolean z = layout.getParagraphDirection(i3) == i2;
                    float[] fArr = new float[iMin2 - iMax2];
                    layout.getPaint().getTextWidths(text, iMax2, iMin2, fArr);
                    float lineTop = layout.getLineTop(i3);
                    float lineBottom = layout.getLineBottom(i3);
                    int i4 = lineForOffset2;
                    int i5 = iMax2;
                    while (i5 < iMin2) {
                        float f2 = fArr[i5 - iMax2];
                        boolean zIsRtlCharAt = layout.isRtlCharAt(i5);
                        float primaryHorizontal = layout.getPrimaryHorizontal(i5);
                        float secondaryHorizontal = layout.getSecondaryHorizontal(i5);
                        if (z) {
                            if (zIsRtlCharAt) {
                                primaryHorizontal = secondaryHorizontal - f2;
                                f = secondaryHorizontal;
                                i = iMin2;
                            } else {
                                f = primaryHorizontal + f2;
                                i = iMin2;
                            }
                        } else if (zIsRtlCharAt) {
                            i = iMin2;
                            primaryHorizontal -= f2;
                            f = primaryHorizontal;
                        } else {
                            f = secondaryHorizontal + f2;
                            primaryHorizontal = secondaryHorizontal;
                            i = iMin2;
                        }
                        float f3 = primaryHorizontal + scrollX;
                        int i6 = i3;
                        float f4 = f + scrollX;
                        int i7 = iMax2;
                        float f5 = lineTop + scrollY;
                        int i8 = iMax;
                        float f6 = lineBottom + scrollY;
                        boolean zM5693a = isPositionVisible(textView, f3, f5);
                        boolean zM5693a2 = isPositionVisible(textView, f4, f6);
                        int i9 = (zM5693a || zM5693a2) ? 1 : 0;
                        if (!zM5693a || !zM5693a2) {
                            i9 |= 2;
                        }
                        if (zIsRtlCharAt) {
                            i9 |= 4;
                        }
                        builder.addCharacterBounds(i5, f3, f5, f4, f6, i9);
                        i5++;
                        iMax2 = i7;
                        lineTop = lineTop;
                        i3 = i6;
                        iMin2 = i;
                        iMax = i8;
                        fArr = fArr;
                        iMin = iMin;
                    }
                    i3++;
                    lineForOffset2 = i4;
                    i2 = 1;
                }
            }
        }
        if (selectionStart >= 0) {
            int lineForOffset3 = layout.getLineForOffset(selectionStart);
            float primaryHorizontal2 = layout.getPrimaryHorizontal(selectionStart) + scrollX;
            float lineTop2 = layout.getLineTop(lineForOffset3) + scrollY;
            float lineBaseline = layout.getLineBaseline(lineForOffset3) + scrollY;
            float lineBottom2 = layout.getLineBottom(lineForOffset3) + scrollY;
            boolean zM5693a3 = isPositionVisible(textView, primaryHorizontal2, lineTop2);
            boolean zM5693a4 = isPositionVisible(textView, primaryHorizontal2, lineBottom2);
            int i10 = (zM5693a3 || zM5693a4) ? 1 : 0;
            if (!zM5693a3 || !zM5693a4) {
                i10 |= 2;
            }
            builder.setInsertionMarkerLocation(primaryHorizontal2, lineTop2, lineBaseline, lineBottom2, layout.isRtlCharAt(selectionStart) ? i10 | 4 : i10);
        }
        return builder.build();
    }
}
