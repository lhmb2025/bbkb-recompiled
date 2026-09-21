package dev.bbkb.ime.core.shared;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.collection.MutableIntFloatMap;



public final class TypefaceUtils {

    private static final char[] KEY_LABEL_REFERENCE_CHAR = {'M'};

    private static final char[] KEY_NUMERIC_HINT_LABEL_REFERENCE_CHAR = {'8'};

    /** Sentinel for "not cached"; a measured height/width is never NaN. */
    private static final float NOT_CACHED = Float.NaN;

    private static final MutableIntFloatMap sTextHeightCache = new MutableIntFloatMap();

    private static final Rect sTextHeightBounds = new Rect();

    private static final MutableIntFloatMap sTextWidthCache = new MutableIntFloatMap();

    private static final Rect sTextWidthBounds = new Rect();

    /**
     * Per-thread scratch rect: {@link #getStringWidth} is called from the draw thread on every
     * frame, so a single shared static would serialise every caller on one monitor.
     */
    private static final ThreadLocal<Rect> sStringWidthBounds = new ThreadLocal<Rect>() {
        @Override
        protected Rect initialValue() {
            return new Rect();
        }
    };

    private static float getCharHeight(char[] cArr, Paint paint) {
        int cacheKey = getCharGeometryCacheKey(cArr[0], paint);
        synchronized (sTextHeightCache) {
            float cached = sTextHeightCache.getOrDefault(cacheKey, NOT_CACHED);
            if (!Float.isNaN(cached)) {
                return cached;
            }
            paint.getTextBounds(cArr, 0, 1, sTextHeightBounds);
            float fHeight = sTextHeightBounds.height();
            sTextHeightCache.set(cacheKey, fHeight);
            return fHeight;
        }
    }

    private static float getCharWidth(char[] cArr, Paint paint) {
        int cacheKey = getCharGeometryCacheKey(cArr[0], paint);
        synchronized (sTextWidthCache) {
            float cached = sTextWidthCache.getOrDefault(cacheKey, NOT_CACHED);
            if (!Float.isNaN(cached)) {
                return cached;
            }
            paint.getTextBounds(cArr, 0, 1, sTextWidthBounds);
            float fWidth = sTextWidthBounds.width();
            sTextWidthCache.set(cacheKey, fWidth);
            return fWidth;
        }
    }

    private static int getCharGeometryCacheKey(char c, Paint paint) {
        int textSize = (int) paint.getTextSize();
        Typeface typeface = paint.getTypeface();
        int i = c << 15;
        return typeface == Typeface.DEFAULT ? i + textSize : typeface == Typeface.DEFAULT_BOLD ? i + textSize + 4096 : typeface == Typeface.MONOSPACE ? i + textSize + 8192 : i + textSize;
    }

    public static float getReferenceCharHeight(Paint paint) {
        return getCharHeight(KEY_LABEL_REFERENCE_CHAR, paint);
    }

    public static float getReferenceCharWidth(Paint paint) {
        return getCharWidth(KEY_LABEL_REFERENCE_CHAR, paint);
    }

    public static float getReferenceDigitWidth(Paint paint) {
        return getCharWidth(KEY_NUMERIC_HINT_LABEL_REFERENCE_CHAR, paint);
    }

    public static float getStringWidth(String str, Paint paint) {
        final Rect bounds = sStringWidthBounds.get();
        paint.getTextBounds(str, 0, str.length(), bounds);
        return bounds.width();
    }
}
