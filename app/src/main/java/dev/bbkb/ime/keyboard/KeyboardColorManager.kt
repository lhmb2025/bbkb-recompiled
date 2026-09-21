package dev.bbkb.ime.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import timber.log.Timber

/**
 * Keyboard color management: one immutable [Palette] per theme, swapped atomically.
 *
 * Consumers fall into three tiers, all valid:
 *  - observers (KeyboardView, AuxBarView, ...) repaint when the palette changes
 *  - pull-at-show views (FccView, VoiceInputView, ...) re-read on every inflate/show
 *  - pull-at-draw (drawKeyBackground, KeyDrawParams) read inside the draw path
 *
 * The palette is backed by Compose snapshot state, so composables that read a color
 * recompose automatically on theme change.
 *
 * The theme is two axes plus a color-source toggle:
 *  - [Style]: CLASSIC keeps the original BlackBerry aesthetic (square keys, opaque
 *    press, hairline dividers); MODERN uses the M3 palettes with the original
 *    geometry; MATERIAL adds the M3 styling (rounded state layers, candidate
 *    emphasis, no hairlines); BB10 recreates the BlackBerry 10 virtual keyboard
 *    (sculpted dark caps, electric-blue accent).
 *  - [Scheme]: light/dark/auto. Not applicable to fixed-palette styles
 *    (CLASSIC, BB10).
 *  - useSystemColors: Material You wallpaper colors (Android 12+), available under
 *    MODERN and MATERIAL.
 *
 * Usage:
 * ```
 * KeyboardColorManager.init(context, style, scheme, useSystemColors)  // in IME onCreate()
 * KeyboardColorManager.tint(imageView)        // icon tint
 * view.setBackground(KeyboardColorManager.fill(KeyboardColorManager.keyColor))
 * KeyboardColorManager.setTheme(Style.MATERIAL, Scheme.DARK, false)   // runtime switch
 * ```
 */
object KeyboardColorManager {

