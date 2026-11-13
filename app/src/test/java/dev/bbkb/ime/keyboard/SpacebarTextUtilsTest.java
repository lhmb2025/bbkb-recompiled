package dev.bbkb.ime.keyboard;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.graphics.Paint;
import android.graphics.Rect;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link SpacebarTextUtils#truncateTextToWidth} runs on the draw thread from the spacebar
 * language-label path (MainKeyboardView.onDraw), so it must terminate for every input.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {34}, manifest = Config.NONE)
public class SpacebarTextUtilsTest {

    /** Stand-in for a font: maps (string, current text size) to the width it would render at. */
    private interface WidthModel {
        int widthOf(String text, float textSize);
    }

    private static Paint paintWith(final WidthModel model) {
        final Paint paint = mock(Paint.class);
        final float[] textSize = {1f};
        doAnswer(invocation -> {
            textSize[0] = invocation.getArgument(0);
            return null;
        }).when(paint).setTextSize(anyFloat());
        doAnswer(invocation -> {
            final String text = invocation.getArgument(0);
            final Rect out = invocation.getArgument(3);
            out.set(0, 0, model.widthOf(text, textSize[0]), 10);
            return null;
        }).when(paint).getTextBounds(anyString(), anyInt(), anyInt(), any(Rect.class));
        return paint;
    }

    /** A well-behaved font: width is proportional to both length and text size. */
    private static final WidthModel LINEAR = (text, size) -> Math.round(text.length() * size);

    @Test
    public void textThatAlreadyFitsIsReturnedUnchanged() {
        assertEquals("abc", SpacebarTextUtils.truncateTextToWidth(100, "abc", 10f, 0.7f, paintWith(LINEAR)));
    }

    @Test
    public void textShrinksUntilItFitsAndIsReturnedWhole() {
        // "hello" is 50px at size 10 but the spacebar is 20px; scaling to 0.4 (above the 0.3
        // minimum) makes it fit exactly, so the whole label is kept.
        assertEquals("hello", SpacebarTextUtils.truncateTextToWidth(20, "hello", 10f, 0.3f, paintWith(LINEAR)));
    }

    @Test
    public void textTooLongToScaleDownIsTruncatedWithAnEllipsis() {
        assertEquals("hel…", SpacebarTextUtils.truncateTextToWidth(20, "hello world", 10f, 0.5f, paintWith(LINEAR)));
    }

    /**
     * A font whose width has a floor: no scale makes the label fit, so the shrink loop must give up
     * and fall through to the ellipsis path instead of spinning on the draw thread.
     */
    @Test(timeout = 5000)
    public void labelThatCanNeverBeScaledToFitTerminates() {
        final WidthModel widthFloor = (text, size) -> Math.max(60, Math.round(text.length() * size));
        // Nothing fits in 20px - not even a bare ellipsis - so no character can be shown.
        assertEquals("", SpacebarTextUtils.truncateTextToWidth(20, "hello", 10f, 0.3f, paintWith(widthFloor)));
    }

    @Test
    public void nullTextIsEmpty() {
        assertEquals("", SpacebarTextUtils.truncateTextToWidth(100, null, 10f, 0.7f, paintWith(LINEAR)));
    }

    /** The first cut estimate lands inside the emoji's surrogate pair; the cut must move back past it. */
    @Test
    public void ellipsisNeverSplitsASurrogatePair() {
        // 8 chars at the 0.5 minimum scale are 5px each, so 20px holds three chars plus the
        // ellipsis; the third char is the high surrogate of the emoji.
        assertEquals("ab…",
                SpacebarTextUtils.truncateTextToWidth(20, "ab\uD83D\uDE00cdef", 10f, 0.5f, paintWith(LINEAR)));
    }
}
