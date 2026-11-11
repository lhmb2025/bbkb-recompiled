package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.vectorResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.builder.DeviceProfileExporter
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
import dev.bbkb.ime.core.layout.LetterLayoutXml
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.backup.BackupBundle
import dev.bbkb.ime.core.settings.backup.BackupOperations
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.rememberListSearchState
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.Collator
import java.util.Locale

private const val TAG = "PhysicalLayouts"

/**
 * Advanced > Device compatibility > Custom keypad layouts: every letter layout on the phone
 * ([LetterLayout]), the user's own and the ones the app ships, listed the same way — name, which
 * keyboards it is for, how many keys it names.
 *
 * **Custom** holds the user's layouts. Add layout reads one from a file the user picks (a letter
 * layout document, or a backup holding some; [BackupOperations.readFrom] tells them apart), the
 * same reading Advanced > Backup and restore > Restore from file does. Which keyboard uses one is
 * chosen per language on the Language screen ([LetterLayoutChoice]); here they are managed. Tapping
 * one opens its sheet ([LayoutSheet]): the keypad drawn with the layout's letters, then Share
 * (hands the stored XML file to the share sheet, the way the device profile builder shares a
 * config), Rename (a new name under the same id, so the languages that chose it keep it) and
 * Delete.
 *
 * **Built-in** holds the documents under `assets/letter_layouts/` that are for the physical keys
 * (the on-screen-only ones, Colemak and Dvorak, are chosen per keyboard on the Language screen).
 * They are read-only and are not picked here: which one a keyboard uses follows its language.
 * Their sheet draws the keypad and offers "Save as a file", which writes the document wherever the
 * user chooses — the way to start a layout of one's own is to save the nearest built-in, edit it,
 * and add it from the file. The current keyboard's language comes first, the document it would use at the top,
 * then the rest by name.
 *
 * Custom comes first because it is the part the user can change; the built-in list has a document
 * per language and would push it off the screen.
 *
 * Both lists are read off the main thread, so on first composition they are briefly empty.
 *
 * @param builtInDocuments where the built-in documents come from; the app's assets when null. The
 *   render test hands in a fixed set, so what it pins does not move with every generated asset.
 */
