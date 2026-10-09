package dev.bbkb.ime.core.settings.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.backup.AutoBackup
import dev.bbkb.ime.core.settings.backup.BackupBundle
import dev.bbkb.ime.core.settings.backup.BackupOperations
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.BbkbDialogs
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.SwitchPreference
import dev.bbkb.ime.core.settings.ui.asRowIcon
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Search anchors for the rows on the Backup and restore screen; spelled as literals in the screen for the sync test. */
object BackupAnchors {
    const val SAVE = "backup_save"
    const val RESTORE = "backup_restore_file"
    const val PHONE = "backup_phone"
}

/**
 * Backup and restore (Advanced > Manage data). Three kinds of data — settings, layouts, words
 * ([BackupBundle.Part]) — go into one file the user saves, or come back out of one; and the same
 * bundle rides along with the phone's own backup to the user's Google account.
 *
 * Nothing is applied before it is read in full and validated, and the user picks what to put
 * back from what the file holds ([RestoreDialog]). While a save or restore runs the rows stay
 * put and a progress line shows at the top; a restore of a large dictionary takes a few seconds.
 */
@Composable
fun BackupRestoreScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var includeSettings by rememberSaveable { mutableStateOf(true) }
    var includeLayouts by rememberSaveable { mutableStateOf(true) }
    var includeWords by rememberSaveable { mutableStateOf(true) }
    val selection = BackupBundle.Selection(includeSettings, includeLayouts, includeWords)

    var busy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<BackupBundle.Bundle?>(null) }
    var includedInPhoneBackup by remember { mutableStateOf(AutoBackup.isIncludedInPhoneBackup(context)) }
    val lastPhoneBackup = remember { AutoBackup.lastPhoneBackupAt(context) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupBundle.MIME_TYPE)
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val written = BackupOperations.writeTo(context, uri, selection)
            busy = false
            toast(context, if (written) R.string.backup_saved else R.string.backup_save_failed)
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            when (val result = BackupOperations.readFrom(context, uri)) {
                is BackupOperations.ReadResult.Ok -> pendingRestore = result.bundle
                is BackupOperations.ReadResult.NotABackup -> BbkbDialogs.builder(context)
                    .setTitle(context.getString(R.string.backup_restore_not_a_backup))
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                BackupOperations.ReadResult.Unreadable -> toast(context, R.string.backup_restore_read_failed)
            }
            busy = false
        }
    }

    SettingsScreenHost(R.string.settings_backup_restore_title, onNavigateBack, listOf(
        Custom(visible = { busy }) { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },

        // ── Back up: pick the parts, then write the file ─────────────────────
        Category(R.string.backup_category_back_up),
        Custom {
            PartCheckboxRow(BackupBundle.Part.SETTINGS, includeSettings, enabled = !busy) { includeSettings = it }
        },
        Custom {
            PartCheckboxRow(BackupBundle.Part.LAYOUTS, includeLayouts, enabled = !busy) { includeLayouts = it }
        },
        Custom {
            PartCheckboxRow(BackupBundle.Part.WORDS, includeWords, enabled = !busy) { includeWords = it }
        },
        Simple(
            title = R.string.backup_save_title,
            summary = R.string.backup_save_summary,
            icon = Icons.Default.Save.asRowIcon(),
            // Literal (== BackupAnchors.SAVE): the search-index sync test scans for it.
            modifier = Modifier.settingsSearchAnchor("backup_save"),
            onClick = {
                when {
                    busy -> {}
                    selection.isEmpty -> toast(it, R.string.backup_nothing_selected)
                    else -> saveLauncher.launch(BackupBundle.defaultFileName())
                }
            },
        ),

        // ── Restore: read, show what is in the file, then apply what is chosen ──
        Category(R.string.backup_category_restore),
        Simple(
            title = R.string.backup_restore_file_title,
            summary = R.string.backup_restore_file_summary,
            icon = Icons.Default.FileOpen.asRowIcon(),
            // Literal (== BackupAnchors.RESTORE).
            modifier = Modifier.settingsSearchAnchor("backup_restore_file"),
            // The zip type plus JSON for the single-document backups earlier builds wrote, plus
            // "*/*" because storage providers often call either application/octet-stream.
            onClick = { if (!busy) restoreLauncher.launch(arrayOf(BackupBundle.MIME_TYPE, "application/json", "*/*")) },
        ),

        // ── Phone backup: the same bundle, carried by Android to the Google account ──
        Category(R.string.backup_category_phone),
        Custom {
            SwitchPreference(
                title = stringResource(R.string.backup_phone_include_title),
                summary = stringResource(R.string.backup_phone_include_summary),
                icon = Icons.Default.Backup,
                checked = includedInPhoneBackup,
                enabled = !busy,
                // Literal (== BackupAnchors.PHONE).
                modifier = Modifier.settingsSearchAnchor("backup_phone"),
            ) { on ->
                includedInPhoneBackup = on
                AutoBackup.setIncludedInPhoneBackup(context, on)
            }
        },
        Custom {
            PreferenceItem(
                title = stringResource(R.string.backup_phone_last_title),
                summary = if (lastPhoneBackup > 0L) {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastPhoneBackup))
                } else {
                    stringResource(R.string.backup_phone_last_never)
                },
                icon = Icons.Default.History,
                enabled = includedInPhoneBackup,
            )
        },
        Nav(
            title = R.string.backup_phone_settings_title,
            summary = R.string.backup_phone_settings_summary,
            icon = Icons.Default.Settings.asRowIcon(),
            onClick = ::openPhoneBackupSettings,
        ),
    ))

    pendingRestore?.let { bundle ->
        RestoreDialog(
            bundle = bundle,
            onDismiss = { pendingRestore = null },
            onConfirm = { chosen ->
                pendingRestore = null
                scope.launch {
                    busy = true
                    val report = BackupOperations.restore(context, bundle, chosen)
                    busy = false
                    toast(context, R.string.backup_restored)
                    if (report.learnedSkipped) toast(context, R.string.backup_restored_words_pending)
                }
            },
        )
    }
}

