package dev.bbkb.ime.core.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.editor.EntryListEditorScreen
import dev.bbkb.ime.core.settings.editor.EntryListSpec
import dev.bbkb.ime.core.settings.ui.PreferenceItem

/**
 * Word substitutions, as an [EntryListSpec]. Everything here was previously spelled out inline in
 * a screen that was otherwise identical to `UserDictionaryScreen`.
 *
 * The no-spaces rule appears twice on purpose, exactly as it did before: the dialog disables Save
 * and shows the message under the field, and [EntryListSpec.validate] refuses the write as well, so
 * an entry that somehow arrives with a space in its shortcut is still turned away with a snackbar.
 */
internal val ShortcutsSpec = EntryListSpec(
    title = "Text Shortcuts",
    noun = "Shortcut",
    plural = "shortcuts",
    loadFailurePrefix = "Failed to load shortcuts",
    emptyIcon = Icons.Default.Edit,
    emptyTitle = "No text shortcuts",
    // Names the button, now that the button says what it does.
    emptyHint = "Tap Add shortcut to create shortcuts that expand to phrases",
    belongsHere = { it.isSubstitution },
    identity = { "${it.shortcut}_${it.locale}" },
    sortKey = { it.shortcut?.lowercase() },
    matches = { entry, query ->
        entry.shortcut?.contains(query, ignoreCase = true) == true ||
            entry.word.contains(query, ignoreCase = true)
    },
    validate = { entry ->
        if (entry.shortcut?.contains(" ") == true) "Shortcut cannot contain spaces" else null
    },
    logTag = "TextShortcutsScreen",
)

/**
 * Text Shortcuts Screen - manages word substitutions with full BASL integration
 * Supports dynamic macros: %D, %T, %d, %t, %n, %w, %y, %b and custom user macros
 * Groups entries by language with sticky headers
 */
@Composable
fun TextShortcutsScreen(
    onNavigateToCustomMacros: () -> Unit = {},
    onNavigateBack: () -> Unit,
    storeFactory: (android.content.Context) -> DictionaryEntryStore = ::DictionaryRepository
) {
    val context = LocalContext.current
    val store = remember { storeFactory(context) }
    var showMacroHelp by remember { mutableStateOf(false) }

    EntryListEditorScreen(
        spec = ShortcutsSpec,
        store = store,
        onNavigateBack = onNavigateBack,
        row = { entry, onClick -> ShortcutListItem(entry = entry, onClick = onClick) },
        actions = {
            // Custom macros button
            IconButton(onClick = onNavigateToCustomMacros) {
                Icon(Icons.Default.Code, "Custom Macros")
            }
            // Macro help button
            IconButton(onClick = { showMacroHelp = true }) {
                Icon(Icons.Default.Info, "Macro help")
            }
        },
        dialogs = { host ->
            if (host.adding) {
                TextShortcutAddEditDialog(
                    entry = null,
                    // A new shortcut lands in the first locale; an edited one keeps its own.
                    locale = host.locales.firstOrNull() ?: "",
                    onDismiss = host.dismissAdd,
                    onSave = { entry ->
                        host.add(entry)
                        host.dismissAdd()
                    }
                )
            }

            host.editing?.let { selectedEntry ->
                TextShortcutAddEditDialog(
                    entry = selectedEntry,
                    locale = selectedEntry.locale,
                    onDismiss = host.dismissEdit,
                    onSave = { entry ->
                        host.update(selectedEntry, entry)
                        host.dismissEdit()
                    },
                    onDelete = { entry ->
                        host.delete(entry)
                        host.dismissEdit()
                    }
                )
            }
        }
    )

    // Macro help dialog
    if (showMacroHelp) {
        MacroHelpDialog(onDismiss = { showMacroHelp = false })
    }
}

/**
 * One shortcut, on the app's own [PreferenceItem].
 *
 * It was a Material 3 `ListItem` with a `HorizontalDivider` under it, a primary-coloured bold
 * shortcut and the expansion beside it on one line — three type treatments in a row, none of them
 * the one the rest of settings uses. The shortcut is the title and the expansion the summary now,
 * which is the shape of every other two-line row in the app.
 *
 * The two markers were `AssistChip`s, which are a *choice* affordance with a click target: these
 * chips had `onClick = {}` and were never choices. They are trailing labels — the same treatment
 * "Preinstalled" gets on Language packs — and they keep their wording.
 */
