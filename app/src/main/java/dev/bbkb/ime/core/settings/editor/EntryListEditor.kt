package dev.bbkb.ime.core.settings.editor

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
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
import dev.bbkb.ime.core.settings.ui.TopBarSearchField
import dev.bbkb.ime.personaldictionary.util.LocaleUtil
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import kotlinx.coroutines.launch

/**
 * What differs between the Text shortcuts and User dictionary editors, as data. Each sentence is
 * its own string and every count a plural, since other languages inflect and agree with the noun.
 */
@Immutable
internal class EntryListSpec(
    @StringRes val title: Int,
    /** "3 words": the line over the list. */
    @PluralsRes val count: Int,
    /** "2 of 6 words": the quantity is the total, the first argument the matches. */
    @PluralsRes val searchCount: Int,
    @StringRes val searchHint: Int,
    /** The add button's label: "Add word". */
    @StringRes val add: Int,
    /** The sheet's edit action: "Edit word". */
    @StringRes val edit: Int,
    @StringRes val added: Int,
    @StringRes val addFailed: Int,
    @StringRes val updated: Int,
    @StringRes val updateFailed: Int,
    /** "Deleted 2 words", over the Undo action. */
    @PluralsRes val deleted: Int,
    @StringRes val deleteFailed: Int,
    @StringRes val restoreFailed: Int,
    /** "Failed to load dictionary: %1$s", given the exception's message. */
    @StringRes val loadFailed: Int,
    @DrawableRes val emptyIcon: Int,
    @StringRes val emptyTitle: Int,
    @StringRes val emptyHint: Int,
    /** Which repository entries this editor owns; the two editors partition the store. */
    val belongsHere: (DictionaryEntry) -> Boolean,
    /** De-duplication, list and selection key. */
    val identity: (DictionaryEntry) -> String,
    /** Secondary sort key; entries are always grouped by locale first. */
    val sortKey: (DictionaryEntry) -> Comparable<*>?,
    val matches: (DictionaryEntry, String) -> Boolean,
    /** What an entry is called at the head of its sheet: the word, or the shortcut. */
    val label: (DictionaryEntry) -> String,
    /** The objection's string resource (shown as a snackbar before the store is touched), or null. */
    val validate: (DictionaryEntry) -> Int? = { null },
    val logTag: String,
)