@Composable
fun PhysicalLayoutsScreen(
    onNavigateBack: () -> Unit,
    builtInDocuments: LetterLayoutRegistry.BuiltInDocuments? = null,
) {
    val context = LocalContext.current
    val registry = remember { LetterLayoutRegistry(context, builtInDocuments) }
    val scope = rememberCoroutineScope()
    val phoneKeypad = remember { DeviceProfile.current()?.keypadLayout }
    val phoneKeypadType = remember { DeviceProfile.current()?.effectiveKeypadType }
    val keyboardLocale = remember { currentKeyboardLocale() }
    val keyboardLayoutSet = remember { currentKeyboardLayoutSet() }

    // null until the first read lands.
    var customLayouts by remember { mutableStateOf<List<LetterLayout>?>(null) }
    var builtInLayouts by remember { mutableStateOf<List<LetterLayout>?>(null) }
    var deleteTarget by remember { mutableStateOf<LetterLayout?>(null) }
    var renameTarget by remember { mutableStateOf<LetterLayout?>(null) }
    // The layout whose sheet is open.
    var sheetLayout by remember { mutableStateOf<LetterLayout?>(null) }
    // The built-in being saved, by id. Saveable, because the activity can be recreated while the
    // file picker is open.
    var savingId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        customLayouts = withContext(Dispatchers.IO) { registry.list() }
        builtInLayouts = withContext(Dispatchers.IO) {
            orderBuiltInLayouts(
                // Only the documents for the physical keys: an on-screen-only document (Colemak,
                // Dvorak) is chosen per keyboard on the Language screen and does nothing here.
                layouts = registry.builtInIds().mapNotNull { registry.builtIn(it) }
                    .filter { LetterLayout.Target.PKB in it.targets },
                keyboardLocale = keyboardLocale,
                chosenId = registry.builtIn(keyboardLocale, phoneKeypad, phoneKeypadType, keyboardLayoutSet)?.id,
            )
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LetterLayoutXml.MIME_TYPE)
    ) { uri ->
        val id = savingId
        savingId = null
        if (uri != null && id != null) scope.launch {
            val saved = saveBuiltIn(context, registry, id, uri)
            toast(context, if (saved) R.string.settings_pkb_layouts_saved else R.string.settings_pkb_layouts_save_failed)
        }
    }

    val addLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val added = addFromFile(context, registry, uri)
            if (added) customLayouts = withContext(Dispatchers.IO) { registry.list() }
            toast(context, if (added) R.string.settings_pkb_layouts_added else R.string.settings_pkb_layouts_add_failed)
        }
    }

    // The built-in list has a document per language, so the screen can be searched: by a
    // layout's name or by anything its second line says (a language, a keypad). While a search
    // is on, only the matching layouts are listed, without the Add row and the notes.
    val search = rememberListSearchState()
    fun shown(layouts: List<LetterLayout>?) = layouts.orEmpty().filter {
        search.matches(it.name, layoutSummary(context, it, phoneKeypad, phoneKeypadType))
    }
    val shownCustom = shown(customLayouts)
    val shownBuiltIn = shown(builtInLayouts)
    val noMatches = search.filtering && shownCustom.isEmpty() && shownBuiltIn.isEmpty()

    SettingsScreenHost(R.string.settings_pkb_layouts_title, onNavigateBack, search = search,
        searchHint = R.string.settings_pkb_layouts_search, rows = listOf(
        Custom(visible = { noMatches }) {
            PreferenceInfo(text = stringResource(R.string.settings_common_no_matches))
        },
        // ── Custom: the user's layouts, chosen per language on the Language screen ──
        Category(R.string.settings_pkb_layouts_category_custom, visible = { !search.filtering || shownCustom.isNotEmpty() }),
        Custom(inGroup = true, visible = { !search.filtering || shownCustom.isNotEmpty() }) {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.groupGap)) {
                shownCustom.forEach { layout ->
                    key(layout.id) {
                        LayoutRow(
                            layout = layout,
                            summary = layoutSummary(context, layout, phoneKeypad, phoneKeypadType),
                            onClick = { sheetLayout = layout },
                        )
                    }
                }
            }
        },
        Custom(visible = { customLayouts?.isEmpty() == true && !search.filtering }) {
            PreferenceInfo(text = stringResource(R.string.settings_pkb_layouts_empty))
        },
        // Where a layout comes from: a file. Pickers filter by type unevenly (a file manager may
        // call an .xml document text or octet-stream), so every file is offered and the reader
        // says no to what is not a layout.
        Custom(inGroup = true, visible = { !search.filtering }) {
            PreferenceItem(
                title = stringResource(R.string.settings_pkb_layouts_add),
                icon = ImageVector.vectorResource(R.drawable.ic_settings_m_add),
                onClick = { addLauncher.launch(arrayOf(LetterLayoutXml.MIME_TYPE, BackupBundle.MIME_TYPE, "*/*")) },
            )
        },

        // ── Built-in: read-only, each one a template to save as a file ──
        // Shown while the list loads; left out only when the build ships no documents at all.
        Category(R.string.settings_pkb_layouts_category_builtin, visible = {
            if (search.filtering) shownBuiltIn.isNotEmpty() else builtInLayouts?.isEmpty() != true
        }),
        Custom(visible = { builtInLayouts?.isEmpty() != true && !search.filtering }) {
            PreferenceInfo(text = stringResource(R.string.settings_pkb_layouts_builtin_note))
        },
        Custom(inGroup = true, visible = { !search.filtering || shownBuiltIn.isNotEmpty() }) {
            shownBuiltIn.forEach { layout ->
                key(layout.id) {
                    LayoutRow(
                        layout = layout,
                        summary = layoutSummary(context, layout, phoneKeypad, phoneKeypadType),
                        onClick = { sheetLayout = layout },
                    )
                }
            }
        },
    ))

    sheetLayout?.let { layout ->
        val custom = layout.origin == LetterLayout.Origin.USER
        LayoutSheet(
            layout = layout,
            summary = layoutSummary(context, layout, phoneKeypad, phoneKeypadType),
            onDismiss = { sheetLayout = null },
            actions = if (custom) {
                listOf(
                    SheetAction(ImageVector.vectorResource(R.drawable.ic_settings_m_share), R.string.clipboard_menu_share) {
                        share(context, registry, layout)
                    },
                    SheetAction(ImageVector.vectorResource(R.drawable.ic_settings_m_edit), R.string.settings_pkb_layouts_rename) {
                        sheetLayout = null
                        renameTarget = layout
                    },
                    SheetAction(ImageVector.vectorResource(R.drawable.ic_settings_m_delete_outline), R.string.clipboard_menu_delete) {
                        sheetLayout = null
                        deleteTarget = layout
                    },
                )
            } else {
                listOf(
                    SheetAction(ImageVector.vectorResource(R.drawable.ic_settings_m_save_alt), R.string.settings_pkb_layouts_save_file) {
                        sheetLayout = null
                        savingId = layout.id
                        saveLauncher.launch(layout.id + LetterLayoutXml.FILE_SUFFIX)
                    },
                )
            },
        )
    }

    renameTarget?.let { layout ->
        RenameLayoutDialog(
            currentName = layout.name,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                renameTarget = null
                scope.launch {
                    val renamed = withContext(Dispatchers.IO) { renameLayout(registry, layout, newName) }
                    if (renamed) customLayouts = withContext(Dispatchers.IO) { registry.list() }
                    else toast(context, R.string.settings_pkb_layouts_save_failed)
                }
            },
        )
    }

    deleteTarget?.let { layout ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_pkb_layouts_delete_title)) },
            text = { Text(stringResource(R.string.settings_pkb_layouts_delete_message, layout.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        customLayouts = withContext(Dispatchers.IO) {
                            registry.delete(layout.id)
                            registry.list()
                        }
                    }
                }) {
                    Text(
                        stringResource(R.string.settings_pkb_layouts_delete_title),
                        color = MaterialTheme.colorScheme.error,
                    )
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
 * One layout, custom or built-in: its name and binding. Tapping it opens its sheet, where what can
 * be done with it lives; the row itself carries nothing else, so built-in and custom rows look
 * alike and line up.
 */
@Composable
private fun LayoutRow(layout: LetterLayout, summary: String, onClick: () -> Unit) {
    PreferenceItem(
        title = layout.name,
        summary = summary,
        onClick = onClick,
    )
}

/** One row at the foot of a layout's sheet. */
private class SheetAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val onClick: () -> Unit,
)

