package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.locale.multilanguage.LocaleItem
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageConfig
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageRepository
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import java.util.Locale

/**
 * Multi-Language Keyboard Wizard Screen (Compose)
 * Add or edit multi-language keyboard configurations
 * Supports selecting multiple supporting languages via a scrollable picker
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MultiLanguageWizardScreen(
    mode: WizardMode,
    existingKeyboard: MultiLanguageConfig? = null,
    onSaveSuccess: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    
    // Initialize keyboard manager
    val keyboardManager = remember {
        MultiLanguageRepository.getInstance(context)
    }
    
    // Available languages
    val availableLanguages = remember {
        keyboardManager.getAvailableLocales()
    }
    
    // State for selected languages
    var primaryLanguage by remember {
        mutableStateOf(
            existingKeyboard?.getPrimaryLocale() ?: LocaleItem(Locale.getDefault().toString())
        )
    }
    
    // Support MULTIPLE supporting languages
    var supportingLanguages by remember {
        mutableStateOf<Set<LocaleItem>>(
            existingKeyboard?.getSupportingLocales()?.toSet() ?: emptySet()
        )
    }
    
    // Filter supporting languages (exclude primary)
    val filteredSupportingLanguages = remember(primaryLanguage, availableLanguages) {
        availableLanguages.filter { it.compareTo(primaryLanguage) != 0 }
    }
    
    // Dialogs
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showLanguagePickerDialog by remember { mutableStateOf(false) }
    
    // Track if user made changes
    val hasChanges = remember(supportingLanguages) {
        supportingLanguages.isNotEmpty()
    }
    
    // Handle back press with unsaved changes
    val handleBack = {
        if (mode == WizardMode.ADD && hasChanges) {
            showDiscardDialog = true
        } else {
            onNavigateBack()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.multi_language_input_subtype_wizard_title))
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    if (mode == WizardMode.EDIT) {
                        // Delete button in edit mode
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.user_dict_settings_delete)
                            )
                        }
                    } else {
                        // Save button in add mode
                        IconButton(
                            onClick = {
                                saveConfiguration(
                                    primaryLanguage = primaryLanguage,
                                    supportingLanguages = supportingLanguages,
                                    keyboardManager = keyboardManager,
                                    context = context,
                                    onSuccess = onSaveSuccess
                                )
                            },
                            enabled = supportingLanguages.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.save)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Instructions
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.multi_language_input_setup_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.contentPadding)
                )
            }
            
            Spacer(modifier = Modifier.height(spacing.large))
            
            // Primary Language Section
            PreferenceCategory(title = stringResource(R.string.multi_language_input_primary_language_title))
            
            Text(
                text = stringResource(R.string.multi_language_input_setup_primary_language_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.listItemPadding, vertical = spacing.small)
            )
            
            LanguageDropdown(
                label = stringResource(R.string.multi_language_input_primary_language_title),
                selectedLanguage = primaryLanguage,
                availableLanguages = availableLanguages,
                onLanguageSelected = { 
                    primaryLanguage = it
                    // Remove from supporting if it was selected there
                    supportingLanguages = supportingLanguages.filter { lang -> lang.compareTo(it) != 0 }.toSet()
                },
                enabled = mode == WizardMode.ADD,
                modifier = Modifier.padding(horizontal = spacing.listItemPadding)
            )
            
            Spacer(modifier = Modifier.height(spacing.extraLarge))
            
            // Supporting Languages Section
            PreferenceCategory(title = stringResource(R.string.multi_language_input_supportive_language_title))
            
            Text(
                text = stringResource(R.string.multi_language_input_setup_supportive_language_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.listItemPadding, vertical = spacing.small)
            )
            
            if (filteredSupportingLanguages.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_dictionaries_available),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(spacing.listItemPadding)
                )
            } else {
                // Show selected languages as chips
                if (supportingLanguages.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.listItemPadding),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        supportingLanguages.sortedBy { it.toString() }.forEach { language ->
                            InputChip(
                                selected = true,
                                onClick = { 
                                    if (mode == WizardMode.ADD) {
                                        supportingLanguages = supportingLanguages - language
                                    }
                                },
                                label = { Text(language.toString()) },
                                trailingIcon = if (mode == WizardMode.ADD) {
                                    {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.user_dict_settings_delete),
                                            modifier = Modifier.clickable {
                                                supportingLanguages = supportingLanguages - language
                                            }
                                        )
                                    }
                                } else null,
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(spacing.medium))
                }
                
                // Add language button (opens picker)
                if (mode == WizardMode.ADD) {
                    // A plain row, like every other tappable row in settings. It was a Material 3
                    // ListItem in its own Surface with a rule under it, and both its glyph and its
                    // label in primary — a link-coloured row in the middle of a form.
                    PreferenceItem(
                        title = if (supportingLanguages.isEmpty()) {
                            stringResource(R.string.multi_language_input_subtype_select_language)
                        } else {
                            stringResource(R.string.user_dict_settings_add_menu_title)
                        },
                        icon = Icons.Default.Language,
                        onClick = { showLanguagePickerDialog = true },
                    )
                }
                
                // Validation message
                if (mode == WizardMode.ADD && supportingLanguages.isEmpty()) {
                    Text(
                        text = stringResource(R.string.multi_language_input_invalid_selection_toast),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(spacing.listItemPadding)
                    )
                }
            }
        }
    }
    
    // Multi-select language picker dialog
    if (showLanguagePickerDialog) {
        MultiSelectLanguagePickerDialog(
            title = stringResource(R.string.multi_language_input_support_language_spinner_dialog_title),
            availableLanguages = filteredSupportingLanguages,
            selectedLanguages = supportingLanguages,
            onSelectionChanged = { supportingLanguages = it },
            onDismiss = { showLanguagePickerDialog = false }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.user_dict_settings_delete)) },
            text = { Text(stringResource(R.string.multi_language_input_activate_subtype_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        existingKeyboard?.let {
                            keyboardManager.removeConfig(it)
                            updateSubtypes(context, keyboardManager)
                            Toast.makeText(
                                context,
                                context.getString(R.string.user_dict_settings_delete),
                                Toast.LENGTH_SHORT
                            ).show()
                            onSaveSuccess()
                        }
                        showDeleteDialog = false
                    }
                ) {
                    Text(stringResource(R.string.user_dict_settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Discard changes dialog
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            // One string, shown once: this dialog used to render "Discard your changes?" as both
            // its title and its body.
            title = { Text(stringResource(R.string.discard_event_changes)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveConfiguration(
                            primaryLanguage = primaryLanguage,
                            supportingLanguages = supportingLanguages,
                            keyboardManager = keyboardManager,
                            context = context,
                            onSuccess = onSaveSuccess
                        )
                        showDiscardDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            showDiscardDialog = false
                            onNavigateBack()
                        }
                    ) {
                        Text(stringResource(R.string.discard))
                    }
                }
            }
        )
    }
}

/**
 * Multi-select language picker dialog with scrollable checkbox list
 * Follows Material 3 design guidelines for selection dialogs
 */
