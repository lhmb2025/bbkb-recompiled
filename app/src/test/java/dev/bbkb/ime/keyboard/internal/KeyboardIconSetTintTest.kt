package dev.bbkb.ime.keyboard.internal

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.keyboard.KeyboardColorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * P-13: applyColorTint tints the set's own drawables in place, and only when the palette's icon
 * color changed since the last call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardIconSetTintTest {

    private class CountingDrawable : ColorDrawable() {
        var tints = 0
        override fun setTintList(tint: ColorStateList?) {
            tints++
            super.setTintList(tint)
        }
    }

    @Test
    fun tintsInPlaceAndOnlyWhenThePaletteChanges() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        KeyboardColorManager.init(context, KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.LIGHT, false)
        val set = KeyboardIconSet()
        val icon = CountingDrawable()
        ReflectionHelpers.getField<Array<Drawable?>>(set, "mIcons")[1] = icon

        set.applyColorTint()
        assertEquals(1, icon.tints)
        assertSame("tinted in place, not replaced by a copy", icon, set.getIconDrawable(1))

        set.applyColorTint()
        set.applyColorTint()
        assertEquals("unchanged palette: no re-tint", 1, icon.tints)

        KeyboardColorManager.setTheme(KeyboardColorManager.Style.MODERN, KeyboardColorManager.Scheme.DARK, false)
        set.applyColorTint()
        assertEquals("palette changed: re-tinted", 2, icon.tints)
        assertSame(icon, set.getIconDrawable(1))
    }
}
