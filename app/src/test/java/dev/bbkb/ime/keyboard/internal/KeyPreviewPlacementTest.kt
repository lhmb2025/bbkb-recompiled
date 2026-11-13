package dev.bbkb.ime.keyboard.internal

import android.content.Context
import android.content.res.TypedArray
import android.view.View
import android.widget.RelativeLayout
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.keyboard.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Key previews are shown, hidden and moved by RenderNode properties (alpha, translation) on a child
 * that stays attached and VISIBLE, so a press does not make the full-window placer re-record its
 * display list (a large refresh per keystroke on an e-ink panel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyPreviewPlacementTest {

    private lateinit var context: Context
    private lateinit var placer: RelativeLayout
    private lateinit var choreographer: KeyPreviewChoreographer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        placer = RelativeLayout(context)
        val attrs = mock(TypedArray::class.java)
        choreographer = KeyPreviewChoreographer(KeyPreviewDrawParams(attrs))
    }

    private fun key(x: Int, y: Int, width: Int = 40, height: Int = 50): Key {
        val key = mock(Key::class.java)
        `when`(key.width).thenReturn(width)
        `when`(key.height).thenReturn(height)
        `when`(key.drawX).thenReturn(x)
        `when`(key.drawWidth).thenReturn(width)
        `when`(key.x).thenReturn(x)
        `when`(key.y).thenReturn(y)
        `when`(key.visibleLabel).thenReturn("a")
        return key
    }

    private fun show(key: Key) {
        choreographer.placeAndShowKeyPreview(key, null, null, 1000, intArrayOf(0, 300), placer, false)
    }

    private fun onlyPreview(): KeyPreviewView {
        assertEquals(1, placer.childCount)
        return placer.getChildAt(0) as KeyPreviewView
    }

    @Test
    fun aShownPreviewIsPositionedByTranslation() {
        show(key(x = 100, y = 20))

        val preview = onlyPreview()
        assertEquals(View.VISIBLE, preview.visibility)
        assertTrue(preview.isPreviewShown)
        assertEquals(100f, preview.translationX)
        // Bottom aligned with the key's bottom: 20 + 50 - 100 + the keyboard's 300 origin.
        assertEquals(270f, preview.translationY)
        val params = preview.layoutParams as RelativeLayout.LayoutParams
        assertEquals(0, params.leftMargin)
        assertEquals(0, params.topMargin)
        assertEquals(40, params.width)
        assertEquals(100, params.height)
    }

    @Test
    fun dismissingHidesByAlphaAndKeepsTheChildAttachedAndVisible() {
        val k = key(x = 100, y = 20)
        show(k)
        choreographer.dismissKeyPreview(k, false)

        val preview = onlyPreview()
        assertEquals(View.VISIBLE, preview.visibility)
        assertFalse(preview.isPreviewShown)
        assertEquals(0f, preview.alpha)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, preview.importantForAccessibility)
    }

    @Test
    fun theNextPressReusesTheSameChildAndOnlyMovesIt() {
        val first = key(x = 100, y = 20)
        show(first)
        choreographer.dismissKeyPreview(first, false)
        val preview = onlyPreview()
        val params = preview.layoutParams

        show(key(x = 300, y = 20))

        assertSame(preview, onlyPreview())
        assertSame("same size: no new layout params", params, preview.layoutParams)
        assertTrue(preview.isPreviewShown)
        assertEquals(300f, preview.translationX)
    }

    @Test
    fun aPooledPreviewDroppedByThePlacerIsAddedBack() {
        val first = key(x = 100, y = 20)
        show(first)
        choreographer.dismissKeyPreview(first, false)
        placer.removeAllViews()

        show(key(x = 200, y = 20))

        assertTrue(onlyPreview().isPreviewShown)
    }
}
