package dev.bbkb.ime.keyboard

import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Use system colors" (Material You) must reach every palette slot, above all the accent:
 * the accent is what paints the Material style's enter-key pill, so a static accent leaves
 * a teal pill on an otherwise wallpaper-tinted board (beta report, 2026-09-15).
 *
 * The expected values are the M3 dynamic-color role mapping, which is also what
 * ThemeOverlay.Material3.DynamicColors assigns: primary is accent1 tone 40 in light
 * (system_accent1_600) and tone 80 in dark (system_accent1_200).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardColorManagerSystemColorsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun palette(
        scheme: KeyboardColorManager.Scheme,
        systemColors: Boolean,
    ): KeyboardColorManager.Palette {
        KeyboardColorManager.init(context, KeyboardColorManager.Style.MATERIAL, scheme, systemColors)
        return KeyboardColorManager.palette
    }

    @Test
    fun `dark accent follows the wallpaper when system colors are on`() {
        val accent = palette(KeyboardColorManager.Scheme.DARK, systemColors = true).accent
        assertNotEquals(
            "accent must not fall back to the static teal highlight",
            context.getColor(R.color.highlight_dark), accent)
        assertEquals(
            context.getColor(android.R.color.system_accent1_200), accent)
    }

    @Test
    fun `light accent follows the wallpaper when system colors are on`() {
        val accent = palette(KeyboardColorManager.Scheme.LIGHT, systemColors = true).accent
        assertNotEquals(
            "accent must not fall back to the static teal highlight",
            context.getColor(R.color.highlight_light), accent)
        assertEquals(
            context.getColor(android.R.color.system_accent1_600), accent)
    }

    /**
     * `keyAlt` paints every functional key -- delete, ?123/ABC, shift, and the enter key in
     * the styles that do not give it the accent pill. It used to be pinned to the static
     * fallback outright, so those keys stayed grey on a wallpaper-tinted board.
     */
    @Test
    fun `functional keys follow the wallpaper when system colors are on`() {
        for (scheme in listOf(KeyboardColorManager.Scheme.LIGHT, KeyboardColorManager.Scheme.DARK)) {
            val dynamic = palette(scheme, systemColors = true)
            val static = palette(scheme, systemColors = false)
            assertNotEquals("keyAlt must not be pinned to the static palette in $scheme",
                static.keyAlt, dynamic.keyAlt)
        }
    }

    /** Every slot, not just the two the old code happened to get right. */
    @Test
    fun `no palette slot falls through to the static palette`() {
        for (scheme in listOf(KeyboardColorManager.Scheme.LIGHT, KeyboardColorManager.Scheme.DARK)) {
            val dynamic = palette(scheme, systemColors = true)
            val static = palette(scheme, systemColors = false)
            assertNotEquals("the whole palette is inert in $scheme", static, dynamic)
            for ((name, pair) in mapOf(
                "icon" to (static.icon to dynamic.icon),
                "hint" to (static.hint to dynamic.hint),
                "background" to (static.background to dynamic.background),
                "key" to (static.key to dynamic.key),
                "keyAlt" to (static.keyAlt to dynamic.keyAlt),
                "keyPressed" to (static.keyPressed to dynamic.keyPressed),
                "accent" to (static.accent to dynamic.accent),
            )) {
                assertNotEquals("$name is not wallpaper-derived in $scheme", pair.first, pair.second)
            }
        }
    }

    @Test
    fun `system colors off keeps the app's own accent`() {
        assertEquals(
            context.getColor(R.color.highlight_dark),
            palette(KeyboardColorManager.Scheme.DARK, systemColors = false).accent)
    }
}

/**
 * The toggle is not the only route to wallpaper colour: `values-night-v31` aliases the dark
 * m3_* tokens to `@android:color/system_*`, so on Android 12+ in night mode the board is
 * wallpaper-tinted whether or not "use system colours" is on. The accent has to follow those
 * aliases, or the Material style's enter pill is a teal island on a wallpaper-tinted board --
 * which is the state the beta screenshot caught.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "night")
class KeyboardColorManagerNightAliasTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the dark palette is wallpaper-consistent with the toggle off`() {
        KeyboardColorManager.init(
            context, KeyboardColorManager.Style.MATERIAL, KeyboardColorManager.Scheme.DARK, false)
        val p = KeyboardColorManager.palette
        // The surfaces already came from the wallpaper here; assert it, so this test fails loudly
        // if values-night-v31 is ever dropped rather than quietly passing on a static palette.
        assertEquals(context.getColor(android.R.color.system_neutral1_900), p.background)
        assertEquals(context.getColor(android.R.color.system_neutral1_600), p.keyAlt)
        assertEquals(context.getColor(android.R.color.system_accent1_200), p.accent)
    }
}
