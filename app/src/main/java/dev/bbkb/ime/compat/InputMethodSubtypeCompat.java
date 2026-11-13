package dev.bbkb.ime.compat;

import android.view.inputmethod.InputMethodSubtype;

/**
 * Helpers around {@link InputMethodSubtype}. A non-zero {@code subtypeId} overrides the subtype's
 * hashCode, which keeps persisted enabled-subtype ids stable.
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
