package dev.bbkb.ime.core.settings.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.engine.dlm.EngineLanguages
import dev.bbkb.ime.core.engine.dlm.LearnedModelStream
import dev.bbkb.ime.core.engine.dlm.LearnedModelTransfer
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.LocalInGroup
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.groupOuterCorners
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How many uses "Use more often" adds to a word's count, and "Use less often" takes away. */
private const val BOOST_USES = 10

/** What the engine's export says about one word: its use count, its status (quality) and its language. */
private class WordDetail(val count: Int, val quality: Int, val language: Int) {
    /**
     * Whether the keyboard will forget the word unless it is typed again. A word typed once gets
     * a negative quality and stays out of the engine's own word list; each time it is offered and
     * passed over the engine lowers the quality, and at −11 drops the word. Typing it again raises
     * the quality to 0, which keeps it. (A word the user deleted is kept at −15 so it stays
     * suppressed; that is not expiring, and it is not listed.)
     */
    val expiring: Boolean get() = quality in EXPIRING_RANGE
}

/** The qualities of a word the engine has not kept yet: see [WordDetail.expiring]. */
private val EXPIRING_RANGE = -11..-1

/** A run of rows under one header, or the whole list when [title] is null. */
private class WordSection(val title: String?, val words: List<String>)

/**
 * [words] in list order, cut into one section per language when the words span more than one.
 * A word that belongs to no language (added on purpose) joins the largest language's section, so
 * a phone with one language never sees headers; the languages come largest first.
 */
private fun sectionsOf(words: List<String>, details: Map<String, WordDetail>?): List<WordSection> {
    if (details == null) return listOf(WordSection(null, words))
    val byLanguage = words.groupBy { details[it]?.language ?: EngineLanguages.ANY }
    val languages = byLanguage.keys.filter { it != EngineLanguages.ANY }
    if (languages.size <= 1) return listOf(WordSection(null, words))
    val largest = languages.maxByOrNull { byLanguage.getValue(it).size }!!
    return languages
        .sortedWith(compareByDescending<Int> { byLanguage.getValue(it).size }.thenBy { EngineLanguages.displayName(it) ?: "" })
        .map { language ->
            val own = byLanguage.getValue(language)
            val list = if (language == largest) words.filter { it in own || (details[it]?.language ?: EngineLanguages.ANY) == EngineLanguages.ANY } else own
            WordSection(EngineLanguages.displayName(language) ?: "Other", list)
        }
}

/** The learned model file is the engine's own byte stream; no registered type describes it. */
private const val MODEL_MIME_TYPE = "application/octet-stream"

/** Up to [limit] bytes of [input]; a longer stream is not a model and is cut there. */
private fun readLimited(input: InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * A connected button group in the Material 3 (Expressive) shape, drawn here because the Material
 * library this app compiles against (1.4.0) does not expose `ToggleButton` and `ButtonGroupDefaults`.
 * The
 * standard small size: 40 dp tall, 14 sp labels, the two outer sides fully round, 8 dp inner
 * corners, the chosen button fully round, 2 dp between the buttons. Colours follow the theme: the
 * chosen button is filled with the primary colour, the others are tonal.
 */
@Composable
private fun ConnectedButtonGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val innerCorner = CornerSize(8.dp)
    val round = CornerSize(50)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val shape = when {
                selected -> CircleShape
                index == 0 -> RoundedCornerShape(topStart = round, bottomStart = round, topEnd = innerCorner, bottomEnd = innerCorner)
                index == options.lastIndex -> RoundedCornerShape(topStart = innerCorner, bottomStart = innerCorner, topEnd = round, bottomEnd = round)
                else -> RoundedCornerShape(innerCorner)
            }
            Surface(
                onClick = { onSelect(index) },
                selected = selected,
                shape = shape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(
                    modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * One learned word, laid out like [PreferenceItem] (same height, padding, card and corners) with
 * what that row cannot hold: the use count on the right as plain text in the accent colour, the
 * way Android's own Settings lists put a size or a percentage, and, for a word the keyboard will
 * forget unless it is typed again, the error container with an hourglass. A tap opens the word's sheet; a long press starts selecting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LearnedWordRow(
    word: String,
    detail: WordDetail?,
    selected: Boolean,
    selectionMode: Boolean,
    groupEdge: GroupEdge,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val shapes = LocalShapes.current
    val inGroup = LocalInGroup.current
    val settingsColors = LocalSettingsColors.current
    val colors = MaterialTheme.colorScheme
    val expiring = detail?.expiring == true
    // A expiring word keeps its error container while selected; the round check says it is selected.
    val container = when {
        expiring -> colors.errorContainer
        selected -> colors.secondaryContainer
        inGroup -> settingsColors.card
        else -> Color.Transparent
    }
    val content = when {
        expiring -> colors.onErrorContainer
        selected -> colors.onSecondaryContainer
        else -> colors.onSurface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .groupOuterCorners(groupEdge)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = if (inGroup) shapes.row else RectangleShape,
        color = container,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = spacing.oneLineListItemHeight)
                .padding(horizontal = spacing.listItemPadding, vertical = spacing.listItemVerticalPadding)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The check column exists only while selecting: it slides in from the start edge and
            // the words move over, so the plain list keeps even margins.
            AnimatedVisibility(
                visible = selectionMode,
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
            Row(
                modifier = Modifier.weight(1f).padding(end = spacing.iconTextGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = word, style = MaterialTheme.typography.bodyLarge, color = content)
                if (expiring) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_settings_hourglass),
                        contentDescription = "Expiring",
                        tint = content,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (detail != null) {
                Text(
                    text = detail.count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (expiring) content else colors.primary,
                )
            }
        }
    }
}

/**
 * The mark of Material's list selection pattern: a filled circle with a check on a selected row,
 * and, as these rows have no avatar for the circle to take over, an empty ring on a row that can
 * be selected. The column holding it slides in when selection starts (see [LearnedWordRow]).
 */
@Composable
private fun RoundCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Box(modifier = modifier.background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.onPrimary, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(modifier = modifier.border(2.dp, colors.outline, CircleShape))
    }
}

