package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.editor.EntryListEditorScreen
import dev.bbkb.ime.core.settings.editor.EntryListSpec
import dev.bbkb.ime.personaldictionary.util.LocaleUtil

/**
 * Personal dictionary words, as an [EntryListSpec] — the same editor as `shortcutsSpec` over the
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
    emptyIcon = Icons.Outlined.Create,
    emptyTitle = "No personal words",
    // Names the button, now that the button says what it does.
    emptyHint = "Tap Add word to add custom words to your dictionary",
    belongsHere = { it.isPersonalWord },
    identity = { "${it.word}_${it.locale}" },
    sortKey = { it.word.lowercase() },
    matches = { entry, query -> entry.word.contains(query, ignoreCase = true) },
    label = { it.word },
    logTag = "UserDictionaryScreen",
)

/**
 * User Dictionary Screen - manages personal words only (no substitutions)
 * Uses BASL pathway for Nuance integration
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
        // The word alone, as Learned words shows its words: a pencil on every row said nothing
        // the row being tappable did not.
        rowContent = { entry -> Text(text = entry.word, style = MaterialTheme.typography.bodyLarge) },
        dialogs = { host ->
            if (host.adding) {
                PersonalWordDialog(
                    entry = null,
                    locales = host.locales,
                    // A new word starts in the first locale ("all languages" when there are
                    // several); the dialog lets it go to another.
                    initialLocale = host.locales.firstOrNull() ?: "",
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
                    locales = host.locales,
                    initialLocale = selectedEntry.locale,
                    onDismiss = host.dismissEdit,
                    onSave = { entry ->
                        host.update(selectedEntry, entry)
                        host.dismissEdit()
                    }
                )
            }
        }
    )
}

/**
 * Add/edit dialog for one personal dictionary word: the word, and which language it belongs to.
 *
 * The language used to be fixed: a new word went into whichever locale came first, with no say,
 * and the dialog only named it. It is a choice now, offered when there is more than one place to
 * put the word. Deleting is no longer here either; it is in the word's sheet, with Undo.
 *
 * `fixedCase` has no control here and never did, but it is still carried from the entry being
 * edited to the entry being saved: a word stored with automatic capitalisation disabled must not
 * quietly lose that because it was opened and re-saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalWordDialog(
    entry: DictionaryEntry?,
    locales: List<String>,
    initialLocale: String,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit,
) {
    val context = LocalContext.current
    var word by remember { mutableStateOf(entry?.word ?: "") }
    var locale by remember { mutableStateOf(initialLocale) }
    var languagesOpen by remember { mutableStateOf(false) }
    val fixedCase = entry?.fixedCase ?: false
    // A word keeps its own language as a choice even when that keyboard has since been removed.
    val choices = remember(locales, initialLocale) { (locales + initialLocale).distinct() }

    val isEditing = entry != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit word" else "Add word") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                if (choices.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = languagesOpen,
                        onExpandedChange = { languagesOpen = it }
                    ) {
                        OutlinedTextField(
                            value = LocaleUtil.getDisplayNameForDictionary(context, locale),
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = { Text("Language") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languagesOpen) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = languagesOpen,
                            onDismissRequest = { languagesOpen = false }
                        ) {
                            choices.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(LocaleUtil.getDisplayNameForDictionary(context, choice)) },
                                    onClick = {
                                        locale = choice
                                        languagesOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        DictionaryEntry(
                            id = entry?.id,
                            word = word.trim(),
                            shortcut = null,
                            locale = locale,
                            fixedCase = fixedCase
                        )
                    )
                },
                enabled = word.trim().isNotEmpty()
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
