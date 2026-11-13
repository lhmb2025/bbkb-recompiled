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
 * Custom keypad layouts: every [LetterLayout] on the phone. Custom holds the user's layouts (added
 * from a file, a layout document or a backup per [BackupOperations.readFrom]); a sheet offers
 * Share, Rename (same id, so languages that chose it keep it) and Delete. Built-in lists the
 * physical-key documents in `assets/letter_layouts/`, read-only, each savable as a file to start
 * from. Which layout a keyboard uses is chosen on the Language screen ([LetterLayoutChoice]).
 *
 * @param builtInDocuments source of built-in documents, the app's assets when null (render tests
 *   pass a fixed set)
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
    var sheetLayout by remember { mutableStateOf<LetterLayout?>(null) }
    // Saveable: the activity can be recreated while the file picker is open.
    var savingId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        customLayouts = withContext(Dispatchers.IO) { registry.list() }
        builtInLayouts = withContext(Dispatchers.IO) {
            orderBuiltInLayouts(
                // Physical-key documents only: on-screen-only ones (Colemak, Dvorak) do nothing here.
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

    // Search by name or second line; while filtering, the Add row and notes are hidden.
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
        // Pickers filter by type unevenly (an .xml may be text or octet-stream), so offer every file
        // and let the reader reject what is not a layout.
        Custom(inGroup = true, visible = { !search.filtering }) {
            PreferenceItem(
                title = stringResource(R.string.settings_pkb_layouts_add),
                icon = ImageVector.vectorResource(R.drawable.ic_settings_m_add),
                onClick = { addLauncher.launch(arrayOf(LetterLayoutXml.MIME_TYPE, BackupBundle.MIME_TYPE, "*/*")) },
            )
        },

        // Shown while loading; left out only when the build ships no documents.
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

/** One layout row (name and binding); tapping opens its sheet. */
@Composable
private fun LayoutRow(layout: LetterLayout, summary: String, onClick: () -> Unit) {
    PreferenceItem(
        title = layout.name,
        summary = summary,
        onClick = onClick,
    )
}

private class SheetAction(
    val icon: ImageVector,
    @StringRes val label: Int,
    val onClick: () -> Unit,
)

/**
 * A layout's sheet: name, summary line, [KeypadPreview], then actions. Scrolls inside the sheet so
 * the actions stay reachable on short screens.
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
 * The keypad with each cap showing what the layout types there ([keyCapLegends]). Rows come from
 * [keypadRows]; unnamed keys are drawn empty. Always left to right, whatever the language.
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

internal class KeyCapLegends(val letter: String?, val shift: String?, val alt: String?)

/**
 * A key's cap: the letter upper-cased, the Shift legend only when it differs from that (Arabic's
 * ط types ى with Shift), and the Alt character.
 */
internal fun keyCapLegends(key: LetterLayout.Key): KeyCapLegends {
    val upper = if (key.keySpec != 0) Character.toUpperCase(key.keySpec) else 0
    return KeyCapLegends(
        letter = upper.takeIf { it != 0 }?.let(LetterLayout::string),
        shift = key.shift.takeIf { it != 0 && it != upper }?.let(LetterLayout::string),
        alt = key.alt.takeIf { it != 0 }?.let(LetterLayout::string),
    )
}

/** The Rename dialog; Save is enabled while the name passes [isValidLayoutName]. */
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

private const val KEYPAD_COLUMNS = 10

/**
 * Rows of Android key codes for [KeypadPreview]. A physical keypad reports keys by QWERTY position,
 * so rows are Q–P, A–L, Z–M; the $ key (KEYCODE_4) joins the bottom row when named, and any other
 * named key (Thai's 0 key) gets its own row in document order.
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

/** Not empty, not too long, no control characters (after trimming). */
internal fun isValidLayoutName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.length <= LetterLayoutXml.MAX_NAME_LENGTH &&
        trimmed.none { Character.isISOControl(it) }
}

/** Renames a user layout keeping its id; false for an invalid name or a failed write. */
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
 * Built-in display order: documents bound to the current keyboard's language first ([chosenId] at
 * their head), then the rest by locale-aware name, ties broken by id.
 *
 * @param keyboardLocale current keyboard locale in app spelling (`iw`, `pt_BR`) or null; matched
 *   like [LetterLayout.Binding.matchesLanguage], so `zh_TW` is not a `zh_CN` keyboard's
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
 * A row's second line, e.g. "German · QWERTZ keypad · 30 keys": languages ("All keyboards" when
 * none), keypad binding, key count. Adds a "does nothing here" line when bound to a keypad this
 * phone lacks (not when the phone's keypad is unknown).
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
    // Drop the language when it repeats the layout's name ("English" reads "26 keys").
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

/** "iw" → "Hebrew", in the phone's language. */
private fun languageDisplayName(code: String): String = try {
    ResourceLocaleUtils.getSubtypeLocaleDisplayName(code).takeIf { it.isNotBlank() } ?: code
} catch (e: RuntimeException) {
    code
}

/**
 * The current keyboard's locale in app spelling. Before the keyboard has been shown in this
 * process, [SubtypeManager] answers with the phone's language.
 */
private fun currentKeyboardLocale(): String? = try {
    LocaleUtils.localeString(SubtypeManager.getInstance().currentSubtypeLocale)
} catch (e: RuntimeException) {
    null
}

/** The current keyboard's layout set (`translit`, `qwerty`, …); a document bound to one applies only there. */
private fun currentKeyboardLayoutSet(): String? = try {
    SubtypeManager.getInstance()?.currentSubtype?.let { ResourceLocaleUtils.getKeyboardLayoutSetName(it) }
} catch (e: RuntimeException) {
    null
}

/** Adds the layouts in [uri] (a document or a backup) to the registry; false and nothing stored when none. */
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
