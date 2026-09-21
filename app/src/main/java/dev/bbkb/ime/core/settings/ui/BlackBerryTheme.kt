package dev.bbkb.ime.core.settings.ui

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
    onError = Color(0xFF690005)               // m3_on_error_dark
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
    onError = Color(0xFFFFFFFF)               // m3_on_error_light
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
 * Dark mode follows `pref_keyboard_theme_mode`: "auto" (system), "light" or "dark".
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

    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "pref_keyboard_theme_mode") {
                themeMode = prefs.getString("pref_keyboard_theme_mode", "auto") ?: "auto"
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val systemInDarkMode = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> systemInDarkMode // "auto" or unknown
    }

    MaterialTheme(
        colorScheme = if (isDarkTheme) DarkColors else LightColors,
        typography = SettingsTypography,
        content = {
            // Provide design system tokens to all composables
            CompositionLocalProvider(
                LocalSpacing provides SettingsSpacing,
                LocalShapes provides SettingsShapes,
                LocalMotion provides SettingsMotion
            ) {
                content()
            }
        }
    )
}
