package dev.bbkb.ime.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-3: the state setters KeyboardState and KeyboardSwitcher call on every update repaint only when
 * the state changed, and the medium-weight key face is created once per base face.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainKeyboardViewStateGuardTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var view: MainKeyboardView

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardBuilder.clearKeyboardCache()
        KeyboardColorManager.init(app, KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.LIGHT, false)
        val themed = BoardKeyboardFactory.themedContext(app)
        val english = InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale("en_US")
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
            .setIsAsciiCapable(true)
            .build()
        val keyboard = KeyboardBuilder.Builder(themed, EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT })
            .setKeyboardGeometry(1080, 700)
            .setSubtype(english)
            .build()
            .getKeyboard(0)
        view = MainKeyboardView(themed, null)
        view.setKeyboard(keyboard)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        drawn()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
    }

    /** Draws, which settles the pending full redraw and empties the repaint set. */
    private fun drawn() {
        view.drawFullForTest(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
    }

    /** Whether a key repaint is pending (KeyboardView.invalidateKey's set; a draw empties it). */
    private fun repainted(): Boolean {
        val field = KeyboardView::class.java.getDeclaredField("invalidatedKeys").apply { isAccessible = true }
        return (field.get(view) as Set<*>).isNotEmpty()
    }

    @Test
    fun shiftSettersRepaintOnlyOnAChange() {
        view.setShiftKeyState(false)
        view.setShiftLocked(false)
        assertFalse("unchanged shift state: no repaint", repainted())

        view.setShiftKeyState(true)
        assertTrue(repainted())
        drawn()
        view.setShiftKeyState(true)
        assertFalse(repainted())

        view.setShiftLocked(true)
        assertTrue(repainted())
        drawn()
        view.setShiftLocked(true)
        assertFalse(repainted())
    }

    @Test
    fun functionalKeyAndSpacebarSettersRepaintOnlyOnAChange() {
        view.setAutoCorrectionText("")
        assertFalse(repainted())
        view.setAutoCorrectionText("word")
        assertTrue(repainted())
        drawn()
        view.setAutoCorrectionText("word")
        assertFalse(repainted())

        val shortcut = view.keyboard.getKeyByCode(-7)
        if (shortcut != null) {
            view.setFunctionalKeyEnabled(shortcut.isActive)
            assertFalse("unchanged enabled state: no repaint", repainted())
            view.setFunctionalKeyEnabled(!shortcut.isActive)
            assertTrue(repainted())
        }
    }

    @Test
    fun theMediumWeightFaceIsCreatedOncePerBaseFace() {
        val base = android.graphics.Typeface.DEFAULT
        assertSame(KeyboardView.mediumWeightTypeface(base), KeyboardView.mediumWeightTypeface(base))
        assertSame(KeyboardView.mediumWeightTypeface(null), KeyboardView.mediumWeightTypeface(base))
    }
}
