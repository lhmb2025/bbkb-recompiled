package dev.bbkb.ime.core.shared;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.List;


public final class UncachedInputMethodManagerUtils {

    private static final String TAG = "UncachedInputMethodManagerUtils";

    /**
     * {@code Settings.Secure.ENABLED_INPUT_METHODS} - not referenced through
     * {@link Settings.Secure} because the constant is {@code @hide}.
     */
    private static final String ENABLED_INPUT_METHODS = "enabled_input_methods";

    /** The separator between two IME entries in {@link #ENABLED_INPUT_METHODS}. */
    private static final char IME_SEPARATOR = ':';

    /** The separator between an IME id and its subtype hash codes inside one entry. */
    private static final char SUBTYPE_SEPARATOR = ';';

    private UncachedInputMethodManagerUtils() {
    }

    /**
     * Whether this IME is <em>enabled</em>, i.e. the user has switched it on in
     * Settings &gt; Languages &amp; input.
     *
     * <p>This used to compare our id against {@code Settings.Secure.default_input_method}, which
     * answers a different and much narrower question - "are we the IME that is currently
     * selected?". Every caller reads the name, so an enabled-but-not-selected keyboard looked
     * disabled; {@link dev.bbkb.ime.core.SystemBroadcastReceiver} then killed the
     * process on every boot (2026-09-21, KEY2, default IME temporarily AOSP LatinIME).
     */
    public static boolean isThisImeEnabled(Context context, InputMethodManager inputMethodManager) {
        final InputMethodInfo thisIme = getInputMethodInfoOf(context.getPackageName(), inputMethodManager);
        if (thisIme == null) {
            return false;
        }
        // The framework's list is the primary source: from targetSdk 34 the secure setting
        // itself is off limits (Settings.Secure.getString throws SecurityException "only readable
        // to apps with targetSdkVersion lower than or equal to: 33" - KEY2, Android 15,
        // 2026-09-21, which crashed the BOOT_COMPLETED receiver and took the process with it).
        if (isInEnabledList(thisIme.getId(), inputMethodManager)) {
            return true;
        }
        final String enabled;
        try {
            enabled = Settings.Secure.getString(context.getContentResolver(), ENABLED_INPUT_METHODS);
        } catch (SecurityException e) {
            return false;
        }
        return isImeIdEnabled(thisIme.getId(), enabled);
    }

    /**
     * Whether {@code imeId} appears in an {@code enabled_input_methods} value.
     *
     * <p>The setting is a {@code ':'}-joined list of entries, each of which is an IME id
     * optionally followed by {@code ';'}-separated subtype hash codes, e.g. the real KEY2 value
     * <pre>
     * dev.bbkb.ime.debug/dev.bbkb.ime.core.BlackBerryIME;242746067;-921088104:com.android.inputmethod.latin/.LatinIME
     * </pre>
     * An IME id is {@code package/class} and can contain neither {@code ':'} nor {@code ';'}, so
     * splitting on those two characters is exact.
     */
    @VisibleForTesting
    public static boolean isImeIdEnabled(@Nullable String imeId, @Nullable String enabledInputMethods) {
        if (TextUtils.isEmpty(imeId) || TextUtils.isEmpty(enabledInputMethods)) {
            return false;
        }
        int entryStart = 0;
        final int length = enabledInputMethods.length();
        while (entryStart <= length) {
            int entryEnd = enabledInputMethods.indexOf(IME_SEPARATOR, entryStart);
            if (entryEnd < 0) {
                entryEnd = length;
            }
            int idEnd = enabledInputMethods.indexOf(SUBTYPE_SEPARATOR, entryStart);
            if (idEnd < 0 || idEnd > entryEnd) {
                idEnd = entryEnd;
            }
            if (idEnd > entryStart
                    && imeId.length() == idEnd - entryStart
                    && enabledInputMethods.startsWith(imeId, entryStart)) {
                return true;
            }
            entryStart = entryEnd + 1;
        }
        return false;
    }

    private static boolean isInEnabledList(@NonNull String imeId, InputMethodManager inputMethodManager) {
        if (inputMethodManager == null) {
            return false;
        }
        final List<InputMethodInfo> enabled = inputMethodManager.getEnabledInputMethodList();
        if (enabled == null) {
            return false;
        }
        for (final InputMethodInfo info : enabled) {
            if (imeId.equals(info.getId())) {
                return true;
            }
        }
        return false;
    }

    public static InputMethodInfo getInputMethodInfoOf(String str, InputMethodManager inputMethodManager) {
        if (inputMethodManager == null) {
            return null;
        }
        for (InputMethodInfo inputMethodInfo : inputMethodManager.getInputMethodList()) {
            if (str.equals(inputMethodInfo.getPackageName())) {
                return inputMethodInfo;
            }
        }
        return null;
    }
}
