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
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository
import timber.log.Timber

/** Emoji per page on the [PaletteLoading.PER_TAB] path, appended as the grid is scrolled. */
private const val EMOJI_PAGE_SIZE = 200

/** Emoji offered on the [PaletteLoading.DEFERRED_SNAPSHOT] path, which does not page. */
private const val DEFERRED_EMOJI_CAP = 200

/**
 * State for one [KeyEditorSpec]: the layout being edited, the palette, and the tap state machine
 * that connects them.
 *
 * Every edit persists immediately through `spec.save`, which is also what invalidates
 * `KeyboardBuilder`'s keyboard cache — there is no separate "save" action and never was.
 */
internal class KeyEditorViewModel(
    application: Application,
    private val spec: KeyEditorSpec,
) : AndroidViewModel(application) {

    private val repository = CustomSymbolRepository(application)

    /** The layout in *editor* order. `spec.load`/`spec.save` own the storage permutation. */
    val layout = mutableStateListOf<String>()

    var selectedKeyIndex by mutableStateOf<Int?>(null)
        private set

    var selectedSymbol by mutableStateOf<String?>(null)
        private set

    var currentTabIndex by mutableIntStateOf(0)
        private set

    val customSymbols = mutableStateListOf<String>()

    // Emoji paging, [PaletteLoading.PER_TAB] only.
    private var allEmojis: List<String> = emptyList()
    private val displayedEmojis = mutableStateListOf<String>()
    private var currentEmojiPage = 0
    var hasMoreEmojis by mutableStateOf(false)
        private set

    /** Observable page marker: the palette re-reads when another emoji page arrives. */
    val displayedEmojiCount: Int get() = displayedEmojis.size

    /** All nine categories, [PaletteLoading.DEFERRED_SNAPSHOT] only; empty until the load lands. */
    var deferredCategories by mutableStateOf<List<List<String>>>(emptyList())

    private var isInitialized = false

    // ========================================================================
    // Loading
    // ========================================================================

    fun initialize() {
        if (isInitialized) return
        layout.clear()
        layout.addAll(spec.load(getApplication()))
        refreshCustomSymbols()
        if (spec.paletteLoading == PaletteLoading.PER_TAB) {
            allEmojis = repository.loadCategorySymbols(EMOJI_TAB)
            loadMoreEmojis()
        }
        isInitialized = true
        Timber.d("KeyEditorViewModel initialized for ${spec.key}, total emojis: ${allEmojis.size}")
    }

    fun loadMoreEmojis() {
        val start = currentEmojiPage * EMOJI_PAGE_SIZE
        if (start >= allEmojis.size) {
            hasMoreEmojis = false
            return
        }
        val end = minOf(start + EMOJI_PAGE_SIZE, allEmojis.size)
        displayedEmojis.addAll(allEmojis.subList(start, end))
        currentEmojiPage++
        hasMoreEmojis = end < allEmojis.size
        Timber.d("Loaded emoji page $currentEmojiPage, total displayed: ${displayedEmojis.size}")
    }

    /**
     * The blocking half of [PaletteLoading.DEFERRED_SNAPSHOT] — the whole Emojibase parse plus
     * seven `getStringArray` reads. The caller runs this off the main thread and hands the result
     * back through [deferredCategories].
     */
    fun readAllCategories(): List<List<String>> = List(CATEGORY_TABS.size) { category ->
        when (category) {
            EMOJI_TAB -> repository.loadCategorySymbols(category).take(DEFERRED_EMOJI_CAP)
            CUSTOM_TAB -> emptyList() // supplied by [customSymbols] instead
            else -> repository.loadCategorySymbols(category)
        }
    }

    /** The palette contents for one tab, blanks removed. */
    fun symbolsFor(tab: Int): List<String> {
        val raw = when {
            tab == CUSTOM_TAB -> customSymbols
            spec.paletteLoading == PaletteLoading.DEFERRED_SNAPSHOT ->
                deferredCategories.getOrNull(tab).orEmpty()
            tab == EMOJI_TAB -> displayedEmojis
            else -> repository.loadCategorySymbols(tab)
        }
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
        layout[keyIndex] = symbol
        selectedKeyIndex = null
        selectedSymbol = null
        persist()
    }

    private fun swap(from: Int, to: Int) {
        if (from !in layout.indices || to !in layout.indices) return
        val held = layout[from]
        layout[from] = layout[to]
        layout[to] = held
        selectedKeyIndex = null
        persist()
    }

    fun clearSelectedKey() {
        val index = selectedKeyIndex ?: return
        if (index !in layout.indices) return
        layout[index] = EMPTY_SLOT
        selectedKeyIndex = null
        persist()
    }

    /**
     * A preset replaces as much of the layout as it covers and never resizes it — `pkb_page_2` is
     * 27 entries against 28 slots, and the odd slot out keeps whatever it held.
     */
    fun applyReset(reset: Reset) {
        val replacement = reset.layout(getApplication(), layout.size)
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
        repository.removeCustomSymbol(symbol)
        refreshCustomSymbols()
        if (selectedSymbol == symbol) selectedSymbol = null
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
