package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.R

/**
 * Typing and Input hub screen (primary settings category)
 * Links to: On-screen keyboard, Physical keyboard (conditional), Voice input, Auxiliary input.
 * Voice input is back on this hub (2026-11-11): it spent a month as a one-row group inside
 * Auxiliary input and outgrew it, with four groups and ten settings of its own. Shake gestures
 * went the other way the same day: its four rows are a section of Auxiliary input now.
 */
@Composable
fun CustomizationScreen(
    onNavigateToTouchScreenKeyboard: () -> Unit,
    onNavigateToPhysicalKeyboard: () -> Unit = {},
    onNavigateToVoiceInput: () -> Unit,
    onNavigateToAuxiliaryInput: () -> Unit,
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_preferences_title, onNavigateBack, listOf(
        Nav(
            title = R.string.settings_keyboard_title,
            summary = R.string.settings_keyboard_summary,
            icon = drawableIcon(R.drawable.ic_settings_keyboard_virtual),
            onClick = { onNavigateToTouchScreenKeyboard() },
        ),
        // Physical Keyboard — only shown on PKB devices
        Nav(
            title = R.string.settings_physical_keyboard_title,
            summary = R.string.settings_physical_keyboard_summary,
            icon = drawableIcon(R.drawable.ic_settings_keyboard_physical),
            visible = { it.hasPhysicalKeyboard },
            onClick = { onNavigateToPhysicalKeyboard() },
        ),
        Nav(
            title = R.string.settings_voice_input_title,
            summary = R.string.settings_voice_input_summary,
            icon = drawableIcon(R.drawable.ic_settings_voice_input),
            onClick = { onNavigateToVoiceInput() },
        ),
        Nav(
            title = R.string.settings_auxiliary_input_title,
            summary = R.string.settings_auxiliary_input_summary,
            icon = drawableIcon(R.drawable.ic_settings_auxiliary_input),
            onClick = { onNavigateToAuxiliaryInput() },
        ),
    ))
}
