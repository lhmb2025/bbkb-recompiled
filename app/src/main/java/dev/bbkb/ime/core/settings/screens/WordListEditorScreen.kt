package dev.bbkb.ime.core.settings.screens

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.personaldictionary.DictionaryManager
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.personaldictionary.PersonalDictionaryConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import dev.bbkb.ime.BuildConfig

/**
 * Word List Editor Screen
 * Displays and manages learned words with multi-select, delete, clear all, and promote to dictionary
 * Includes undo functionality for deletions
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var showWordMenu by remember { mutableStateOf<String?>(null) }
    
    val nuanceSDK = remember { NuanceSDKManager.getInstance() }
    val dictionaryRepository = remember { DictionaryRepository(context) }
    
    // Load words
    fun loadWords() {
        scope.launch {
            isLoading = true
            val loadedWords = withContext(Dispatchers.IO) {
                try {
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
            words = loadedWords.sorted()
            isLoading = false
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
    
    val title = if (isWorkProfile) "Work Learned Words" else "Personal Learned Words"
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (isSelectionMode) {
                            "${selectedWords.size} selected"
                        } else {
                            title
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            isSelectionMode = false
                            selectedWords = emptySet()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isSelectionMode && selectedWords.isNotEmpty()) {
                        // Add to dictionary button
                        IconButton(onClick = { showPromoteDialog = true }) {
                            Icon(Icons.Default.Add, "Add to dictionary")
                        }
                        // Delete button
                        IconButton(onClick = {
                            pendingDeleteWords = selectedWords.toList()
                            showDeleteDialog = true
                        }) {
                            Icon(Icons.Default.Delete, "Delete selected")
                        }
                    }
                    if (!isSelectionMode && words.isNotEmpty()) {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear all")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isSelectionMode) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    titleContentColor = if (isSelectionMode) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            )
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
                            imageVector = Icons.AutoMirrored.Filled.LibraryBooks,
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Header with explanation
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (isWorkProfile) {
                                        "Manage words learned in your work profile"
                                    } else {
                                        "Manage words learned from your typing"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        
                        // Word count
                        item {
                            Text(
                                text = "${words.size} ${if (words.size == 1) "word" else "words"}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        
                        // Word list
                        items(words) { word ->
                            val isSelected = selectedWords.contains(word)
                            
                            // PreferenceItem, not a Material 3 ListItem with a rule under it: the
                            // selected tint is the one thing this list needs that a flat row does
                            // not have, and it is a parameter now rather than a reason to draw the
                            // row a different way.
                            PreferenceItem(
                                title = word,
                                leading = if (isSelectionMode) {
                                    {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = null
                                        )
                                    }
                                } else null,
                                iconSpaceReserved = true,
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else null,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedWords = if (isSelected) {
                                            selectedWords - word
                                        } else {
                                            selectedWords + word
                                        }
                                    } else {
                                        // Start selection mode on long press would be better, but click for now
                                        isSelectionMode = true
                                        selectedWords = setOf(word)
                                    }
                                },
                                trailing = if (!isSelectionMode) {
                                    {
                                        Box {
                                            IconButton(onClick = { showWordMenu = word }) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.MenuBook,
                                                    "Options",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showWordMenu == word,
                                                onDismissRequest = { showWordMenu = null }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Add to dictionary") },
                                                    onClick = {
                                                        showWordMenu = null
                                                        promoteToUserDictionary(word)
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Add, null)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Delete") },
                                                    onClick = {
                                                        showWordMenu = null
                                                        deleteWords(listOf(word))
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Delete, null)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else null,
                            )
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
