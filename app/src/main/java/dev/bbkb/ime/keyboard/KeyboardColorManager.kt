package dev.bbkb.ime.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.ColorUtils
import dev.bbkb.ime.R
import dev.bbkb.ime.core.shared.Logger

/**
 * Keyboard colors: one immutable [Palette] per theme, swapped atomically and backed by Compose
 * snapshot state. Consumers either observe changes, re-read on show, or read in the draw path.
 * Theme = [Style] (CLASSIC and BB10 have fixed palettes; MODERN = M3 palettes with BlackBerry
 * geometry; MATERIAL adds M3 styling) x [Scheme] (light/dark/auto, ignored by fixed palettes)
 * + system (wallpaper) colors, Android 12+, MODERN and MATERIAL only.
 */
object KeyboardColorManager {

    private const val TAG = "KeyboardColorManager"

    enum class Style(
        /** One fixed palette: no scheme axis, no system (wallpaper) colors. */
        @JvmField val fixedPalette: Boolean,
    ) {
        CLASSIC(true),
        MODERN(false),
        MATERIAL(false),
        BB10(true);

        companion object {
            @JvmStatic
            fun fromPref(value: String?): Style = when (value) {
                "classic" -> CLASSIC
                "material" -> MATERIAL
                "bb10" -> BB10
                else -> MODERN
            }
        }
    }

    enum class Scheme {
        LIGHT,
        DARK,
        AUTO;       // Follow system theme

        companion object {
            @JvmStatic
            fun fromPref(value: String?): Scheme = when (value) {
                "light" -> LIGHT
                "dark" -> DARK
                else -> AUTO
            }
        }
    }

