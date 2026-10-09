package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.databinding.ClipDataHeaderBinding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A clipboard row must paste wherever you tap it, and only the overflow button may take a touch
 * for itself.
 *
 * `View.onTouchEvent` consumes a gesture when the view is clickable **or** long-clickable.
 * `clip_data_header.xml` once declared `android:longClickable="true"` on the text container, so it
 * swallowed every touch over the text; only the thumbnail let the touch reach the card, and pasting
 * worked only there (found on the KEY2, 2026-09-15). The card is now the row's root and owns both
 * the paste tap and the menu long-press; the overflow button beside it opens the same menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardRowTapTargetTest {

    private fun inflate(): ClipDataHeaderBinding {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(dev.bbkb.ime.R.style.Theme_BlackberryKeyboard_IME)
        return ClipDataHeaderBinding.inflate(LayoutInflater.from(context), null, false)
    }

    private fun row(): ClipDataHeaderBinding {
        val binding = inflate()
        ClipboardViewHolder(binding)          // the holder is what clears the flags
        val root = binding.root
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 270)
        return binding
    }

    private fun touchCentreOf(binding: ClipDataHeaderBinding, target: View) {
        val x = (target.left + target.width / 2).toFloat()
        val y = (target.top + target.height / 2).toFloat()
        val t = SystemClock.uptimeMillis()
        binding.root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
    }

    private fun release(binding: ClipDataHeaderBinding) {
        val t = SystemClock.uptimeMillis()
        binding.root.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, 0f, 0f, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theTextContainerConsumesNothing() {
        val b = row()
        assertFalse("text container must not be clickable", b.clipboardTextContainer.isClickable)
        assertFalse("text container must not be focusable", b.clipboardTextContainer.isFocusable)
        assertFalse(
            "text container must not be long-clickable: onTouchEvent consumes the gesture for " +
                "clickable OR longClickable, which is what stole taps over the text",
            b.clipboardTextContainer.isLongClickable,
        )
    }

    @Test
    fun theCardIsTheRowAndTakesTapsAndLongPresses() {
        val b = row()
        assertSame("the card is the row's root", b.root, b.clipboardForeground)
        assertTrue("the card is the paste target", b.clipboardForeground.isClickable)
        assertTrue("the card opens the menu on a long-press", b.clipboardForeground.isLongClickable)
    }

    @Test
    fun aTouchOverTheTextReachesTheCard() {
        val b = row()
        val text = b.clipboardTextContainer
        assertTrue("text container laid out", text.width > 0 && text.height > 0)

        touchCentreOf(b, text)

        assertTrue(
            "a touch over the text must reach the card, not be swallowed by the text container",
            b.clipboardForeground.isPressed,
        )
        release(b)
    }

    @Test
    fun aTouchOnTheOverflowButtonIsTheButtonsNotTheCards() {
        val b = row()
        val overflow = b.clipboardOverflow
        assertTrue("overflow laid out", overflow.width > 0 && overflow.height > 0)

        touchCentreOf(b, overflow)

        assertTrue(overflow.isPressed)
        assertFalse("the card must not paste when the menu button is pressed", b.clipboardForeground.isPressed)
        release(b)
    }

    @Test
    fun theLayoutItselfDeclaresNoTouchConsumingFlagsOnTheTextContainer() {
        // The holder clears these defensively, so this case guards the other layer: the XML must
        // not re-introduce android:longClickable (or clickable/focusable) on the text container.
        val raw = inflate()
        assertFalse("layout sets longClickable on the text container", raw.clipboardTextContainer.isLongClickable)
        assertFalse("layout sets clickable on the text container", raw.clipboardTextContainer.isClickable)
    }
}