/**
 * What [EntryListEditorScreen] hands its `dialogs` slot: the open dialog, the locales, and the two
 * writes. The dialogs stay with their screens: they differ, and under Robolectric a Material 3 text
 * field in a dialog never reaches idle, so a merge could not be tested.
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
 * The body both dictionary editors share, laid out like Learned words: count line, grouped list,
 * tap for the sheet, long press to select, deletes with Undo, search in the top bar.
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
    sheetSummary: @Composable (DictionaryEntry, String) -> String = { _, language -> language },
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
    var sheetEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadData() {
        scope.launch {
            isLoading = true
            try {
                if (!isInitialized) {
                    when (val initResult = store.initialize()) {
                        is DictionaryRepository.InitResult.Success -> {
                            isInitialized = true
                        }
                        is DictionaryRepository.InitResult.SuccessAfterRecovery -> {
                            isInitialized = true
                            // Its own coroutine: showSnackbar suspends until dismissed, which
                            // would hold the list empty for the whole notice.
                            launch {
                                snackbarHostState.showSnackbar(
                                    message = context.getString(R.string.settings_dict_recovered),
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
                error = context.getString(spec.loadFailed, e.message)
            }
            isLoading = false
        }
    }

    /** An add or edit: validate, call the store, reload, report. */
    fun mutate(
        entry: DictionaryEntry,
        @StringRes done: Int,
        @StringRes failed: Int,
        verb: String,
        write: suspend () -> Result<Unit>
    ) {
        scope.launch {
            try {
                spec.validate(entry)?.let {
                    snackbarHostState.showSnackbar(context.getString(it))
                    return@launch
                }

                val result = write()
                if (result.isSuccess) {
                    loadData()
                    snackbarHostState.showSnackbar(context.getString(done))
                } else {
                    snackbarHostState.showSnackbar(failureMessage(context, result.exceptionOrNull(), failed))
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error $verb", e)
                snackbarHostState.showSnackbar(context.getString(R.string.settings_common_error, e.message))
            }
        }
    }

    fun endSelection() {
        selecting = false
        selected = emptySet()
    }

    /** Deletes without asking, then offers Undo, which adds the entries back as they were. */
    fun delete(toDelete: List<DictionaryEntry>) {
        endSelection()
        scope.launch {
            val gone = toDelete.filter { entry ->
                try {
                    store.deleteEntry(entry).isSuccess
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error deleting", e)
                    false
                }
            }
            loadData()
            if (gone.isEmpty()) {
                snackbarHostState.showSnackbar(context.getString(spec.deleteFailed))
                return@launch
            }
            val result = snackbarHostState.showSnackbar(
                message = context.resources.getQuantityString(spec.deleted, gone.size, gone.size),
                actionLabel = context.getString(R.string.ckb_action_undo),
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                val back = gone.count { entry ->
                    try {
                        store.addEntry(entry).isSuccess
                    } catch (e: Exception) {
                        if (BuildConfig.DEBUG) Log.e(spec.logTag, "Error restoring", e)
                        false
                    }
                }
                loadData()
                if (back < gone.size) snackbarHostState.showSnackbar(context.getString(spec.restoreFailed))
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
        title = if (selecting) pluralStringResource(R.plurals.settings_common_selected_count, selected.size, selected.size)
        else stringResource(spec.title),
        // Selection cancels with a close cross; search keeps the arrow, which leaves the search.
        navigationIcon = ImageVector.vectorResource(if (selecting) R.drawable.ic_settings_m_close else R.drawable.ic_settings_m_arrow_back),
        navigationContentDescription = when {
            selecting -> stringResource(R.string.settings_common_cancel_selection)
            searching -> stringResource(R.string.settings_common_close_search)
            else -> stringResource(R.string.settings_common_back)
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
            { TopBarSearchField(query = searchQuery, onQueryChange = { searchQuery = it }, hint = stringResource(spec.searchHint)) }
        } else null,
        actions = {
            if (selecting) {
                if (selected.isNotEmpty()) {
                    IconButton(onClick = { delete(entries.filter { spec.identity(it) in selected }) }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_delete_outline), stringResource(R.string.settings_common_delete_selected))
                    }
                }
            } else {
                IconButton(onClick = { searching = true }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_search), stringResource(R.string.settings_common_search))
                }
                if (menuItems != null) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_more_vert), stringResource(R.string.clipboard_more_options))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            menuItems { showMenu = false }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Hidden while selecting.
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_add), contentDescription = null) },
                    text = { Text(stringResource(spec.add)) }
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
                    // Rows paint themselves as cards and take the group inset, so the count line
                    // and headers keep page alignment. Headers only when there are several languages.
                    val showHeaders = groupedEntries.size > 1
                    CompositionLocalProvider(LocalInGroup provides true) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(spacing.groupGap)
                        ) {
                            item(key = "count") {
                                Text(
                                    // While searching, count what is listed against the whole.
                                    text = if (searchQuery.isBlank()) {
                                        pluralStringResource(spec.count, entries.size, entries.size)
                                    } else {
                                        pluralStringResource(spec.searchCount, entries.size, filteredEntries.size, entries.size)
                                    },
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

                            // Clears the extended FAB: 56dp + 16dp margin + 16dp breathing room.
                            item {
                                Spacer(modifier = Modifier.height(88.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    sheetEntry?.let { entry ->
        EntrySheet(
            title = spec.label(entry),
            summary = sheetSummary(entry, LocaleUtil.getDisplayNameForDictionary(context, entry.locale)),
            editLabel = stringResource(spec.edit),
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

/** One entry row: group card, selected tint, and a round check that slides in while selecting. */
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

/** One entry's sheet: its name, a line about it, the screen's extras, then its actions. */
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
            PreferenceItem(title = editLabel, icon = ImageVector.vectorResource(R.drawable.ic_settings_m_edit), onClick = onEdit)
            PreferenceItem(title = stringResource(R.string.clipboard_menu_delete), icon = ImageVector.vectorResource(R.drawable.ic_settings_m_delete_outline), onClick = onDelete)
        }
    }
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
            ImageVector.vectorResource(spec.emptyIcon),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(if (hasSearchQuery) R.string.settings_common_no_matches else spec.emptyTitle),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(if (hasSearchQuery) R.string.settings_common_no_matches_hint else spec.emptyHint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * A refused add or edit's message: the entry in the way when the name is taken (words and
 * shortcuts share names), else [failed].
 */
internal fun failureMessage(context: android.content.Context, error: Throwable?, @StringRes failed: Int): String =
    when (error) {
        is dev.bbkb.ime.core.settings.data.EntryExistsException -> context.getString(
            if (error.isWord) R.string.settings_dict_word_exists else R.string.settings_shortcuts_exists,
            error.name
        )
        else -> context.getString(failed)
    }
