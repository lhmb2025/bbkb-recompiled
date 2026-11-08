package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.core.settings.ui.groupBreak
import dev.bbkb.ime.core.settings.ui.intAsStringPref
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.util.SettingsManager

/**
 * Auto-Correction Settings (Subpage 1 of Correction & Learning)
 * Automatic correction behavior
 */
@Composable
fun AutoCorrectionScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_auto_correction_title, onNavigateBack, listOf(
        // The switch for auto-correction as a whole (SettingsValues.isAutoCorrectEnabled); the
        // two strength choices below only matter while it is on. With it and "Show suggestions"
        // both off, no suggestion lookup runs at all.
        Toggle(
            store = boolPref(SettingsManager.PREF_AUTO_CORRECTION_ENABLED, true),
            title = R.string.settings_autocorrect_enable_title,
            summary = RowSummary.Res(R.string.settings_autocorrect_enable_summary),
            icon = drawableIcon(R.drawable.ic_settings_autocorrect),
            modifier = Modifier.settingsSearchAnchor("auto_correction_enabled"),
        ),
        groupBreak(),
        // PKB auto-correction mode — only on devices with a physical keyboard.
        Choice(
            store = intAsStringPref("auto_correction_mode_PKB", 1),
            title = R.string.settings_autocorrect_pkb_title,
            options = ::autoCorrectionModes,
            fallbackIndex = 1,
            icon = drawableIcon(R.drawable.ic_settings_keyboard_physical_correction),
            enabled = { it.bool(SettingsManager.PREF_AUTO_CORRECTION_ENABLED) },
            modifier = Modifier.settingsSearchAnchor("auto_correction_mode_PKB"),
            visible = { it.hasPhysicalKeyboard },
        ),
        Choice(
            store = intAsStringPref("auto_correction_mode_VKB", 2),
            title = R.string.settings_autocorrect_vkb_title,
            options = ::autoCorrectionModes,
            fallbackIndex = 2,
            icon = drawableIcon(R.drawable.ic_settings_keyboard_virtual_correction),
            enabled = { it.bool(SettingsManager.PREF_AUTO_CORRECTION_ENABLED) },
            modifier = Modifier.settingsSearchAnchor("auto_correction_mode_VKB"),
        ),
        // Capitals and punctuation work whether auto-correction is on or not.
        groupBreak(),
        Toggle(
            store = boolPref("auto_cap", true),
            title = R.string.settings_autocorrect_auto_cap_title,
            summary = RowSummary.Res(R.string.settings_autocorrect_auto_cap_summary),
            icon = drawableIcon(R.drawable.ic_settings_auto_capitalization),
            modifier = Modifier.settingsSearchAnchor("auto_cap"),
        ),
        Toggle(
            store = boolPref("pref_key_use_double_space_period", true),
            title = R.string.settings_autocorrect_double_space_title,
            summary = RowSummary.Res(R.string.settings_autocorrect_double_space_summary),
            icon = drawableIcon(R.drawable.ic_settings_double_space_period),
            modifier = Modifier.settingsSearchAnchor("pref_key_use_double_space_period"),
        ),
    ))
}

/** The three modes both auto-correction rows offer. Values are the legacy int-as-string codes. */
private fun autoCorrectionModes(context: android.content.Context) = listOf(
    ChoiceOption("0", context.getString(R.string.settings_autocorrect_mode_off)),
    ChoiceOption("1", context.getString(R.string.settings_autocorrect_mode_modest)),
    ChoiceOption("2", context.getString(R.string.settings_autocorrect_mode_aggressive)),
)
