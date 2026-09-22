package dev.bbkb.ime.core.settings.screens

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.backup.SettingsBackup
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.util.DebugSettingsUtils
import dev.bbkb.ime.R
import java.io.IOException

/**
 * Advanced Settings Screen (per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
 * Contains:
 * - About (renamed from Credits)
 * - Device compatibility
 * - Debug settings (hidden by default)
 * - Manage data: back up, restore, clear settings data
 */
@Composable
fun AdvancedSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDeviceCompatibility: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {}
) {
    val context = LocalContext.current

    // Both rows are the launch site for a system document picker; the work happens in the result
    // callbacks below, so nothing here holds state between the two halves of the trip.
    val backUpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackup.MIME_TYPE)
    ) { uri -> if (uri != null) writeBackup(context, uri) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) confirmAndRestore(context, uri) }

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

        // The three whole-preference-file actions, together: two that move the settings to and
        // from a file and one that throws them away.
        Category(R.string.settings_category_manage_data),
        Simple(
            title = R.string.settings_backup_title,
            summary = R.string.settings_backup_summary,
            icon = Icons.Default.Save.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("settings_backup"),
            onClick = { backUpLauncher.launch(SettingsBackup.defaultFileName()) },
        ),
        Simple(
            title = R.string.settings_restore_title,
            summary = R.string.settings_restore_summary,
            icon = Icons.Default.FileOpen.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("settings_restore"),
            // "*/*" as well as the JSON type: plenty of storage providers hand back
            // application/octet-stream for a .json file, and a filter that hides the file the
            // user just saved is worse than one that shows too much.
            onClick = { restoreLauncher.launch(arrayOf(SettingsBackup.MIME_TYPE, "*/*")) },
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

// ── back up ──────────────────────────────────────────────────────────────────

private fun writeBackup(context: Context, uri: Uri) {
    val json = SettingsBackup.serialize(
        prefs = PrefsManager.getPrefs(context),
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
    )
    try {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("no output stream for $uri")
        stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
    } catch (e: IOException) {
        toast(context, R.string.settings_backup_failed)
        return
    } catch (e: SecurityException) {
        toast(context, R.string.settings_backup_failed)
        return
    }
    toast(context, R.string.settings_backed_up)
}

// ── restore ──────────────────────────────────────────────────────────────────

/**
 * Reads and validates the picked document, then asks before writing anything. The file is parsed
 * *before* the dialog so a file that is not a backup is rejected outright rather than after the
 * user has confirmed a replacement that cannot happen.
 */
private fun confirmAndRestore(context: Context, uri: Uri) {
    val json = try {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("no input stream for $uri")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        toast(context, R.string.settings_restore_read_failed)
        return
    } catch (e: SecurityException) {
        toast(context, R.string.settings_restore_read_failed)
        return
    }

    val backup = SettingsBackup.parse(json).getOrElse {
        toast(context, R.string.settings_restore_not_a_backup)
        return
    }

    AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.settings_restore_title))
        .setMessage(context.getString(R.string.settings_restore_dialog_message))
        .setPositiveButton(R.string.settings_restore_dialog_confirm) { _, _ ->
            val restored = SettingsBackup.apply(PrefsManager.getPrefs(context), backup)
            // Same re-sync the clear path does: the subtype list is derived from preferences and
            // is not rebuilt by the preference write itself. Everything else that cares —
            // ThemePrefsListener's theme/height/UIM rebuilds, and every other registered
            // OnSharedPreferenceChangeListener — is driven by the writes in
            // SettingsBackup.apply(), so the running IME picks the new values up without a
            // broadcast of our own.
            SubtypeManager.ensureInitialized(context)
            Toast.makeText(
                context,
                context.getString(R.string.settings_restored_count, restored),
                Toast.LENGTH_SHORT,
            ).show()
        }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
        .show()
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}
