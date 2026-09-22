package dev.bbkb.ime.core.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.R

/**
 * Typing and Input hub screen (primary settings category)
 * Links to: On-Screen Keyboard, Physical Keyboard (conditional), Voice Input, Shake gestures
 */
@Composable
fun CustomizationScreen(
    onNavigateToTouchScreenKeyboard: () -> Unit,
    onNavigateToPhysicalKeyboard: () -> Unit = {},
    onNavigateToVoiceInput: () -> Unit,
    onNavigateToShakeGestures: () -> Unit,
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
            icon = Icons.Default.Mic.asRowIcon(),
            onClick = { onNavigateToVoiceInput() },
        ),
        Nav(
            title = R.string.settings_shake_title,
            summary = R.string.settings_shake_summary,
            icon = Icons.Default.Vibration.asRowIcon(),
            onClick = { onNavigateToShakeGestures() },
        ),
    ))
}
