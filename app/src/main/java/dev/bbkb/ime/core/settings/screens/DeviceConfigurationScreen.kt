package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager
import dev.bbkb.ime.core.device.config.builder.DeviceConfigSummary
import dev.bbkb.ime.core.device.config.builder.DeviceProfileBuilder
import dev.bbkb.ime.core.device.config.builder.DeviceProfileExporter
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceDeleteButton
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import java.io.File

/**
 * The debug flag that reveals the device profile builder.
 *
 * The builder is unfinished, so the row that opens it — and the "THIS DEVICE" subhead above it —
 * is off by default and only appears once this is switched on from the debug screen. Nothing else
 * is gated: the `device_profile_builder` route still resolves, so a deep link (and the builder's
 * own tests) reach the screen either way.
 */
internal const val PREF_SHOW_DEVICE_PROFILE_BUILDER = "pref_show_device_profile_builder"

/**
 * Device Configuration Screen
 * Allows users to select, import, and manage device input configurations.
 * Accessible from Physical Keyboard settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigurationScreen(
    onNavigateToProfileBuilder: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { CustomDeviceConfigManager.getInstance(context) }

    // Read once when the screen first composes, as every other screen here reads its preferences.
    val showProfileBuilder = remember {
        PrefsManager.getPrefs(context).getBoolean(PREF_SHOW_DEVICE_PROFILE_BUILDER, false)
    }

    var configs by remember { mutableStateOf(manager.availableConfigs) }
    var activeId by remember { mutableStateOf(manager.activeConfigId) }
    var deleteTarget by remember { mutableStateOf<CustomDeviceConfigManager.ConfigInfo?>(null) }

    // An imported config is not activated on import. It is read back out of the file it was just
    // copied to and its <match> block is shown first: a config built for another handset parses
    // perfectly and then does nothing at all, and until now the only feedback was "Configuration
    // imported".
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }

    val importFailed = stringResource(R.string.device_config_import_failed)
    val importUnreadable = stringResource(R.string.device_config_import_unreadable)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val importedId = manager.importCustomConfig(uri)
            if (importedId == null) {
                Toast.makeText(context, importFailed, Toast.LENGTH_SHORT).show()
            } else {
                configs = manager.availableConfigs
                val summary = importedFile(context, importedId)?.let { DeviceConfigSummary.of(it) }
                if (summary == null) {
                    Toast.makeText(context, importUnreadable, Toast.LENGTH_SHORT).show()
                } else {
                    pendingImport = PendingImport(importedId, summary)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_device_configuration_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        // The screen's one creative action, named. It was a bare + in the app bar, which gave no
        // hint that it opened a file picker for an XML device config.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("text/xml", "application/xml")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Import configuration") }
            )
        }
    ) { paddingValues ->

        val standardConfigs = configs.filter { it.type == CustomDeviceConfigManager.ConfigType.DEFAULT }
        val preloadedConfigs = configs.filter { it.type == CustomDeviceConfigManager.ConfigType.PRELOADED }
        val customConfigs = configs.filter { it.type == CustomDeviceConfigManager.ConfigType.CUSTOM }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // This device — the builder, before the list of configs someone else made. Unfinished,
            // so it is behind the debug flag; see [PREF_SHOW_DEVICE_PROFILE_BUILDER].
            if (showProfileBuilder) {
                item { PreferenceCategory(title = stringResource(R.string.device_profile_category_this_device)) }
                item {
                    PreferenceItem(
                        title = stringResource(R.string.device_profile_builder_entry_title),
                        summary = stringResource(R.string.device_profile_builder_entry_summary),
                        onClick = onNavigateToProfileBuilder,
                        trailing = {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            // Standard section
            item { PreferenceCategory(title = context.getString(R.string.settings_device_config_category_standard)) }
            items(standardConfigs) { config ->
                ConfigItem(
                    config = config,
                    isSelected = config.id == activeId,
                    onSelect = {
                        manager.activeConfigId = config.id
                        activeId = config.id
                    },
                    onDelete = null
                )
            }

            // Preloaded section
            if (preloadedConfigs.isNotEmpty()) {
                item { PreferenceCategory(title = context.getString(R.string.settings_device_config_category_preloaded)) }
                items(preloadedConfigs) { config ->
                    ConfigItem(
                        config = config,
                        isSelected = config.id == activeId,
                        onSelect = {
                            manager.activeConfigId = config.id
                            activeId = config.id
                        },
                        onDelete = null
                    )
                }
            }

            // Custom section
            if (customConfigs.isNotEmpty()) {
                item { PreferenceCategory(title = context.getString(R.string.settings_device_config_category_custom)) }
                items(customConfigs) { config ->
                    ConfigItem(
                        config = config,
                        isSelected = config.id == activeId,
                        onSelect = {
                            manager.activeConfigId = config.id
                            activeId = config.id
                        },
                        onDelete = { deleteTarget = config }
                    )
                }
            }

            // Keeps the last row clear of the extended FAB (56dp + 16dp margin + 16dp breathing
            // room), as Language packs does.
            item { Spacer(modifier = Modifier.height(88.dp)) }
        }
    }

    // What the imported file claims, before it is allowed to decide anything.
    pendingImport?.let { pending ->
        ImportSummaryDialog(
            summary = pending.summary,
            onActivate = {
                manager.activeConfigId = pending.id
                activeId = manager.activeConfigId
                configs = manager.availableConfigs
                pendingImport = null
            },
            onKeep = {
                configs = manager.availableConfigs
                pendingImport = null
            },
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { config ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Configuration") },
            text = { Text("Delete \"${config.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    manager.deleteCustomConfig(config.id)
                    activeId = manager.activeConfigId
                    configs = manager.availableConfigs
                    deleteTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * One configuration, on [PreferenceItem] rather than a hand-rolled Row with its own 12dp vertical
 * padding and its own 12dp gap.
 *
 * The radio goes in the leading slot — the same 24dp column an icon would take — so the names line
 * up with the rows of the screen this was reached from, and a selected row is no longer a
 * semibold-vs-normal weight change, which was the only thing distinguishing it besides the radio
 * itself and which made the list look like it had two type ramps.
 *
 * `selectable` with [Role.RadioButton] rather than a plain click: a screen reader announces "radio
 * button, selected", and the radio itself takes no click of its own, so the whole row is one
 * target rather than two overlapping ones.
 */
