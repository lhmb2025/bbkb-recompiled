package dev.bbkb.ime.keyboard.inputboard;

import android.content.res.Resources;
import android.view.View;

import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

/**
 * Board size: full width, and the live alphabet keyboard's occupied height, falling back to the
 * configured keyboard height when there is no keyboard yet or it reports zero. The fallback is
 * resolved once per view and passed in, since measure runs on the layout hot path.
 */
public final class BoardHeightPolicy {

    private BoardHeightPolicy() {
        // Static utility.
    }

    /** Height when there is no alphabet keyboard to match; resolve once per view at construction. */
    public static int fallbackHeight(Resources resources) {
        return ResourceConfigManager.getKeyboardHeightWithPadding(resources);
    }

    /**
     * The board's content height: the alphabet keyboard's occupied height when there is one,
     * otherwise {@code fallbackHeight}. Excludes the view's own padding.
     */
    public static int contentHeight(KeyboardSwitcher switcher, int fallbackHeight) {
        final Keyboard alphabet = switcher == null ? null : switcher.getAlphabetKeyboard();
        if (alphabet != null && alphabet.mOccupiedHeight != 0) {
            return alphabet.mOccupiedHeight;
        }
        return fallbackHeight;
    }

    /** Full measured width for a board view: the screen width plus the view's own padding. */
    public static int measuredWidth(View view) {
        return ResourceConfigManager.getScreenWidthPixels(view.getResources())
                + view.getPaddingLeft() + view.getPaddingRight();
    }

    /** Full measured height for a board view: {@link #contentHeight} plus the view's own padding. */
    public static int measuredHeight(View view, KeyboardSwitcher switcher, int fallbackHeight) {
        return contentHeight(switcher, fallbackHeight)
                + view.getPaddingTop() + view.getPaddingBottom();
    }
}
