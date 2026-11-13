package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.editor.EntryListEditorScreen
import dev.bbkb.ime.core.settings.editor.EntryListSpec
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import dev.bbkb.ime.personaldictionary.macro.CustomMacroRepository
import dev.bbkb.ime.personaldictionary.macro.DynamicContentHandler
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import java.util.Locale

/**
 * Word substitutions as an [EntryListSpec]; the title is the Learning screen row's string. Spaces
 * are refused twice on purpose: the form strips them as typed and [EntryListSpec.validate] rejects
 * any that still arrive.
 */
internal val ShortcutsSpec = EntryListSpec(
    title = R.string.settings_dict_text_shortcuts_title,
    count = R.plurals.settings_shortcuts_count,
    searchCount = R.plurals.settings_shortcuts_search_count,
    searchHint = R.string.settings_shortcuts_search_hint,
    add = R.string.settings_shortcuts_add,
    edit = R.string.settings_shortcuts_edit,
    added = R.string.settings_shortcuts_added,
    addFailed = R.string.settings_shortcuts_add_failed,
    updated = R.string.settings_shortcuts_updated,
    updateFailed = R.string.settings_shortcuts_update_failed,
    deleted = R.plurals.settings_shortcuts_deleted,
    deleteFailed = R.string.settings_shortcuts_delete_failed,
    restoreFailed = R.string.settings_shortcuts_restore_failed,
    loadFailed = R.string.settings_shortcuts_load_failed,
    emptyIcon = R.drawable.ic_settings_m_edit,
    emptyTitle = R.string.settings_shortcuts_empty_title,
    emptyHint = R.string.settings_shortcuts_empty_hint,
    belongsHere = { it.isSubstitution },
    identity = { "${it.shortcut}_${it.locale}" },
    sortKey = { it.shortcut?.lowercase() },
    matches = { entry, query ->
        entry.shortcut?.contains(query, ignoreCase = true) == true ||
            entry.word.contains(query, ignoreCase = true)
    },
    label = { it.shortcut ?: "" },
    validate = { entry ->
        if (entry.shortcut?.contains(" ") == true) R.string.settings_shortcuts_no_spaces else null
    },
    logTag = "TextShortcutsScreen",
)

/**
 * The built-in macros by the names the chips and help sheet use, everyday ones first. A test keeps
 * this in step with `DynamicMacroType`.
 */
internal val BuiltInMacros = listOf(
    "%D" to R.string.settings_shortcuts_macro_date,
    "%T" to R.string.settings_shortcuts_macro_time,
    "%n" to R.string.settings_shortcuts_macro_new_line,
    "%w" to R.string.settings_shortcuts_macro_weekday,
    "%y" to R.string.settings_shortcuts_macro_year,
    "%d" to R.string.settings_shortcuts_macro_iso_date,
    "%t" to R.string.settings_shortcuts_macro_time_24h,
    "%b" to R.string.settings_shortcuts_macro_battery,
)

/** Text shortcuts, with dynamic macros (%D, %T, %d, %t, %n, %w, %y, %b) and the user's own. */
@Composable
fun TextShortcutsScreen(
    onNavigateToCustomMacros: () -> Unit = {},
    onNavigateBack: () -> Unit,
    storeFactory: (android.content.Context) -> DictionaryEntryStore = ::DictionaryRepository
) {
    val context = LocalContext.current
    val store = remember { storeFactory(context) }
    // Read once per visit: macros are edited on their own screen.
    val customMacros = remember { CustomMacroRepository(context).getAllMacros() }
    val macroTags = remember(customMacros) { BuiltInMacros.map { it.first }.toSet() + customMacros.map { it.fullTag } }
    var showMacroHelp by remember { mutableStateOf(false) }

    EntryListEditorScreen(
        spec = ShortcutsSpec,
        store = store,
        onNavigateBack = onNavigateBack,
        rowContent = { entry -> ShortcutRowContent(entry, macroTags) },
        sheetSummary = { entry, language ->
            buildList {
                add(language)
                if (hasMacro(entry.word, macroTags)) add(stringResource(R.string.settings_shortcuts_changes_each_time))
                if (entry.fixedCase) add(stringResource(R.string.settings_shortcuts_fixed_case))
            }.joinToString(" · ")
        },
        sheetContent = { entry -> ShortcutPreview(entry, macroTags) },
        menuItems = { close ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_custom_macros_title)) },
                onClick = { close(); onNavigateToCustomMacros() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_shortcuts_about_dynamic)) },
                onClick = { close(); showMacroHelp = true }
            )
        },
        dialogs = { host ->
            if (host.adding) {
                ShortcutForm(
                    entry = null,
                    // A new shortcut lands in the first locale; an edited one keeps its own.
                    locale = host.locales.firstOrNull() ?: "",
                    customMacros = customMacros,
                    onDismiss = host.dismissAdd,
                    onSave = { entry ->
                        host.add(entry)
                        host.dismissAdd()
                    }
                )
            }

            host.editing?.let { selectedEntry ->
                ShortcutForm(
                    entry = selectedEntry,
                    locale = selectedEntry.locale,
                    customMacros = customMacros,
                    onDismiss = host.dismissEdit,
                    onSave = { entry ->
                        host.update(selectedEntry, entry)
                        host.dismissEdit()
                    }
                )
            }
        }
    )

    if (showMacroHelp) {
        DynamicContentSheet(customMacros = customMacros, onDismiss = { showMacroHelp = false })
    }
}