@Composable
private fun ConfigItem(
    config: CustomDeviceConfigManager.ConfigInfo,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    PreferenceItem(
        title = config.name,
        summary = if (config.type == CustomDeviceConfigManager.ConfigType.DEFAULT) {
            "System auto-detection"
        } else null,
        leading = {
            RadioButton(selected = isSelected, onClick = null)
        },
        modifier = Modifier.selectable(
            selected = isSelected,
            onClick = onSelect,
            role = Role.RadioButton,
        ),
        trailing = onDelete?.let { delete ->
            { PreferenceDeleteButton(contentDescription = "Delete ${config.name}", onClick = delete) }
        }
    )
}

/** An imported config waiting for the user to look at its `<match>` block. */
private data class PendingImport(val id: String, val summary: DeviceConfigSummary)

/** The file [CustomDeviceConfigManager.importCustomConfig] just wrote, from the id it returned. */
private fun importedFile(context: Context, configId: String): File? {
    if (!configId.startsWith(DeviceProfileExporter.PREFIX_CUSTOM)) return null
    val name = configId.substring(DeviceProfileExporter.PREFIX_CUSTOM.length)
    val file = File(DeviceProfileExporter.configDir(context), name)
    return if (file.isFile) file else null
}

/**
 * What an imported configuration matches on, and whether that is this phone.
 *
 * The decision offered is deliberately not "import / cancel" — the file is already copied by the
 * time it can be read — but "use it now, or keep it for later". A config for another handset is a
 * perfectly good thing to be carrying around; it just must not be switched on silently.
 */
@VisibleForTesting
@Composable
internal fun ImportSummaryDialog(
    summary: DeviceConfigSummary,
    onActivate: () -> Unit,
    onKeep: () -> Unit,
) {
    val context = LocalContext.current
    val facts = remember { DeviceProfileBuilder.detect(context) }
    val matchesThisPhone = remember(summary) { summary.couldMatch(facts) }

    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(summary.name ?: stringResource(R.string.device_config_import_title)) },
        text = {
            Column {
                summary.deviceName?.let {
                    Text(stringResource(R.string.device_config_import_matches_device, it))
                }
                summary.buildDevice?.let {
                    Text(stringResource(R.string.device_config_import_matches_build, it))
                }
                summary.brand?.let {
                    Text(stringResource(R.string.device_config_import_matches_brand, it))
                }
                if (summary.matchesNothing()) {
                    Text(stringResource(R.string.device_config_import_matches_nothing))
                }
                summary.deviceType?.let {
                    Text(stringResource(R.string.device_config_import_type, it))
                }
                summary.keypadLayout?.let {
                    Text(stringResource(R.string.device_config_import_keypad_layout, it))
                }
                Text(stringResource(R.string.device_config_import_keys, summary.keyCount()))
                if (!matchesThisPhone) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.device_config_import_mismatch),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onActivate) {
                Text(stringResource(R.string.device_config_import_activate))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text(stringResource(R.string.device_config_import_keep))
            }
        },
    )
}