    /** One theme's colors as an immutable snapshot, so a reader never sees a half-updated theme. */
    data class Palette(
        val icon: Int,
        val iconAlt: Int,
        val hint: Int,
        val text: Int,
        val background: Int,
        val key: Int,
        val keyAlt: Int,
        val keyPressed: Int,
        /** Gesture trail and sliding-key preview (the latter at [ALPHA_SLIDING_PREVIEW]). */
        val accent: Int,
        /** Base tone of the fret bars; BB10 pins it so the frets stay bright on dark keys. */
        val fret: Int = key,
        /**
         * Slideboard panels (number pad, quick phrases), a contrasting layer under the keyboard.
         * Ground, separator and pressed tones derive from this pair (see [slideGroundColor]).
         */
        val slide: Int = key,
        val onSlide: Int = text,
    ) {
        companion object {
            val EMPTY = Palette(0, 0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    const val ALPHA_FULL = 1.0f
    const val ALPHA_SECONDARY = 0.6f
    const val ALPHA_DISABLED = 0.38f

    const val ALPHA_SLIDING_PREVIEW = 0.7f

    /** M3 pressed state layer: content color over the surface at low opacity. */
    const val ALPHA_STATE_LAYER = 0.12f

    /** Corner radius / inset of the rounded pressed state layer, in dp. */
    private const val STATE_LAYER_RADIUS_DP = 8f
    private const val STATE_LAYER_INSET_DP = 4f

    private lateinit var appContext: Context
    private var currentStyle: Style = Style.MODERN
    private var currentScheme: Scheme = Scheme.AUTO
    private var useSystemColors: Boolean = false
    private val observers = mutableListOf<() -> Unit>()

    /** Compose snapshot state: composables reading it (or the getters below) recompose on change. */
    var palette: Palette by mutableStateOf(Palette.EMPTY)
        private set

    val isInitialized: Boolean
        get() = ::appContext.isInitialized

    val iconColor: Int get() = palette.icon
    val iconColorAlt: Int get() = palette.iconAlt
    val hintColor: Int get() = palette.hint
    val textColor: Int get() = palette.text
    val backgroundColor: Int get() = palette.background
    val keyColor: Int get() = palette.key
    val keyColorAlt: Int get() = palette.keyAlt
    val keyColorPressed: Int get() = palette.keyPressed
    val accentColor: Int get() = palette.accent
    val fretColor: Int get() = palette.fret

    /** Translucent press feedback for the Material theme: text color at state-layer alpha. */
    val pressedStateLayer: Int get() = applyAlpha(palette.text, ALPHA_STATE_LAYER)

    /** The active style's non-color rendering spec; renderers read it instead of branching on style. */
    @JvmStatic
    fun styleSpec(): StyleSpec = when (currentStyle) {
        Style.MATERIAL -> StyleSpec.MATERIAL
        Style.BB10 -> StyleSpec.BB10
        else -> StyleSpec.LEGACY
    }

    val slideKeyColor: Int get() = palette.slide
    val slideTextColor: Int get() = palette.onSlide
    /** The panel behind the slide keys and phrase tiles: 10% on-color over the container. */
    val slideGroundColor: Int get() = ColorUtils.blendARGB(palette.slide, palette.onSlide, 0.10f)
    /** Hairlines between number-pad keys in the flat styles. */
    val slideSeparatorColor: Int get() = ColorUtils.blendARGB(palette.slide, palette.onSlide, 0.18f)
    /** Opaque pressed fill for the flat and sculpted painters. */
    val slidePressedColor: Int get() = ColorUtils.blendARGB(palette.slide, palette.onSlide, 0.14f)
    /** Translucent pressed state layer for the rounded painter and the phrase tiles. */
    val slidePressedStateLayer: Int get() = applyAlpha(palette.onSlide, ALPHA_STATE_LAYER)

    /**
     * Start color of the shadow the keyboard casts on an open panel. On a light container a tint
     * of its on-color reads as shade; on a dark one only black does.
     */
    val slideEdgeShadowColor: Int
        get() = if (ColorUtils.calculateLuminance(palette.slide) > 0.5) {
            applyAlpha(palette.onSlide, 0.18f)
        } else {
            applyAlpha(Color.BLACK, 0.45f)
        }

    val slidingPreviewColor: Int get() = applyAlpha(palette.accent, ALPHA_SLIDING_PREVIEW)

    fun init(
        context: Context,
        style: Style = Style.MODERN,
        scheme: Scheme = Scheme.AUTO,
        systemColors: Boolean = false,
    ) {
        appContext = context.applicationContext
        currentStyle = style
        currentScheme = scheme
        useSystemColors = systemColors
        reload()
        Logger.info(TAG, "KeyboardColorManager initialized: style=$style scheme=$scheme systemColors=$systemColors")
    }

    /**
     * For screens outside the IME (Settings): before the IME's onCreate the palette is
     * [Palette.EMPTY] (all transparent). Reads the theme prefs and re-applies them if already set.
     */
    fun ensureInitialized(context: Context) {
        val prefs = dev.bbkb.ime.core.settings.PrefsManager.getPrefs(context)
        dev.bbkb.ime.core.settings.PrefsManager.migrateThemePrefs(prefs)
        val style = Style.fromPref(
            prefs.getString(dev.bbkb.ime.core.settings.PrefsManager.Keys.KEYBOARD_THEME_STYLE, "modern"))
        val scheme = Scheme.fromPref(
            prefs.getString(dev.bbkb.ime.core.settings.PrefsManager.Keys.KEYBOARD_THEME_MODE, "auto"))
        val systemColors =
            prefs.getBoolean(dev.bbkb.ime.core.settings.PrefsManager.Keys.KEYBOARD_USE_SYSTEM_COLORS, false)
        if (isInitialized) setTheme(style, scheme, systemColors) else init(context, style, scheme, systemColors)
    }

    fun setTheme(style: Style, scheme: Scheme, systemColors: Boolean) {
        if (!isInitialized) {
            Logger.warn(TAG, "Cannot set theme - manager not initialized")
            return
        }
        if (currentStyle != style || currentScheme != scheme || useSystemColors != systemColors) {
            currentStyle = style
            currentScheme = scheme
            useSystemColors = systemColors
            reload()
            Logger.debug(TAG, "Theme changed: style=$style scheme=$scheme systemColors=$systemColors")
        }
    }

    fun getStyle(): Style = currentStyle

    fun getScheme(): Scheme = currentScheme

    fun isSystemColorsEnabled(): Boolean = useSystemColors

    /** Call from the IME's onConfigurationChanged (dark mode, wallpaper). */
    fun onConfigurationChanged() {
        if (!isInitialized) return
        if (currentScheme == Scheme.AUTO || systemColorsActive()) {
            // Most configuration changes leave the palette unchanged; skip the repaint then.
            val next = currentPalette()
            if (next == palette) return
            palette = next
            notifyObservers()
            Logger.debug(TAG, "Configuration changed, colors reloaded")
        }
    }

    private fun reload() {
        palette = currentPalette()
        notifyObservers()
    }

    private fun notifyObservers() {
        // Snapshot: an observer may call removeObserver from inside its own callback.
        observers.toList().forEach { it() }
    }

    private fun isDarkResolved(): Boolean = when (currentScheme) {
        Scheme.LIGHT -> false
        Scheme.DARK -> true
        Scheme.AUTO -> isSystemInDarkMode()
    }

    /** System (wallpaper) colors: opted in, not a fixed-palette style, and Android 12+. */
    private fun systemColorsActive(): Boolean =
        useSystemColors && !currentStyle.fixedPalette &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun currentPalette(): Palette = when {
        currentStyle == Style.CLASSIC -> classicPalette()
        currentStyle == Style.BB10 -> bb10Palette()
        systemColorsActive() -> dynamicPalette(isDarkResolved())
        isDarkResolved() -> darkPalette()
        else -> lightPalette()
    }

    private fun lightPalette() = Palette(
        icon = appContext.getColor(R.color.m3_on_surface_light),
        iconAlt = appContext.getColor(R.color.m3_on_surface_variant_light),
        hint = appContext.getColor(R.color.m3_on_surface_variant_light),
        text = appContext.getColor(R.color.m3_on_surface_light),
        background = appContext.getColor(R.color.m3_surface_light),
        key = appContext.getColor(R.color.m3_surface_container_light),
        keyAlt = appContext.getColor(R.color.m3_surface_container_highest_light),
        keyPressed = appContext.getColor(R.color.m3_secondary_container_light),
        accent = appContext.getColor(R.color.highlight_light),
        slide = appContext.getColor(R.color.m3_primary_container_light),
        onSlide = appContext.getColor(R.color.m3_on_primary_container_light),
    )

    private fun darkPalette() = Palette(
        icon = appContext.getColor(R.color.m3_on_surface_dark),
        iconAlt = appContext.getColor(R.color.m3_on_surface_variant_dark),
        hint = appContext.getColor(R.color.m3_on_surface_variant_dark),
        text = appContext.getColor(R.color.m3_on_surface_dark),
        background = appContext.getColor(R.color.m3_surface_dark),
        key = appContext.getColor(R.color.m3_surface_container_dark),
        keyAlt = appContext.getColor(R.color.m3_surface_container_highest_dark),
        keyPressed = appContext.getColor(R.color.m3_secondary_container_dark),
        accent = appContext.getColor(R.color.highlight_dark),
        slide = appContext.getColor(R.color.m3_primary_container_dark),
        onSlide = appContext.getColor(R.color.m3_on_primary_container_dark),
    )

    private fun classicPalette() = Palette(
        icon = appContext.getColor(R.color.bb_color_icon),
        iconAlt = appContext.getColor(R.color.bb_color_icon_alt),
        hint = appContext.getColor(R.color.bb_color_hint),
        text = appContext.getColor(R.color.bb_color_text),
        background = appContext.getColor(R.color.bb_color_background),
        key = appContext.getColor(R.color.bb_color_key),
        keyAlt = appContext.getColor(R.color.bb_color_key_alt),
        keyPressed = appContext.getColor(R.color.bb_color_key_pressed),
        // Classic uses the pressed blue for emphasis.
        accent = appContext.getColor(R.color.bb_color_key_pressed),
        slide = appContext.getColor(R.color.bb_color_slide_key),
        onSlide = appContext.getColor(R.color.bb_color_slide_text),
    )

    /** BlackBerry 10 look: near-black board, dark sculpted caps, electric-blue accent. */
    private fun bb10Palette() = Palette(
        icon = appContext.getColor(R.color.bb10_color_icon),
        iconAlt = appContext.getColor(R.color.bb10_color_icon_alt),
        hint = appContext.getColor(R.color.bb10_color_hint),
        text = appContext.getColor(R.color.bb10_color_text),
        background = appContext.getColor(R.color.bb10_color_background),
        key = appContext.getColor(R.color.bb10_color_key),
        keyAlt = appContext.getColor(R.color.bb10_color_key_alt),
        keyPressed = appContext.getColor(R.color.bb10_color_key_pressed),
        accent = appContext.getColor(R.color.bb10_color_accent),
        fret = appContext.getColor(R.color.bb10_color_fret),
        slide = appContext.getColor(R.color.bb10_color_slide_key),
        onSlide = appContext.getColor(R.color.bb10_color_text),
    )

    /**
     * Material You colors read straight off `@android:color/system_*`, which needs no theme. Do not
     * use MaterialColors (the app context has no theme, so lookups return the fallback) or
     * DynamicColors (below API 33 it allowlists manufacturers and rejects a BlackBerry KEY2).
     * Shade = (100 - tone) * 10; the dark column matches the values-night-v31 aliases.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun dynamicPalette(dark: Boolean): Palette {
        fun sys(darkShade: Int, lightShade: Int): Int =
            appContext.getColor(if (dark) darkShade else lightShade)

        val icon = sys(android.R.color.system_neutral1_100, android.R.color.system_neutral1_900)
        val hint = sys(android.R.color.system_neutral2_300, android.R.color.system_neutral2_700)
        return Palette(
            icon = icon,
            iconAlt = hint,
            hint = hint,
            text = icon,
            // surface
            background = sys(android.R.color.system_neutral1_900, android.R.color.system_neutral1_10),
            // surfaceContainer
            key = sys(android.R.color.system_neutral1_800, android.R.color.system_neutral1_50),
            // surfaceContainerHighest: the functional keys
            keyAlt = sys(android.R.color.system_neutral1_600, android.R.color.system_neutral1_100),
            // secondaryContainer
            keyPressed = sys(android.R.color.system_accent2_700, android.R.color.system_accent2_100),
            // primary
            accent = sys(android.R.color.system_accent1_200, android.R.color.system_accent1_600),
            // primaryContainer / onPrimaryContainer
            slide = sys(android.R.color.system_accent1_700, android.R.color.system_accent1_100),
            onSlide = sys(android.R.color.system_accent1_100, android.R.color.system_accent1_900),
        )
    }

    /** Tint with the primary icon color. */
    fun tint(drawable: Drawable) = tint(drawable, palette.icon)

    fun tint(drawable: Drawable, color: Int) {
        drawable.setTint(color)
        drawable.setTintMode(PorterDuff.Mode.SRC_IN)
    }

    fun tint(view: ImageView) = tint(view, palette.icon)

    fun tint(view: ImageView, color: Int) {
        view.imageTintList = ColorStateList.valueOf(color)
        view.imageTintMode = PorterDuff.Mode.SRC_IN
    }

    /**
     * Flat fill for backgrounds sized by the view. A GradientDrawable (not setBackgroundColor)
     * avoids overlay issues on keyboard surfaces.
     */
    @JvmStatic
    fun fill(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
    }

    /**
     * Fill with an intrinsic size in px. Needed when the consumer measures it (wrap_content):
     * without setSize() a GradientDrawable reports -1 and the consumer collapses to zero.
     */
    @JvmStatic
    fun fill(color: Int, widthPx: Int, heightPx: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setSize(widthPx, heightPx)
    }

    /**
     * Pressed-state foreground for tappable items: an opaque keyPressed fill, or a rounded inset
     * state layer under modern boards. Build one per view (StateListDrawables must not be shared).
     */
    @JvmStatic
    fun pressedHighlight(): Drawable =
        pressedHighlight(STATE_LAYER_RADIUS_DP, STATE_LAYER_INSET_DP)

    /** Explicit geometry for shaped surfaces (e.g. pill chips: radius = height/2, inset 0). */
    @JvmStatic
    fun pressedHighlight(cornerRadiusDp: Float, insetDp: Float): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedFill(cornerRadiusDp, insetDp))
            addState(IntArray(0), null)
        }

    private fun pressedFill(cornerRadiusDp: Float, insetDp: Float): Drawable {
        if (!styleSpec().modernBoards) return fill(palette.keyPressed)
        val density = appContext.resources.displayMetrics.density
        val rounded = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(pressedStateLayer)
            cornerRadius = cornerRadiusDp * density
        }
        return InsetDrawable(rounded, (insetDp * density).toInt())
    }

    fun applyAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())

    fun getIconColor(alpha: Float = ALPHA_FULL): Int = applyAlpha(palette.icon, alpha)

    fun getHintColor(alpha: Float = ALPHA_FULL): Int = applyAlpha(palette.hint, alpha)

    private fun isSystemInDarkMode(): Boolean {
        val uiMode = appContext.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Pair with [removeObserver] passing the same instance (identity-keyed): a second lambda
     * literal removes nothing and leaks the captured view. Hold it in a field.
     */
    fun addObserver(observer: () -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }
}
