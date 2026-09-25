package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import dev.bbkb.ime.core.locale.multilanguage.LocaleItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import dev.bbkb.ime.core.languagepack.PackDownloadManager
import dev.bbkb.ime.core.languagepack.PackOfferSource
import dev.bbkb.ime.core.languagepack.PackState
import dev.bbkb.ime.core.languagepack.PackText
import dev.bbkb.ime.core.locale.KeyboardLanguages
import dev.bbkb.ime.core.locale.SubtypeEnabler
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.PreferenceLeadingBadge
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The consolidated Languages screen (preview, alongside the three screens it is meant to
 * replace): the keyboards the user switches between, adding a language (which fetches its
 * dictionary), a keyboard's extra prediction languages (what the multi-language wizard did), and
 * links to the switching options and the raw dictionary files.
 *
 * Built for Android 14+, where [SubtypeEnabler.setEnabledList] lets the app edit its own enabled
 * keyboards. Before that every add/remove still works but ends in Android's language list.
 * All state that says which keyboards are on is re-read from the framework ([KeyboardLanguages.read])
 * on resume and after every edit; nothing here is the source of truth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagesHubScreen(
    onNavigateToLanguageSwitching: () -> Unit,
    onNavigateToDictionaryFiles: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val direct = remember { SubtypeEnabler.canEnableDirectly() }

    var snapshot by remember { mutableStateOf<KeyboardLanguages.Snapshot?>(null) }
    var packs by remember { mutableStateOf<Packs?>(null) }
    // Dictionaries each keyboard still needs, keyed by subtype hash.
    var missing by remember { mutableStateOf<Map<Int, List<PackEntry>>>(emptyMap()) }
    var reloads by remember { mutableIntStateOf(0) }

    var showPicker by remember { mutableStateOf(false) }
    // The keyboard whose sheet is open: its extra prediction languages and its removal.
    var sheetFor by remember { mutableStateOf<KeyboardLanguages.Keyboard?>(null) }

    val downloads = remember { PackDownloadManager.getInstance(context) }
    val downloadStates by downloads.states.collectAsStateWithLifecycle()

    fun reload() { reloads++ }

    LaunchedEffect(Unit) { packs = PackOfferSource.loadPacks(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }
    // A finished download may have registered and turned on a keyboard.
    LaunchedEffect(downloadStates.values.count { it is PackState.Installed }) { reload() }

    LaunchedEffect(reloads, packs) {
        val read = withContext(Dispatchers.IO) { KeyboardLanguages.read(context) }
        snapshot = read
        val catalogue = packs ?: return@LaunchedEffect
        missing = withContext(Dispatchers.IO) {
            read.keyboards.associate { it.subtype.hashCode() to PackOfferSource.missingPacks(context, it.locales, catalogue) }
        }
    }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /**
     * [turnOn]: whether each language should also become a keyboard of its own when it installs.
     * True only when the user is adding that language; a dictionary fetched for a keyboard that
     * is already on, or as an extra prediction language, must not add a second keyboard.
     */
    fun download(entries: List<PackEntry>, turnOn: Boolean) {
        val catalogue = packs ?: return
        entries.forEach { downloads.download(catalogue, it, turnOn) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_languages_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        val current = snapshot
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (!direct) {
                item(key = "old-android") {
                    PreferenceItem(title = stringResource(R.string.languages_hub_old_android))
                }
            }
            item(key = "follow") {
                val follows = current?.followsSystem == true
                SwitchPreference(
                    title = stringResource(R.string.languages_hub_follow_system_title),
                    summary = stringResource(
                        if (follows) R.string.languages_hub_follow_system_on else R.string.languages_hub_follow_system_off
                    ),
                    checked = follows,
                    enabled = current != null,
                    onCheckedChange = { follow ->
                        if (!direct) {
                            SubtypeEnabler.openLanguageSettings(context)
                        } else scope.launch {
                            val ok = withContext(Dispatchers.IO) { KeyboardLanguages.setFollowsSystem(context, follow) }
                            if (!ok) toast(context.getString(R.string.languages_hub_failed))
                            reload()
                        }
                    },
                )
            }

            item(key = "keyboards-header") {
                PreferenceCategory(stringResource(R.string.languages_hub_keyboards_header))
                Text(
                    text = stringResource(R.string.languages_hub_keyboards_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = LocalSpacing.current.listItemPadding,
                        end = LocalSpacing.current.listItemPadding,
                        bottom = LocalSpacing.current.small,
                    ),
                )
            }
            items(current?.keyboards.orEmpty(), key = { "kb-" + it.subtype.hashCode() }) { keyboard ->
                val needed = missing[keyboard.subtype.hashCode()].orEmpty()
                KeyboardRow(
                    keyboard = keyboard,
                    dictionaryLine = dictionaryLine(context, needed, downloadStates),
                    // Offered while nothing is downloading: a Download that queues a duplicate
                    // would read as a button that did nothing.
                    downloadable = needed.filter { downloadStates[it.locale] !is PackState.Downloading },
                    onDownload = { download(it, turnOn = false) },
                    onOpen = { sheetFor = keyboard },
                )
            }
            item(key = "add") {
                PreferenceItem(
                    title = stringResource(R.string.languages_hub_add),
                    icon = Icons.Default.Add,
                    enabled = current != null,
                    onClick = { showPicker = true },
                )
            }

            item(key = "more-header") {
                PreferenceCategory(stringResource(R.string.languages_hub_more_header))
            }
            item(key = "switching") {
                PreferenceScreen(
                    title = stringResource(R.string.settings_language_switching_title),
                    summary = stringResource(R.string.settings_language_switching_summary),
                    icon = Icons.Default.SyncAlt,
                    onClick = onNavigateToLanguageSwitching,
                )
            }
            item(key = "files") {
                PreferenceScreen(
                    title = stringResource(R.string.settings_language_packs_title),
                    summary = stringResource(R.string.settings_language_packs_summary),
                    icon = Icons.Default.Archive,
                    onClick = onNavigateToDictionaryFiles,
                )
            }
        }
    }

    sheetFor?.let { keyboard ->
        KeyboardSheet(
            keyboard = keyboard,
            canRemove = (snapshot?.keyboards?.size ?: 0) > 1,
            choices = remember(keyboard) { KeyboardLanguages.extraChoices(context, keyboard) },
            onSave = { extras ->
                sheetFor = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) { KeyboardLanguages.setExtras(context, keyboard, extras) }
                    when (result) {
                        KeyboardLanguages.ExtrasResult.DONE -> Unit
                        KeyboardLanguages.ExtrasResult.NEEDS_SYSTEM_SETTINGS -> {
                            toast(context.getString(R.string.languages_hub_turn_on_in_settings, keyboard.name))
                            SubtypeEnabler.openLanguageSettings(context)
                        }
                        KeyboardLanguages.ExtrasResult.FAILED -> toast(context.getString(R.string.languages_hub_failed))
                    }
                    reload()
                    // Extra languages are only as good as their dictionaries: fetch what is missing.
                    val catalogue = packs
                    if (catalogue != null && extras.isNotEmpty()) {
                        val needed = withContext(Dispatchers.IO) {
                            PackOfferSource.missingPacks(context, extras.map { it.first }, catalogue)
                        }
                        download(needed, turnOn = false)
                    }
                }
            },
            onRemove = {
                sheetFor = null
                if (!direct) {
                    toast(context.getString(R.string.languages_hub_turn_off_in_settings, keyboard.name))
                    SubtypeEnabler.openLanguageSettings(context)
                } else scope.launch {
                    val ok = withContext(Dispatchers.IO) { KeyboardLanguages.remove(context, keyboard) }
                    if (!ok) toast(context.getString(R.string.languages_hub_failed))
                    reload()
                }
            },
            onDismiss = { sheetFor = null },
        )
    }

    val pickerSnapshot = snapshot
    if (showPicker && pickerSnapshot != null) {
        LanguagePickerDialog(
            loadCandidates = {
                val catalogue = packs
                KeyboardLanguages.candidates(context, pickerSnapshot, catalogue) { locale ->
                    catalogue?.let { PackOfferSource.missingPacks(context, listOf(locale), it).firstOrNull() }
                }
            },
            onPick = { candidate ->
                showPicker = false
                scope.launch {
                    val subtype = candidate.subtype
                    when {
                        subtype == null -> {
                            // No keyboard until the pack installs; the installer registers it and,
                            // on Android 14+, turns it on.
                            candidate.pack?.let { download(listOf(it), turnOn = true) }
                            toast(context.getString(R.string.languages_hub_downloading_then_on, candidate.name))
                        }
                        direct -> {
                            val ok = withContext(Dispatchers.IO) { KeyboardLanguages.add(context, subtype) }
                            // Already on: the dictionary must not turn on a second keyboard.
                            candidate.pack?.let { download(listOf(it), turnOn = false) }
                            toast(context.getString(if (ok) R.string.languages_hub_added else R.string.languages_hub_failed, candidate.name))
                        }
                        else -> {
                            candidate.pack?.let { download(listOf(it), turnOn = false) }
                            toast(context.getString(R.string.languages_hub_turn_on_in_settings, candidate.name))
                            SubtypeEnabler.openLanguageSettings(context)
                        }
                    }
                    reload()
                }
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** The dictionary status for a keyboard's row, or null when every dictionary it needs is here. */
private fun dictionaryLine(
    context: Context,
    missing: List<PackEntry>,
    states: Map<String, PackState>,
): String? {
    if (missing.isEmpty()) return null
    missing.forEach { entry ->
        val state = states[entry.locale]
        if (state is PackState.Downloading) {
            return context.getString(R.string.languages_hub_dictionary_downloading, (state.progress * 100).toInt())
        }
    }
    if (missing.any { states[it.locale] is PackState.Failed }) {
        return context.getString(R.string.languages_hub_dictionary_failed)
    }
    return context.getString(R.string.languages_hub_dictionary_missing)
}

/**
 * One keyboard, as the navigation row it is: tapping opens [KeyboardEditorScreen] for its extra
 * prediction languages and its removal. The trailing chevron is the Material signal for that. A
 * missing dictionary gets its own Download button here, since that is the row's primary call to
 * action rather than a secondary one.
 */
@Composable
private fun KeyboardRow(
    keyboard: KeyboardLanguages.Keyboard,
    dictionaryLine: String?,
    downloadable: List<PackEntry>,
    onDownload: (List<PackEntry>) -> Unit,
    onOpen: () -> Unit,
) {
    val extras = keyboard.extras
    val extrasLine = extras.takeIf { it.isNotEmpty() }?.let {
        stringResource(R.string.languages_hub_also_predicts, it.joinToString(", ") { item -> item.toString() })
    }
    val summary = listOfNotNull(extrasLine, dictionaryLine).joinToString("\n").ifEmpty { null }
    PreferenceItem(
        title = keyboard.name,
        summary = summary,
        leading = { PreferenceLeadingBadge(keyboard.locale.substringBefore('_').uppercase(Locale.ROOT)) },
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloadable.isNotEmpty()) {
                    TextButton(onClick = { onDownload(downloadable) }) {
                        Text(stringResource(R.string.language_packs_download))
                    }
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * Everything about one keyboard, as a modal bottom sheet over the list it was tapped in: its
 * extra prediction languages (Latin-script keyboards only) and, at the bottom, its removal.
 *
 * A sheet rather than a page or a dialog: it is the Material surface for a short set of options
 * about the item just tapped, it keeps the list in view, and a keyboard with nothing to combine
 * gets a small sheet instead of an empty page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardSheet(
    keyboard: KeyboardLanguages.Keyboard,
    canRemove: Boolean,
    choices: List<LocaleItem>,
    onSave: (List<LocaleItem>) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = remember(keyboard) { keyboard.extras.map { it.first as String }.toSet() }
    var selected by remember(keyboard) { mutableStateOf(initial) }
    var query by remember(keyboard) { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val changed = selected != initial

    // Chosen-when-opened first, then the rest, each alphabetical. Grouped by the opening state
    // so a row does not jump to another group under the finger that just ticked it.
    val (chosen, available) = remember(choices, initial) {
        choices.sortedBy { it.toString() }.partition { it.first in initial }
    }
    fun matches(item: LocaleItem) = query.isBlank() || item.toString().contains(query.trim(), ignoreCase = true)
    val shownChosen = chosen.filter(::matches)
    val shownAvailable = available.filter(::matches)

    // The sheet is as tall as its content, capped so it never outgrows the screen: on the KEY2's
    // short display a fixed-height list pushed the Remove row off the bottom. The list takes
    // whatever height is left within that cap and scrolls inside it.
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
            // Title row: the keyboard's name, and Done when there is something to apply.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = spacing.listItemPadding, end = spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = keyboard.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (keyboard.canHaveExtras) {
                    TextButton(
                        onClick = { onSave(choices.filter { it.first in selected }.map { LocaleItem(it.first) }) },
                        enabled = changed,
                    ) { Text(stringResource(R.string.languages_hub_sheet_done)) }
                }
            }

            if (keyboard.canHaveExtras) {
                PreferenceCategory(stringResource(R.string.languages_hub_sheet_extras_header))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.languages_hub_search)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.languages_hub_sheet_clear_search))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.listItemPadding, vertical = spacing.small),
                )
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(shownChosen + shownAvailable, key = { it.first }) { item ->
                        if (item === shownAvailable.firstOrNull() && shownChosen.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = spacing.listItemPadding))
                        }
                        val checked = item.first in selected
                        ListItem(
                            headlineContent = { Text(item.toString()) },
                            leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.toggleable(value = checked, role = Role.Checkbox) { on ->
                                selected = if (on) selected + item.first else selected - item.first
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.languages_hub_options_extras_latin_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = spacing.listItemPadding, vertical = spacing.small),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = spacing.small))
            // The destructive action last, as a list row in the error colour. Disabled for the
            // last keyboard: an empty list would mean "follow the phone".
            ListItem(
                headlineContent = { Text(stringResource(R.string.languages_hub_sheet_remove)) },
                supportingContent = if (canRemove) null else {
                    { Text(stringResource(R.string.languages_hub_last_keyboard)) }
                },
                leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    leadingIconColor = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                ),
                modifier = Modifier.clickable(enabled = canRemove) { confirmRemove = true },
            )
            Spacer(modifier = Modifier.height(spacing.large))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.languages_hub_sheet_remove_title, keyboard.name)) },
            text = { Text(stringResource(R.string.languages_hub_sheet_remove_message)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text(stringResource(R.string.languages_hub_sheet_remove_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun LanguagePickerDialog(
    loadCandidates: () -> List<KeyboardLanguages.Candidate>,
    onPick: (KeyboardLanguages.Candidate) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    var candidates by remember { mutableStateOf<List<KeyboardLanguages.Candidate>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { candidates = withContext(Dispatchers.IO) { loadCandidates() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = spacing.listItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.languages_hub_search)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                val shown = candidates.orEmpty().filter {
                    query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(shown, key = { (it.subtype?.hashCode() ?: 0).toString() + it.locale }) { candidate ->
                        PreferenceItem(
                            title = candidate.name,
                            summary = candidate.pack?.let {
                                stringResource(R.string.languages_hub_downloads, PackText.size(context, it.size))
                            },
                            leading = {
                                PreferenceLeadingBadge(candidate.locale.substringBefore('_').uppercase(Locale.ROOT))
                            },
                            onClick = { onPick(candidate) },
                        )
                    }
                }
            }
        }
    }
}
