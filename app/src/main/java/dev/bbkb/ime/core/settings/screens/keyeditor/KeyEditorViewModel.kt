package dev.bbkb.ime.core.settings.screens.keyeditor

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor just did that can be undone; the screen shows it in a snackbar. */
internal enum class EditorNotice { LAYOUT_RESET, KEY_CLEARED, SYMBOL_DELETED }

/** One undoable edit; [serial] tells two of the same kind apart. */
internal data class PendingUndo(val what: EditorNotice, val serial: Int)

/**
 * State for one [KeyEditorSpec]: the layout being edited, the palette, and the tap state machine
 * that connects them.
 *
 * Every edit persists immediately through `spec.save`, which is also what invalidates
 * `KeyboardBuilder`'s keyboard cache — there is no separate "save" action and never was. The
 * three edits that can destroy work (a reset, clearing a key, deleting a custom symbol) keep what
 * they replaced until the next edit, for [undo].
 */
internal class KeyEditorViewModel(
    application: Application,
    private val spec: KeyEditorSpec,
) : AndroidViewModel(application) {

    private val repository = CustomSymbolRepository(application)

    /** The layout in slot order. `spec.load`/`spec.save` own the storage order. */
    val layout = mutableStateListOf<String>()

    var selectedKeyIndex by mutableStateOf<Int?>(null)
        private set

    var selectedSymbol by mutableStateOf<String?>(null)
        private set

    var currentTabIndex by mutableIntStateOf(0)
        private set

    val customSymbols = mutableStateListOf<String>()

    /**
     * The palette's categories by tab index, empty until the load lands: the emoji category is
     * the whole Emojibase parse when the keyboard has not run in this process yet, so all of it is
     * read off the main thread. The Custom tab's entry stays empty; [customSymbols] supplies it.
     */
    var categories by mutableStateOf<List<List<String>>>(emptyList())
        private set

    /** The edit [undo] would take back, or null once anything else has been edited. */
    var pendingUndo by mutableStateOf<PendingUndo?>(null)
        private set

    private var undoSerial = 0

    private var undoLayout: List<String>? = null
    private var undoCustomSymbols: List<String>? = null

    init {
        layout.addAll(spec.load(application))
        refreshCustomSymbols()
        viewModelScope.launch {
            categories = withContext(Dispatchers.IO) {
                List(CATEGORY_TABS.size) { category ->
                    if (category == CUSTOM_TAB) emptyList() else repository.loadCategorySymbols(category)
                }
            }
        }
    }

    /** The palette contents for one tab, blanks removed. */
    fun symbolsFor(tab: Int): List<String> {
        val raw = if (tab == CUSTOM_TAB) customSymbols else categories.getOrNull(tab).orEmpty()
        return raw.filter { it.isNotEmpty() && it != EMPTY_SLOT }
    }

    // ========================================================================
    // The tap state machine
    // ========================================================================

    fun selectTab(index: Int) {
        currentTabIndex = index
        if (spec.clearsSymbolSelectionOnContextChange) selectedSymbol = null
    }

    /**
     * Four transitions, and only four: a pending palette symbol lands on the key, a second key
     * swaps with the selected one, the selected key toggles off, or the key becomes selected.
     */
    fun onKeyTapped(index: Int) {
        val pending = selectedSymbol
        when {
            pending != null -> assign(pending, index)
            selectedKeyIndex != null && selectedKeyIndex != index -> swap(selectedKeyIndex!!, index)
            selectedKeyIndex == index -> selectedKeyIndex = null
            else -> selectedKeyIndex = index
        }
    }

    fun onSymbolTapped(symbol: String) {
        val target = selectedKeyIndex
        if (target != null) {
            assign(symbol, target)
        } else {
            selectedSymbol = if (selectedSymbol == symbol) null else symbol
        }
    }

    private fun assign(symbol: String, keyIndex: Int) {
        if (keyIndex !in layout.indices) return
        forgetUndo()
        layout[keyIndex] = symbol
        selectedKeyIndex = null
        selectedSymbol = null
        persist()
    }

    private fun swap(from: Int, to: Int) {
        if (from !in layout.indices || to !in layout.indices) return
        forgetUndo()
        val held = layout[from]
        layout[from] = layout[to]
        layout[to] = held
        selectedKeyIndex = null
        persist()
    }

    fun clearSelectedKey() {
        val index = selectedKeyIndex ?: return
        if (index !in layout.indices) return
        rememberLayout(EditorNotice.KEY_CLEARED)
        layout[index] = EMPTY_SLOT
        selectedKeyIndex = null
        persist()
    }

    /**
     * A preset replaces as much of the layout as it covers and never resizes it — `pkb_page_2` is
     * shorter than the on-screen page, and a slot it does not reach keeps whatever it held.
     */
    fun applyReset(reset: Reset) {
        val replacement = reset.layout(getApplication(), layout.size)
        rememberLayout(EditorNotice.LAYOUT_RESET)
        for (i in layout.indices) {
            if (i < replacement.size) layout[i] = replacement[i]
        }
        selectedKeyIndex = null
        if (spec.clearsSymbolSelectionOnContextChange) selectedSymbol = null
        persist()
    }

    // ========================================================================
    // Custom symbols
    // ========================================================================

    private fun refreshCustomSymbols() {
        customSymbols.clear()
        customSymbols.addAll(repository.loadCustomSymbols())
    }

    /** @return false when [hexInput] is not a codepoint that can be drawn. */
    fun addCustomSymbol(hexInput: String): Boolean {
        try {
            val codePoint = Integer.parseInt(hexInput, 16)
            if (Character.isDefined(codePoint) &&
                Character.getType(codePoint) != Character.PRIVATE_USE.toInt()
            ) {
                repository.addCustomSymbol(String(Character.toChars(codePoint)))
                refreshCustomSymbols()
                return true
            }
        } catch (e: Exception) {
            // Not a hex codepoint; the dialog shows its error state.
        }
        return false
    }

    fun deleteCustomSymbol(symbol: String) {
        forgetUndo()
        undoCustomSymbols = customSymbols.toList()
        pendingUndo = PendingUndo(EditorNotice.SYMBOL_DELETED, ++undoSerial)
        repository.removeCustomSymbol(symbol)
        refreshCustomSymbols()
        if (selectedSymbol == symbol) selectedSymbol = null
    }

    // ========================================================================
    // Undo
    // ========================================================================

    private fun rememberLayout(what: EditorNotice) {
        undoCustomSymbols = null
        undoLayout = layout.toList()
        pendingUndo = PendingUndo(what, ++undoSerial)
    }

    private fun forgetUndo() {
        undoLayout = null
        undoCustomSymbols = null
        pendingUndo = null
    }

    /** Puts back what the last reset, clear or delete replaced, if nothing has been edited since. */
    fun undo() {
        undoLayout?.let { previous ->
            for (i in layout.indices) {
                if (i < previous.size) layout[i] = previous[i]
            }
            selectedKeyIndex = null
            persist()
        }
        undoCustomSymbols?.let { previous ->
            repository.replaceCustomSymbols(previous)
            refreshCustomSymbols()
        }
        forgetUndo()
    }

    private fun persist() = spec.save(getApplication(), layout.toList())
}

internal class KeyEditorViewModelFactory(
    private val application: Application,
    private val spec: KeyEditorSpec,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KeyEditorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return KeyEditorViewModel(application, spec) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