private fun hasMacro(phrase: String, tags: Set<String>) = tags.any { phrase.contains(it) }

/**
 * [text] with each macro in [tags] drawn in [style]. With [showBreaks] a new line (`%n` or real)
 * shows as ↵ for one-line rows; text fields pass false so characters do not move.
 */
private fun highlightMacros(text: String, tags: Set<String>, style: SpanStyle, showBreaks: Boolean): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val tag = if (text[i] == '%' && i + 1 < text.length) text.substring(i, i + 2) else null
            when {
                tag != null && tag in tags -> {
                    withStyle(style) { append(if (showBreaks && tag == "%n") "↵" else tag) }
                    i += 2
                }
                showBreaks && text[i] == '\n' -> {
                    withStyle(style) { append("↵") }
                    i++
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

@Composable
private fun macroStyle() = SpanStyle(color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)

/**
 * What [phrase] would type now, via the keyboard's own expansion (as written if it cannot expand).
 * Previews in the shortcut's language, or the phone's for all languages: an empty locale must not
 * reach the expansion, which would format in the root locale ("2026 M11 03, Tue").
 */
private fun expandNow(context: Context, phrase: String, shortcut: String, locale: String): String =
    try {
        DynamicContentHandler.handleDynamicContent(
            context,
            WordSubstitution.createUserSubstitution(locale.ifEmpty { "all" }, shortcut.ifEmpty { "_" }, phrase, true),
            locale.takeIf { it.isNotEmpty() && it != "all" }?.let { LocaleUtils.constructLocaleFromString(it) }
                ?: Locale.getDefault()
        )
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) android.util.Log.w("TextShortcutsScreen", "preview failed", e)
        phrase
    }

/**
 * One shortcut row: the shortcut, then its expansion on one line with macros marked. Dynamic and
 * fixed-case shortcuts carry a small icon after the name; the sheet explains each.
 */
@Composable
private fun ShortcutRowContent(entry: DictionaryEntry, macroTags: Set<String>) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = entry.shortcut ?: "", style = MaterialTheme.typography.bodyLarge)
            if (hasMacro(entry.word, macroTags)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_data_object), contentDescription = stringResource(R.string.settings_shortcuts_changes_each_time), tint = variant, modifier = Modifier.size(18.dp))
            }
            if (entry.fixedCase) {
                Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_text_fields), contentDescription = stringResource(R.string.settings_shortcuts_fixed_case), tint = variant, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(LocalSpacing.current.titleSummaryGap))
        Text(
            text = highlightMacros(entry.word, macroTags, macroStyle(), showBreaks = true),
            style = MaterialTheme.typography.bodyMedium,
            color = variant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The sheet's card: the full phrase and, with macros, what it would type now. */
@Composable
private fun ShortcutPreview(entry: DictionaryEntry, macroTags: Set<String>) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val dynamic = hasMacro(entry.word, macroTags)
    val now = remember(entry) { if (dynamic) expandNow(context, entry.word, entry.shortcut ?: "", entry.locale) else entry.word }
    Surface(
        shape = LocalShapes.current.group,
        color = LocalSettingsColors.current.card,
        modifier = Modifier.padding(horizontal = spacing.groupInset).fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(if (dynamic) R.string.settings_shortcuts_preview_now else R.string.settings_shortcuts_expands_to),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = now, style = MaterialTheme.typography.bodyLarge)
            if (dynamic) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

/** Every macro with a live example, then the user's own macros. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicContentSheet(customMacros: List<CustomMacro>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val examples = remember {
        BuiltInMacros.associate { (tag, _) ->
            tag to if (tag == "%n") context.getString(R.string.settings_shortcuts_macro_new_line_example)
            else expandNow(context, tag, "_", "")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_shortcuts_dynamic_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.settings_shortcuts_dynamic_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BuiltInMacros.forEach { (tag, name) -> MacroLine(tag, stringResource(name), examples.getValue(tag)) }
            if (customMacros.isNotEmpty()) {
                PreferenceCategory(title = stringResource(R.string.settings_shortcuts_your_macros))
                customMacros.forEach { MacroLine(it.fullTag, it.name, it.value) }
            }
        }
    }
}

@Composable
private fun MacroLine(tag: String, name: String, example: String) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.oneLineListItemHeight)
            .padding(horizontal = spacing.categoryStartPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )
        Spacer(modifier = Modifier.width(spacing.iconTextGap))
        Column {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = example,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Draws the macros in a phrase field in the accent colour; the characters themselves stay put. */
private class MacroHighlight(private val tags: Set<String>, private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(highlightMacros(text.text, tags, style, showBreaks = false), OffsetMapping.Identity)
}

/**
 * Full-screen add/edit form for one shortcut; Save is in the top bar, delete lives in the sheet
 * (with Undo). Macro chips insert at the cursor; the preview is the keyboard's own expansion.
 */
@Composable
private fun ShortcutForm(
    entry: DictionaryEntry?,
    locale: String,
    customMacros: List<CustomMacro>,
    onDismiss: () -> Unit,
    onSave: (DictionaryEntry) -> Unit,
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var shortcut by remember { mutableStateOf(entry?.shortcut ?: "") }
    var phrase by remember {
        val text = entry?.word ?: ""
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var fixedCase by remember { mutableStateOf(entry?.fixedCase ?: false) }
    var showMacroHelp by remember { mutableStateOf(false) }

    val chips = BuiltInMacros.map { (tag, name) -> tag to stringResource(name) } + customMacros.map { it.fullTag to it.name }
    val macroTags = remember(chips) { chips.map { it.first }.toSet() }
    val highlight = MacroHighlight(macroTags, macroStyle())
    val preview = remember(phrase.text, shortcut) {
        if (hasMacro(phrase.text, macroTags)) expandNow(context, phrase.text, shortcut, locale) else phrase.text
    }
    val canSave = shortcut.isNotBlank() && phrase.text.isNotBlank() && !shortcut.contains(" ")

    fun insert(tag: String) {
        val selection = phrase.selection
        val text = phrase.text.replaceRange(selection.min, selection.max, tag)
        phrase = TextFieldValue(text, TextRange(selection.min + tag.length))
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A full-screen form is a screen: without this the window dims the status bar behind it.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        Surface(modifier = Modifier.fillMaxSize(), color = LocalSettingsColors.current.page) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_close), stringResource(R.string.settings_common_close))
                    }
                    Text(
                        text = stringResource(if (entry != null) R.string.settings_shortcuts_edit else R.string.settings_shortcuts_new),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                DictionaryEntry(
                                    id = entry?.id,
                                    word = phrase.text.trim(),
                                    shortcut = shortcut.trim(),
                                    locale = locale,
                                    fixedCase = fixedCase
                                )
                            )
                        }
                    ) { Text(stringResource(R.string.settings_common_save)) }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    // The 24dp margin is per block, so the chip row can scroll to the edges.
                    val inset = Modifier.padding(horizontal = 24.dp)
                    OutlinedTextField(
                        value = shortcut,
                        onValueChange = { shortcut = it.replace(" ", "") },
                        label = { Text(stringResource(R.string.settings_shortcuts_field_shortcut)) },
                        supportingText = { Text(stringResource(R.string.settings_shortcuts_field_shortcut_help)) },
                        singleLine = true,
                        modifier = inset.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = { Text(stringResource(R.string.settings_shortcuts_field_phrase)) },
                        supportingText = { Text(stringResource(R.string.settings_shortcuts_field_phrase_help)) },
                        visualTransformation = highlight,
                        minLines = 2,
                        maxLines = 5,
                        modifier = inset.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_shortcuts_insert_at_cursor),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showMacroHelp = true }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_info), stringResource(R.string.settings_shortcuts_about_dynamic), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // One scrolling row: on a short screen the fields and preview matter more than every chip.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        chips.forEach { (tag, name) ->
                            AssistChip(
                                onClick = { insert(tag) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(name)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            tag,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                    }

                    if (phrase.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            shape = LocalShapes.current.group,
                            color = LocalSettingsColors.current.card,
                            modifier = inset.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_common_preview),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(text = preview, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = inset
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .toggleable(value = fixedCase, role = Role.Checkbox, onValueChange = { fixedCase = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = fixedCase, onCheckedChange = null)
                        Spacer(modifier = Modifier.width(spacing.iconTextGap))
                        Column {
                            Text(stringResource(R.string.settings_shortcuts_fixed_case), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_shortcuts_fixed_case_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMacroHelp) {
        DynamicContentSheet(customMacros = customMacros, onDismiss = { showMacroHelp = false })
    }
}
