package dev.bbkb.ime.core.settings.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

import dev.bbkb.ime.core.settings.PrefsManager

/**
 * Design-system tokens, built once.
 *
 * These three are all-default @Immutable data classes backing staticCompositionLocalOf, whose
 * contract is that the value never changes. Constructing them inline made every recomposition of
 * the theme wrapper hand the locals a fresh instance and turned every LocalSpacing.current reader
 * into a recomposition candidate.
 */
private val SettingsSpacing = Spacing()
private val SettingsShapes = Shapes()
private val SettingsMotion = Motion()

private val DarkColors = darkColorScheme(
    // Material 3 Dark Colors - matches the keyboard theme
    primary = Color(0xFF90CAF9),              // m3_primary_dark
    onPrimary = Color(0xFF1A1C1E),            // m3_on_primary_dark
    primaryContainer = Color(0xFF004A77),     // m3_primary_container_dark
    onPrimaryContainer = Color(0xFFD3E4F7),   // m3_on_primary_container_dark

    secondary = Color(0xFFB0BEC5),            // m3_secondary_dark
    onSecondary = Color(0xFF1A1C1E),          // m3_on_secondary_dark
    secondaryContainer = Color(0xFF3F4759),   // m3_secondary_container_dark
    onSecondaryContainer = Color(0xFFDBE2F9), // m3_on_secondary_container_dark

    surface = Color(0xFF1A1C1E),              // m3_surface_dark
    onSurface = Color(0xFFE2E2E9),            // m3_on_surface_dark
    surfaceVariant = Color(0xFF42474E),       // m3_surface_variant_dark
    onSurfaceVariant = Color(0xFFC4C6D0),     // m3_on_surface_variant_dark

    background = Color(0xFF1A1C1E),           // m3_background_dark
    onBackground = Color(0xFFE2E2E9),         // m3_on_background_dark

    error = Color(0xFFFFB4AB),                // m3_error_dark
    onError = Color(0xFF690005),              // m3_on_error_dark

    // Surface containers: the keyboard's m3_surface_container_* tokens with the steps between
    // them. The grouped settings layout reads these (page, row cards, search field); left unset,
    // the builder fills them with the Material baseline purples.
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    surfaceDim = Color(0xFF1A1C1E),
    surfaceBright = Color(0xFF3A3B41),
    surfaceContainerLowest = Color(0xFF14151A),
    surfaceContainerLow = Color(0xFF1E1F25),  // m3_surface_container_dark
    surfaceContainer = Color(0xFF22242A),
    surfaceContainerHigh = Color(0xFF2B2D33),
    surfaceContainerHighest = Color(0xFF33343A) // m3_surface_container_highest_dark
)

private val LightColors = lightColorScheme(
    // Material 3 Light Colors - matches the keyboard theme
    primary = Color(0xFF0052CC),              // m3_primary_light
    onPrimary = Color(0xFFFFFFFF),            // m3_on_primary_light
    primaryContainer = Color(0xFFD3E4F7),     // m3_primary_container_light
    onPrimaryContainer = Color(0xFF001C3A),   // m3_on_primary_container_light

    secondary = Color(0xFF546E7A),            // m3_secondary_light
    onSecondary = Color(0xFFFFFFFF),          // m3_on_secondary_light
    secondaryContainer = Color(0xFFDBE2F9),   // m3_secondary_container_light
    onSecondaryContainer = Color(0xFF0F1D2A), // m3_on_secondary_container_light

    surface = Color(0xFFF9F9FF),              // m3_surface_light
    onSurface = Color(0xFF1A1C1E),            // m3_on_surface_light
    surfaceVariant = Color(0xFFE1E2EC),       // m3_surface_variant_light
    onSurfaceVariant = Color(0xFF44474F),     // m3_on_surface_variant_light

    background = Color(0xFFF9F9FF),           // m3_background_light
    onBackground = Color(0xFF1A1C1E),         // m3_on_background_light

    error = Color(0xFFBA1A1A),                // m3_error_light
    onError = Color(0xFFFFFFFF),              // m3_on_error_light

    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceDim = Color(0xFFD9DAE0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4FA),
    surfaceContainer = Color(0xFFEDEEF4),     // m3_surface_container_light
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9) // m3_surface_container_highest_light
)

