package dev.bbkb.ime.keyboard

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/**
 * The shift-state indicator bar in [MainKeyboardView.drawKeyContent] draws with one hoisted paint
 * instead of allocating a `Paint` on every shift-key repaint. The hoisted paint must carry exactly
 * the settings the per-draw allocation had (anti-aliased, FILL); only the colour varies per draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MainKeyboardViewShiftIndicatorPaintTest {

    @Test
    fun theIndicatorPaintIsAFinalFieldBuiltOnce() {
        val field = MainKeyboardView::class.java.getDeclaredField("mShiftIndicatorPaint")
        assertEquals(Paint::class.java, field.type)
        assertTrue(Modifier.isFinal(field.modifiers))
        assertTrue(!Modifier.isStatic(field.modifiers))
    }

    @Test
    fun theIndicatorPaintHasTheSettingsTheDrawPathUsedToAllocate() {
        val paint = MainKeyboardView.newShiftIndicatorPaint()
        val perDraw = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        assertEquals(perDraw.flags, paint.flags)
        assertEquals(Paint.Style.FILL, paint.style)
        assertTrue(paint.isAntiAlias)
    }
}
