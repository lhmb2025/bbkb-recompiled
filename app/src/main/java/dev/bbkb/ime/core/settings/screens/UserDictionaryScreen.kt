package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.editor.EntryListEditorScreen
import dev.bbkb.ime.core.settings.editor.EntryListSpec
import dev.bbkb.ime.core.settings.ui.PreferenceItem

/**
 * Personal dictionary words, as an [EntryListSpec] — the same editor as `ShortcutsSpec` over the
 * other half of the store.
 *
 * The title is the one field that has to be built per composition rather than held in a constant,
 * because unlike the shortcuts editor this screen reads its title from a string resource.
 */
internal fun userDictionarySpec(title: String) = EntryListSpec(
    title = title,
    noun = "Word",
    plural = "words",
    loadFailurePrefix = "Failed to load dictionary",
    emptyIcon = Icons.Default.Create,
    emptyTitle = "No personal words",
    // Names the button, now that the button says what it does.
    emptyHint = "Tap Add word to add custom words to your dictionary",
    belongsHere = { it.isPersonalWord },
    identity = { "${it.word}_${it.locale}" },
    sortKey = { it.word.lowercase() },
    matches = { entry, query -> entry.word.contains(query, ignoreCase = true) },
    logTag = "UserDictionaryScreen",
)

/**
 * User Dictionary Screen - manages personal words only (no substitutions)
 * Uses BASL pathway for Nuance integration
 * Groups entries by language with sticky headers
 */
@Composable
fun UserDictionaryScreen(
    onNavigateBack: () -> Unit,
    storeFactory: (android.content.Context) -> DictionaryEntryStore = ::DictionaryRepository
) {
    val context = LocalContext.current
    val store = remember { storeFactory(context) }
    val spec = remember(context) {
        userDictionarySpec(context.getString(R.string.settings_user_dictionary_screen_title))
    }

    EntryListEditorScreen(
        spec = spec,
        store = store,
        onNavigateBack = onNavigateBack,
        row = { entry, onClick -> WordListItem(entry = entry, onClick = onClick) },
        dialogs = { host ->
            if (host.adding) {
                PersonalWordDialog(
                    entry = null,
                    // A new word lands in the first locale; an edited one keeps its own.
                    locale = host.locales.firstOrNull() ?: "",
                    onDismiss = host.dismissAdd,
                    onSave = { entry ->
                        host.add(entry)
                        host.dismissAdd()
                    }
                )
            }

            host.editing?.let { selectedEntry ->
                PersonalWordDialog(
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
}

/**
 * One personal word, on the app's own [PreferenceItem] — a Material 3 `ListItem` with a
 * `HorizontalDivider` under it and a secondary-tinted leading glyph before. The glyph is
 * onSurfaceVariant now, like every other leading icon in settings, and the rule is gone: a list of
 * one-line rows does not need a line between each of them to be read as a list.
 */
@Composable
private fun WordListItem(
    entry: DictionaryEntry,
    onClick: () -> Unit
) {
    PreferenceItem(
        title = entry.word,
        icon = Icons.Default.Create,
        onClick = onClick,
    )
}

/**
 * Add/edit dialog for one personal dictionary word.
 *
 * This was `core/settings/ui/UnifiedAddEditDialog`, the third of three add/edit dialogs and the
 * only one that tried to serve two screens: it took an `isSubstitution` flag and grew a shortcut
 * field and a fixed-case row when it was true. Nothing ever passed true — the shortcuts editor has
 * always had its own dialog — so half of it had never run. What is left is the half that did.
 *
 * `fixedCase` has no control here and never did, but it is still carried from the entry being
 * edited to the entry being saved: a word stored with automatic capitalisation disabled must not
 * quietly lose that because it was opened and re-saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalWordDialog(
    entry: DictionaryEntry? = null,
    locale: String,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit,
    onDelete: ((DictionaryEntry) -> Unit)? = null
) {
    var word by remember { mutableStateOf(entry?.word ?: "") }
    val fixedCase = entry?.fixedCase ?: false
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val isEditing = entry != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Word" else "Add Word") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Word field
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    maxLines = 1,
                    supportingText = {
                        Text("Add a custom word")
                    }
                )

                // Locale info
                if (locale.isNotEmpty() && locale != "all") {
                    Text(
                        text = "Language: ${getLocaleDisplayName(locale)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        shortcut = null,
                        locale = locale,
                        fixedCase = fixedCase
                    )
                    onSave(newEntry)
                },
                enabled = word.trim().isNotEmpty()
            ) {
                Text(if (isEditing) "Save" else "Add")
            }
        },
        dismissButton = {
            Row {
                // Delete button (only when editing)
                if (isEditing && onDelete != null) {
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
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
            title = { Text("Delete Word?") },
            text = {
                Text("Delete \"${entry.word}\"?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry)
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
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
private fun getLocaleDisplayName(locale: String): String {
    return try {
        val loc = LocaleUtils.constructLocaleFromString(locale)
        loc?.displayName ?: locale
    } catch (e: Exception) {
        locale
    }
}
