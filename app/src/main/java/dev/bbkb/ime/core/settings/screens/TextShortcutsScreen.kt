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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TextFields
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
 * Word substitutions, as an [EntryListSpec]. Everything here was previously spelled out inline in
 * a screen that was otherwise identical to `UserDictionaryScreen`. Like `userDictionarySpec` it
 * takes its title, which the screen reads from the string the Learning screen's row uses, so the
 * two agree in every language.
 *
 * The no-spaces rule appears twice on purpose: the form strips spaces from the shortcut as it is
 * typed, and [EntryListSpec.validate] refuses the write as well, so an entry that somehow arrives
 * with a space in its shortcut is still turned away with a snackbar.
 */
internal fun shortcutsSpec(title: String) = EntryListSpec(
    title = title,
    noun = "Shortcut",
    plural = "shortcuts",
    loadFailurePrefix = "Failed to load shortcuts",
    emptyIcon = Icons.Outlined.Edit,
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
    label = { it.shortcut ?: "" },
    validate = { entry ->
        if (entry.shortcut?.contains(" ") == true) "Shortcut cannot contain spaces" else null
    },
    logTag = "TextShortcutsScreen",
)

/**
 * The macros the keyboard fills in itself, by what the form's chips and the help sheet call them,
 * in the order they offer them: the everyday ones first. `DynamicMacroType` is the set the
 * expansion knows; a test keeps the two the same.
 */
internal val BuiltInMacros = listOf(
    "%D" to "Date",
    "%T" to "Time",
    "%n" to "New line",
    "%w" to "Weekday",
    "%y" to "Year",
    "%d" to "ISO date",
    "%t" to "24-hour time",
    "%b" to "Battery",
)

/**
 * Text Shortcuts Screen - manages word substitutions with full BASL integration
 * Supports dynamic macros: %D, %T, %d, %t, %n, %w, %y, %b and custom user macros
 */
