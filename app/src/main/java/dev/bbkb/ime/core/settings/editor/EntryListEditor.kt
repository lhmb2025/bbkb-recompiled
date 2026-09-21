package dev.bbkb.ime.core.settings.editor

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
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
 * "Search shortcuts...". The one phrase that does not follow — the shortcuts editor says "Failed to
 * load shortcuts" while the dictionary editor says "Failed to load dictionary" — is spelled out in
 * [loadFailurePrefix]. `EntryListSpecTest` pins every derived string against the wording the
 * hand-written screens used.
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
    /** De-duplication and list key. Two entries with the same identity are one row. */
    val identity: (DictionaryEntry) -> String,
    /** Secondary sort key; entries are always grouped by locale first. */
    val sortKey: (DictionaryEntry) -> Comparable<*>?,
    /** Search predicate. */
    val matches: (DictionaryEntry, String) -> Boolean,
    /** Refused before the store is touched, reported as a snackbar. Null means "no objection". */
    val validate: (DictionaryEntry) -> String? = { null },
    val logTag: String,
) {
    private val lowerNoun get() = noun.lowercase()

    fun counter(size: Int) = "$size $plural"
    val searchHint get() = "Search $plural..."
    val addDescription get() = "Add $lowerNoun"

    val added get() = "$noun added"
    val addFailed get() = "Failed to add $lowerNoun"
    val updated get() = "$noun updated"
    val updateFailed get() = "Failed to update $lowerNoun"
    val deleted get() = "$noun deleted"
    val deleteFailed get() = "Failed to delete $lowerNoun"

    fun loadFailure(message: String?) = "$loadFailurePrefix: $message"
}

/**
 * What [EntryListEditorScreen] hands to its `dialogs` slot: which dialog should be open, the
 * locales to offer a new entry, and the three writes.
 *
 * The dialogs stay with their screens rather than being unified here. They are genuinely different
 * — the shortcuts one has two fields, a macro-insertion pad and a fixed-case toggle, the dictionary
 * one has a single field and a language line — and, decisively, neither can be tested: under this
 * project's Robolectric/Compose combination a Material 3 text field inside an `AlertDialog` never
 * reaches an idle composition. Merging code that cannot be observed is how a refactor ships a bug.
 */
internal class EntryEditorHost(
    val locales: List<String>,
    val adding: Boolean,
    val editing: DictionaryEntry?,
    val dismissAdd: () -> Unit,
    val dismissEdit: () -> Unit,
    val add: (DictionaryEntry) -> Unit,
    val update: (DictionaryEntry, DictionaryEntry) -> Unit,
    val delete: (DictionaryEntry) -> Unit,
)

/**
 * The body both dictionary editors share: load, search, group by language, list, and the three
 * writes with their snackbars.
 *
 * @param row how one entry is drawn; the two editors show different things on a row.
 * @param actions extra top-app-bar icons, placed before the search toggle.
 * @param dialogs the screen's own add/edit dialogs, driven from [EntryEditorHost].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryListEditorScreen(
    spec: EntryListSpec,
    store: DictionaryEntryStore,
    onNavigateBack: () -> Unit,
    row: @Composable (DictionaryEntry, () -> Unit) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    dialogs: @Composable (EntryEditorHost) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isLoading by remember { mutableStateOf(true) }
    var isInitialized by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<DictionaryEntry>>(emptyList()) }
    var locales by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
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
     * The one shape every write took: validate, call the store, reload, say what happened.
     *
     * [entry] is null for a delete, which the hand-written screens never validated — only the
     * incoming text of an add or an edit was ever checked.
     */
    fun mutate(
        entry: DictionaryEntry?,
        done: String,
        failed: String,
        verb: String,
        write: suspend () -> Result<Unit>
    ) {
        scope.launch {
            try {
                entry?.let(spec.validate)?.let {
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

    LaunchedEffect(Unit) {
        loadData()
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(spec.title)
                            Text(
                                // While searching, count what is listed; otherwise the counter
                                // read "2 shortcuts" above a single filtered row.
                                text = spec.counter(filteredEntries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        actions()
                        IconButton(onClick = {
                            if (showSearchBar && searchQuery.isNotEmpty()) {
                                searchQuery = ""
                            }
                            showSearchBar = !showSearchBar
                        }) {
                            Icon(
                                if (showSearchBar) Icons.Default.Close else Icons.Default.Search,
                                if (showSearchBar) "Close search" else "Search"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                AnimatedVisibility(
                    visible = showSearchBar,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(spec.searchHint) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, "Clear")
                                }
                            }
                        } else null,
                        singleLine = true
                    )
                }
            }
        },
        // The editor's one creative action, named — "Add shortcut", "Add word". It was a round
        // FAB carrying that text only as a content description, so a sighted user got a bare +.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(spec.addDescription.replaceFirstChar { it.uppercase() }) }
            )
        }
    ) { paddingValues ->
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        groupedEntries.forEach { (locale, localeEntries) ->
                            item(key = "header_$locale") {
                                EntryLanguageHeader(
                                    locale = locale,
                                    count = localeEntries.size,
                                    context = context
                                )
                            }

                            items(
                                items = localeEntries,
                                key = { spec.identity(it) }
                            ) { entry ->
                                row(entry) { selectedEntry = entry }
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

    dialogs(
        EntryEditorHost(
            locales = locales,
            adding = showAddDialog,
            editing = selectedEntry,
            dismissAdd = { showAddDialog = false },
            dismissEdit = { selectedEntry = null },
            add = { entry ->
                mutate(entry, spec.added, spec.addFailed, "adding") { store.addEntry(entry) }
            },
            update = { oldEntry, newEntry ->
                mutate(newEntry, spec.updated, spec.updateFailed, "updating") {
                    store.updateEntry(oldEntry, newEntry)
                }
            },
            delete = { entry ->
                // null: an entry already in the store is never re-validated on the way out.
                mutate(null, spec.deleted, spec.deleteFailed, "deleting") { store.deleteEntry(entry) }
            },
        )
    )
}

/**
 * The subhead over one language's entries.
 *
 * It was a full-bleed `surfaceVariant` bar with bold titleSmall text — a banner, not a subhead,
 * and the only one of its kind in settings. It is [PreferenceCategory] now, like every other
 * section heading in the app: primary, labelMedium metrics, and its 24dp of top padding doing the
 * separating that the tinted bar was doing.
 */
@Composable
private fun EntryLanguageHeader(
    locale: String,
    count: Int,
    context: android.content.Context
) {
    val displayName = LocaleUtil.getDisplayNameForDictionary(context, locale)

    PreferenceCategory(title = "$displayName ($count)")
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
