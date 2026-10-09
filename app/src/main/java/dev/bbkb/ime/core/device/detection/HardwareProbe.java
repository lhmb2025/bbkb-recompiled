package dev.bbkb.ime.core.device.detection;

import android.os.Build;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.shared.SystemProps;

/**
 * Low-level hardware probing utilities.
 * All methods are static - no state.
 */
public final class HardwareProbe {

    private HardwareProbe() {} // No instantiation

    /** Check if running on an emulator */
    public static boolean isEmulator() {
        return "goldfish".equals(Build.HARDWARE) || "ranchu".equals(Build.HARDWARE);
    }

    /** Check if running on Dalvik (vs ART) */
    public static boolean isDalvikVM() {
        return "Dalvik".equals(System.getProperty("java.vm.name"));
    }

    /**
     * The raw firmware keypad-language property, or null when neither key is set.
     *
     * <p>Stock KEY2 firmware writes {@code "<index> <name>"} — the owner's athena reports
     * {@code "1 qwerty"}. It is returned verbatim: interpreting it belongs to
     * {@link KeypadLayoutDetector}, which treats it as only one of several sources because
     * several LineageOS builds leave it unset entirely. This used to be
     * {@code getSystemKeypadLanguage()}, which parsed the value here and returned
     * {@code "qwerty"} when there was nothing to parse — laundering "the firmware did not say"
     * into a confident wrong answer that no later source could correct.
     */
    @Nullable
    public static String getKeypadLanguageProp() {
        final String val = SystemProps.get("ro.hwf.keypadlanguage");
        return val != null ? val : SystemProps.get("ro.product.keypadlanguage");
    }

    /** Get keypad type from system properties */
    @Nullable
    public static String getSystemKeypadType() {
        String val = SystemProps.get("ro.hwf.keypadtype");
        if (val == null) {
            val = SystemProps.get("ro.product.keypadtype");
        }
        return val != null ? val.toLowerCase(java.util.Locale.ROOT) : "none";
    }

    /** Check if device is BlackBerry branded */
    public static boolean isBlackBerryDevice() {
        return "blackberry".equalsIgnoreCase(Build.BRAND);
    }

    /**
     * Whether this phone runs on a MediaTek chip (every Unihertz Titan does). Shizuku 13.6.0 does
     * not work fully on MediaTek phones, so the Touch surface helper names the version that does.
     */
    public static boolean isMediaTek() {
        final boolean hasSoc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        return isMediaTek(Build.HARDWARE, Build.BOARD,
                hasSoc ? Build.SOC_MANUFACTURER : null, hasSoc ? Build.SOC_MODEL : null);
    }

    /**
     * The decision behind {@link #isMediaTek()}: a hardware, board or SoC model named
     * {@code mt<digits>} ("mt6771", "MT6789"), or a SoC manufacturer that says MediaTek.
     */
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
