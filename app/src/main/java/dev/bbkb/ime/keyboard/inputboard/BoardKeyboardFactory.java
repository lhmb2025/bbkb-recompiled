package dev.bbkb.ime.keyboard.inputboard;

import android.content.Context;
import android.content.res.Resources;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.R;

/**
 * Builds input-board keyboards. They must be built from a context carrying
 * {@code R.style.KeyboardTheme_LXX}: the icons resolve from {@code KeyboardTheme} attributes the
 * raw IME context lacks, and an unwrapped build silently renders default colours and blank icon
 * keys. {@link #themedContext} is the one place that applies the style.
 */
public final class BoardKeyboardFactory {

    private BoardKeyboardFactory() {
        // Static utility.
    }

    /** The context every board keyboard is built from; re-wrapping a themed context is harmless. */
    public static Context themedContext(Context base) {
        return new ContextThemeWrapper(base, R.style.KeyboardTheme_LXX);
    }

    /**
     * A {@link KeyboardBuilder} for a board layout, themed and sized.
     *
     * @param subtype the layout-set subtype, e.g. {@code SubtypeFactory.createSubtype(locale, "number_pad")}
     * @param width   board width in pixels
     * @param height  board height in pixels
     */
    public static KeyboardBuilder builder(Context base, InputMethodSubtype subtype,
            int width, int height) {
        final KeyboardBuilder.Builder builder =
                new KeyboardBuilder.Builder(themedContext(base), null);
        builder.setSubtype(subtype);
        builder.setKeyboardGeometry(width, height);
        return builder.build();
    }

    /**
     * Build one board keyboard element at screen width by keyboard height with padding.
     * Deliberately uncached: {@code NumberPadController} owns the only cache, and a second one would
     * be another thing to invalidate on a theme change.
     */
    public static Keyboard buildBoardKeyboard(Context base, InputMethodSubtype subtype,
            int elementId) {
        final Resources resources = base.getResources();
        return builder(base, subtype,
                ResourceConfigManager.getScreenWidthPixels(resources),
                ResourceConfigManager.getKeyboardHeightWithPadding(resources))
                .getKeyboardForShift(elementId, false);
    }
}
