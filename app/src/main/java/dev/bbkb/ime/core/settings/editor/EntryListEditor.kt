package dev.bbkb.ime.core.settings.editor

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.LocalInGroup
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.groupOuterCorners
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.RoundCheck
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.personaldictionary.util.LocaleUtil
import dev.bbkb.ime.BuildConfig
import kotlinx.coroutines.launch

/**
 * What one dictionary-entry editor is, as data.
 *
 * `TextShortcutsScreen` and `UserDictionaryScreen` were the same three hundred lines twice over:
 * the same load-and-recover sequence, the same add/update/delete trio, the same top bar with its
 * counter and collapsing search field, the same grouped list under the same byte-identical language
 * header, the same loading spinner and empty state. Everything that genuinely differed between them
 * is in this class, and there turns out not to be much of it.
 *
 * Most of the user-facing wording is derived rather than listed, because in both editors it is the
 * same sentence about a different noun: [noun] "Shortcut" gives "Shortcut added", "Failed to add
 * shortcut" and "Add shortcut", and [plural] "shortcuts" gives "3 shortcuts" and
 * "Search shortcuts". The one phrase that does not follow — the shortcuts editor says "Failed to
 * load shortcuts" while the dictionary editor says "Failed to load dictionary" — is spelled out in
 * [loadFailurePrefix]. `EntryListSpecTest` pins every derived string.
 */
@Immutable
internal class EntryListSpec(
    /** Top-app-bar title. */
    val title: String,
    /** Capitalised singular, as it appears at the start of a sentence: "Shortcut", "Word". */
    val noun: String,
    /** Lower-case plural, as it appears mid-sentence: "shortcuts", "words". */
    val plural: String,
    /** The one message that is not derived from [noun]; see the class comment. */
    val loadFailurePrefix: String,
    val emptyIcon: ImageVector,
    val emptyTitle: String,
    val emptyHint: String,
    /** Which of the repository's entries this editor owns. The two editors partition the store. */
    val belongsHere: (DictionaryEntry) -> Boolean,
    /** De-duplication, list and selection key. Two entries with the same identity are one row. */
    val identity: (DictionaryEntry) -> String,
    /** Secondary sort key; entries are always grouped by locale first. */
    val sortKey: (DictionaryEntry) -> Comparable<*>?,
    /** Search predicate. */
    val matches: (DictionaryEntry, String) -> Boolean,
    /** What an entry is called at the head of its sheet: the word, or the shortcut. */
    val label: (DictionaryEntry) -> String,
    /** Refused before the store is touched, reported as a snackbar. Null means "no objection". */
    val validate: (DictionaryEntry) -> String? = { null },
    val logTag: String,
) {
    private val lowerNoun get() = noun.lowercase()

    /** "1 word", "3 words": the line over the list, and the subject of the delete snackbar. */
    fun counter(size: Int) = if (size == 1) "1 $lowerNoun" else "$size $plural"
    /** The same line while searching: how many of the whole list the query kept. */
    fun searchCounter(matching: Int, total: Int) = "$matching of ${counter(total)}"
    val searchHint get() = "Search $plural"
    val addDescription get() = "Add $lowerNoun"
    val editDescription get() = "Edit $lowerNoun"

    val added get() = "$noun added"
    val addFailed get() = "Failed to add $lowerNoun"
    val updated get() = "$noun updated"
    val updateFailed get() = "Failed to update $lowerNoun"
    fun deleted(count: Int) = "Deleted ${counter(count)}"
    val deleteFailed get() = "Failed to delete $lowerNoun"
    val restoreFailed get() = "Couldn't bring back every $lowerNoun"

    fun loadFailure(message: String?) = "$loadFailurePrefix: $message"
}

/**
 * What [EntryListEditorScreen] hands to its `dialogs` slot: which dialog should be open, the
 * locales an entry can be filed under, and the two writes a dialog makes. Deleting is not among
 * them: it lives in the entry's sheet and in selection, both of which the editor draws itself.
 *
 * The dialogs stay with their screens rather than being unified here. They are genuinely different
 * — the shortcuts one is a full-screen form with a macro row and a preview, the dictionary one has
 * a word and a language — and, decisively, neither can be tested: under this project's
 * Robolectric/Compose combination a Material 3 text field inside a dialog never reaches an idle
 * composition. Merging code that cannot be observed is how a refactor ships a bug.
 */