@Composable
fun TextShortcutsScreen(
    onNavigateToCustomMacros: () -> Unit = {},
    onNavigateBack: () -> Unit,
    storeFactory: (android.content.Context) -> DictionaryEntryStore = ::DictionaryRepository
) {
    val context = LocalContext.current
    val store = remember { storeFactory(context) }
    val spec = remember(context) { shortcutsSpec(context.getString(R.string.settings_dict_text_shortcuts_title)) }
    // Read once per visit: the macros are edited on their own screen, which this one is left for.
    val customMacros = remember { CustomMacroRepository(context).getAllMacros() }
    val macroTags = remember(customMacros) { BuiltInMacros.map { it.first }.toSet() + customMacros.map { it.fullTag } }
    var showMacroHelp by remember { mutableStateOf(false) }

    EntryListEditorScreen(
        spec = spec,
        store = store,
        onNavigateBack = onNavigateBack,
        rowContent = { entry -> ShortcutRowContent(entry, macroTags) },
        sheetSummary = { entry, language ->
            buildList {
                add(language)
                if (hasMacro(entry.word, macroTags)) add("Changes each time")
                if (entry.fixedCase) add("Fixed case")
            }.joinToString(" · ")
        },
        sheetContent = { entry -> ShortcutPreview(entry, macroTags) },
        // The screen's two rarer places, named. They were a bare </> and a bare ⓘ in the top bar.
        menuItems = { close ->
            DropdownMenuItem(
                text = { Text("Custom macros") },
                onClick = { close(); onNavigateToCustomMacros() }
            )
            DropdownMenuItem(
                text = { Text("About dynamic content") },
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

/** Whether [phrase] holds a macro the keyboard will fill in. */
private fun hasMacro(phrase: String, tags: Set<String>) = tags.any { phrase.contains(it) }

/**
 * [text] with each macro in [tags] drawn in [style], so the parts that change stand out from the
 * parts that are typed as written. With [showBreaks], a new line (the `%n` macro, or a real one)
 * shows as ↵, for the one-line rows; a text field passes false, as its characters must not move.
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
 * What [phrase] would type right now: the keyboard's own expansion, so the preview cannot drift
 * from what a shortcut really produces. A phrase the expansion cannot take is shown as written.
 *
 * The keyboard expands in the language being typed. A shortcut for one language previews in that
 * language; one for all languages previews in the phone's. (An empty locale must not reach the
 * expansion: it formats in the root locale, which writes the date as "2026 M11 03, Tue".)
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
 * One shortcut in the list: the shortcut, then what it expands to on one line, with its macros
 * marked. A shortcut whose phrase changes each time, or that keeps its case, carries a small icon
 * after its name, the way Learned words marks an expiring word; the sheet says what each means.
 *
 * The two markers were trailing text labels ("Dynamic", "Fixed case"), which took half the row's
 * width on a narrow phone.
 */
@Composable
private fun ShortcutRowContent(entry: DictionaryEntry, macroTags: Set<String>) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = entry.shortcut ?: "", style = MaterialTheme.typography.bodyLarge)
            if (hasMacro(entry.word, macroTags)) {
                Icon(Icons.Outlined.DataObject, contentDescription = "Changes each time", tint = variant, modifier = Modifier.size(18.dp))
            }
            if (entry.fixedCase) {
                Icon(Icons.Outlined.TextFields, contentDescription = "Fixed case", tint = variant, modifier = Modifier.size(18.dp))
            }
        }
        // The gap PreferenceItem leaves between a title and its summary.
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

/**
 * The card in a shortcut's sheet: the whole phrase, which the row cuts to one line, and for a
 * phrase with macros, what it would type right now with the phrase as written under it.
 */
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
                text = if (dynamic) "If you type it now" else "Expands to",
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

/**
 * Every macro, with what it would type right now, and the user's own macros after them. It was a
 * dialog with fixed examples from January; a reference this long reads better as a sheet, and the
 * live examples answer "what will it say" without a test run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicContentSheet(customMacros: List<CustomMacro>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val examples = remember {
        BuiltInMacros.associate { (tag, _) ->
            tag to if (tag == "%n") "Starts a new line" else expandNow(context, tag, "_", "")
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
                text = "Dynamic content",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding, vertical = 8.dp),
            )
            Text(
                text = "Put these in a phrase. They are filled in each time the shortcut expands.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BuiltInMacros.forEach { (tag, name) -> MacroLine(tag, name, examples.getValue(tag)) }
            if (customMacros.isNotEmpty()) {
                PreferenceCategory(title = "Your macros")
                customMacros.forEach { MacroLine(it.fullTag, it.name, it.value) }
            }
        }
    }
}

/** One macro in the help sheet: the tag in its own column, then its name and an example. */
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
 * Add/edit form for one text shortcut, as a full-screen dialog: Material's form for a task with
 * several fields, where a basic dialog squeezed them. Saving is in its top bar; deleting is not
 * here at all but in the shortcut's sheet, with Undo.
 *
 * The macro chips put their tag where the cursor is (they used to add it to the end, whatever was
 * selected) and include the user's own macros. The preview under them is the keyboard's own
 * expansion of the phrase as it stands.
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

    val chips = remember(customMacros) { BuiltInMacros + customMacros.map { it.fullTag to it.name } }
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
        // A full-screen form is a screen, not a dialog over one: without this the window dims the
        // settings page's status bar behind it to grey.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(0f) }
        Surface(modifier = Modifier.fillMaxSize(), color = LocalSettingsColors.current.page) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, "Close")
                    }
                    Text(
                        text = if (entry != null) "Edit shortcut" else "New shortcut",
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
                    ) { Text("Save") }
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
                        label = { Text("Shortcut") },
                        supportingText = { Text("Type this to expand it. No spaces.") },
                        singleLine = true,
                        modifier = inset.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = { Text("Phrase") },
                        supportingText = { Text("What it expands to") },
                        visualTransformation = highlight,
                        minLines = 2,
                        maxLines = 5,
                        modifier = inset.fillMaxWidth(),
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Insert at the cursor",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showMacroHelp = true }) {
                            Icon(Icons.Outlined.Info, "About dynamic content", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // One scrolling row rather than a block of rows: on a short screen the fields
                    // and the preview matter more than seeing every chip at once.
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
                                    text = "Preview",
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
                            Text("Fixed case", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Always type the phrase exactly as written",
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
