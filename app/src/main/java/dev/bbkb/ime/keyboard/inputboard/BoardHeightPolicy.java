package dev.bbkb.ime.keyboard.inputboard;

import android.content.res.Resources;
import android.view.View;

import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

/**
 * How big an input board is.
 *
 * <p>Every board is a full-width panel that sits exactly over the alphabet keyboard, so its size is
 * one rule: take the live alphabet keyboard's occupied height, and fall back to the configured
 * keyboard height when there is no keyboard yet (first show before any layout has been built) or
 * when it reports zero.
 *
 * <p>That rule was written out four times — {@code ClipboardView.onMeasure},
 * {@code FccView.onMeasure}, {@code VoiceInputView.onMeasure} and {@code EmojiPalettesView.onMeasure},
 * the first three character-for-character identical including the re-measure with EXACTLY specs.
 * Four copies of a sizing rule is four chances for a board to be a few pixels off the keyboard it
 * covers (§5.6 item 2).
 *
 * <p>The fallback is deliberately resolved once and passed back in rather than read here on every
 * measure pass: that is what the views did (a {@code final} field set in the constructor), and
 * measure runs on the layout hot path.
 */
public final class BoardHeightPolicy {

    private BoardHeightPolicy() {
    }

    /**
     * The height to use when there is no alphabet keyboard to match. Resolve once per view, at
     * construction, and hand it back to {@link #contentHeight} / {@link #measuredHeight}.
     */
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