internal class EntryEditorHost(
    val locales: List<String>,
    val adding: Boolean,
    val editing: DictionaryEntry?,
    val dismissAdd: () -> Unit,
    val dismissEdit: () -> Unit,
    val add: (DictionaryEntry) -> Unit,
    val update: (DictionaryEntry, DictionaryEntry) -> Unit,
)

/**
 * The body both dictionary editors share, laid out the way Learned words is: a count line, the
 * entries as one grouped list (cut by language only when there is more than one), a tap opening
 * the entry's sheet, a long press starting selection, deletes that offer Undo instead of asking
 * first, and search in the top bar.
 *
 * @param rowContent what one entry shows inside its row; the two editors show different things.
 * @param sheetSummary the line under the entry's name in its sheet, given the language's name.
 * @param sheetContent anything the sheet shows between that line and its actions.
 * @param menuItems the overflow menu's items, given a way to close the menu; no menu when null.
 * @param dialogs the screen's own add/edit dialogs, driven from [EntryEditorHost].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EntryListEditorScreen(
    spec: EntryListSpec,
    store: DictionaryEntryStore,
    onNavigateBack: () -> Unit,
    rowContent: @Composable (DictionaryEntry) -> Unit,
    sheetSummary: (DictionaryEntry, String) -> String = { _, language -> language },
    sheetContent: @Composable (DictionaryEntry) -> Unit = {},
    menuItems: (@Composable (close: () -> Unit) -> Unit)? = null,
    dialogs: @Composable (EntryEditorHost) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var isInitialized by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var locales by remember { mutableStateOf<List<String>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    // The entry whose sheet is open.
    var sheetEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var selecting by remember { mutableStateOf(false) }
    // Identities of the selected entries.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                // Initialize if needed
                if (!isInitialized) {
                    when (val initResult = store.initialize()) {
                        is DictionaryRepository.InitResult.Success -> {
                            isInitialized = true
                        }
                        is DictionaryRepository.InitResult.SuccessAfterRecovery -> {
                            isInitialized = true
                            // Its own coroutine: showSnackbar suspends until the snackbar is
                            // dismissed, so awaiting it here held the list empty (and the counter
                            // at zero) for the whole notice — right when the user is checking
                            // whether their entries survived.
                            launch {
                                snackbarHostState.showSnackbar(
                                    message = "Dictionary was recovered from corrupted state",
                                    duration = SnackbarDuration.Long
                                )
                            }
                        }
                        is DictionaryRepository.InitResult.Failure -> {
                            error = initResult.message
                            isLoading = false
                            return@launch
                        }
                    }
                }

                locales = store.getLocales()

                val allEntries = mutableListOf<DictionaryEntry>()
                for (locale in locales) {
                    allEntries.addAll(store.getAllEntries(locale).filter(spec.belongsHere))
                }

                entries = allEntries.distinctBy(spec.identity)
                    .sortedWith(compareBy({ it.locale }, spec.sortKey))

            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error loading data", e)
                error = spec.loadFailure(e.message)
            }
            isLoading = false
        }
    }

    /**
     * The shape an add or an edit takes: validate, call the store, reload, say what happened.
     */
    fun mutate(
        entry: DictionaryEntry,
        done: String,
        failed: String,
        verb: String,
        write: suspend () -> Result<Unit>
    ) {
        scope.launch {
            try {
                spec.validate(entry)?.let {
                    snackbarHostState.showSnackbar(it)
                    return@launch
                }

                if (write().isSuccess) {
                    loadData()
                    snackbarHostState.showSnackbar(done)
                } else {
                    snackbarHostState.showSnackbar(failed)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error $verb ${spec.noun.lowercase()}", e)
                snackbarHostState.showSnackbar("Error: ${e.message}")
            }
        }
    }

    fun endSelection() {
        selecting = false
        selected = emptySet()
    }

    /**
     * Deletes without asking, then offers Undo, which adds the entries back as they were (word,
     * shortcut, language and fixed case are all on the entry). Learned words deletes the same way.
     */
    fun delete(toDelete: List<DictionaryEntry>) {
        endSelection()
        scope.launch {
            val gone = toDelete.filter { entry ->
                try {
                    store.deleteEntry(entry).isSuccess
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error deleting ${spec.noun.lowercase()}", e)
                    false
                }
            }
            loadData()
            if (gone.isEmpty()) {
                snackbarHostState.showSnackbar(spec.deleteFailed)
                return@launch
            }
            val result = snackbarHostState.showSnackbar(
                message = spec.deleted(gone.size),
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                val back = gone.count { entry ->
                    try {
                        store.addEntry(entry).isSuccess
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error restoring ${spec.noun.lowercase()}", e)
                        false
                    }
                }
                loadData()
                if (back < gone.size) snackbarHostState.showSnackbar(spec.restoreFailed)
            }
        }
    }

    LaunchedEffect(Unit) {
        loadData()
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    // The system back leaves selection first, then search, as the top bar's own icon does.
    BackHandler(enabled = selecting || searching) {
        if (selecting) endSelection() else {
            searching = false
            searchQuery = ""
        }
    }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { spec.matches(it, searchQuery) }
        }
    }

    val groupedEntries = remember(filteredEntries) {
        filteredEntries.groupBy { it.locale }
    }

    SettingsScaffold(
        title = if (selecting) "${selected.size} selected" else spec.title,
        // Selection is a contextual bar: a close cross cancels it, as on Learned words. Search
        // keeps the arrow, which leaves the search rather than the screen.
        navigationIcon = if (selecting) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
        navigationContentDescription = when {
            selecting -> "Cancel selection"
            searching -> "Close search"
            else -> "Back"
        },
        onNavigateBack = {
            when {
                selecting -> endSelection()
                searching -> {
                    searching = false
                    searchQuery = ""
                }
                else -> onNavigateBack()
            }
        },
        searchField = if (searching && !selecting) {
            { EntrySearchField(query = searchQuery, onQueryChange = { searchQuery = it }, hint = spec.searchHint) }
        } else null,
        actions = {
            if (selecting) {
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { delete(entries.filter { spec.identity(it) in selected }) }) {
                        Icon(Icons.Outlined.DeleteOutline, "Delete selected")
                    }
                }
            } else {
                IconButton(onClick = { searching = true }) {
                    Icon(Icons.Outlined.Search, "Search")
                }
                if (menuItems != null) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, "More options")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            menuItems { showMenu = false }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The editor's one creative action, named — "Add shortcut", "Add word". Selection has
        // nothing to add, so the button steps aside for it.
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(spec.addDescription.replaceFirstChar { it.uppercase() }) }
                )
            }
        }
    ) { paddingValues ->
        val spacing = LocalSpacing.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                filteredEntries.isEmpty() -> {
                    EntryListEmptyState(spec, hasSearchQuery = searchQuery.isNotEmpty())
                }
                else -> {
                    // The entries are grouped as Learned words groups its words: the rows paint
                    // themselves as cards, a language's first and last carry the group's outer
                    // corners, and the rows take the group inset themselves so the count line and
                    // the language headers keep their page-level alignment. A language header
                    // only appears when the entries span more than one language.
                    val showHeaders = groupedEntries.size > 1
                    CompositionLocalProvider(LocalInGroup provides true) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(spacing.groupGap)
                        ) {
                            item(key = "count") {
                                Text(
                                    // While searching, count what is listed against the whole.
                                    text = if (searchQuery.isBlank()) spec.counter(entries.size)
                                    else spec.searchCounter(filteredEntries.size, entries.size),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = spacing.categoryStartPadding,
                                        top = 8.dp,
                                        end = 16.dp,
                                        bottom = 8.dp
                                    )
                                )
                            }

                            groupedEntries.forEach { (locale, localeEntries) ->
                                if (showHeaders) {
                                    item(key = "header_$locale") {
                                        PreferenceCategory(
                                            title = LocaleUtil.getDisplayNameForDictionary(context, locale)
                                        )
                                    }
                                }

                                itemsIndexed(
                                    items = localeEntries,
                                    key = { _, entry -> spec.identity(entry) }
                                ) { index, entry ->
                                    val id = spec.identity(entry)
                                    val isSelected = id in selected
                                    Box(modifier = Modifier.padding(horizontal = spacing.groupInset)) {
                                        EntryRow(
                                            selected = isSelected,
                                            selecting = selecting,
                                            groupEdge = groupEdge(index, localeEntries.size),
                                            onClick = {
                                                if (selecting) {
                                                    selected = if (isSelected) selected - id else selected + id
                                                } else {
                                                    sheetEntry = entry
                                                }
                                            },
                                            onLongClick = {
                                                if (selecting) {
                                                    selected = if (isSelected) selected - id else selected + id
                                                } else {
                                                    selecting = true
                                                    selected = setOf(id)
                                                }
                                            },
                                        ) { rowContent(entry) }
                                    }
                                }
                            }

                            // Keeps the last row clear of the extended FAB (56dp + 16dp margin + 16dp
                            // breathing room), as Language packs does.
                            item {
                                Spacer(modifier = Modifier.height(88.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // The tapped entry's sheet.
    sheetEntry?.let { entry ->
        EntrySheet(
            title = spec.label(entry),
            summary = sheetSummary(entry, LocaleUtil.getDisplayNameForDictionary(context, entry.locale)),
            editLabel = spec.editDescription.replaceFirstChar { it.uppercase() },
            onEdit = {
                sheetEntry = null
                editingEntry = entry
            },
            onDelete = {
                sheetEntry = null
                delete(listOf(entry))
            },
            onDismiss = { sheetEntry = null },
        ) { sheetContent(entry) }
    }

    dialogs(
        EntryEditorHost(
            locales = locales,
            adding = showAddDialog,
            editing = editingEntry,
            dismissAdd = { showAddDialog = false },
            dismissEdit = { editingEntry = null },
            add = { entry ->
                mutate(entry, spec.added, spec.addFailed, "adding") { store.addEntry(entry) }
            },
            update = { oldEntry, newEntry ->
                mutate(newEntry, spec.updated, spec.updateFailed, "updating") {
                    store.updateEntry(oldEntry, newEntry)
                }
            },
        )
    )
}

/**
 * One entry, laid out like Learned words' rows (which are laid out like [PreferenceItem]): the
 * group card, the selected tint, and the round check that slides in from the start edge while
 * selecting. A tap opens the entry's sheet; a long press starts selecting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    selected: Boolean,
    selecting: Boolean,
    groupEdge: GroupEdge,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .groupOuterCorners(groupEdge)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = LocalShapes.current.row,
        color = if (selected) colors.secondaryContainer else LocalSettingsColors.current.card,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = spacing.oneLineListItemHeight)
                .padding(horizontal = spacing.listItemPadding, vertical = spacing.listItemVerticalPadding)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = selecting,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                Box(
                    modifier = Modifier.padding(end = spacing.iconTextGap).size(spacing.iconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    RoundCheck(selected = selected, modifier = Modifier.size(spacing.iconSize))
                }
            }
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

/**
 * One entry's sheet, built as Learned words builds a word's: its name, a line about it, anything
 * the screen adds, then what can be done with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    title: String,
    summary: String,
    editLabel: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding, vertical = 8.dp),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding),
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
            PreferenceItem(title = editLabel, icon = Icons.Outlined.Edit, onClick = onEdit)
            PreferenceItem(title = "Delete", icon = Icons.Outlined.DeleteOutline, onClick = onDelete)
        }
    }
}

/**
 * The search field the top bar holds while searching: a pill like the one on the settings home
 * page, focused as it opens, with a cross to empty it once there is something to empty.
 */
@Composable
private fun RowScope.EntrySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
            .weight(1f)
            .padding(end = 12.dp)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .background(colors.surfaceContainerHighest, CircleShape)
                    .padding(start = 20.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Outlined.Close, "Clear search")
                    }
                }
            }
        },
    )
}

@Composable
private fun EntryListEmptyState(
    spec: EntryListSpec,
    hasSearchQuery: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            spec.emptyIcon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (hasSearchQuery) "No matches found" else spec.emptyTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasSearchQuery) {
                "Try a different search term"
            } else {
                spec.emptyHint
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
