package dev.bbkb.ime.keyboard

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import dev.bbkb.ime.keyboard.internal.DrawingPreviewPlacerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The key-preview placer MainKeyboardView adds to the window's content view leaves with the
 * keyboard view, so recreating the input view (theme or height change) does not stack placers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainKeyboardViewPlacerLifecycleTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardColorManager.init(app, KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.LIGHT, false)
    }

    private fun placers(content: ViewGroup): Int =
        (0 until content.childCount).count { content.getChildAt(it) is DrawingPreviewPlacerView }

    @Test
    fun recreatingTheKeyboardViewLeavesOnePlacer() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val inputArea = FrameLayout(activity)
        activity.setContentView(inputArea)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)

        var view = MainKeyboardView(BoardKeyboardFactory.themedContext(activity), null)
        inputArea.addView(view)
        assertEquals(1, placers(content))

        repeat(3) {
            inputArea.removeView(view)
            view = MainKeyboardView(BoardKeyboardFactory.themedContext(activity), null)
            inputArea.addView(view)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("one placer after recreation ${it + 1}", 1, placers(content))
        }

        val placer = (0 until content.childCount).map { content.getChildAt(it) }
            .first { it is DrawingPreviewPlacerView }
        inputArea.removeView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("the placer leaves with its keyboard view", placer.parent)
        assertEquals(0, placers(content))
    }
}
