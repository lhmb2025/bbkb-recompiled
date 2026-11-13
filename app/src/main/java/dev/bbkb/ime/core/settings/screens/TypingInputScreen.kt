package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.R

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
        // Only on devices with a physical keyboard
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
