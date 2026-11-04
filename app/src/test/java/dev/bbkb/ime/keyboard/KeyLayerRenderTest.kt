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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * D-3: on a hardware canvas the main keyboard draws its keys at rest and then each key with per-tap
 * content (pressed, or the spacebar's word) in its own layer, which repaints the background and
 * frets under the key before the key. The composite must be exactly the pixels of the old full
 * redraw, in every key style. Rendered here through the software equivalents of both paths
 * ([KeyboardView.drawFullForTest], [KeyboardView.drawLayeredForTest]) with real Skia rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyLayerRenderTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private val english: InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("en_US")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
        .setIsAsciiCapable(true)
        .build()

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardBuilder.clearKeyboardCache()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
    }

    private fun alphabet(context: Context): Keyboard {
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        return KeyboardBuilder.Builder(context, editor)
            .setKeyboardGeometry(1080, 700)
            .setSubtype(english)
            .build()
            .getKeyboard(0)
    }

    private fun render(view: KeyboardView, layered: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (layered) view.drawLayeredForTest(canvas) else view.drawFullForTest(canvas)
        return bitmap
    }

    private fun assertLayeredMatchesFull(style: KeyboardColorManager.Style, scheme: KeyboardColorManager.Scheme) {
        KeyboardColorManager.init(app, style, scheme, false)
        val themed = BoardKeyboardFactory.themedContext(app)
        val view = MainKeyboardView(themed, null)
        val keyboard = alphabet(themed)
        view.setKeyboard(keyboard)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.background.setBounds(0, 0, view.width, view.height)

        val canUse = KeyboardView::class.java.getDeclaredMethod("canUseKeyLayers").apply { isAccessible = true }
        assertTrue("$style $scheme: an opaque background, so the layers are on", canUse.invoke(view) as Boolean)

        val atRest = render(view, layered = false)
        val pressed = keyboard.getKeyByCode('e'.code)!!
        val shift = keyboard.getKeyByCode(-1)
        pressed.onPressed()
        shift?.onPressed()
        view.setAutoCorrectionText("hello")
        try {
            val full = render(view, layered = false)
            val layered = render(view, layered = true)
            assertFalse("$style: the pressed key and the spacebar word must be visible", full.sameAs(atRest))
            assertTrue("$style $scheme: layered composite differs from the full redraw", full.sameAs(layered))
        } finally {
            pressed.onReleased()
            shift?.onReleased()
            view.setAutoCorrectionText("")
        }
    }

    @Test
    fun layeredCompositeIsPixelIdenticalInEveryStyle() {
        for (style in KeyboardColorManager.Style.values()) {
            for (scheme in listOf(KeyboardColorManager.Scheme.LIGHT, KeyboardColorManager.Scheme.DARK)) {
                assertLayeredMatchesFull(style, scheme)
            }
        }
    }
}
