package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
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
import dev.bbkb.ime.core.settings.ui.PreferenceDeleteButton
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * chosen per language on the Language screen ([LetterLayoutChoice]); here they are managed. Share
 * hands the stored XML file to the share sheet, the way the device profile builder shares a
 * config; delete removes it.
 *
 * **Built-in** holds the documents under `assets/letter_layouts/` that are for the physical keys
 * (the on-screen-only ones, Colemak and Dvorak, are chosen per keyboard on the Language screen).
 * They are read-only and are not picked here: which one a keyboard uses follows its language.
 * What a row offers is "Save as file", which writes the document wherever the user chooses — the
 * way to start a layout of one's own is to save the nearest built-in, edit it, and add it from
 * the file. The current keyboard's language comes first, the document it would use at the top,
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

    SettingsScreenHost(R.string.settings_pkb_layouts_title, onNavigateBack, listOf(
        // ── Custom: the user's layouts, chosen per language on the Language screen ──
        Category(R.string.settings_pkb_layouts_category_custom),
        Custom(inGroup = true) {
            Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.groupGap)) {
                customLayouts.orEmpty().forEach { layout ->
                    key(layout.id) {
                        CustomLayoutRow(
                            title = layout.name,
                            summary = layoutSummary(context, layout, phoneKeypad, phoneKeypadType),
                            onShare = { share(context, registry, layout) },
                            onDelete = { deleteTarget = layout },
                        )
                    }
                }
            }
        },
        Custom(visible = { customLayouts?.isEmpty() == true }) {
            PreferenceInfo(text = stringResource(R.string.settings_pkb_layouts_empty))
        },
        // Where a layout comes from: a file. Pickers filter by type unevenly (a file manager may
        // call an .xml document text or octet-stream), so every file is offered and the reader
        // says no to what is not a layout.
        Custom(inGroup = true) {
            PreferenceItem(
                title = stringResource(R.string.settings_pkb_layouts_add),
                icon = Icons.Outlined.Add,
                onClick = { addLauncher.launch(arrayOf(LetterLayoutXml.MIME_TYPE, BackupBundle.MIME_TYPE, "*/*")) },
            )
        },

        // ── Built-in: read-only, each one a template to save as a file ──
        // Shown while the list loads; left out only when the build ships no documents at all.
        Category(R.string.settings_pkb_layouts_category_builtin, visible = { builtInLayouts?.isEmpty() != true }),
        Custom(visible = { builtInLayouts?.isEmpty() != true }) {
            PreferenceInfo(text = stringResource(R.string.settings_pkb_layouts_builtin_note))
        },
        Custom(inGroup = true) {
            builtInLayouts.orEmpty().forEach { layout ->
                key(layout.id) {
                    BuiltInLayoutRow(
                        layout = layout,
                        summary = layoutSummary(context, layout, phoneKeypad, phoneKeypadType),
                        onSave = {
                            savingId = layout.id
                            saveLauncher.launch(layout.id + LetterLayoutXml.FILE_SUFFIX)
                        },
                    )
                }
            }
        },
    ))

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

/** One imported layout: its name and binding, share and delete in the trailing slot. */
@Composable
private fun CustomLayoutRow(
    title: String,
    summary: String,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    PreferenceItem(
        title = title,
        summary = summary,
        trailing = {
            Row {
                RowIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = stringResource(R.string.settings_pkb_layouts_share, title),
                    onClick = onShare,
                )
                PreferenceDeleteButton(
                    contentDescription = stringResource(R.string.settings_pkb_layouts_delete, title),
                    onClick = onDelete,
                )
            }
        },
    )
}

/**
 * A built-in layout. There is nothing to pick, so the row itself does nothing and its name and
 * summary read as one item; the save button is a stop of its own. The leading space is kept so
 * the names line up with the custom rows above, which carry a radio there.
 */
@Composable
private fun BuiltInLayoutRow(layout: LetterLayout, summary: String, onSave: () -> Unit) {
    PreferenceItem(
        title = layout.name,
        summary = summary,
        iconSpaceReserved = true,
        modifier = Modifier.semantics(mergeDescendants = true) {},
        trailing = {
            RowIconButton(
                icon = Icons.Outlined.SaveAlt,
                contentDescription = stringResource(R.string.settings_pkb_layouts_save, layout.name),
                onClick = onSave,
            )
        },
    )
}

/**
 * A trailing icon button laid out at the glyph's height, as [PreferenceDeleteButton] is, so a row
 * holding one is no taller than a row holding none, and share, save and delete line up. The
 * button keeps its 48dp touch target, drawn unbounded around the glyph.
 */
@Composable
private fun RowIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(LocalSpacing.current.iconSize),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.wrapContentHeight(unbounded = true),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 * (by name; "All keyboards" when none), the keypad shape or type it is bound to, and how many keys
 * it names. When it is bound to a keypad this phone does not have, a second line says it does
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
    val details = context.getString(R.string.settings_pkb_layouts_details, parts.joinToString(" · "), layout.keyCount)
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