    enum class Style(
        /** One fixed palette: no scheme axis, no system (wallpaper) colors. */
        @JvmField val fixedPalette: Boolean,
    ) {
        CLASSIC(true),      // Classic BlackBerry look
        MODERN(false),      // M3 palettes, original geometry
        MATERIAL(false),    // M3 palettes + M3 styling
        BB10(true);         // The BlackBerry 10 virtual keyboard look

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
        LIGHT,      // Force light
        DARK,       // Force dark
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

    /**
     * One theme's colors, as an immutable snapshot. Reads can never observe a
     * half-updated theme, and a reference to a Palette stays internally consistent.
     */
    data class Palette(
        val icon: Int,
        val iconAlt: Int,
        val hint: Int,
        val text: Int,
        val background: Int,
        val key: Int,
        val keyAlt: Int,
        val keyPressed: Int,
        /**
         * The theme's accent: gesture trail and sliding-key preview (the latter at
         * [ALPHA_SLIDING_PREVIEW]). Formerly the gestureTrailColor /
         * slidingKeyInputPreviewColor theme attrs, which only the light/dark styles set --
         * classic and dynamic were stuck with the light teal.
         */
        val accent: Int,
        /**
         * Base tone of the fret bars (KeyboardView.drawFrets derives the metal
         * finish from it). Defaults to the key color; BB10 pins it explicitly so
         * the frets stay bright when the key surface darkens.
         */
        val fret: Int = key,
    ) {
        companion object {
            val EMPTY = Palette(0, 0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    // Alpha values for states
    const val ALPHA_FULL = 1.0f
    const val ALPHA_SECONDARY = 0.6f
    const val ALPHA_DISABLED = 0.38f

    /** The old sliding_key_input_preview_* resources were the trail color at 0xB3 alpha. */
    const val ALPHA_SLIDING_PREVIEW = 0.7f

    /** M3 pressed state layer: content color over the surface at low opacity. */
    const val ALPHA_STATE_LAYER = 0.12f

    /** Corner radius / inset of the rounded pressed state layer, in dp. */
    private const val STATE_LAYER_RADIUS_DP = 8f
    private const val STATE_LAYER_INSET_DP = 4f

    // ============================================================================
    // STATE
    // ============================================================================

    private lateinit var appContext: Context
    private var currentStyle: Style = Style.MODERN
    private var currentScheme: Scheme = Scheme.AUTO
    private var useSystemColors: Boolean = false
    private val observers = mutableListOf<() -> Unit>()

    /**
     * The active palette. Compose snapshot state: composables reading it (directly or
     * through the delegating getters below) recompose when it changes.
     */
    var palette: Palette by mutableStateOf(Palette.EMPTY)
        private set

    val isInitialized: Boolean
        get() = ::appContext.isInitialized

    // Delegating getters — keep the pre-Palette call sites (Java and Kotlin) compiling
    // unchanged: KeyboardColorManager.INSTANCE.getKeyColor() etc.
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

    /**
     * The active style's non-color rendering spec ([StyleSpec]). Renderers read
     * fields off this instead of branching on the style, so it carries everything
     * that used to hang off isMaterialTheme(): key-cap geometry, label styling, the
     * preview bubble, and the modern-boards treatment.
     */
    @JvmStatic
    fun styleSpec(): StyleSpec = when (currentStyle) {
        Style.MATERIAL -> StyleSpec.MATERIAL
        Style.BB10 -> StyleSpec.BB10
        else -> StyleSpec.LEGACY
    }

    /** Sliding-key input preview: the accent, translucent. */
    val slidingPreviewColor: Int get() = applyAlpha(palette.accent, ALPHA_SLIDING_PREVIEW)

    // ============================================================================
    // INITIALIZATION / THEME
    // ============================================================================

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
        Timber.i("KeyboardColorManager initialized: style=$style scheme=$scheme systemColors=$systemColors")
    }

    fun setTheme(style: Style, scheme: Scheme, systemColors: Boolean) {
        if (!isInitialized) {
            Timber.w("Cannot set theme - manager not initialized")
            return
        }
        if (currentStyle != style || currentScheme != scheme || useSystemColors != systemColors) {
            currentStyle = style
            currentScheme = scheme
            useSystemColors = systemColors
            reload()
            Timber.d("Theme changed: style=$style scheme=$scheme systemColors=$systemColors")
        }
    }

    fun getStyle(): Style = currentStyle

    fun getScheme(): Scheme = currentScheme

    fun isSystemColorsEnabled(): Boolean = useSystemColors

    /**
     * Handle configuration changes (e.g., dark mode toggle, wallpaper change).
     * Call this from the IME's onConfigurationChanged().
     */
    fun onConfigurationChanged() {
        if (!isInitialized) return
        // AUTO tracks system dark mode; system colors track the wallpaper palette
        if (currentScheme == Scheme.AUTO || systemColorsActive()) {
            reload()
            Timber.d("Configuration changed, colors reloaded")
        }
    }

    // ============================================================================
    // PALETTE CONSTRUCTION
    // ============================================================================

    private fun reload() {
        palette = currentPalette()
        // Snapshot: an observer is allowed to detach its view (and therefore call
        // removeObserver) from inside its own callback.
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
        // The classic BB blue; also the pressed color, which is what classic used for emphasis.
        accent = appContext.getColor(R.color.bb_color_key_pressed),
    )

    /**
     * The BlackBerry 10 virtual keyboard: near-black board, dark sculpted caps, and
     * the BB10 electric-blue accent (which also colors pressed keys, like the
     * original's press feedback).
     */
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
    )

    /**
     * Material You (system/wallpaper colors), read straight off the framework palette.
     *
     * This used to ask MaterialColors.getColor() for colorSurface / colorPrimary / ... against
     * `appContext.createConfigurationContext(config)`. Neither context carries an app theme --
     * the `<application>` tag declares none, and createConfigurationContext() does not inherit
     * one -- so every lookup missed and returned its fallback argument, making this function
     * byte-identical to [lightPalette] / [darkPalette]. The whole "use system colors" toggle was
     * inert; what wallpaper tint dark-mode users did see came from values-night-v31 aliasing the
     * static m3_* tokens, and `highlight_*` has no such alias, which is why the Material style's
     * accent-pill enter key stayed teal on an otherwise wallpaper-tinted board (beta, 2026-09-15).
     *
     * `@android:color/system_*` has no theme dependency, so it cannot fail the same way. It is
     * also why this does not go through DynamicColors.wrapContextIfAvailable(): below API 33 that
     * gates on exact-match Build.MANUFACTURER / Build.BRAND allowlists, and a KEY2 reports
     * "TCL Technology" / "BlackBerry", neither of which is a key (the list has a bare "tcl").
     * It would have fallen back just as silently, and on the one device this keyboard is for.
     *
     * Tones follow the M3 role mapping in the framework palette's shade numbering
     * (shade = (100 - tone) * 10, so _0 is white and _1000 is black). The dark column repeats the
     * choices values-night-v31 already made, so turning the toggle on does not shift the board
     * for a dark-mode user who was seeing those aliases.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun dynamicPalette(dark: Boolean): Palette {
        /** Pick the [dark] or light member of one framework tonal pair. */
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
            // surfaceContainerHighest -- the functional keys (enter, delete, ?123, shift)
            keyAlt = sys(android.R.color.system_neutral1_600, android.R.color.system_neutral1_100),
            // secondaryContainer, matching what the static palettes use for press feedback
            keyPressed = sys(android.R.color.system_accent2_700, android.R.color.system_accent2_100),
            // primary -- the Material style's enter pill, the gesture trail, the sliding preview
            accent = sys(android.R.color.system_accent1_200, android.R.color.system_accent1_600),
        )
    }

