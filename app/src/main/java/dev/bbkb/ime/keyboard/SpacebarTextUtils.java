package dev.bbkb.ime.keyboard;

import android.graphics.Paint;
import android.util.Log;
import dev.bbkb.ime.BuildConfig;

import dev.bbkb.ime.core.shared.TypefaceUtils;



public final class SpacebarTextUtils {

    private static final String TAG = "SpacebarTextUtils";

    private SpacebarTextUtils() {
    }

    public static String truncateTextToWidth(int i, String str, float f, float f2, Paint paint) {
        if (str == null) {
            return "";
        }
        paint.setTextSize(f);
        float fM5651a = TypefaceUtils.getStringWidth(str, paint);
        float f3 = i;
        if (fM5651a <= f3) {
            return str;
        }
        float f4 = f3 / fM5651a;
        if (f4 > f2) {
            paint.setTextSize(f4 * f);
            float fM5651a2 = TypefaceUtils.getStringWidth(str, paint);
            if (fM5651a2 <= f3) {
                return str;
            }
            // Shrink the text until it fits. If the required scale drops below the minimum scale
            // we must stop: leaving the loop without updating the measured width would spin
            // forever on the draw thread (the loop condition can no longer change). Fall through
            // to the ellipsis path below.
            boolean fits = true;
            do {
                f4 = (f4 * f3) / fM5651a2;
                if (f4 < f2) {
                    fits = false;
                    break;
                }
                paint.setTextSize(f4 * f);
                fM5651a2 = TypefaceUtils.getStringWidth(str, paint);
            } while (fM5651a2 > f3);
            if (fits) {
                return str;
            }
        }
        paint.setTextSize(f2 * f);
        float fM5651a3 = TypefaceUtils.getStringWidth(str + "…", paint);
        int iRound = Math.round(((fM5651a3 - f3) / (fM5651a3 / ((float) str.length()))) + 0.5f);
        // Cut index. It only ever moves back by whole code points, so the kept text never ends
        // in half of a surrogate pair (an emoji, or a supplementary-plane letter).
        int end = str.length() - iRound;
        if (end > 0 && end < str.length()
                && Character.isLowSurrogate(str.charAt(end))
                && Character.isHighSurrogate(str.charAt(end - 1))) {
            end--;
        }
        float fM5651a4 = TypefaceUtils.getStringWidth(str.substring(0, end) + "…", paint);
        while (fM5651a4 > f3) {
            if (end <= 0) {
                if (BuildConfig.DEBUG) Log.w(TAG, "No character can be shown on space");
                return "";
            }
            end -= Character.charCount(str.codePointBefore(end));
            fM5651a4 = TypefaceUtils.getStringWidth(str.substring(0, end) + "…", paint);
        }
        return str.substring(0, end) + "…";
    }
}
