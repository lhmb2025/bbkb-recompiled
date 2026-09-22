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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A clipboard row must paste wherever you tap it, not only on the thumbnail.
 *
 * `View.onTouchEvent` consumes a gesture when the view is clickable **or** long-clickable, and
 * `clip_data_header.xml` used to declare `android:longClickable="true"` on the text container.
 * `ClipboardViewHolder` cleared `clickable`/`focusable` but not `longClickable`, so the container
 * swallowed every touch over the text; only the thumbnail — the one child with neither flag — let
 * the touch reach the card, and pasting worked only there (found on the KEY2, 2026-09-15).
 * The long-press that expands a row lives on the foreground card, so nothing wanted the flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ClipboardRowTapTargetTest {

    private fun row(): ClipDataHeaderBinding {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(dev.bbkb.ime.R.style.Theme_BlackberryKeyboard_IME)
        val binding = ClipDataHeaderBinding.inflate(LayoutInflater.from(context), null, false)
        ClipboardViewHolder(binding)          // the holder is what clears the flags
        val root = binding.root
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 270)
        return binding
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
    fun theCardItselfTakesTapsAndLongPresses() {
        val b = row()
        assertTrue("the card is the paste target", b.clipboardForeground.isClickable)
    }

    @Test
    fun aTouchOverTheTextReachesTheCard() {
        val b = row()
        val text = b.clipboardTextContainer
        assertTrue("text container laid out", text.width > 0 && text.height > 0)
        val x = (text.left + text.width / 2).toFloat()
        val y = (text.top + text.height / 2).toFloat()
        val t = SystemClock.uptimeMillis()
        b.root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        // The card is the paste target (ClipboardAdapter.setupClickListeners puts the listener
        // there). If the text container consumes the DOWN, the card never presses and the tap is
        // lost -- which is exactly what android:longClickable="true" on it used to cause.
        assertTrue(
            "a touch over the text must reach the card, not be swallowed by the text container",
            b.clipboardForeground.isPressed,
        )
        b.root.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun theLayoutItselfDeclaresNoTouchConsumingFlagsOnTheTextContainer() {
        // The holder clears these defensively, so this case guards the other layer: the XML must
        // not re-introduce android:longClickable (or clickable/focusable) on the text container.
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.setTheme(dev.bbkb.ime.R.style.Theme_BlackberryKeyboard_IME)
        val raw = ClipDataHeaderBinding.inflate(LayoutInflater.from(context), null, false)
        assertFalse("layout sets longClickable on the text container", raw.clipboardTextContainer.isLongClickable)
        assertFalse("layout sets clickable on the text container", raw.clipboardTextContainer.isClickable)
    }
}
