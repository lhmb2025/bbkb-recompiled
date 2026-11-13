package dev.bbkb.ime.core.device.detection;

import android.os.Build;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.shared.SystemProps;

public final class HardwareProbe {
    private HardwareProbe() {}

    public static boolean isEmulator() {
        return "goldfish".equals(Build.HARDWARE) || "ranchu".equals(Build.HARDWARE);
    }

    public static boolean isDalvikVM() {
        return "Dalvik".equals(System.getProperty("java.vm.name"));
    }

    /**
     * Raw keypad-language property ({@code "1 qwerty"} on stock KEY2), or null when unset. Returned
     * verbatim; {@link KeypadLayoutDetector} interprets it.
     */
    @Nullable
    public static String getKeypadLanguageProp() {
        final String val = SystemProps.get("ro.hwf.keypadlanguage");
        return val != null ? val : SystemProps.get("ro.product.keypadlanguage");
    }

    /** Lowercased {@code ro.*.keypadtype}, or "none". */
    @Nullable
    public static String getSystemKeypadType() {
        String val = SystemProps.get("ro.hwf.keypadtype");
        if (val == null) {
            val = SystemProps.get("ro.product.keypadtype");
        }
        return val != null ? val.toLowerCase(java.util.Locale.ROOT) : "none";
    }

    public static boolean isBlackBerryDevice() {
        return "blackberry".equalsIgnoreCase(Build.BRAND);
    }

    /** Every Titan is MediaTek; Shizuku 13.6.0 is not fully usable there, so the helper names a version that is. */
    public static boolean isMediaTek() {
        final boolean hasSoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        return isMediaTek(Build.HARDWARE, Build.BOARD,
                hasSoc ? Build.SOC_MANUFACTURER : null, hasSoc ? Build.SOC_MODEL : null);
    }

    /** True for a hardware, board or SoC model named {@code mt<4 digits>}, or a MediaTek SoC manufacturer. */
    public static boolean isMediaTek(@Nullable String hardware, @Nullable String board,
                                     @Nullable String socManufacturer, @Nullable String socModel) {
        return isMediaTekPart(hardware) || isMediaTekPart(board) || isMediaTekPart(socModel)
                || (socManufacturer != null
                        && socManufacturer.toLowerCase(java.util.Locale.ROOT).contains("mediatek"));
    }

    private static boolean isMediaTekPart(@Nullable String name) {
        return name != null && MEDIATEK_PART.matcher(name).lookingAt();
    }

    private static final java.util.regex.Pattern MEDIATEK_PART =
            java.util.regex.Pattern.compile("(?i)mt\\d{4}");
}
