package dev.bbkb.ime.core.settings.screens.keyeditor

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.keyboard.slideboard.NumericSubpanelKeyboardView

/** The marker a blank slot is stored as, shared with `CustomSymbolRepository`. */
internal const val EMPTY_SLOT = "\u0000"

/** Index of the "Custom" tab in [CATEGORY_TABS] — the only one with an Add/Delete bar. */
internal const val CUSTOM_TAB = 8

/** Index of the "Emoji" tab, the only category large enough to need paging. */
internal const val EMOJI_TAB = 0

/**
 * The nine palette categories, in the order `CustomSymbolRepository.loadCategorySymbols()`
 * numbers them. Tab index *is* the category index; do not reorder.
 */
internal val CATEGORY_TABS = listOf(
    R.string.symbol_list_category_emoji_title,
    R.string.symbol_list_category_alphanumeric_title,
    R.string.symbol_list_category_punctuation_title,
    R.string.symbol_list_category_accents_title,
    R.string.symbol_list_category_brackets_title,
    R.string.symbol_list_category_currency_title,
    R.string.symbol_list_category_math_title,
    R.string.symbol_list_category_arrows_title,
    R.string.symbol_list_category_custom_title,
)

/** One entry of the overflow menu: a label and the layout it replaces the current one with. */
internal data class Reset(
    @StringRes val label: Int,
    /** @param slots how many editable slots the layout currently has. */
    val layout: (context: Context, slots: Int) -> List<String>,
)

/**
 * Everything that differs between the three key-layout editors — the VKB symbol page, the PKB
 * symbol page and the slideboard numpad. [KeyLayoutEditorScreen] renders any of them.
 *
 * `load` and `save` speak in slots: reading order over the board's editable keys. The stored
 * order differs for the on-screen page and the pad (each has one table, shared with the keyboard);
 * getting one backwards silently scrambles every existing user's layout, so
 * `KeyLayoutEditorBehaviourTest` pins all three against the keyboard the editor draws.
 */
@Immutable
internal class KeyEditorSpec(
    /** Distinguishes the three editors' ViewModels within one store. Not persisted. */
    val key: String,
    @StringRes val title: Int,
    /** The keyboard board drawn under the palette; its editable keys are the slots, in reading order. */
    val board: PreviewBoard,
    /**
     * For the number pad, which half of the keyboard strip it sits in: 0 left, 1 right (the
     * user's numpad side). `null` for a board that spans the whole width.
     */
    val half: (Context) -> Int?,
    val resets: List<Reset>,
    val load: (Context) -> List<String>,
    val save: (Context, List<String>) -> Unit,
    /**
     * Whether changing tab or applying a reset drops a pending palette selection. The slideboard
     * editor does; the symbol pages keep it.
     */
    val clearsSymbolSelectionOnContextChange: Boolean,
)

// ============================================================================
// The symbol pages
// ============================================================================

private fun symbolPageResets(isPkb: Boolean) = listOf(
    Reset(R.string.customized_symbols_page_reset_to_page_1) { c, _ ->
        CustomSymbolRepository(c).loadDefaultLayout(isPkb, 1)
    },
    Reset(R.string.customized_symbols_page_reset_to_page_2) { c, _ ->
        CustomSymbolRepository(c).loadDefaultLayout(isPkb, 2)
    },
    Reset(R.string.customized_symbols_page_reset_to_blank) { _, slots -> List(slots) { EMPTY_SLOT } },
    Reset(R.string.customized_symbols_page_reset_umlaut_layout) { c, _ ->
        CustomSymbolRepository(c).loadUmlautLayout(isPkb)
    },
)

private fun symbolPageSpec(isPkb: Boolean) = KeyEditorSpec(
    key = if (isPkb) "symbol_page_pkb" else "symbol_page_vkb",
    title = if (isPkb) R.string.settings_keyeditor_title_pkb else R.string.settings_keyeditor_title_vkb,
    board = if (isPkb) PreviewBoard.PKB_SYMBOLS else PreviewBoard.VKB_SYMBOLS,
    half = { null },
    resets = symbolPageResets(isPkb),
    load = { c -> CustomSymbolRepository(c).loadLayout(isPkb) },
    save = { c, layout -> CustomSymbolRepository(c).saveLayout(isPkb, layout) },
    clearsSymbolSelectionOnContextChange = false,
)

/** On-screen keyboard: 26 editable slots, in rows of 10, 9 and 7. */
internal val VkbSymbolPageSpec = symbolPageSpec(isPkb = false)

/**
 * Physical keyboard: 27 editable slots, the shape of `rows_symbols_custom_pkb.xml` — ten, nine,
 * then eight (the seven letter keys and the currency key).
 */
internal val PkbSymbolPageSpec = symbolPageSpec(isPkb = true)

// ============================================================================
// The slideboard numpad
// ============================================================================

private const val NUMPAD_PREF = "custom_slideboard_symbols"

/**
 * Editor position -> storage index for the slideboard's 4x5 numpad. Shared with the keyboard,
 * which matches keys by on-screen position through the same table.
 *
 * ```
 *   visual   0..4  : 7  8  9  &  *
 *   visual   5..9  : 4  5  6  $  %
 *   visual  10..14 : 1  2  3  @  #
 *   visual  15..19 : 0  .  ,  ?  !
 * ```
 *
 * Load reads `storage[order[i]]` into editor slot `i`; save writes editor slot `i` back to
 * `storage[order[i]]`. Reversing that scrambles every customised numpad, in silence.
 */
private val NUMPAD_KEYBOARD_READ_ORDER = NumericSubpanelKeyboardView.STORAGE_INDEX_FOR_POSITION

private fun numpadDefaults(context: Context) =
    context.resources.getStringArray(R.array.slideboard_numpad_symbols).toList()

internal val SlideboardNumpadSpec = KeyEditorSpec(
    key = "slideboard_numpad",
    title = R.string.settings_vkb_customize_numberpad_title,
    board = PreviewBoard.NUMPAD,
    half = { c ->
        PrefsManager.getPrefs(c).getString("slideboard_numeric_location", "1")?.toIntOrNull() ?: 1
    },
    resets = listOf(
        Reset(R.string.customize_slideboard_reset_to_default) { c, _ -> numpadDefaults(c) },
        Reset(R.string.customize_slideboard_clear_keypad) { _, slots -> List(slots) { EMPTY_SLOT } },
    ),
    load = { c ->
        val stored = SettingsManager.getStringListPref(PrefsManager.getPrefs(c), NUMPAD_PREF)
        // Anything short of a complete, fully-populated pad is treated as "never customised" and
        // falls back to the shipped defaults, which are already in visual order.
        if (stored.size != 20 || stored.any { it.isNullOrEmpty() }) {
            numpadDefaults(c)
        } else {
            NUMPAD_KEYBOARD_READ_ORDER.map { storagePos ->
                if (storagePos < stored.size) stored[storagePos] else EMPTY_SLOT
            }
        }
    },
    save = { c, layout ->
        val storage = MutableList(20) { EMPTY_SLOT }
        NUMPAD_KEYBOARD_READ_ORDER.forEachIndexed { editorPos, storagePos ->
            if (editorPos < layout.size && storagePos < storage.size) {
                storage[storagePos] = layout[editorPos]
            }
        }
        SettingsManager.setStringListPref(PrefsManager.getPrefs(c), NUMPAD_PREF, storage)
    },
    clearsSymbolSelectionOnContextChange = true,
)