/**
 * Word List Editor Screen
 * Displays and manages learned words with multi-select, delete, clear all, and promote to dictionary
 * Includes undo functionality for deletions
 */
/**
 * One learned word's sheet: what the keyboard knows about it, then what can be done with it. A
 * sheet rather than a menu because it has room to say why a word is expiring, and because it is how
 * this app's other lists open an item (the Language screen's keyboards).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LearnedWordSheet(
    word: String,
    detail: WordDetail?,
    onAddToDictionary: () -> Unit,
    onKeep: () -> Unit,
    onUseMore: () -> Unit,
    onUseLess: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = word,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding, vertical = 8.dp),
            )
            Text(
                text = when {
                    detail == null -> "Use count not available"
                    detail.count == 1 -> "Used once"
                    else -> "Used ${detail.count} times"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding),
            )
            Spacer(modifier = Modifier.height(16.dp))
            PreferenceItem(title = "Add to dictionary", iconPainter = painterResource(R.drawable.ic_settings_add_to_dictionary), onClick = onAddToDictionary)
            if (detail != null) {
                if (detail.expiring) {
                    PreferenceItem(
                        title = "Keep this word",
                        summary = "Stop this learned word from expiring",
                        icon = Icons.Outlined.CheckBox,
                        onClick = onKeep,
                    )
                } else {
                    PreferenceItem(title = "Use more often", iconPainter = painterResource(R.drawable.ic_settings_increase_rank), onClick = onUseMore)
                }
                if (detail.count > 1) PreferenceItem(title = "Use less often", iconPainter = painterResource(R.drawable.ic_settings_decrease_rank), onClick = onUseLess)
            }
            PreferenceItem(title = "Delete", icon = Icons.Outlined.DeleteOutline, onClick = onDelete)
        }
    }
}

@Composable
fun WordListEditorScreen(
    isWorkProfile: Boolean = false,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedWords by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPromoteDialog by remember { mutableStateOf(false) }
    var pendingDeleteWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentlyDeletedWords by remember { mutableStateOf<List<String>>(emptyList()) }
    // The word whose sheet is open.
    var sheetWord by remember { mutableStateOf<String?>(null) }
    // Each word's use count and quality, read from the engine's own export of the model; null
    // when the export cannot be reached on this phone (the rows then show no counts and the model
    // rows are hidden).
    var details by remember { mutableStateOf<Map<String, WordDetail>?>(null) }
    var sortByUse by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    // A model read from a file, waiting for the user to confirm that it replaces this phone's.
    var pendingImport by remember { mutableStateOf<LearnedModelStream.Model?>(null) }
    
    val nuanceSDK = remember { NuanceSDKManager.getInstance() }
    val dictionaryRepository = remember { DictionaryRepository(context) }
    
    // Load words
    fun loadWords() {
        scope.launch {
            isLoading = true
            val loadedWords = withContext(Dispatchers.IO) {
                try {
                    // Only the keyboard initialises DictionaryManager (BlackBerryIME, on its
                    // locale setup), so this screen opened in a fresh process, before any field
                    // has been focused, found it unloaded and showed "No learned words" for a
                    // full model (KEY2, 2026-10-17). The add/undo paths below already initialise
                    // through the repository and wait for the load; do the same before reading.
                    dictionaryRepository.initialize()
                    // The callback is delivered on whichever thread the dictionary finishes on.
                    // Sleeping 100 ms and reading a plain MutableList had no happens-before edge
                    // and no guarantee the callback had fired at all - on a slow first DLM load
                    // the editor showed "No learned words" for a full model, and the user's next
                    // move is Clear All. Suspend until the callback arrives instead, with a
                    // bound in case the dictionary never calls back.
                    withTimeoutOrNull(5_000) {
                        suspendCancellableCoroutine<List<String>> { continuation ->
                            DictionaryManager.getInstance().getLearnedWords(
                                object : DictionaryManager.LearnedWordsCallback {
                                    override fun onLearnedWords(list: List<String>) {
                                        if (continuation.isActive) {
                                            continuation.resume(list.toList())
                                        }
                                    }
                                },
                                isWorkProfile
                            )
                        }
                    } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            }
            // The engine's own list leaves out the words it has not kept yet: a word typed once
            // sits in the model with a negative quality until it is typed again, and would be
            // forgotten without a trace. The export has them, so they are listed too, marked as
            // expiring, for the languages the engine's list covers (the model holds every
            // language's words; the list only the active ones). The export also carries words
            // the screen does not list: dictionary words the model boosts, deleted words it keeps
            // suppressed.
            val model = if (isWorkProfile) null else withContext(Dispatchers.IO) {
                try {
                    LearnedModelTransfer.current()
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "learned model unavailable", e)
                    null
                }
            }
            val expiringWords = if (model == null) emptyList() else {
                val listed = loadedWords.toHashSet()
                val languages = model.words.filter { it.text in listed }.mapTo(HashSet()) { it.language }
                model.words.filter {
                    it.quality in EXPIRING_RANGE && it.category <= 1 && it.text !in listed &&
                        (languages.isEmpty() || it.language in languages || it.language == 0xffff)
                }.map { it.text }.distinct()
            }
            words = (loadedWords + expiringWords).sorted()
            // Join by text; a word that appears twice (case variants) shows its larger count.
            details = model?.let { m ->
                HashMap<String, WordDetail>().also { map ->
                    m.words.forEach { w ->
                        val old = map[w.text]
                        if (old == null || w.count > old.count) map[w.text] = WordDetail(w.count, w.quality, w.language)
                    }
                }
            }
            isLoading = false
        }
    }

    // Moves a word's use count so the engine offers it sooner or later, or with a [delta] of 0
    // only keeps a expiring word: the model is exported, the record rewritten in the stream, and the
    // stream imported again (LearnedModelTransfer keeps the previous model to put back if the
    // engine refuses).
    fun changeUses(word: String, delta: Int) {
        val wasExpiring = details?.get(word)?.expiring == true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val model = LearnedModelTransfer.current() ?: return@withContext null
                    val records = model.words.withIndex().filter { it.value.text == word }
                    val counts = if (delta == 0) emptyMap() else records.associate { it.index to (it.value.count + delta).coerceIn(1, 0xffff) }
                    // Keeping a expiring word, or asking for it more often, sets quality 0: what the
                    // word would reach when typed again. The count is left alone by a plain keep.
                    val qualities = if (delta >= 0) records.filter { it.value.quality in EXPIRING_RANGE }.associate { it.index to 0 } else emptyMap()
                    if (counts.isEmpty() && qualities.isEmpty()) null
                    else LearnedModelTransfer.importParsed(LearnedModelStream.parse(LearnedModelStream.withChanges(model, counts, qualities)))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "changing the use count failed", e)
                    null
                }
            }
            loadWords()
            snackbarHostState.showSnackbar(
                when {
                    outcome !is LearnedModelTransfer.Outcome.Imported -> "Couldn't change \"$word\""
                    delta == 0 -> "\"$word\" is kept"
                    wasExpiring && delta > 0 -> "\"$word\" is kept and will come up sooner"
                    delta > 0 -> "\"$word\" will come up sooner"
                    else -> "\"$word\" will come up less"
                }
            )
        }
    }

    // Replaces this phone's model with one read from a file, after the user confirmed.
    fun importModel(model: LearnedModelStream.Model) {
        scope.launch {
            isLoading = true
            val outcome = withContext(Dispatchers.IO) {
                try {
                    LearnedModelTransfer.importParsed(model)
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "import failed", e)
                    null
                }
            }
            loadWords()
            snackbarHostState.showSnackbar(
                when (outcome) {
                    is LearnedModelTransfer.Outcome.Imported ->
                        if (outcome.words == 1) "Loaded 1 learned word" else "Loaded ${outcome.words} learned words"
                    is LearnedModelTransfer.Outcome.Refused ->
                        if (outcome.restored) "The keyboard refused that file; your words are unchanged"
                        else "The keyboard refused that file and had to start a new model"
                    is LearnedModelTransfer.Outcome.Rejected -> outcome.reason
                    LearnedModelTransfer.Outcome.NotAvailable -> "The keyboard isn't ready; open a text field first"
                    null -> "Couldn't load the learned model"
                }
            )
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MODEL_MIME_TYPE)
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = LearnedModelTransfer.export() ?: return@withContext false
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "save model failed", e)
                    false
                }
            }
            snackbarHostState.showSnackbar(if (ok) "Learned model saved" else "Couldn't save the learned model")
        }
    }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val result: Result<LearnedModelStream.Model> = withContext(Dispatchers.IO) {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { readLimited(it, LearnedModelStream.MAX_BYTES + 1) }
                        ?: return@withContext Result.failure(Exception("Couldn't read that file"))
                    if (bytes.size > LearnedModelStream.MAX_BYTES) {
                        Result.failure(Exception("That file is too large to be a learned model"))
                    } else {
                        Result.success(LearnedModelStream.parse(bytes))
                    }
                } catch (e: LearnedModelStream.InvalidModelException) {
                    Result.failure(Exception("That file isn't a learned model (${e.message})"))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "read model failed", e)
                    Result.failure(Exception("Couldn't read that file"))
                }
            }
            result.onSuccess { pendingImport = it }
                .onFailure { snackbarHostState.showSnackbar(it.message ?: "Couldn't read that file") }
        }
    }
    
    // Undo deletion by re-learning words
    fun undoDelete(wordsToRestore: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    wordsToRestore.forEach { word ->
                        // Re-learn the word via Nuance addWord
                        nuanceSDK?.addWord(word)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error restoring words", e)
                }
            }
            
            loadWords()
            snackbarHostState.showSnackbar("Restored ${wordsToRestore.size} word(s)")
        }
    }
    
    // Delete words with undo support
    fun deleteWords(wordsToDelete: List<String>, showUndo: Boolean = true) {
        scope.launch {
            // Store for potential undo
            recentlyDeletedWords = wordsToDelete
            
            withContext(Dispatchers.IO) {
                try {
                    if (isWorkProfile) {
                        nuanceSDK?.deleteWorkDLMWordsExplicitly(ArrayList(wordsToDelete))
                    } else {
                        wordsToDelete.forEach { word ->
                            nuanceSDK?.deleteDLMWord(word)
                        }
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error deleting words", e)
                }
            }
            
            loadWords()
            selectedWords = emptySet()
            isSelectionMode = false
            
            if (showUndo) {
                val count = wordsToDelete.size
                val message = if (count == 1) "Deleted 1 word" else "Deleted $count words"
                
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long
                )
                
                if (result == SnackbarResult.ActionPerformed) {
                    // Undo: re-learn the deleted words
                    undoDelete(recentlyDeletedWords)
                }
            }
        }
    }
    
    // Promote word to user dictionary
    fun promoteToUserDictionary(word: String) {
        scope.launch {
            try {
                // Initialize repository if needed
                dictionaryRepository.initialize()
                
                // Add to user dictionary via BASL (ensures Nuance integration)
                val entry = DictionaryEntry(
                    word = word,
                    shortcut = null,
                    locale = PersonalDictionaryConstants.LOCALE_ALL,
                    fixedCase = false
                )
                
                val result = dictionaryRepository.addEntry(entry)
                if (result.isSuccess) {
                    snackbarHostState.showSnackbar("\"$word\" added to dictionary")
                } else {
                    snackbarHostState.showSnackbar("Failed to add word to dictionary")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error promoting word", e)
                snackbarHostState.showSnackbar("Error: ${e.message}")
            }
        }
    }
    
    // Promote selected words to user dictionary
    fun promoteSelectedToUserDictionary() {
        scope.launch {
            try {
                dictionaryRepository.initialize()
                
                var successCount = 0
                selectedWords.forEach { word ->
                    val entry = DictionaryEntry(
                        word = word,
                        shortcut = null,
                        locale = PersonalDictionaryConstants.LOCALE_ALL,
                        fixedCase = false
                    )
                    val result = dictionaryRepository.addEntry(entry)
                    if (result.isSuccess) successCount++
                }
                
                snackbarHostState.showSnackbar("Added $successCount word(s) to dictionary")
                selectedWords = emptySet()
                isSelectionMode = false
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error promoting words", e)
                snackbarHostState.showSnackbar("Error: ${e.message}")
            }
        }
    }
    
    // Load words on first composition
    LaunchedEffect(Unit) {
        loadWords()
    }

    // The system back leaves selection mode first, as the top bar's arrow does.
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedWords = emptySet()
    }
    
    // The same string the Learning screen's row uses, so the two agree in every language.
    val title = if (isWorkProfile) "Work learned words" else stringResource(R.string.settings_dict_learned_words_title)
    
    SettingsScaffold(
        // Selection mode is a contextual bar: a close cross cancels it, as Android's own apps do.
        navigationIcon = if (isSelectionMode) Icons.Outlined.Close else Icons.AutoMirrored.Outlined.ArrowBack,
        navigationContentDescription = if (isSelectionMode) "Cancel selection" else "Back",
        title = if (isSelectionMode) {
            "${selectedWords.size} selected"
        } else {
            title
        },
        // In selection mode the back arrow leaves selection mode rather than the screen.
        onNavigateBack = {
            if (isSelectionMode) {
                isSelectionMode = false
                selectedWords = emptySet()
            } else {
                onNavigateBack()
            }
        },
        actions = {
            if (isSelectionMode && selectedWords.isNotEmpty()) {
                IconButton(onClick = {
                    pendingDeleteWords = selectedWords.toList()
                    showDeleteDialog = true
                }) {
                    Icon(Icons.Outlined.DeleteOutline, "Delete selected")
                }
                IconButton(onClick = { showPromoteDialog = true }) {
                    Icon(painterResource(R.drawable.ic_settings_add_to_dictionary), "Add to dictionary")
                }
            }
            // The screen's rarer actions: the learned model as a whole (moving it in and out as a
            // file, which needs the engine's export; the work profile's model has no path to it
            // yet) and clearing the list.
            val modelActions = !isWorkProfile && details != null
            if (!isSelectionMode && (modelActions || words.isNotEmpty())) {
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Outlined.MoreVert, "More")
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        if (modelActions) {
                            DropdownMenuItem(
                                text = { Text("Export to file") },
                                onClick = {
                                    showOverflow = false
                                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    saveLauncher.launch("bbkb-learned-model-$date.xt9dlm")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import from file") },
                                onClick = { showOverflow = false; loadLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                        if (words.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Clear learned words") },
                                onClick = { showOverflow = false; showClearAllDialog = true }
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                words.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No learned words",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Words you type will be learned and appear here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    val spacing = LocalSpacing.current
                    // The words are one group laid out by the list itself: the rows paint
                    // themselves as cards, the first and last carry the group's outer corners, and
                    // the rows take the group inset themselves so the header and the count above
                    // them keep their page-level alignment.
                    val sections = remember(words, details, sortByUse) {
                        val d = details
                        val ordered = if (sortByUse && d != null) words.sortedWith(compareByDescending<String> { d[it]?.count ?: 0 }.thenBy { it }) else words
                        sectionsOf(ordered, d)
                    }
                    val expiringCount = remember(words, details) { words.count { details?.get(it)?.expiring == true } }
                    CompositionLocalProvider(LocalInGroup provides true) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(spacing.groupGap)
                        ) {
                            // Word count, and the order when the engine's use counts are available
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = spacing.categoryStartPadding, top = 8.dp, end = 16.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = buildAnnotatedString {
                                            append("${words.size} ${if (words.size == 1) "word" else "words"}")
                                            if (expiringCount > 0) {
                                                append(" · ")
                                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append("$expiringCount expiring") }
                                            }
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (details != null && words.size > 1) {
                                        ConnectedButtonGroup(
                                            options = listOf("A to Z", "Most used"),
                                            selectedIndex = if (sortByUse) 1 else 0,
                                            onSelect = { sortByUse = it == 1 },
                                        )
                                    }
                                }
                            }
                        
                            // Word list: one group per section, each under its language's header
                            // when there is more than one
                            sections.forEach { section ->
                                if (section.title != null) {
                                    item(key = "header:${section.title}") { PreferenceCategory(title = section.title) }
                                }
                                itemsIndexed(section.words, key = { _, w -> "word:$w" }) { index, word ->
                                    val isSelected = selectedWords.contains(word)
                                    Box(modifier = Modifier.padding(horizontal = spacing.groupInset)) {
                                        LearnedWordRow(
                                            word = word,
                                            detail = details?.get(word),
                                            selected = isSelected,
                                            selectionMode = isSelectionMode,
                                            groupEdge = groupEdge(index, section.words.size),
                                        onClick = {
                                            if (isSelectionMode) {
                                                selectedWords = if (isSelected) selectedWords - word else selectedWords + word
                                            } else {
                                                sheetWord = word
                                            }
                                        },
                                        onLongClick = {
                                            if (isSelectionMode) {
                                                selectedWords = if (isSelected) selectedWords - word else selectedWords + word
                                            } else {
                                                isSelectionMode = true
                                                selectedWords = setOf(word)
                                            }
                                        },
                                        )
                                    }
                                }
                            }
                        
                            // Bottom spacing
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    
    // The tapped word's sheet.
    sheetWord?.let { word ->
        LearnedWordSheet(
            word = word,
            detail = details?.get(word),
            onAddToDictionary = { sheetWord = null; promoteToUserDictionary(word) },
            onKeep = { sheetWord = null; changeUses(word, 0) },
            onUseMore = { sheetWord = null; changeUses(word, BOOST_USES) },
            onUseLess = { sheetWord = null; changeUses(word, -BOOST_USES) },
            onDelete = { sheetWord = null; deleteWords(listOf(word)) },
            onDismiss = { sheetWord = null },
        )
    }

    // A model read from a file: say what it replaces before it does.
    pendingImport?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import learned model") },
            text = {
                Text(
                    "This replaces this phone's learned model with the one in the file " +
                        "(${model.words.size} words, with their use counts and word pairs). This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; importModel(model) }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
            }
        )
    }

    // Clear all dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Learned Words") },
            text = {
                Text(
                    if (isWorkProfile) {
                        "This will delete all ${words.size} learned words. " +
                            "This action cannot be undone. Are you sure?"
                    } else {
                        "This will erase the entire learned model — words, phrases, " +
                            "and usage history. This action cannot be undone. Are you sure?"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        if (isWorkProfile) {
                            deleteWords(words, showUndo = false)
                            scope.launch {
                                snackbarHostState.showSnackbar("Cleared all learned words")
                            }
                        } else {
                            // Full model reset: per-word deletion only prunes the
                            // enumerable unigrams; bigrams and usage boosts survive it.
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        nuanceSDK?.resetDynamicModel(
                                            java.io.File(context.filesDir, "nuance")) ?: false
                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) Log.e("WordListEditor", "resetDynamicModel failed", e)
                                        false
                                    }
                                }
                                loadWords()
                                snackbarHostState.showSnackbar(
                                    if (ok) "Learned model reset" else "Couldn't reset learned model")
                            }
                        }
                    }
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Delete selected dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Selected Words") },
            text = { 
                Text(
                    "Delete ${pendingDeleteWords.size} selected ${if (pendingDeleteWords.size == 1) "word" else "words"}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        deleteWords(pendingDeleteWords)
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Promote to dictionary dialog
    if (showPromoteDialog) {
        AlertDialog(
            onDismissRequest = { showPromoteDialog = false },
            title = { Text("Add to Dictionary") },
            text = { 
                Column {
                    Text(
                        "Add ${selectedWords.size} selected ${if (selectedWords.size == 1) "word" else "words"} to your personal dictionary?"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Words added to your dictionary will be suggested during typing and won't be marked as misspelled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPromoteDialog = false
                        promoteSelectedToUserDictionary()
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromoteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
