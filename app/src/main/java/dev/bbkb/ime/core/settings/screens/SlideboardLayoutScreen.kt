package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.PrefStore
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.core.settings.ui.groupBreak

/**
 * Slideboard settings (under On-screen keyboard): enable the slideboard, swap its sides,
 * customize the number pad, and edit the quick phrases.
 */
@Composable
fun SlideboardLayoutScreen(
    onNavigateToCustomizeSlideBoard: () -> Unit,
    onNavigateToQuickPhrases: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    // Refreshed on resume: QuickPhrasesScreen writes these keys, and a summary read once would
    // keep showing the old phrases on the way back.
    var quickPhrases by remember { mutableStateOf(readQuickPhrases(context, prefs)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        quickPhrases = readQuickPhrases(context, prefs)
    }
    val (quickPhrase1, quickPhrase2, quickPhrase3) = quickPhrases

    SettingsScreenHost(R.string.settings_slideboard_settings_title, onNavigateBack, listOf(
        Toggle(
            store = boolPref("slideboard_active", R.bool.config_default_slideboard),
            title = R.string.settings_vkb_slideboard_enable_title,
            icon = drawableIcon(R.drawable.ic_settings_slideboard_enable),
            summary = RowSummary.Res(R.string.settings_vkb_slideboard_enable_summary),
            modifier = Modifier.settingsSearchAnchor("slideboard_active"),
        ),
        groupBreak(),
        Toggle(
            store = swapLocationsStore,
            title = R.string.settings_vkb_slideboard_swap_title,
            icon = drawableIcon(R.drawable.ic_settings_slideboard_swap),
            summary = RowSummary.OnOff(
                R.string.settings_vkb_slideboard_swap_summary_swapped,
                R.string.settings_vkb_slideboard_swap_summary_default,
            ),
            enabled = { it.bool("slideboard_active") },
            modifier = Modifier.settingsSearchAnchor("slideboard_numeric_location"),
        ),
        // The two editors stay open while the slideboard is off: a switch greys out the settings
        // that tune it, never a place where something is set up. The number pad and the phrases
        // can be prepared first, and the link to this editor from Customize symbols always works.
        Nav(
            title = R.string.settings_vkb_customize_numberpad_title,
            summary = R.string.settings_vkb_customize_numberpad_summary,
            icon = drawableIcon(R.drawable.ic_settings_slideboard_numpad),
            onClick = { onNavigateToCustomizeSlideBoard() },
        ),
        Nav(
            title = R.string.settings_vkb_quick_phrases_title,
            summaryText = {
                context.getString(R.string.settings_vkb_quick_phrases_summary, quickPhrase1, quickPhrase2, quickPhrase3)
            },
            icon = drawableIcon(R.drawable.ic_settings_quick_phrases),
            onClick = { onNavigateToQuickPhrases() },
        ),
    ))
}

/**
 * "Swap slideboard locations" is one switch over two stored strings: the number pad's side
 * (`"0"` = left, the switch's on state) and the quick phrases' side, always the other one. The
 * anchor and the state key are the number pad's, as the switch was indexed before.
 */
private val swapLocationsStore: PrefStore<Boolean> = PrefStore(
    "slideboard_numeric_location",
    { prefs, _ -> prefs.getString("slideboard_numeric_location", "1") == "0" },
    { editor, swapped ->
        editor
            .putString("slideboard_numeric_location", if (swapped) "0" else "1")
            .putString("slideboard_quick_phrases_location", if (swapped) "1" else "0")
    },
)