@Composable
private fun ShortcutListItem(
    entry: DictionaryEntry,
    onClick: () -> Unit
) {
    val markers = buildList {
        if (entry.word.contains("%")) add("Dynamic")
        if (entry.fixedCase) add("Fixed case")
    }

    PreferenceItem(
        title = entry.shortcut ?: "",
        summary = entry.word,
        icon = Icons.Default.Edit,
        onClick = onClick,
        trailing = if (markers.isEmpty()) null else {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    // Meets the right edge of an icon button on a neighbouring row; see
                    // PreferenceTrailingLabel, which this is the multi-value form of.
                    modifier = Modifier.padding(end = 12.dp)
                ) {
                    markers.forEach { marker ->
                        Text(
                            text = marker,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun MacroHelpDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dynamic Macros") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Use these macros in your shortcut expansions for dynamic content:",
                    style = MaterialTheme.typography.bodyMedium
                )

                MacroItem("%D", "Full date (e.g., Saturday, January 25, 2026)")
                MacroItem("%T", "Full time (12/24hr based on settings)")
                MacroItem("%d", "ISO date (e.g., 2026-01-25)")
                MacroItem("%t", "Short time 24hr (e.g., 13:21)")
                MacroItem("%n", "Newline (line break)")
                MacroItem("%w", "Weekday name (e.g., Monday)")
                MacroItem("%y", "Year (e.g., 2026)")
                MacroItem("%b", "Battery level (e.g., 85%)")

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    "You can also create custom macros using the </> button in the top bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Example: Type \"sig\" to expand to \"Best regards,%n%D\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
private fun MacroItem(macro: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = macro,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(48.dp)
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Enhanced Add/Edit dialog for text shortcuts with macro support
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextShortcutAddEditDialog(
    entry: DictionaryEntry? = null,
    locale: String,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit,
    onDelete: ((DictionaryEntry) -> Unit)? = null
) {
    var word by remember { mutableStateOf(entry?.word ?: "") }
    var shortcut by remember { mutableStateOf(entry?.shortcut ?: "") }
    var fixedCase by remember { mutableStateOf(entry?.fixedCase ?: false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMacroSection by remember { mutableStateOf(false) }

    val isEditing = entry != null
    val hasMacro = word.contains("%")

    // Shortcut validation
    val shortcutError = when {
        shortcut.contains(" ") -> "Shortcut cannot contain spaces"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Shortcut" else "Add Shortcut") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Shortcut field
                OutlinedTextField(
                    value = shortcut,
                    onValueChange = { shortcut = it.replace(" ", "") },
                    label = { Text("Shortcut") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = shortcutError != null,
                    supportingText = {
                        Text(shortcutError ?: "Type this to expand (no spaces)")
                    }
                )

                // Phrase field
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 4,
                    supportingText = {
                        if (hasMacro) {
                            Text(
                                "Contains dynamic content",
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text("The full text that appears")
                        }
                    }
                )

                // Macro insertion section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showMacroSection = !showMacroSection }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Code,
                                    "Macros",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Insert Dynamic Content",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            Icon(
                                if (showMacroSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                "Expand"
                            )
                        }

                        AnimatedVisibility(visible = showMacroSection) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MacroButton("%D", "Date") { word += "%D" }
                                    MacroButton("%T", "Time") { word += "%T" }
                                    MacroButton("%n", "Newline") { word += "%n" }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MacroButton("%d", "ISO Date") { word += "%d" }
                                    MacroButton("%t", "ISO Time") { word += "%t" }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MacroButton("%w", "Weekday") { word += "%w" }
                                    MacroButton("%y", "Year") { word += "%y" }
                                    MacroButton("%b", "Battery") { word += "%b" }
                                }
                            }
                        }
                    }
                }

                // Fixed case checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Fixed case",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Disable automatic capitalization",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Checkbox(
                        checked = fixedCase,
                        onCheckedChange = { fixedCase = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val newEntry = DictionaryEntry(
                        id = entry?.id,
                        word = word.trim(),
                        shortcut = shortcut.trim(),
                        locale = locale,
                        fixedCase = fixedCase
                    )
                    onSave(newEntry)
                },
                enabled = word.trim().isNotEmpty() &&
                          shortcut.trim().isNotEmpty() &&
                          shortcutError == null
            ) {
                Text(if (isEditing) "Save" else "Add")
            }
        },
        dismissButton = {
            Row {
                if (isEditing && onDelete != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Delete",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                    Spacer(Modifier.width(8.dp))
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )

    // Delete confirmation dialog
    if (showDeleteConfirm && entry != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Shortcut?") },
            text = {
                Text("Delete \"${entry.shortcut}\" → \"${entry.word}\"?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry)
                        showDeleteConfirm = false
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MacroButton(
    macro: String,
    label: String,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Text(
                macro,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    )
}