/**
 * The single Material 3 theme for every settings surface.
 *
 * <p>ST-14: `ComposeSettingsActivity.SettingsTheme` and
 * `SpellCheckerComposeActivity.SpellCheckerTheme` were byte-for-byte identical private copies of
 * this (colour tables, the `pref_keyboard_theme_mode` listener, the design-token singletons),
 * while this file — the one with the shared name — held an unused default-colours stub. Both
 * activities now call this.
 *
 * Dark mode follows `pref_keyboard_theme_mode`: "auto" (system), "light" or "dark". The palette
 * follows `pref_keyboard_use_system_colors`: on Android 12+ with it on, the Material You scheme
 * built from the wallpaper; otherwise the fixed blue tables above. The settings screens are
 * always Material — the keyboard's Classic and BB10 styles do not reach them (owner decision,
 * 2026-10-27) — so these two keyboard preferences are the whole theme choice here.
 * Provides the full design system: colours, typography, spacing, shapes, motion.
 */
@Composable
fun BlackBerryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    // Use state to observe preference changes
    var themeMode by remember {
        mutableStateOf(prefs.getString("pref_keyboard_theme_mode", "auto") ?: "auto")
    }
    var useSystemColors by remember {
        mutableStateOf(prefs.getBoolean("pref_keyboard_use_system_colors", false))
    }
    // The Classic and BB10 keyboard styles hide the Color scheme and Use system colors rows, so
    // while one of them is chosen neither preference can be reached: Settings then follows the
    // system's light/dark and keeps the fixed palette, as Gboard does.
    var themeStyle by remember {
        mutableStateOf(prefs.getString(PrefsManager.Keys.KEYBOARD_THEME_STYLE, "modern") ?: "modern")
    }

    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "pref_keyboard_theme_mode" ->
                    themeMode = prefs.getString("pref_keyboard_theme_mode", "auto") ?: "auto"
                "pref_keyboard_use_system_colors" ->
                    useSystemColors = prefs.getBoolean("pref_keyboard_use_system_colors", false)
                PrefsManager.Keys.KEYBOARD_THEME_STYLE ->
                    themeStyle = prefs.getString(PrefsManager.Keys.KEYBOARD_THEME_STYLE, "modern") ?: "modern"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val systemInDarkMode = isSystemInDarkTheme()
    val plainStyle = themeStyle == "classic" || themeStyle == "bb10"
    val isDarkTheme = when {
        plainStyle -> systemInDarkMode
        themeMode == "light" -> false
        themeMode == "dark" -> true
        else -> systemInDarkMode // "auto" or unknown
    }

    // Material You needs Android 12; below that the switch is hidden and the fixed tables stay.
    val dynamic = !plainStyle && useSystemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = remember(dynamic, isDarkTheme, context) {
        when {
            dynamic && isDarkTheme -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            isDarkTheme -> DarkColors
            else -> LightColors
        }
    }

    // The activities draw edge to edge, so the status and navigation bars sit on this theme's
    // own surfaces and their icons must follow the theme the user picked here, not the system's
    // day/night setting. Without this the icons stay light (the manifest theme's default), which
    // was invisible while the status bar was an opaque dark strip and became white-on-white once
    // Android 15 made the bar transparent.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDarkTheme
            controller.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    // The grouped layout's two surfaces: white cards on a tinted page in light mode, lighter cards
    // on the plain surface in dark mode (the Android 16 Settings pairing).
    val settingsColors = remember(colorScheme, isDarkTheme) {
        if (isDarkTheme) {
            SettingsColors(page = colorScheme.surface, card = colorScheme.surfaceContainerHigh)
        } else {
            SettingsColors(page = colorScheme.surfaceContainer, card = colorScheme.surfaceContainerLowest)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SettingsTypography,
        content = {
            // Provide design system tokens to all composables
            CompositionLocalProvider(
                LocalSpacing provides SettingsSpacing,
                LocalShapes provides SettingsShapes,
                LocalMotion provides SettingsMotion,
                LocalSettingsColors provides settingsColors
            ) {
                content()
            }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
