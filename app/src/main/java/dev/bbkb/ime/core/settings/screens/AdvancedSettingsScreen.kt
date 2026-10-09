package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.backup.BackupBundle
import dev.bbkb.ime.core.settings.backup.BackupOperations
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.asRowIcon
import kotlinx.coroutines.launch

/** Search anchors for the Manage data rows; spelled as literals in the screen for the sync test. */
object ManageDataAnchors {
    const val BACKUP_RESTORE = "settings_backup_restore"
    const val RESET = "settings_reset"
}

/**
 * Advanced Settings Screen (per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
 * Contains:
 * - About (renamed from Credits)
 * - Device compatibility
 * - Debug settings (hidden by default)
 * - Manage data: Backup and restore (its own screen), Reset
 */
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDeviceCompatibility: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToBackupRestore: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showReset by remember { mutableStateOf(false) }

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

        // The user's data as a whole: one screen that saves and restores it (file or phone
        // backup), and one action that puts any of it back to defaults.
        Category(R.string.settings_category_manage_data),
        Nav(
            title = R.string.settings_backup_restore_title,
            summary = R.string.settings_backup_restore_summary,
            icon = Icons.Default.SettingsBackupRestore.asRowIcon(),
            // Literal (== ManageDataAnchors.BACKUP_RESTORE): the search-index sync test scans for it.
            modifier = Modifier.settingsSearchAnchor("settings_backup_restore"),
            onClick = { onNavigateToBackupRestore() },
        ),
        Simple(
            title = R.string.settings_reset_title,
            summary = R.string.settings_reset_summary,
            icon = Icons.Default.RestartAlt.asRowIcon(),
            // Literal (== ManageDataAnchors.RESET).
            modifier = Modifier.settingsSearchAnchor("settings_reset"),
            onClick = { showReset = true },
        ),
    ))

    if (showReset) {
        ResetDialog(
            onDismiss = { showReset = false },
            onConfirm = { selection ->
                showReset = false
                scope.launch {
                    BackupOperations.reset(context, selection)
                    toast(context, R.string.reset_done)
                }
            },
        )
    }
}

/**
 * Which of the three kinds of data to put back to defaults. Mirrors the Back up checkboxes, so
 * "Words" here is what "Words" is there: the dictionary, the shortcuts and the learned words.
 */
@Composable
private fun ResetDialog(
    onDismiss: () -> Unit,
    onConfirm: (BackupBundle.Selection) -> Unit,
) {
    var chosen by remember { mutableStateOf(BackupBundle.Selection.NONE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reset_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.reset_dialog_message), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                DialogCheckboxRow(
                    title = stringResource(R.string.backup_part_settings),
                    summary = stringResource(R.string.reset_part_settings_summary),
                    checked = chosen.settings,
                    enabled = true,
                ) { chosen = chosen.copy(settings = it) }
                DialogCheckboxRow(
                    title = stringResource(R.string.backup_part_layouts),
                    summary = stringResource(R.string.reset_part_layouts_summary),
                    checked = chosen.layouts,
                    enabled = true,
                ) { chosen = chosen.copy(layouts = it) }
                DialogCheckboxRow(
                    title = stringResource(R.string.backup_part_words),
                    summary = stringResource(R.string.reset_part_words_summary),
                    checked = chosen.words,
                    enabled = true,
                ) { chosen = chosen.copy(words = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = !chosen.isEmpty) {
                Text(stringResource(R.string.reset_dialog_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}
