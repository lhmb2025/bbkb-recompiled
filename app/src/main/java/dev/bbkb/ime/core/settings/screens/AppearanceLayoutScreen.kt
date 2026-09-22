package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.EmojiSymbols
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.R

private const val THEME_STYLE = "pref_keyboard_theme_style"

/**
 * Personalization hub screen (primary settings category)
 * Direct controls: Keyboard theme, Keyboard height.
 * Links to: Symbol customization. (Slideboard settings now live under On-Screen Keyboard.)
 */
@Composable
fun AppearanceLayoutScreen(
    onNavigateToSymbolCustomization: () -> Unit,
    onNavigateBack: () -> Unit
) {
    // Migrate here too: settings can open before the IME process has run. This runs before the
    // host seeds its state map, so the rows below read post-migration values, as they did before.
    val context = LocalContext.current
    remember { PrefsManager.migrateThemePrefs(PrefsManager.getPrefs(context)) }

    SettingsScreenHost(R.string.settings_appearance_layout_title, onNavigateBack, listOf(
        // Keyboard theme (Classic / Modern / Material / BB10). Classic and BB10 are fixed looks;
        // Modern and Material expose the color-scheme selector (and the system-colors toggle on
        // Android 12+) below.
        Choice(
            store = stringPref(THEME_STYLE, "modern"),
            title = R.string.settings_keyboard_theme_title,
            options = ::themeStyles,
            fallbackIndex = 1,
            icon = Icons.Default.Palette.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("pref_keyboard_theme_style"),
        ),
        Choice(
            store = stringPref("pref_keyboard_theme_mode", "auto"),
            title = R.string.settings_color_scheme_title,
            options = ::colorSchemes,
            fallbackIndex = 2,
            icon = Icons.Default.BrightnessMedium.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("pref_keyboard_theme_mode"),
            visible = { it.hasColorScheme() },
        ),
        // Material You wallpaper colors (Android 12+ only)
        Toggle(
            store = boolPref("pref_keyboard_use_system_colors", false),
            title = R.string.settings_use_system_colors_title,
            summary = RowSummary.Res(R.string.settings_use_system_colors_summary),
            icon = Icons.Default.Wallpaper.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("pref_keyboard_use_system_colors"),
            visible = { it.hasColorScheme() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S },
        ),
        Choice(
            store = stringPref("pref_keyboard_height_mode", "regular"),
            title = R.string.settings_keyboard_height_title,
            options = ::keyboardHeights,
            fallbackIndex = 1,
            icon = Icons.Default.Height.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("pref_keyboard_height_mode"),
        ),
        // Custom symbol page + default currency (VKB + PKB)
        Nav(
            title = R.string.settings_symbol_customization_title,
            summary = R.string.settings_symbol_customization_summary,
            icon = Icons.Default.EmojiSymbols.asRowIcon(),
            onClick = { onNavigateToSymbolCustomization() },
        ),
    ))
}

/** Classic and BB10 are fixed looks, so they hide the colour controls. */
private fun dev.bbkb.ime.core.settings.ui.SettingsEnv.hasColorScheme(): Boolean {
    val style = str(THEME_STYLE)
    return style != "classic" && style != "bb10"
}

private fun themeStyles(context: Context) = listOf(
    ChoiceOption("classic", context.getString(R.string.settings_appearance_classic)),
    ChoiceOption("modern", context.getString(R.string.settings_appearance_modern)),
    ChoiceOption("material", context.getString(R.string.settings_appearance_material)),
    ChoiceOption("bb10", context.getString(R.string.settings_appearance_bb10)),
)

private fun colorSchemes(context: Context) = listOf(
    ChoiceOption("light", context.getString(R.string.settings_appearance_light)),
    ChoiceOption("dark", context.getString(R.string.settings_appearance_dark)),
    ChoiceOption("auto", context.getString(R.string.settings_appearance_auto)),
)

private fun keyboardHeights(context: Context) = listOf(
    ChoiceOption("expanded", context.getString(R.string.settings_keyboard_height_expanded)),
    ChoiceOption("regular", context.getString(R.string.settings_keyboard_height_regular)),
    ChoiceOption("compact", context.getString(R.string.settings_keyboard_height_compact)),
    ChoiceOption("extra_compact", context.getString(R.string.settings_keyboard_height_extra_compact)),
)