@Composable
private fun MultiSelectLanguagePickerDialog(
    title: String,
    availableLanguages: List<LocaleItem>,
    selectedLanguages: Set<LocaleItem>,
    onSelectionChanged: (Set<LocaleItem>) -> Unit,
    onDismiss: () -> Unit
) {
    // Local state for tracking selections within dialog
    var localSelection by remember(selectedLanguages) { 
        mutableStateOf(selectedLanguages) 
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                Text(
                    text = "${localSelection.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                // Scrollable list of languages with checkboxes
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(
                        items = availableLanguages.sortedBy { it.toString() },
                        key = { it.first.toString() }
                    ) { language ->
                        val isSelected = localSelection.any { it.compareTo(language) == 0 }
                        
                        Surface(
                            onClick = {
                                localSelection = if (isSelected) {
                                    localSelection.filter { it.compareTo(language) != 0 }.toSet()
                                } else {
                                    localSelection + language
                                }
                            },
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            ListItem(
                                headlineContent = { Text(language.toString()) },
                                leadingContent = {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            localSelection = if (checked) {
                                                localSelection + language
                                            } else {
                                                localSelection.filter { it.compareTo(language) != 0 }.toSet()
                                            }
                                        }
                                    )
                                }
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSelectionChanged(localSelection)
                    onDismiss()
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Language dropdown component for primary language selection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    label: String,
    selectedLanguage: LocaleItem?,
    availableLanguages: List<LocaleItem>,
    onLanguageSelected: (LocaleItem) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLanguage?.toString() ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = OutlinedTextFieldDefaults.colors()
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableLanguages.sortedBy { it.toString() }.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.toString()) },
                    onClick = {
                        onLanguageSelected(language)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Save keyboard configuration with multiple supporting languages
 */
private fun saveConfiguration(
    primaryLanguage: LocaleItem,
    supportingLanguages: Set<LocaleItem>,
    keyboardManager: MultiLanguageRepository,
    context: Context,
    onSuccess: () -> Unit
) {
    if (supportingLanguages.isEmpty()) {
        Toast.makeText(
            context,
            context.getString(R.string.multi_language_input_invalid_selection_toast),
            Toast.LENGTH_SHORT
        ).show()
        return
    }
    
    val supportingList = ArrayList(supportingLanguages.toList())
    
    val keyboardId = keyboardManager.getLayoutSetFor(primaryLanguage)
    val newKeyboard = MultiLanguageConfig(primaryLanguage, supportingList, keyboardId)
    
    val success = keyboardManager.addConfig(newKeyboard)
    
    if (success) {
        updateSubtypes(context, keyboardManager)
        Toast.makeText(
            context,
            context.getString(R.string.save),
            Toast.LENGTH_SHORT
        ).show()
        onSuccess()
    } else {
        Toast.makeText(
            context,
            context.getString(R.string.multi_language_input_already_exist_toast),
            Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Update IME subtypes
 */
private fun updateSubtypes(context: Context, keyboardManager: MultiLanguageRepository) {
    val keyboards = keyboardManager.getConfigs()
    MultiLanguageUtils.registerAdditionalSubtypes(RichInputMethodManager.getInstance(), context.resources, keyboards)
}

/**
 * Wizard mode enum
 */
enum class WizardMode {
    ADD,
    EDIT
}

