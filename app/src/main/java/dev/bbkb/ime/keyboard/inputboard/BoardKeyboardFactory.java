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
 * One way to build a keyboard for an input board.
 *
 * <h3>The theme wrapper is load-bearing (C7)</h3>
 *
 * <p>A board keyboard must be built from a context carrying {@code R.style.KeyboardTheme_LXX}. Its
 * icon set — the backspace glyph, the board icons — resolves from {@code KeyboardTheme} styleable
 * attributes, which the raw IME service context does not carry. Build from an unwrapped context and
 * you get a keyboard with default colours, blank icon keys, and <em>no crash</em>: it renders wrong
 * and says nothing. Three places wrapped the context by hand to avoid that, each with the same
 * comment explaining why; a fourth forgetting to would have been invisible in review.
 *
 * <p>So the wrapping is not a caller's responsibility any more. {@link #themedContext} is the one
 * place that knows the style, and {@link #builder} and {@link #buildBoardKeyboard} go through it.
 */
public final class BoardKeyboardFactory {

    private BoardKeyboardFactory() {
    }

    /**
     * The context every board keyboard is built from. Wrapping an already-wrapped context is
     * harmless (the same style is re-applied over the same base), so callers holding a themed
     * context need not track whether they have one.
     */
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
     * Build one board keyboard element at the standard board size: full screen width by the
     * configured keyboard height with padding.
     *
     * <p>Deliberately un-cached. The one board that reuses a built keyboard across shows
     * ({@code NumberPadController}) owns its own single-entry cache keyed on the locale it built
     * for, because that is also what its {@code invalidateKeyboard()} contract is written against;
     * a second cache here would be a second thing to invalidate on a theme change, which is the
     * failure mode §5.6 item 3 is trying to remove rather than add to.
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
