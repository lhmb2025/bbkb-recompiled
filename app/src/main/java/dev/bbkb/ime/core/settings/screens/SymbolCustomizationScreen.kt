package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.groupBreak
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

private const val PKB_ENABLED = "enable_symbol_customization_pkb"
private const val VKB_ENABLED = "enable_symbol_customization_vkb"

/**
 * Custom symbol pages. The on-screen section is always drawn; the physical-keyboard section is
 * added on keypad devices. `SettingsSearchIndex` mirrors that with PHYSICAL_KEYBOARD on the
 * `pkb_custom_page_first` entry only.
 */
@Composable
fun SymbolCustomizationScreen(
    onNavigateToCustomSymbolPagePKB: () -> Unit,
    onNavigateToCustomSymbolPageVKB: () -> Unit,
    onNavigateToCustomizeNumberPad: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_symbol_customization_title, onNavigateBack, listOf(
        Category(R.string.settings_category_on_screen_keyboard),
        Toggle(
            store = boolPref(VKB_ENABLED, false),
            title = R.string.settings_symbol_enable_customization_title,
            summary = RowSummary.Res(R.string.settings_symbol_enable_customization_summary),
            modifier = Modifier.settingsSearchAnchor("symbol_customization"),
        ),
        groupBreak(),
        // Editors stay enabled while their page is off so a page can be filled in first;
        // "Show page first" only tunes a page that is on, so it greys out.
        Nav(
            title = R.string.settings_symbol_customize_page_title,
            summary = R.string.settings_symbol_customize_page_summary,
            onClick = { onNavigateToCustomSymbolPageVKB() },
        ),
        Toggle(
            store = boolPref("vkb_custom_page_first", false),
            title = R.string.settings_symbol_custom_page_first_title,
            summary = RowSummary.Res(R.string.settings_symbol_custom_page_first_summary),
            enabled = { it.bool(VKB_ENABLED) },
            modifier = Modifier.settingsSearchAnchor("vkb_custom_page_first"),
        ),

        Category(R.string.settings_category_physical_keyboard, visible = { it.hasPhysicalKeyboard }),
        Toggle(
            store = boolPref(PKB_ENABLED, false),
            title = R.string.settings_symbol_enable_customization_title,
            summary = RowSummary.Res(R.string.settings_symbol_enable_customization_summary),
            visible = { it.hasPhysicalKeyboard },
        ),
        groupBreak(visible = { it.hasPhysicalKeyboard }),
        Nav(
            title = R.string.settings_symbol_customize_page_title,
            summary = R.string.settings_symbol_customize_page_summary,
            visible = { it.hasPhysicalKeyboard },
            onClick = { onNavigateToCustomSymbolPagePKB() },
        ),
        Toggle(
            store = boolPref("pkb_custom_page_first", false),
            title = R.string.settings_symbol_custom_page_first_title,
            summary = RowSummary.Res(R.string.settings_symbol_custom_page_first_summary),
            enabled = { it.bool(PKB_ENABLED) },
            modifier = Modifier.settingsSearchAnchor("pkb_custom_page_first"),
            visible = { it.hasPhysicalKeyboard },
        ),

        // Straight to the number pad editor; always enabled, even while the slideboard is off.
        Category(R.string.settings_category_slideboard),
        Nav(
            title = R.string.settings_vkb_customize_numberpad_title,
            summary = R.string.settings_vkb_customize_numberpad_summary,
            onClick = { onNavigateToCustomizeNumberPad() },
        ),
    ))
}
