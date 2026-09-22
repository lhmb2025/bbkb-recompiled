package dev.bbkb.ime.compat;

import android.view.inputmethod.InputMethodSubtype;

/**
 * Helpers around {@link InputMethodSubtype}. Formerly reflected the hidden 8-arg
 * constructor to set an explicit subtype id; {@link InputMethodSubtype.InputMethodSubtypeBuilder}
 * (API 19) exposes the same fields publicly, including {@code setSubtypeId} — a non-zero id
 * overrides the subtype's hashCode exactly like the hidden constructor did, which keeps
 * persisted enabled-subtype ids stable.
 */
public final class InputMethodSubtypeCompat {

    private InputMethodSubtypeCompat() {
    }

    public static InputMethodSubtype newInputMethodSubtype(int nameResId, int iconResId, String locale, String mode,
            String extraValue, boolean isAuxiliary, boolean overridesImplicitlyEnabledSubtype, int subtypeId) {
        return new InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeNameResId(nameResId)
                .setSubtypeIconResId(iconResId)
                .setSubtypeLocale(locale)
                .setSubtypeMode(mode)
                .setSubtypeExtraValue(extraValue)
                .setIsAuxiliary(isAuxiliary)
                .setOverridesImplicitlyEnabledSubtype(overridesImplicitlyEnabledSubtype)
                .setSubtypeId(subtypeId)
                .build();
    }

    public static boolean isAsciiCapable(InputMethodSubtype subtype) {
        return subtype.isAsciiCapable() || subtype.containsExtraValueKey("AsciiCapable");
    }
}
