package dev.bbkb.ime.core.settings.screens

import android.app.AlertDialog
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.util.DebugSettingsUtils
import dev.bbkb.ime.R

/**
 * Advanced Settings Screen (per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
 * Contains:
 * - About (renamed from Credits)
 * - Device compatibility
 * - Debug settings (hidden by default)
 * - Clear settings data
 */
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDeviceCompatibility: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {}
) {
    SettingsScreenHost(R.string.settings_advanced_title, onNavigateBack, listOf(
        Nav(
            title = R.string.settings_about_title,
            summary = R.string.settings_about_summary,
            icon = Icons.Default.Info.asRowIcon(),
            onClick = { onNavigateToAbout() },
        ),
        // Device Compatibility (config loader, meta state override, keyboard helper)
        Nav(
            title = R.string.settings_device_compatibility_title,
            summary = R.string.settings_device_compatibility_summary,
            icon = Icons.Default.PhoneAndroid.asRowIcon(),
            onClick = { onNavigateToDeviceCompatibility() },
        ),
        Nav(
            title = R.string.settings_debug_title,
            summary = R.string.settings_debug_summary,
            icon = Icons.Default.BugReport.asRowIcon(),
            onClick = { onNavigateToDebug() },
        ),
        Simple(
            title = R.string.settings_clear_settings_title,
            summary = R.string.settings_clear_settings_summary,
            icon = Icons.Default.Restore.asRowIcon(),
            onClick = ::showClearSettingsDataDialog,
        ),
    ))
}

private fun showClearSettingsDataDialog(context: Context) {
    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.settings_clear_settings_dialog_title))
        .setMessage(context.getString(R.string.settings_clear_settings_dialog_message))
        .setPositiveButton(android.R.string.ok) { _, _ -> DebugSettingsUtils.clearAllSettings(context) }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
        .show()
}
