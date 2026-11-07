package dev.bbkb.ime.core.settings.screens

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.engine.engineLoaderDispatcher
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
import dev.bbkb.ime.core.settings.ui.RoundCheck
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
internal class WordDetail(val count: Int, val quality: Int, val language: Int) {
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

/** The inline-content key of the auto-delete icon after the word count's "N expiring". */
private const val EXPIRING_ICON_ID = "expiringIcon"

/** The orders the sort menu offers, in menu order. */
private enum class WordSort(@StringRes val label: Int) {
    ALPHABETICAL(R.string.settings_learned_sort_az),
    MOST_USED(R.string.settings_learned_sort_most_used),
    EXPIRING(R.string.settings_learned_expiring),
}

/** A run of rows under one header, or the whole list when [title] is null. */
internal class WordSection(val title: String?, val words: List<String>)

/**
 * [words] in list order, cut into one section per language when the words span more than one.
 * A word that belongs to no language (added on purpose) joins the largest language's section, so
 * a phone with one language never sees headers; the languages come largest first.
 */
internal fun sectionsOf(words: List<String>, details: Map<String, WordDetail>?, other: String): List<WordSection> {
    if (details == null) return listOf(WordSection(null, words))
    val byLanguage = words.groupBy { details[it]?.language ?: EngineLanguages.ANY }
    val languages = byLanguage.keys.filter { it != EngineLanguages.ANY }
    if (languages.size <= 1) return listOf(WordSection(null, words))
    val largest = languages.maxByOrNull { byLanguage.getValue(it).size }!!
    return languages
        .sortedWith(compareByDescending<Int> { byLanguage.getValue(it).size }.thenBy { EngineLanguages.displayName(it) ?: "" })
        .map { language ->
            val own = byLanguage.getValue(language)
            // One pass on each word's language: a membership test against [own] (a List) made
            // this N x L String.equals on the main thread (P-19).
            val list = if (language == largest) {
                words.filter { val l = details[it]?.language ?: EngineLanguages.ANY; l == largest || l == EngineLanguages.ANY }
            } else own
            WordSection(EngineLanguages.displayName(language) ?: other, list)
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
 * One learned word, laid out like [PreferenceItem] (same height, padding, card and corners) with
 * what that row cannot hold: the use count on the right as plain text in the accent colour, the
 * way Android's own Settings lists put a size or a percentage, and, for a word the keyboard will
 * forget unless it is typed again, the error container with the auto-delete icon. A tap opens the word's sheet; a long press starts selecting.
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
                        painter = painterResource(R.drawable.ic_settings_auto_delete),
                        contentDescription = stringResource(R.string.settings_learned_expiring),
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
                    detail == null -> stringResource(R.string.settings_learned_use_count_unavailable)
                    detail.count == 1 -> stringResource(R.string.settings_learned_used_once)
                    else -> pluralStringResource(R.plurals.settings_learned_used_times, detail.count, detail.count)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.categoryStartPadding),
            )
            Spacer(modifier = Modifier.height(16.dp))
            PreferenceItem(title = stringResource(R.string.add_word_dialog_title), iconPainter = painterResource(R.drawable.ic_settings_add_to_dictionary), onClick = onAddToDictionary)
            if (detail != null) {
                if (detail.expiring) {
                    PreferenceItem(
                        title = stringResource(R.string.settings_learned_keep),
                        summary = stringResource(R.string.settings_learned_keep_summary),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_check_box),
                        onClick = onKeep,
                    )
                } else {
                    PreferenceItem(title = stringResource(R.string.settings_learned_use_more), iconPainter = painterResource(R.drawable.ic_settings_increase_rank), onClick = onUseMore)
                }
                if (detail.count > 1) PreferenceItem(title = stringResource(R.string.settings_learned_use_less), iconPainter = painterResource(R.drawable.ic_settings_decrease_rank), onClick = onUseLess)
            }
            PreferenceItem(title = stringResource(R.string.clipboard_menu_delete), icon = ImageVector.vectorResource(R.drawable.ic_settings_m_delete_outline), onClick = onDelete)
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
    var showPromoteDialog by remember { mutableStateOf(false) }
    var recentlyDeletedWords by remember { mutableStateOf<List<String>>(emptyList()) }
    // The word whose sheet is open.
    var sheetWord by remember { mutableStateOf<String?>(null) }
    // Each word's use count and quality, read from the engine's own export of the model; null
    // when the export cannot be reached on this phone (the rows then show no counts and the model
    // rows are hidden).
    var details by remember { mutableStateOf<Map<String, WordDetail>?>(null) }
    var sortOrder by remember { mutableStateOf(WordSort.ALPHABETICAL) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    // A model read from a file, waiting for the user to confirm that it replaces this phone's.
    var pendingImport by remember { mutableStateOf<LearnedModelStream.Model?>(null) }
    
    // No engine handle here: composition runs on the main thread, which never resolves or calls
    // the engine. The add, delete and reset below each run as one op on the engine's loader
    // thread and look the engine up there; the list reloads after the op has finished.
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
            // A full model is about 13k records: the passes, the sort and the map below took
            // tens of ms on the main thread after every delete, undo and import (P-19), so they
            // run off it and only the result comes back.
            val (loaded, loadedDetails) = withContext(Dispatchers.Default) {
                val expiringWords = if (model == null) emptyList() else {
                    val listed = loadedWords.toHashSet()
                    val languages = model.words.filter { it.text in listed }.mapTo(HashSet()) { it.language }
                    model.words.filter {
                        it.quality in EXPIRING_RANGE && it.category <= 1 && it.text !in listed &&
                            (languages.isEmpty() || it.language in languages || it.language == 0xffff)
                    }.map { it.text }.distinct()
                }
                // Join by text; a word that appears twice (case variants) shows its larger count.
                val joined = model?.let { m ->
                    HashMap<String, WordDetail>().also { map ->
                        m.words.forEach { w ->
                            val old = map[w.text]
                            if (old == null || w.count > old.count) map[w.text] = WordDetail(w.count, w.quality, w.language)
                        }
                    }
                }
                (loadedWords + expiringWords).sorted() to joined
            }
            words = loaded
            details = loadedDetails
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
                    outcome !is LearnedModelTransfer.Outcome.Imported -> context.getString(R.string.settings_learned_change_failed, word)
                    delta == 0 -> context.getString(R.string.settings_learned_kept, word)
                    wasExpiring && delta > 0 -> context.getString(R.string.settings_learned_kept_sooner, word)
                    delta > 0 -> context.getString(R.string.settings_learned_sooner, word)
                    else -> context.getString(R.string.settings_learned_less, word)
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
                        context.resources.getQuantityString(R.plurals.settings_learned_loaded, outcome.words, outcome.words)
                    is LearnedModelTransfer.Outcome.Refused ->
                        context.getString(
                            if (outcome.restored) R.string.settings_learned_import_refused
                            else R.string.settings_learned_import_refused_reset
                        )
                    is LearnedModelTransfer.Outcome.Rejected -> outcome.reason
                    LearnedModelTransfer.Outcome.NotAvailable -> context.getString(R.string.settings_learned_not_ready)
                    null -> context.getString(R.string.settings_learned_load_failed)
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
            snackbarHostState.showSnackbar(
                context.getString(if (ok) R.string.settings_learned_saved else R.string.settings_learned_save_failed)
            )
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
                        ?: return@withContext Result.failure(Exception(context.getString(R.string.settings_learned_read_failed)))
                    if (bytes.size > LearnedModelStream.MAX_BYTES) {
                        Result.failure(Exception(context.getString(R.string.settings_learned_file_too_large)))
                    } else {
                        Result.success(LearnedModelStream.parse(bytes))
                    }
                } catch (e: LearnedModelStream.InvalidModelException) {
                    Result.failure(Exception(context.getString(R.string.settings_learned_not_a_model, e.message)))
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "read model failed", e)
                    Result.failure(Exception(context.getString(R.string.settings_learned_read_failed)))
                }
            }
            result.onSuccess { pendingImport = it }
                .onFailure { snackbarHostState.showSnackbar(it.message ?: context.getString(R.string.settings_learned_read_failed)) }
        }
    }
    
    // Undo deletion by re-learning words
    fun undoDelete(wordsToRestore: List<String>) {
        scope.launch {
            withContext(engineLoaderDispatcher) {
                try {
                    val nuanceSDK = NuanceSDKManager.getInstance()
                    wordsToRestore.forEach { word ->
                        // Re-learn the word via Nuance addWord
                        nuanceSDK?.addWord(word)
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error restoring words", e)
                }
            }
            
            loadWords()
            snackbarHostState.showSnackbar(
                context.resources.getQuantityString(R.plurals.settings_learned_restored, wordsToRestore.size, wordsToRestore.size)
            )
        }
    }
    
    // Delete words with undo support
    fun deleteWords(wordsToDelete: List<String>, showUndo: Boolean = true) {
        scope.launch {
            // Store for potential undo
            recentlyDeletedWords = wordsToDelete
            
            withContext(engineLoaderDispatcher) {
                try {
                    val nuanceSDK = NuanceSDKManager.getInstance()
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
                val message = context.resources.getQuantityString(R.plurals.settings_dict_words_deleted, count, count)
                
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = context.getString(R.string.ckb_action_undo),
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
                    snackbarHostState.showSnackbar(context.getString(R.string.settings_learned_added_to_dictionary, word))
                } else {
                    snackbarHostState.showSnackbar(context.getString(R.string.settings_learned_add_to_dictionary_failed))
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error promoting word", e)
                snackbarHostState.showSnackbar(context.getString(R.string.settings_common_error, e.message))
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
                
                snackbarHostState.showSnackbar(
                    context.resources.getQuantityString(R.plurals.settings_learned_added_count, successCount, successCount)
                )
                selectedWords = emptySet()
                isSelectionMode = false
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e("WordListEditor", "Error promoting words", e)
                snackbarHostState.showSnackbar(context.getString(R.string.settings_common_error, e.message))
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
    val title = stringResource(if (isWorkProfile) R.string.settings_learned_work_title else R.string.settings_dict_learned_words_title)
    
    SettingsScaffold(
        // Selection mode is a contextual bar: a close cross cancels it, as Android's own apps do.
        navigationIcon = ImageVector.vectorResource(if (isSelectionMode) R.drawable.ic_settings_m_close else R.drawable.ic_settings_m_arrow_back),
        navigationContentDescription = stringResource(
            if (isSelectionMode) R.string.settings_common_cancel_selection else R.string.settings_common_back
        ),
        title = if (isSelectionMode) {
            pluralStringResource(R.plurals.settings_common_selected_count, selectedWords.size, selectedWords.size)
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
                // No confirmation: the snackbar's Undo puts the words back, as it does for one.
                IconButton(onClick = { deleteWords(selectedWords.toList()) }) {
                    Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_delete_outline), stringResource(R.string.settings_common_delete_selected))
                }
                IconButton(onClick = { showPromoteDialog = true }) {
                    Icon(painterResource(R.drawable.ic_settings_add_to_dictionary), stringResource(R.string.add_word_dialog_title))
                }
            }
            // The screen's rarer actions: the learned model as a whole (moving it in and out as a
            // file, which needs the engine's export; the work profile's model has no path to it
            // yet) and clearing the list.
            val modelActions = !isWorkProfile && details != null
            if (!isSelectionMode && (modelActions || words.isNotEmpty())) {
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_more_vert), stringResource(R.string.clipboard_more_options))
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        if (modelActions) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_learned_export)) },
                                onClick = {
                                    showOverflow = false
                                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                                    saveLauncher.launch("bbkb-learned-model-$date.xt9dlm")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_learned_import)) },
                                onClick = { showOverflow = false; loadLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                        if (words.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_learned_clear)) },
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
                            imageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_library_books),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.settings_learned_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_learned_empty_hint),
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
                    val expiringCount = remember(words, details) { words.count { details?.get(it)?.expiring == true } }
                    val otherLanguage = stringResource(R.string.settings_learned_language_other)
                    val sections = remember(words, details, sortOrder, otherLanguage) {
                        val d = details
                        val ordered = when {
                            d == null -> words
                            sortOrder == WordSort.MOST_USED -> words.sortedWith(compareByDescending<String> { d[it]?.count ?: 0 }.thenBy { it })
                            // Expiring words first, the lowest quality (the nearest to the engine
                            // dropping the word) first; the kept words follow A to Z
                            sortOrder == WordSort.EXPIRING -> words.sortedWith(
                                compareBy<String> { d[it]?.takeIf { w -> w.expiring }?.quality ?: Int.MAX_VALUE }.thenBy { it }
                            )
                            else -> words
                        }
                        sectionsOf(ordered, d, otherLanguage)
                    }
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
                                    val wordCount = pluralStringResource(R.plurals.settings_dict_word_count, words.size, words.size)
                                    val expiringText = pluralStringResource(R.plurals.settings_learned_expiring_count, expiringCount, expiringCount)
                                    val expiringLabel = stringResource(R.string.settings_learned_expiring)
                                    Text(
                                        text = buildAnnotatedString {
                                            append(wordCount)
                                            if (expiringCount > 0) {
                                                append(" · ")
                                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append("$expiringText ") }
                                                appendInlineContent(EXPIRING_ICON_ID, "[$expiringLabel]")
                                            }
                                        },
                                        inlineContent = mapOf(
                                            EXPIRING_ICON_ID to InlineTextContent(
                                                Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_settings_auto_delete),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (details != null && words.size > 1) {
                                        Box {
                                            TextButton(onClick = { showSortMenu = true }) {
                                                Text(stringResource(sortOrder.label))
                                                Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_arrow_drop_down), contentDescription = stringResource(R.string.settings_learned_sort_order))
                                            }
                                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                                WordSort.entries.forEach { order ->
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(order.label)) },
                                                        trailingIcon = if (sortOrder == order) {
                                                            { Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_check_filled), contentDescription = stringResource(R.string.settings_common_selected)) }
                                                        } else null,
                                                        onClick = { sortOrder = order; showSortMenu = false },
                                                    )
                                                }
                                            }
                                        }
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
            title = { Text(stringResource(R.string.settings_learned_import_title)) },
            text = {
                Text(pluralStringResource(R.plurals.settings_learned_import_message, model.words.size, model.words.size))
            },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; importModel(model) }) {
                    Text(stringResource(R.string.settings_common_replace))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Clear all dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.settings_learned_clear_title)) },
            text = {
                Text(
                    if (isWorkProfile) {
                        pluralStringResource(R.plurals.settings_learned_clear_message_work, words.size, words.size)
                    } else {
                        stringResource(R.string.settings_learned_clear_message)
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
                                snackbarHostState.showSnackbar(context.getString(R.string.settings_learned_cleared))
                            }
                        } else {
                            // Full model reset: per-word deletion only prunes the
                            // enumerable unigrams; bigrams and usage boosts survive it.
                            scope.launch {
                                val ok = withContext(engineLoaderDispatcher) {
                                    try {
                                        NuanceSDKManager.getInstance()?.resetDynamicModel(
                                            java.io.File(context.filesDir, "nuance")) ?: false
                                    } catch (e: Exception) {
                                        if (BuildConfig.DEBUG) Log.e("WordListEditor", "resetDynamicModel failed", e)
                                        false
                                    }
                                }
                                loadWords()
                                snackbarHostState.showSnackbar(
                                    context.getString(if (ok) R.string.settings_learned_reset else R.string.settings_learned_reset_failed))
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_learned_clear_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    
    // Promote to dictionary dialog
    if (showPromoteDialog) {
        AlertDialog(
            onDismissRequest = { showPromoteDialog = false },
            title = { Text(stringResource(R.string.add_word_dialog_title)) },
            text = { 
                Column {
                    Text(pluralStringResource(R.plurals.settings_learned_add_selected_message, selectedWords.size, selectedWords.size))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_dict_add_to_dictionary_note),
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
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromoteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
