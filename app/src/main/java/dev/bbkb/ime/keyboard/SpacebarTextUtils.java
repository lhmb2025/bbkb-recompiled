package dev.bbkb.ime.keyboard;

import android.graphics.Paint;
import android.util.Log;
import dev.bbkb.ime.BuildConfig;

import dev.bbkb.ime.core.shared.TypefaceUtils;



public final class SpacebarTextUtils {

    private static final String TAG = "SpacebarTextUtils";

    private SpacebarTextUtils() {
        // Static helpers only.
    }

    /**
     * @param maxWidth available width
     * @param text the text
     * @param textSize text size
     * @param minScale minimum scale
     */
    public static String truncateTextToWidth(int maxWidth, String text, float textSize, float minScale, Paint paint) {
        if (text == null) {
            return "";
        }
        paint.setTextSize(textSize);
        float fM5651a = TypefaceUtils.getStringWidth(text, paint);
        float f3 = maxWidth;
        if (fM5651a <= f3) {
            return text;
        }
        float f4 = f3 / fM5651a;
        if (f4 > minScale) {
            paint.setTextSize(f4 * textSize);
            float fM5651a2 = TypefaceUtils.getStringWidth(text, paint);
            if (fM5651a2 <= f3) {
                return text;
            }
            // Below the minimum scale we must break: the width would stop changing and the loop
            // would spin forever on the draw thread. Falls through to the ellipsis path.
            boolean fits = true;
            do {
                f4 = (f4 * f3) / fM5651a2;
                if (f4 < minScale) {
                    fits = false;
                    break;
                }
                paint.setTextSize(f4 * textSize);
                fM5651a2 = TypefaceUtils.getStringWidth(text, paint);
            } while (fM5651a2 > f3);
            if (fits) {
                return text;
            }
        }
        paint.setTextSize(minScale * textSize);
        float fM5651a3 = TypefaceUtils.getStringWidth(text + "…", paint);
        int iRound = Math.round(((fM5651a3 - f3) / (fM5651a3 / ((float) text.length()))) + 0.5f);
        // Moves back by whole code points so the kept text never ends in half a surrogate pair.
        int end = text.length() - iRound;
        if (end > 0 && end < text.length()
                && Character.isLowSurrogate(text.charAt(end))
                && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        float fM5651a4 = TypefaceUtils.getStringWidth(text.substring(0, end) + "…", paint);
        while (fM5651a4 > f3) {
            if (end <= 0) {
                if (BuildConfig.DEBUG) Log.w(TAG, "No character can be shown on space");
                return "";
            }
            end -= Character.charCount(text.codePointBefore(end));
            fM5651a4 = TypefaceUtils.getStringWidth(text.substring(0, end) + "…", paint);
        }
        return text.substring(0, end) + "…";
    }
}