/**
 * A layout's sheet, built as the User dictionary and Learned words build an entry's: its name, the
 * line the row shows, the keypad drawn with its letters ([KeypadPreview]), then what can be done
 * with it. Scrolls inside the sheet so the actions stay reachable on the KEY2's short screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayoutSheet(
    layout: LetterLayout,
    summary: String,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = spacing.extraLarge),
        ) {
            Text(
                text = layout.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding, vertical = spacing.small),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding),
            )
            Spacer(modifier = Modifier.height(spacing.large))
            KeypadPreview(
                layout = layout,
                modifier = Modifier.padding(horizontal = spacing.groupInset),
            )
            Spacer(modifier = Modifier.height(spacing.small))
            actions.forEach { action ->
                PreferenceItem(title = stringResource(action.label), icon = action.icon, onClick = action.onClick)
            }
        }
    }
}

/**
 * The keypad as the phone has it, each cap showing what the layout types there ([keyCapLegends]):
 * the letter, and small in the corners a Shift legend that is not its upper case and the Alt
 * character. The rows are
 * [keypadRows]'s; a key the layout does not name is drawn empty, since it types whatever it would
 * without the layout. Always left to right: the keys sit where they sit whatever the language.
 */
@Composable
private fun KeypadPreview(layout: LetterLayout, modifier: Modifier = Modifier) {
    val rows = remember(layout) { keypadRows(layout) }
    val gap = 4.dp
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val columns = KEYPAD_COLUMNS
            val keyWidth = (maxWidth - gap * (columns - 1)) / columns
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                    ) {
                        row.forEach { keyCode ->
                            KeyCap(layout.keys[keyCode], Modifier.size(width = keyWidth, height = 44.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: LetterLayout.Key?, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
            if (key == null) return@Box
            val legends = keyCapLegends(key)
            legends.letter?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            legends.shift?.let { CornerLegend(it, Modifier.align(Alignment.TopStart)) }
            legends.alt?.let { CornerLegend(it, Modifier.align(Alignment.TopEnd)) }
        }
    }
}

@Composable
private fun CornerLegend(text: String, modifier: Modifier) {
    Text(
        text = text,
        fontSize = 9.sp,
        lineHeight = 9.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/** What a key cap in [KeypadPreview] prints; null where it prints nothing. */
internal class KeyCapLegends(val letter: String?, val shift: String?, val alt: String?)

/**
 * A key's cap: the letter in upper case, as keycaps print it; the Shift legend in a corner only
 * when it is another character than that (Arabic's ط types ى with Shift), so a Latin cap is not
 * printed twice; the Alt character in the other corner.
 */
internal fun keyCapLegends(key: LetterLayout.Key): KeyCapLegends {
    val upper = if (key.keySpec != 0) Character.toUpperCase(key.keySpec) else 0
    return KeyCapLegends(
        letter = upper.takeIf { it != 0 }?.let(LetterLayout::string),
        shift = key.shift.takeIf { it != 0 && it != upper }?.let(LetterLayout::string),
        alt = key.alt.takeIf { it != 0 }?.let(LetterLayout::string),
    )
}

/**
 * The dialog Rename opens: the layout's name in a field, Save while what is typed is a name a
 * layout document can carry ([isValidLayoutName]).
 */
@Composable
private fun RenameLayoutDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentName, selection = TextRange(currentName.length)))
    }
    val valid = isValidLayoutName(name.text)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_pkb_layouts_rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.text.length <= LetterLayoutXml.MAX_NAME_LENGTH) name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.settings_macros_name)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onRename(name.text.trim()) }) {
                Text(stringResource(R.string.settings_common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The keypad's widest row: ten keys, Q to P. */
private const val KEYPAD_COLUMNS = 10

/**
 * The keys [KeypadPreview] draws, row by row, by Android key code. A physical keypad reports each
 * key by its QWERTY position whatever its caps say, so the three letter rows are Q–P, A–L and
 * Z–M; the KEY2's $ key (KEYCODE_4, after M) joins the bottom row when the layout names it, and
 * any other key the layout names (Thai's 0 key) gets a row of its own, in document order.
 */
internal fun keypadRows(layout: LetterLayout): List<List<Int>> {
    val rows = mutableListOf(
        "QWERTYUIOP".map(::letterKeyCode),
        "ASDFGHJKL".map(::letterKeyCode),
        "ZXCVBNM".map(::letterKeyCode) +
            listOfNotNull(KeyEvent.KEYCODE_4.takeIf { it in layout.keys }),
    )
    val placed = rows.flatten().toSet()
    val others = layout.keys.keys.filter { it !in placed }
    if (others.isNotEmpty()) rows += others.take(KEYPAD_COLUMNS)
    return rows
}

private fun letterKeyCode(letter: Char): Int = KeyEvent.KEYCODE_A + (letter - 'A')

/** Whether [name] (trimmed) is one a layout document can carry: not empty, not too long, no control characters. */
internal fun isValidLayoutName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.length <= LetterLayoutXml.MAX_NAME_LENGTH &&
        trimmed.none { Character.isISOControl(it) }
}

/**
 * Stores user layout [layout] under [newName] (trimmed), keeping its id, so every language that
 * chose it keeps it. False when the name is not one a document can carry or the write fails.
 */
internal fun renameLayout(registry: LetterLayoutRegistry, layout: LetterLayout, newName: String): Boolean {
    if (layout.origin != LetterLayout.Origin.USER || !isValidLayoutName(newName)) return false
    return try {
        registry.save(layout.withName(newName.trim()))
        true
    } catch (e: IOException) {
        Logger.warn(TAG, "Could not rename ${layout.id}: ${e.message}")
        false
    }
}

/**
 * The built-in list in the order the screen shows it: the documents bound to the current
 * keyboard's language first — [chosenId], the one that keyboard would use, at their head — then
 * every other document by name. A document bound to no language belongs to no
 * language, so it sorts with the rest. Names compare the way the reader's language orders them;
 * equal names fall back to the id, so the order never depends on how the files were listed.
 *
 * @param keyboardLocale the current keyboard's locale in the app's spelling (`iw`, `pt_BR`), or
 *   null when there is none; matched as [LetterLayout.Binding.matchesLanguage] matches, so a
 *   document bound to `zh_TW` is not a `zh_CN` keyboard's.
 */
internal fun orderBuiltInLayouts(
    layouts: List<LetterLayout>,
    keyboardLocale: String?,
    chosenId: String?,
): List<LetterLayout> {
    val collator = Collator.getInstance()
    val byName = Comparator<LetterLayout> { a, b -> collator.compare(a.name, b.name) }.thenBy { it.id }
    val (forKeyboard, others) = layouts.partition {
        !keyboardLocale.isNullOrEmpty() && it.binding.isLanguageBound && it.binding.matchesLanguage(keyboardLocale)
    }
    return forKeyboard.sortedWith(compareByDescending<LetterLayout> { it.id == chosenId }.then(byName)) +
        others.sortedWith(byName)
}

/**
 * A layout row's second line: "German · QWERTZ keypad · 30 keys" — the languages it is bound to
 * (by name; "All keyboards" when none; left out when it is the layout's own name, so "English"
 * reads "26 keys"), the keypad shape or type it is bound to, and how many keys it names. When it is bound to a keypad this phone does not have, a second line says it does
 * nothing here. A phone whose keypad is unknown gets no such line: unknown is not different.
 *
 * @param languageName a language code's display name; the keyboard's own names by default.
 */
internal fun layoutSummary(
    context: Context,
    layout: LetterLayout,
    phoneKeypad: String?,
    phoneKeypadType: String?,
    languageName: (String) -> String = ::languageDisplayName,
): String {
    val binding = layout.binding
    val parts = ArrayList<String>(3)
    parts += if (binding.languages.isEmpty()) {
        context.getString(R.string.settings_pkb_layouts_any_language)
    } else {
        binding.languages.joinToString(", ") { languageName(it) }
    }
    if (binding.keypad != LetterLayout.ANY) {
        parts += context.getString(R.string.settings_pkb_layouts_keypad, binding.keypad.uppercase(Locale.ROOT))
    }
    if (binding.keypadType != LetterLayout.ANY) {
        parts += if (binding.keypadType == LetterLayout.KEYPAD_TYPE_THREE_ROW) {
            context.getString(R.string.settings_pkb_layouts_three_row)
        } else {
            binding.keypadType
        }
    }
    // A layout named for its one language ("English", bound to English) would say the name twice,
    // title and second line; there the second line starts with what is left.
    if (parts.first().equals(layout.name.trim(), ignoreCase = true)) parts.removeAt(0)
    val details = if (parts.isEmpty()) {
        context.getString(R.string.settings_pkb_layouts_keys, layout.keyCount)
    } else {
        context.getString(R.string.settings_pkb_layouts_details, parts.joinToString(" · "), layout.keyCount)
    }
    val notThisKeypad =
        (binding.keypad != LetterLayout.ANY && phoneKeypad != null &&
            !phoneKeypad.equals(binding.keypad, ignoreCase = true)) ||
            (binding.keypadType != LetterLayout.ANY && phoneKeypadType != null &&
                !binding.keypadTypeMatches(phoneKeypadType))
    return if (notThisKeypad) details + "\n" + context.getString(R.string.settings_pkb_layouts_other_keypad) else details
}

/** "iw" → "Hebrew", in the phone's language, the way the keyboard names its languages elsewhere. */
private fun languageDisplayName(code: String): String = try {
    ResourceLocaleUtils.getSubtypeLocaleDisplayName(code).takeIf { it.isNotBlank() } ?: code
} catch (e: RuntimeException) {
    code
}

/**
 * The current keyboard's locale in the app's spelling. Settings can open before the keyboard has
 * been shown in this process; [SubtypeManager] then answers with the phone's language, a fair
 * guess at the keyboard someone opening this screen types with.
 */
private fun currentKeyboardLocale(): String? = try {
    LocaleUtils.localeString(SubtypeManager.getInstance().currentSubtypeLocale)
} catch (e: RuntimeException) {
    null
}

/** The current keyboard's layout set (`translit`, `qwerty`, …): a document bound to one applies only there. */
private fun currentKeyboardLayoutSet(): String? = try {
    SubtypeManager.getInstance()?.currentSubtype?.let { ResourceLocaleUtils.getKeyboardLayoutSetName(it) }
} catch (e: RuntimeException) {
    null
}

/**
 * Reads the letter layouts in the file at [uri] (one document, or a backup holding some) into the
 * registry. False when the file holds none or cannot be read; nothing is stored then.
 */
private suspend fun addFromFile(context: Context, registry: LetterLayoutRegistry, uri: Uri): Boolean {
    val result = BackupOperations.readFrom(context, uri)
    val layouts = (result as? BackupOperations.ReadResult.Ok)?.bundle?.letterLayouts.orEmpty()
    if (layouts.isEmpty()) {
        Logger.warn(TAG, "Could not add a layout from $uri: $result")
        return false
    }
    withContext(Dispatchers.IO) { layouts.forEach(registry::save) }
    return true
}

/** Writes built-in [id]'s document to [uri], the file the picker just created. */
private suspend fun saveBuiltIn(context: Context, registry: LetterLayoutRegistry, id: String, uri: Uri): Boolean =
    withContext(Dispatchers.IO) {
        val layout = registry.builtIn(id) ?: return@withContext false
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: return@withContext false
            stream.use { it.write(LetterLayoutXml.serialize(layout).toByteArray(Charsets.UTF_8)) }
            true
        } catch (e: Exception) {
            Logger.warn(TAG, "Could not save built-in $id: ${e.message}")
            false
        }
    }

private fun share(context: Context, registry: LetterLayoutRegistry, layout: LetterLayout) {
    val file = registry.fileFor(layout.id)
    val intent = if (file.isFile) {
        DeviceProfileExporter.shareIntent(
            context,
            file,
            context.getString(R.string.settings_pkb_layouts_share_chooser),
            LetterLayoutXml.MIME_TYPE,
        )
    } else null
    if (intent == null) {
        toast(context, R.string.settings_pkb_layouts_share_failed)
        return
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        toast(context, R.string.settings_pkb_layouts_share_failed)
    }
}

private fun toast(context: Context, @StringRes message: Int) {
    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
}
