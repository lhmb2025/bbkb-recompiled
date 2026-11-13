package dev.bbkb.ime.core.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import dev.bbkb.ime.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.ui.LocalInGroup
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceLeadingBadge
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import dev.bbkb.ime.personaldictionary.macro.CustomMacroRepository

/** User-defined %x placeholders for text shortcuts. */
@Composable
fun CustomMacrosScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { CustomMacroRepository(context) }
    
    var macros by remember { mutableStateOf(repository.getAllMacros()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingMacro by remember { mutableStateOf<CustomMacro?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<CustomMacro?>(null) }
    
    fun refreshMacros() {
        macros = repository.getAllMacros()
    }
    
    SettingsScaffold(
        title = context.getString(R.string.settings_custom_macros_title),
        onNavigateBack = onNavigateBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_add), contentDescription = null) },
                text = { Text(stringResource(R.string.settings_macros_add)) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PreferenceInfo(
                text = stringResource(R.string.settings_custom_macros_intro),
                detail = stringResource(R.string.settings_macros_reserved_tags),
                // %D and %d are different macros.
                monospaceDetail = true,
            )

            if (macros.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            ImageVector.vectorResource(R.drawable.ic_settings_m_code),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.settings_macros_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.settings_macros_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                val spacing = LocalSpacing.current
                CompositionLocalProvider(LocalInGroup provides true) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Bottom inset keeps the last row clear of the extended FAB.
                        contentPadding = PaddingValues(
                            start = spacing.groupInset,
                            end = spacing.groupInset,
                            bottom = 88.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.groupGap)
                    ) {
                        val count = macros.size
                        itemsIndexed(macros, key = { _, macro -> macro.tag }) { index, macro ->
                            MacroListItem(
                                macro = macro,
                                edge = groupEdge(index, count),
                                onEdit = { editingMacro = macro },
                                onDelete = { showDeleteConfirm = macro }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        CustomMacroDialog(
            macro = null,
            repository = repository,
            onDismiss = { showAddDialog = false },
            onSave = { newMacro ->
                repository.saveMacro(newMacro)
                refreshMacros()
                showAddDialog = false
            }
        )
    }

    editingMacro?.let { macro ->
        CustomMacroDialog(
            macro = macro,
            repository = repository,
            onDismiss = { editingMacro = null },
            onSave = { updatedMacro ->
                repository.saveMacro(updatedMacro)
                refreshMacros()
                editingMacro = null
            }
        )
    }

    showDeleteConfirm?.let { macro ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.settings_macros_delete_title)) },
            text = { 
                Text(stringResource(R.string.settings_macros_delete_message, macro.fullTag, macro.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.deleteMacro(macro.tag)
                        refreshMacros()
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.clipboard_menu_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * One macro row: the tag in the leading (icon) slot so names align with other settings titles,
 * the overflow menu trailing.
 */
@Composable
private fun MacroListItem(
    macro: CustomMacro,
    edge: GroupEdge,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    PreferenceItem(
        title = macro.name,
        summary = macro.value.replace("\n", "↵"),
        leading = { PreferenceLeadingBadge(macro.fullTag) },
        groupEdge = edge,
        onClick = onEdit,
        trailing = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        ImageVector.vectorResource(R.drawable.ic_settings_m_more_vert),
                        contentDescription = stringResource(R.string.settings_common_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_common_edit)) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_edit), contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.clipboard_menu_delete)) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                ImageVector.vectorResource(R.drawable.ic_settings_m_delete),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomMacroDialog(
    macro: CustomMacro?,
    repository: CustomMacroRepository,
    onDismiss: () -> Unit,
    onSave: (CustomMacro) -> Unit
) {
    val isEditing = macro != null
    
    var tag by remember { mutableStateOf(macro?.tag ?: "") }
    var name by remember { mutableStateOf(macro?.name ?: "") }
    var value by remember { mutableStateOf(macro?.value ?: "") }

    val tagError = when {
        tag.isEmpty() -> null
        tag.length > 1 -> stringResource(R.string.settings_macros_tag_single)
        !tag[0].isLetterOrDigit() -> stringResource(R.string.settings_macros_tag_alnum)
        tag in CustomMacro.RESERVED_TAGS -> stringResource(R.string.settings_macros_tag_reserved)
        !isEditing && !repository.isTagAvailable(tag) -> stringResource(R.string.settings_macros_tag_in_use)
        isEditing && !repository.isTagAvailableForEdit(tag, macro!!.tag) -> stringResource(R.string.settings_macros_tag_in_use)
        else -> null
    }
    
    val isValid = tag.isNotEmpty() && 
                  name.trim().isNotEmpty() && 
                  value.isNotEmpty() && 
                  tagError == null
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.settings_macros_edit else R.string.settings_macros_add)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { 
                        if (it.length <= 1) {
                            tag = it.uppercase()
                        }
                    },
                    label = { Text(stringResource(R.string.settings_macros_tag)) },
                    prefix = { Text("%", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = tagError != null,
                    supportingText = {
                        when {
                            tagError != null -> Text(
                                tagError,
                                color = MaterialTheme.colorScheme.error
                            )
                            tag.isNotEmpty() -> Text(
                                "✓ " + stringResource(R.string.settings_macros_tag_available),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_macros_name)) },
                    placeholder = { Text(stringResource(R.string.settings_macros_name_example)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.settings_macros_value)) },
                    placeholder = { Text("e.g., +1-555-123-4567") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    minLines = 3,
                    maxLines = 5
                )

                if (tag.isNotEmpty() && value.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.settings_common_preview),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "%$tag → ${value.take(50)}${if (value.length > 50) "..." else ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newMacro = CustomMacro(
                        tag = tag,
                        name = name.trim(),
                        value = value,
                        createdAt = macro?.createdAt ?: System.currentTimeMillis()
                    )
                    onSave(newMacro)
                },
                enabled = isValid
            ) {
                Text(stringResource(if (isEditing) R.string.settings_common_save else R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
