package dev.bbkb.ime.core.device;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;

import java.util.regex.PatternSyntaxException;
import dev.bbkb.ime.BuildConfig;



public final class ResourceConfigManager {

    private static final String TAG = "ResourceConfigManager";

    // Performance: Cache frequently accessed dimension values
    // These are called many times during keyboard initialization and layout
    private static volatile int sCachedScreenWidth = -1;
    private static volatile int sCachedKeyboardHeight = -1;
    private static volatile int sCachedOrientation = -1;
    private static volatile int sCachedDensityDpi = -1;
    private static volatile int sCachedScreenWidthDp = -1;

    public static boolean isValidFraction(float f) {
        return f >= 0.0f;
    }

    public static boolean isValidPixelSize(int i) {
        return i > 0;
    }

    private ResourceConfigManager() {
    }

    public static int getScreenWidthPixels(Resources resources) {
        final android.content.res.Configuration configuration = resources.getConfiguration();
        final int orientation = configuration.orientation;
        final int densityDpi = configuration.densityDpi;
        final int screenWidthDp = configuration.screenWidthDp;
        // Orientation alone is not enough: a multi-window resize or a fold changes the width at
        // constant orientation, and a density change changes it too.
        if (sCachedScreenWidth > 0 && sCachedOrientation == orientation
                && sCachedDensityDpi == densityDpi && sCachedScreenWidthDp == screenWidthDp) {
            return sCachedScreenWidth;
        }
        sCachedScreenWidth = resources.getDisplayMetrics().widthPixels;
        sCachedOrientation = orientation;
        sCachedDensityDpi = densityDpi;
        sCachedScreenWidthDp = screenWidthDp;
        return sCachedScreenWidth;
    }
    
    /**
     * Height of the aux bar / suggestion strip, scaled by the keyboard height setting.
     *
     * The dimension resource is a single 40dp base for every screen and orientation; the
     * per-device sw411dp/sw411dp-land overrides it used to carry are gone. Callers must use
     * this rather than reading the dimension directly, or the strip stays full height while
     * the keyboard around it shrinks.
     */
    public static int getSuggestionsStripHeight(Resources resources) {
        int base = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height);
        float scale = SettingsManager.getEffectiveKeyboardHeightScale(resources);
        if (scale == 1.0f) {
            return base;
        }
        return Math.max(1, Math.round(base * scale));
    }

    /**
     * Invalidate cached dimension values.
     * Call this when configuration changes (e.g., orientation, density).
     */
    public static void invalidateDimensionCache() {
        sCachedScreenWidth = -1;
        sCachedKeyboardHeight = -1;
        sCachedOrientation = -1;
        sCachedDensityDpi = -1;
        sCachedScreenWidthDp = -1;
    }

    public static int getKeyboardHeight(Resources resources) throws Resources.NotFoundException {
        float dimension;
        DisplayMetrics displayMetrics = resources.getDisplayMetrics();
        if (DeviceProfile.current().hasPhysicalKeyboard() && resources.getConfiguration().orientation == 1 && displayMetrics.heightPixels - ((int) resources.getDimension(R.dimen.config_mercury_max_screen_height)) <= 0) {
            return (int) resources.getDimension(R.dimen.config_default_mercury_keyboard_height);
        }
        dimension = resources.getDimension(R.dimen.config_default_keyboard_height);
        float fraction = resources.getFraction(R.fraction.config_max_keyboard_height, displayMetrics.heightPixels, displayMetrics.heightPixels);
        float fraction2 = resources.getFraction(R.fraction.config_min_keyboard_height, displayMetrics.heightPixels, displayMetrics.heightPixels);
        if (fraction2 < 0.0f) {
            fraction2 = -resources.getFraction(R.fraction.config_min_keyboard_height, displayMetrics.widthPixels, displayMetrics.widthPixels);
        }
        int baseHeight = (int) Math.max(Math.min(dimension, fraction), fraction2);

        // Apply keyboard height scaling after clamping (expanded=1.15, regular=1.0, compact=0.85, extra_compact=0.75)
        float scale = SettingsManager.getEffectiveKeyboardHeightScale(resources);
        if (scale != 1.0f) {
            baseHeight = (int)(baseHeight * scale);
        }

        return baseHeight;
    }

    public static int getKeyboardHeightWithPadding(Resources resources) throws Resources.NotFoundException {
        int iM5592b = getKeyboardHeight(resources);
        int fraction = (int) resources.getFraction(R.fraction.config_key_vertical_gap_holo, iM5592b, iM5592b);
        int fraction2 = (int) resources.getFraction(R.fraction.config_keyboard_bottom_padding_holo, iM5592b, iM5592b);
        int fraction3 = (int) resources.getFraction(R.fraction.config_keyboard_top_padding_holo, iM5592b, iM5592b);
        return (((((((iM5592b - fraction2) - fraction3) + fraction) / 4) * 4) + fraction3) + fraction2) - fraction;
    }

    public static float getFractionValue(Resources resources, int i) {
        return resources.getFraction(i, 1, 1);
    }

    public static float getFractionOrDefault(TypedArray typedArray, int i, float f) {
        TypedValue typedValuePeekValue = typedArray.peekValue(i);
        return (typedValuePeekValue == null || !isFractionType(typedValuePeekValue)) ? f : typedArray.getFraction(i, 1, 1, f);
    }

    public static float getFractionOrInvalid(TypedArray typedArray, int i) {
        return getFractionOrDefault(typedArray, i, -1.0f);
    }

    public static int getDimensionPixelSizeOrInvalid(TypedArray typedArray, int i) {
        TypedValue typedValuePeekValue = typedArray.peekValue(i);
        if (typedValuePeekValue == null || !isDimensionType(typedValuePeekValue)) {
            return -1;
        }
        return typedArray.getDimensionPixelSize(i, -1);
    }

    public static float getFractionOrDimensionOrDefault(TypedArray typedArray, int i, int i2, float f) {
        TypedValue typedValuePeekValue = typedArray.peekValue(i);
        if (typedValuePeekValue == null) {
            return f;
        }
        if (isFractionType(typedValuePeekValue)) {
            return typedArray.getFraction(i, i2, i2, f);
        }
        // FIX: Use getDimensionPixelSize() instead of getDimension() to match SuggestionStripView's rounding behavior
        // getDimension() returns float which gets truncated when cast to int (52.5 -> 52)
        // getDimensionPixelSize() rounds to nearest int (52.5 -> 53)
        return isDimensionType(typedValuePeekValue) ? typedArray.getDimensionPixelSize(i, (int) f) : f;
    }

    public static int getIntOrDefault(TypedArray typedArray, int i, int i2) {
        TypedValue typedValuePeekValue = typedArray.peekValue(i);
        return (typedValuePeekValue != null && isIntegerType(typedValuePeekValue)) ? typedArray.getInt(i, i2) : i2;
    }

    public static boolean isFractionType(TypedValue typedValue) {
        return typedValue.type == 6;
    }

    public static boolean isDimensionType(TypedValue typedValue) {
        return typedValue.type == 5;
    }

    public static boolean isIntegerType(TypedValue typedValue) {
        return typedValue.type >= 16 && typedValue.type <= 31;
    }

    public static boolean isStringType(TypedValue typedValue) {
        return typedValue.type == 3;
    }
}
