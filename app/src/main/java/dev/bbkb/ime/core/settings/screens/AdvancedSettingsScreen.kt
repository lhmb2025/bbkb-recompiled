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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.keyevent.UserLetterMapRepository
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.backup.LayoutsBundle
import dev.bbkb.ime.core.settings.backup.SettingsBackup
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.util.DebugSettingsUtils
import dev.bbkb.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Advanced Settings Screen (per docs/archived/2026-01_compose-settings-and-dictionaries/2026-01_settings-compose_history.md)
 * Contains:
 * - About (renamed from Credits)
 * - Device compatibility
 * - Debug settings (hidden by default)
 * - Manage data: back up, restore, export and import layouts, clear settings data
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

    // The layouts pair does its file IO on Dispatchers.IO, unlike the two above: a layouts file
    // can carry letter maps of up to a quarter megabyte each, and the export reads every one.
    val scope = rememberCoroutineScope()
    val exportLayoutsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LayoutsBundle.MIME_TYPE)
    ) { uri -> if (uri != null) scope.launch { exportLayouts(context, uri) } }

    val importLayoutsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) scope.launch { confirmAndImportLayouts(context, uri, scope) } }

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

        // The whole-preference-file actions, together: two that move the settings to and from a
        // file, two that do the same for the layouts alone, and one that throws them away.
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
        // The layouts, as a file a person can read and pass on: symbol pages, palette,
        // slideboard, quick phrases, currency and the custom physical layouts. Import also takes a
        // single custom physical layout file. Anchors are LayoutsBundle.ANCHOR_EXPORT/_IMPORT.
        Simple(
            title = R.string.settings_layouts_export_title,
            summary = R.string.settings_layouts_export_summary,
            icon = Icons.Default.Upload.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("layouts_export"),
            onClick = { exportLayoutsLauncher.launch(LayoutsBundle.defaultFileName()) },
        ),
        Simple(
            title = R.string.settings_layouts_import_title,
            summary = R.string.settings_layouts_import_summary,
            icon = Icons.Default.Download.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("layouts_import"),
            // "*/*" for the same reason as Restore: providers often call a .json file
            // application/octet-stream.
            onClick = { importLayoutsLauncher.launch(arrayOf(LayoutsBundle.MIME_TYPE, "*/*")) },
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
    dev.bbkb.ime.core.settings.ui.BbkbDialogs.builder(context)
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

    dev.bbkb.ime.core.settings.ui.BbkbDialogs.builder(context)
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

// ── layouts ──────────────────────────────────────────────────────────────────

private suspend fun exportLayouts(context: Context, uri: Uri) {
    val written = withContext(Dispatchers.IO) {
        try {
            val layouts = LayoutsBundle.capture(context, UserLetterMapRepository(context).list())
            val json = LayoutsBundle.serialize(layouts, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("no output stream for $uri")
            stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
    toast(context, if (written) R.string.settings_layouts_exported else R.string.settings_layouts_export_failed)
}

/**
 * Reads and validates the picked file off the main thread, then asks before writing anything —
 * the same parse-before-confirm order as restore, so a bad file is refused, with the reason, before
 * the user is asked to replace anything with it.
 */
private suspend fun confirmAndImportLayouts(context: Context, uri: Uri, scope: CoroutineScope) {
    val json = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("no input stream for $uri")
            stream.use { LayoutsBundle.readCapped(it) }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }
    if (json == null) {
        toast(context, R.string.settings_layouts_import_read_failed)
        return
    }

    val parsed = withContext(Dispatchers.Default) { LayoutsBundle.parse(json) }.getOrElse { e ->
        dev.bbkb.ime.core.settings.ui.BbkbDialogs.builder(context)
            .setTitle(context.getString(R.string.settings_layouts_import_invalid))
            .setMessage(e.message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return
    }

    val (message, layouts) = when (parsed) {
        is LayoutsBundle.Import.Bundle -> {
            val parts = mutableListOf<String>()
            if (parsed.layouts.hasEditorSections) {
                parts += context.getString(R.string.settings_layouts_import_dialog_message)
            }
            if (parsed.layouts.pkbLetterMaps.isNotEmpty()) {
                parts += context.getString(
                    R.string.settings_layouts_import_dialog_letter_maps,
                    parsed.layouts.pkbLetterMaps.size,
                )
            }
            parts.joinToString("\n\n") to parsed.layouts
        }
        is LayoutsBundle.Import.LetterMap -> context.getString(
            R.string.settings_layouts_import_letter_map_message,
            parsed.map.name,
        ) to LayoutsBundle.Layouts(pkbLetterMaps = listOf(parsed.map))
    }

    dev.bbkb.ime.core.settings.ui.BbkbDialogs.builder(context)
        .setTitle(context.getString(R.string.settings_layouts_import_title))
        .setMessage(message)
        .setPositiveButton(R.string.settings_layouts_import_confirm) { _, _ ->
            scope.launch { importLayouts(context, layouts) }
        }
        .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.cancel() }
        .show()
}

/**
 * Letter maps first, on the IO dispatcher — a failure there leaves the editors untouched — then
 * the editor sections on the main thread, through the repositories that clear the keyboard's
 * cache as an edit does.
 */
private suspend fun importLayouts(context: Context, layouts: LayoutsBundle.Layouts) {
    val mapsSaved = withContext(Dispatchers.IO) {
        try {
            LayoutsBundle.saveLetterMaps(UserLetterMapRepository(context), layouts.pkbLetterMaps)
            true
        } catch (e: IOException) {
            false
        }
    }
    if (!mapsSaved) {
        toast(context, R.string.settings_layouts_import_failed)
        return
    }
    LayoutsBundle.applyEditors(context, layouts)
    toast(context, R.string.settings_layouts_imported)
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}
