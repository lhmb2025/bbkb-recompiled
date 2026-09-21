package dev.bbkb.ime.core.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import dev.bbkb.ime.R
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceLeadingBadge
import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import dev.bbkb.ime.personaldictionary.macro.CustomMacroRepository

/**
 * Screen for managing user-defined custom macros.
 * Allows users to create their own %x placeholders for use in text shortcuts.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_custom_macros_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        // One naming of one action. The screen used to offer adding a macro twice — a bare + in
        // the app bar and an unlabelled round FAB — and neither said what it would add.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add macro") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // What the screen is for. Was a tinted card with an Info glyph — decoration around a
            // sentence, and a tint that made an explanation look like a warning.
            PreferenceInfo(
                text = "Define your own %x placeholders for use in text shortcuts.",
                detail = "Reserved tags: %D %T %d %t %n %w %y %b",
                // The characters ARE the content here: %D and %d are different macros.
                monospaceDetail = true,
            )

            if (macros.isEmpty()) {
                // Empty state
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
                            Icons.Default.Code,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No custom macros",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            // Names the button it means, now that the button says what it does.
                            "Tap Add macro to create your first one",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Rows bring their own 16dp gutter (PreferenceItem); the bottom inset keeps the
                    // last one clear of the extended FAB.
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(macros, key = { it.tag }) { macro ->
                        MacroListItem(
                            macro = macro,
                            onEdit = { editingMacro = macro },
                            onDelete = { showDeleteConfirm = macro }
                        )
                    }
                }
            }
        }
    }
    
    // Add dialog
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
    
    // Edit dialog
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
    
    // Delete confirmation
    showDeleteConfirm?.let { macro ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Macro?") },
            text = { 
                Text("Are you sure you want to delete the macro %${macro.tag} (${macro.name})?") 
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
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * One macro, on the app's own [PreferenceItem] rather than an elevated card.
 *
 * A card per row turned a list of six macros into six floating objects with a gutter between them,
 * at a left edge 16dp further in than every other settings list. The tag goes in the leading slot,
 * where the icon of an icon-bearing row would be, so the name lines up with the titles on the
 * screen the user navigated here from; the overflow menu takes the trailing slot.
 */
@Composable
private fun MacroListItem(
    macro: CustomMacro,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    PreferenceItem(
        title = macro.name,
        summary = macro.value.replace("\n", "↵"),
        leading = { PreferenceLeadingBadge(macro.fullTag) },
        onClick = onEdit,
        trailing = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
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
    
    // Validation
    val tagError = when {
        tag.isEmpty() -> null
        tag.length > 1 -> "Tag must be a single character"
        !tag[0].isLetterOrDigit() -> "Tag must be a letter or number"
        tag in CustomMacro.RESERVED_TAGS -> "This tag is reserved for built-in macros"
        !isEditing && !repository.isTagAvailable(tag) -> "This tag is already in use"
        isEditing && !repository.isTagAvailableForEdit(tag, macro!!.tag) -> "This tag is already in use"
        else -> null
    }
    
    val isValid = tag.isNotEmpty() && 
                  name.trim().isNotEmpty() && 
                  value.isNotEmpty() && 
                  tagError == null
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Macro" else "Add Macro") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Tag field
                OutlinedTextField(
                    value = tag,
                    onValueChange = { 
                        if (it.length <= 1) {
                            tag = it.uppercase()
                        }
                    },
                    label = { Text("Tag") },
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
                                "✓ Tag is available",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                
                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g., Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                // Value field
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    placeholder = { Text("e.g., +1-555-123-4567") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    minLines = 3,
                    maxLines = 5
                )
                
                // Preview
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
                                "Preview",
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
                Text(if (isEditing) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
