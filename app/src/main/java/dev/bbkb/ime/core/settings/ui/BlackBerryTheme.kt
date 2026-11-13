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
 * Design tokens built once: staticCompositionLocalOf assumes the value never changes, and a fresh
 * instance per recomposition would recompose every reader.
 */
private val SettingsSpacing = Spacing()
private val SettingsShapes = Shapes()
private val SettingsMotion = Motion()

private val DarkColors = darkColorScheme(
    // Mirrors the keyboard theme's m3_*_dark colour resources.
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF1A1C1E),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFD3E4F7),

    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF1A1C1E),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),

    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC4C6D0),

    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E9),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),

    // Surface containers are read by the grouped layout; left unset they default to baseline purples.
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    surfaceDim = Color(0xFF1A1C1E),
    surfaceBright = Color(0xFF3A3B41),
    surfaceContainerLowest = Color(0xFF14151A),
    surfaceContainerLow = Color(0xFF1E1F25),
    surfaceContainer = Color(0xFF22242A),
    surfaceContainerHigh = Color(0xFF2B2D33),
    surfaceContainerHighest = Color(0xFF33343A),

    // Snackbar colours (container, text, action): the light scheme turned round. Unset would be
    // the baseline lavender.
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF0052CC)
)

private val LightColors = lightColorScheme(
    // Mirrors the keyboard theme's m3_*_light colour resources.
    primary = Color(0xFF0052CC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF001C3A),

    secondary = Color(0xFF546E7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF0F1D2A),

    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),

    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1C1E),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),

    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceDim = Color(0xFFD9DAE0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F4FA),
    surfaceContainer = Color(0xFFEDEEF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),

    // See DarkColors: the snackbar's colours, here the dark scheme's turned round.
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    inversePrimary = Color(0xFF90CAF9)
)

/**
 * The Material 3 theme for every settings surface, with the design-token locals.
 *
 * Dark mode follows `pref_keyboard_theme_mode` ("auto", "light", "dark"); the palette follows
 * `pref_keyboard_use_system_colors` (Material You on Android 12+, else the fixed tables). The
 * keyboard's Classic and BB10 styles never restyle settings.
 */
@Composable
fun BlackBerryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    var themeMode by remember {
        mutableStateOf(prefs.getString("pref_keyboard_theme_mode", "auto") ?: "auto")
    }
    var useSystemColors by remember {
        mutableStateOf(prefs.getBoolean("pref_keyboard_use_system_colors", false))
    }
    // Classic and BB10 hide the colour rows, so with them Settings follows system light/dark and
    // keeps the fixed palette.
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

    // Edge to edge: bar icons must follow this theme, not system day/night, or they stay light
    // (white on white on Android 15's transparent bars).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDarkTheme
            controller.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

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
