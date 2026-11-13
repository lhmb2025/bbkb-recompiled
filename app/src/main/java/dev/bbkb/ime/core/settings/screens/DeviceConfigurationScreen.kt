package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager
import dev.bbkb.ime.core.device.config.builder.DeviceConfigSummary
import dev.bbkb.ime.core.device.config.builder.DeviceProfileBuilder
import dev.bbkb.ime.core.device.config.builder.DeviceProfileExporter
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.ui.LocalInGroup
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceDeleteButton
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import java.io.File

/**
 * Debug flag that shows the (unfinished) device profile builder row and its subhead. Only the row
 * is gated: the `device_profile_builder` route still resolves for deep links and tests.
 */
internal const val PREF_SHOW_DEVICE_PROFILE_BUILDER = "pref_show_device_profile_builder"

/** Select, import and manage device input configurations. */
@Composable
fun DeviceConfigurationScreen(
    onNavigateToProfileBuilder: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember { CustomDeviceConfigManager.getInstance(context) }

    val showProfileBuilder = remember {
        PrefsManager.getPrefs(context).getBoolean(PREF_SHOW_DEVICE_PROFILE_BUILDER, false)
    }

    var configs by remember { mutableStateOf(manager.availableConfigs) }
    var activeId by remember { mutableStateOf(manager.activeConfigId) }
    var deleteTarget by remember { mutableStateOf<CustomDeviceConfigManager.ConfigInfo?>(null) }

    // An imported config is not activated: its <match> block is shown first, since a config for
    // another handset parses fine and then does nothing.
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

    SettingsScaffold(
        title = context.getString(R.string.settings_device_configuration_title),
        onNavigateBack = onNavigateBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("text/xml", "application/xml")) },
                icon = { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_add), contentDescription = null) },
                text = { Text(stringResource(R.string.device_config_import_button)) }
            )
        }
    ) { paddingValues ->

        val standardConfigs = configs.filter { it.type == CustomDeviceConfigManager.ConfigType.DEFAULT }
        val preloadedConfigs = configs.filter { it.type == CustomDeviceConfigManager.ConfigType.PRELOADED }
        val customConfigs = configs.filter { it.type == CustomDeviceConfigManager.ConfigType.CUSTOM }
        val spacing = LocalSpacing.current

        // Each section is one group; rows paint themselves as cards with the group inset so the labels
        // keep page-level alignment.
        CompositionLocalProvider(LocalInGroup provides true) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(spacing.groupGap)
            ) {
                // The builder, behind [PREF_SHOW_DEVICE_PROFILE_BUILDER] while unfinished.
                if (showProfileBuilder) {
                    item { PreferenceCategory(title = stringResource(R.string.device_profile_category_this_device)) }
                    item {
                        Box(modifier = Modifier.padding(horizontal = spacing.groupInset)) {
                            PreferenceItem(
                                title = stringResource(R.string.device_profile_builder_entry_title),
                                summary = stringResource(R.string.device_profile_builder_entry_summary),
                                groupEdge = groupEdge(0, 1),
                                onClick = onNavigateToProfileBuilder,
                                trailing = {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_chevron_right),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }

                item { PreferenceCategory(title = context.getString(R.string.settings_device_config_category_standard)) }
                itemsIndexed(standardConfigs) { index, config ->
                    ConfigItem(
                        config = config,
                        isSelected = config.id == activeId,
                        edge = groupEdge(index, standardConfigs.size),
                        onSelect = { activeId = select(manager, config.id) },
                        onDelete = null
                    )
                }

                if (preloadedConfigs.isNotEmpty()) {
                    item { PreferenceCategory(title = context.getString(R.string.settings_device_config_category_preloaded)) }
                    itemsIndexed(preloadedConfigs) { index, config ->
                        ConfigItem(
                            config = config,
                            isSelected = config.id == activeId,
                            edge = groupEdge(index, preloadedConfigs.size),
                            onSelect = { activeId = select(manager, config.id) },
                            onDelete = null
                        )
                    }
                }

                if (customConfigs.isNotEmpty()) {
                    item { PreferenceCategory(title = context.getString(R.string.settings_device_config_category_custom)) }
                    itemsIndexed(customConfigs) { index, config ->
                        ConfigItem(
                            config = config,
                            isSelected = config.id == activeId,
                            edge = groupEdge(index, customConfigs.size),
                            onSelect = { activeId = select(manager, config.id) },
                            onDelete = { deleteTarget = config }
                        )
                    }
                }

                // Keeps the last row clear of the extended FAB (56dp + 16dp margin + 16dp).
                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
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

    deleteTarget?.let { config ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.device_config_delete_title)) },
            text = { Text(stringResource(R.string.settings_pkb_layouts_delete_message, config.name)) },
            confirmButton = {
                TextButton(onClick = {
                    manager.deleteCustomConfig(config.id)
                    activeId = manager.activeConfigId
                    configs = manager.availableConfigs
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.clipboard_menu_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * One configuration on [PreferenceItem] with the radio in the leading slot. `selectable` with
 * [Role.RadioButton] so screen readers announce it and the whole row is one target.
 */
@Composable
private fun ConfigItem(
    config: CustomDeviceConfigManager.ConfigInfo,
    isSelected: Boolean,
    edge: GroupEdge,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Box(modifier = Modifier.padding(horizontal = LocalSpacing.current.groupInset)) {
        PreferenceItem(
            // No summary: the row detection picks is the one shown selected.
            title = config.name,
            leading = {
                RadioButton(selected = isSelected, onClick = null)
            },
            modifier = Modifier.selectable(
                selected = isSelected,
                onClick = onSelect,
                role = Role.RadioButton,
            ),
            groupEdge = edge,
            trailing = onDelete?.let { delete ->
                { PreferenceDeleteButton(contentDescription = stringResource(R.string.settings_pkb_layouts_delete, config.name), onClick = delete) }
            }
        )
    }
}

/**
 * Activates [id] and returns the config now in effect, which the list shows selected. For "Detect
 * automatically" that is the profile detection picked.
 */
private fun select(manager: CustomDeviceConfigManager, id: String): String {
    manager.activeConfigId = id
    return manager.activeConfigId
}

private data class PendingImport(val id: String, val summary: DeviceConfigSummary)

/** The file [CustomDeviceConfigManager.importCustomConfig] just wrote, from the id it returned. */
private fun importedFile(context: Context, configId: String): File? {
    if (!configId.startsWith(DeviceProfileExporter.PREFIX_CUSTOM)) return null
    val name = configId.substring(DeviceProfileExporter.PREFIX_CUSTOM.length)
    val file = File(DeviceProfileExporter.configDir(context), name)
    return if (file.isFile) file else null
}

/**
 * What an imported config matches on and whether that is this phone. The file is already copied,
 * so the choice is "use it now or keep it". Activate applies it whatever it matches (a hand-picked
 * profile always applies), so a mismatch line warns about what Activate will do.
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
                summary.basedOn?.takeIf { it.isNotBlank() }?.let { base ->
                    val manager = CustomDeviceConfigManager.getInstance(context)
                    Text(stringResource(R.string.device_config_import_based_on, manager.builtInProfileName(base) ?: base))
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
