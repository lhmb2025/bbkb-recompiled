package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.GridPreference
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

private const val PKB_ENABLED = "enable_symbol_customization_pkb"
private const val VKB_ENABLED = "enable_symbol_customization_vkb"

/**
 * Symbol Customization Screen
 * Configure custom symbol layouts for physical and touch screen keyboards
 *
 * The device draws exactly one of the two symbol-page branches — a physical keyboard reaches the
 * PKB page, a touchscreen-only device the on-screen one — so each branch's three rows carry the
 * matching `visible` gate. `SettingsSearchIndex` mirrors that with PHYSICAL_KEYBOARD /
 * TOUCH_ONLY on the two `*_custom_page_first` entries.
 */
@Composable
fun SymbolCustomizationScreen(
    onNavigateToCustomSymbolPagePKB: () -> Unit,
    onNavigateToCustomSymbolPageVKB: () -> Unit,
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_symbol_customization_title, onNavigateBack, listOf(
        // Default currency — independent of keyboard type, and the one grid picker in settings,
        // so it stays a hand-written block rather than earning a row type of its own.
        Custom { CurrencyPicker() },

        Toggle(
            store = boolPref(PKB_ENABLED, false),
            title = R.string.settings_symbol_enable_customization_title,
            summary = RowSummary.Res(R.string.settings_symbol_enable_customization_summary),
            modifier = Modifier.settingsSearchAnchor("symbol_customization"),
            visible = { it.hasPhysicalKeyboard },
        ),
        Nav(
            title = R.string.settings_symbol_customize_page_title,
            summary = R.string.settings_symbol_customize_page_summary,
            enabled = { it.bool(PKB_ENABLED) },
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

        Toggle(
            store = boolPref(VKB_ENABLED, false),
            title = R.string.settings_symbol_enable_customization_title,
            summary = RowSummary.Res(R.string.settings_symbol_enable_customization_summary),
            modifier = Modifier.settingsSearchAnchor("symbol_customization"),
            visible = { !it.hasPhysicalKeyboard },
        ),
        Nav(
            title = R.string.settings_symbol_customize_page_title,
            summary = R.string.settings_symbol_customize_page_summary,
            enabled = { it.bool(VKB_ENABLED) },
            visible = { !it.hasPhysicalKeyboard },
            onClick = { onNavigateToCustomSymbolPageVKB() },
        ),
        Toggle(
            store = boolPref("vkb_custom_page_first", false),
            title = R.string.settings_symbol_custom_page_first_title,
            summary = RowSummary.Res(R.string.settings_symbol_custom_page_first_summary),
            enabled = { it.bool(VKB_ENABLED) },
            modifier = Modifier.settingsSearchAnchor("vkb_custom_page_first"),
            visible = { !it.hasPhysicalKeyboard },
        ),
    ))
}

@Composable
private fun CurrencyPicker() {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    val currencySymbols = remember {
        context.resources.getStringArray(R.array.symbols_list_currency).toList()
    }
    // "" is the runtime default (SettingsManager.getCurrencySymbol) and means "leave the layout's own
    // currency key alone" — CurrencyKeyHandler, KeyboardSwitcher and the slideboard number pad all
    // skip the replacement on it. So an unset key is not "$" (a £ layout shows £); say so, and
    // highlight no cell.
    var currency by remember { mutableStateOf(prefs.getString("pref_currency_key", "") ?: "") }

    GridPreference(
        title = context.getString(R.string.settings_default_currency_title),
        summary = context.getString(
            R.string.settings_default_currency_summary,
            currency.ifEmpty { "Keyboard default" },
        ),
        entries = currencySymbols,
        value = currency,
        columns = 5,
        modifier = Modifier.settingsSearchAnchor("pref_currency_key"),
        onValueChange = { newValue ->
            currency = newValue
            prefs.edit().putString("pref_currency_key", newValue).apply()
        }
    )
}