/** One of the three parts as a row with a checkbox: the row and the box toggle together. */
@Composable
private fun PartCheckboxRow(
    part: BackupBundle.Part,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    PreferenceItem(
        title = stringResource(partTitle(part)),
        summary = stringResource(partSummary(part)),
        iconSpaceReserved = false,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = { Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    )
}

internal fun partTitle(part: BackupBundle.Part): Int = when (part) {
    BackupBundle.Part.SETTINGS -> R.string.backup_part_settings
    BackupBundle.Part.LAYOUTS -> R.string.backup_part_layouts
    BackupBundle.Part.WORDS -> R.string.backup_part_words
}

private fun partSummary(part: BackupBundle.Part): Int = when (part) {
    BackupBundle.Part.SETTINGS -> R.string.backup_part_settings_summary
    BackupBundle.Part.LAYOUTS -> R.string.backup_part_layouts_summary
    BackupBundle.Part.WORDS -> R.string.backup_part_words_summary
}

/**
 * What the file holds and what to put back. A part the file lacks is shown unchecked and
 * disabled with "Not in this file", so the user sees what an older or partial backup carries.
 */
@Composable
private fun RestoreDialog(
    bundle: BackupBundle.Bundle,
    onDismiss: () -> Unit,
    onConfirm: (BackupBundle.Selection) -> Unit,
) {
    var chosen by remember(bundle) { mutableStateOf(BackupBundle.Selection.ALL.limitedTo(bundle)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_dialog_title)) },
        text = {
            Column {
                val origin = bundle.origin
                val device = origin.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") }
                    ?.takeIf { it.isNotBlank() }
                val created = origin.created?.let(::formatCreated)
                if (created != null) {
                    Text(
                        stringResource(R.string.backup_restore_dialog_saved, created),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (device != null) {
                    Text(
                        stringResource(R.string.backup_restore_dialog_device, device),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (created != null || device != null) Spacer(Modifier.height(12.dp))
                BackupBundle.Part.entries.forEach { part ->
                    val present = part in bundle.parts()
                    DialogCheckboxRow(
                        title = stringResource(partTitle(part)),
                        summary = if (present) null else stringResource(R.string.backup_restore_not_in_file),
                        checked = present && chosen.has(part),
                        enabled = present,
                    ) { chosen = chosen.with(part, it) }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.backup_restore_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = !chosen.isEmpty) {
                Text(stringResource(R.string.backup_restore_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** A checkbox line for the dialogs here and the Reset dialog on the Advanced screen. */
@Composable
internal fun DialogCheckboxRow(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The manifest's `2026-10-09T14:02:11Z` as a local date and time; the raw text if it is not that shape. */
private fun formatCreated(created: String): String {
    val parsed = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .parse(created)
    } catch (e: java.text.ParseException) {
        null
    } ?: return created
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(parsed)
}

/**
 * The phone's backup page: Google's on phones with Play services (where the account backup
 * lives), else the phone's own settings, where Backup is under System.
 */
private fun openPhoneBackupSettings(context: Context) {
    val google = Intent().setComponent(
        ComponentName("com.google.android.gms", "com.google.android.gms.backup.component.BackupSettingsActivity")
    )
    val candidates = listOf(google, Intent(Settings.ACTION_SETTINGS))
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next
        } catch (e: SecurityException) {
            // a ROM that exports the page but guards it
        }
    }
    toast(context, R.string.backup_phone_settings_unavailable)
}

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}