    // ============================================================================
    // COLOR APPLICATION
    // ============================================================================

    /** Tint a drawable with the primary icon color. */
    fun tint(drawable: Drawable) = tint(drawable, palette.icon)

    /** Tint a drawable with a specific color. */
    fun tint(drawable: Drawable, color: Int) {
        drawable.setTint(color)
        drawable.setTintMode(PorterDuff.Mode.SRC_IN)
    }

    /** Tint an ImageView's image with the primary icon color. */
    fun tint(view: ImageView) = tint(view, palette.icon)

    /** Tint an ImageView's image with a specific color. */
    fun tint(view: ImageView, color: Int) {
        view.imageTintList = ColorStateList.valueOf(color)
        view.imageTintMode = PorterDuff.Mode.SRC_IN
    }

    // ============================================================================
    // DRAWABLE FACTORIES
    // ============================================================================

    /**
     * Flat rectangular fill. Use for view backgrounds where the view determines the
     * size. A GradientDrawable (rather than setBackgroundColor) avoids overlay issues
     * on keyboard surfaces.
     */
    @JvmStatic
    fun fill(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
    }

    /**
     * Flat rectangular fill with an explicit intrinsic size, in pixels.
     *
     * Required whenever the consumer measures the drawable — wrap_content ImageViews,
     * anything calling getIntrinsicWidth() — because a GradientDrawable reports -1
     * for both dimensions unless setSize() is called, which silently collapses the
     * consumer to zero width.
     */
    @JvmStatic
    fun fill(color: Int, widthPx: Int, heightPx: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        setSize(widthPx, heightPx)
    }

    /**
     * Pressed-state overlay: transparent at rest, press feedback while pressed.
     * Use as a foreground on tappable items (suggestion words, list rows).
     *
     * Classic/Modern: the original edge-to-edge opaque keyPressed fill. Material:
     * an inset, rounded translucent state layer (M3). Build a fresh instance per view
     * — StateListDrawables must not be shared across views.
     */
    @JvmStatic
    fun pressedHighlight(): Drawable =
        pressedHighlight(STATE_LAYER_RADIUS_DP, STATE_LAYER_INSET_DP)

    /**
     * Variant with explicit geometry, for shaped surfaces whose state layer must
     * match their own outline (e.g. pill chips: radius = height/2, inset 0).
     */
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

    // ============================================================================
    // UTILITIES
    // ============================================================================

    /** Apply an alpha fraction to a color. */
    fun applyAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha * 255).toInt())

    /** Icon color with alpha applied. */
    fun getIconColor(alpha: Float = ALPHA_FULL): Int = applyAlpha(palette.icon, alpha)

    /** Hint color with alpha applied. */
    fun getHintColor(alpha: Float = ALPHA_FULL): Int = applyAlpha(palette.hint, alpha)

    private fun isSystemInDarkMode(): Boolean {
        val uiMode = appContext.resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // ============================================================================
    // OBSERVERS
    // ============================================================================

    /**
     * Register for palette-change notifications.
     *
     * Pair with [removeObserver] on detach, passing **the same instance**: the registry is keyed
     * by identity, so registering a lambda literal and removing another literal with the same
     * body removes nothing and leaks whatever the first one captured (typically a View, i.e. a
     * whole view tree). Hold the lambda in a field — see `KeyboardView.colorObserver`.
     */
    fun addObserver(observer: () -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) {
        observers.remove(observer)
    }
}
