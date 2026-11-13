package dev.bbkb.ime.core.shared;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import androidx.collection.MutableLongFloatMap;

import java.util.IdentityHashMap;



public final class TypefaceUtils {

    private static final char[] KEY_LABEL_REFERENCE_CHAR = {'M'};

    private static final char[] KEY_NUMERIC_HINT_LABEL_REFERENCE_CHAR = {'8'};

    /** Sentinel for "not cached"; a measured height/width is never NaN. */
    private static final float NOT_CACHED = Float.NaN;

    private static final MutableLongFloatMap sTextHeightCache = new MutableLongFloatMap();

    private static final Rect sTextHeightBounds = new Rect();

    private static final MutableLongFloatMap sTextWidthCache = new MutableLongFloatMap();

    private static final Rect sTextWidthBounds = new Rect();

    /** Per-thread scratch rect: {@link #getStringWidth} runs every frame, so a shared one would serialise callers. */
    private static final ThreadLocal<Rect> sStringWidthBounds = new ThreadLocal<Rect>() {
        @Override
        protected Rect initialValue() {
            return new Rect();
        }
    };

    private static float getCharHeight(char[] referenceChar, Paint paint) {
        long cacheKey = getCharGeometryCacheKey(referenceChar[0], paint);
        synchronized (sTextHeightCache) {
            float cached = sTextHeightCache.getOrDefault(cacheKey, NOT_CACHED);
            if (!Float.isNaN(cached)) {
                return cached;
            }
            paint.getTextBounds(referenceChar, 0, 1, sTextHeightBounds);
            float fHeight = sTextHeightBounds.height();
            sTextHeightCache.set(cacheKey, fHeight);
            return fHeight;
        }
    }

    private static float getCharWidth(char[] referenceChar, Paint paint) {
        long cacheKey = getCharGeometryCacheKey(referenceChar[0], paint);
        synchronized (sTextWidthCache) {
            float cached = sTextWidthCache.getOrDefault(cacheKey, NOT_CACHED);
            if (!Float.isNaN(cached)) {
                return cached;
            }
            paint.getTextBounds(referenceChar, 0, 1, sTextWidthBounds);
            float fWidth = sTextWidthBounds.width();
            sTextWidthCache.set(cacheKey, fWidth);
            return fWidth;
        }
    }

    /** Small id per typeface for the cache key; the framework caches Typeface instances, so identity equality works. */
    private static final IdentityHashMap<Typeface, Integer> sTypefaceIds = new IdentityHashMap<>();

    private static int typefaceId(Typeface typeface) {
        // A null typeface draws as the default one.
        Typeface key = typeface != null ? typeface : Typeface.DEFAULT;
        synchronized (sTypefaceIds) {
            Integer id = sTypefaceIds.get(key);
            if (id == null) {
                id = sTypefaceIds.size();
                sTypefaceIds.put(key, id);
            }
            return id;
        }
    }

    /** Key = (typeface id, char, text size); every typeface needs its own id or faces share metrics. */
    private static long getCharGeometryCacheKey(char referenceChar, Paint paint) {
        long textSize = ((long) (int) paint.getTextSize()) & 0xFFFFFFL;
        return ((long) typefaceId(paint.getTypeface()) << 40) | ((long) referenceChar << 24) | textSize;
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

    public static float getStringWidth(String string, Paint paint) {
        final Rect bounds = sStringWidthBounds.get();
        paint.getTextBounds(string, 0, string.length(), bounds);
        return bounds.width();
    }
}
