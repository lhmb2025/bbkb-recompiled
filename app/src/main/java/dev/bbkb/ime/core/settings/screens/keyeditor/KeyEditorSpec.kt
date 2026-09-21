package dev.bbkb.ime.core.settings.screens.keyeditor

import android.content.Context
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.R

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

/** One cell of the mock keyboard drawn under the palette. */
internal sealed interface Slot {
    val weight: Float

    /** An editable slot; [index] addresses the layout list. */
    data class Key(val index: Int, override val weight: Float = 1f) : Slot

    /** A key the editor draws but cannot change (space, enter, the page toggle…). */
    data class Fixed(
        val text: String? = null,
        @DrawableRes val icon: Int? = null,
        override val weight: Float = 1f,
    ) : Slot

    /** Empty space, used for the half-key insets on the VKB's second row. */
    data class Gap(override val weight: Float) : Slot
}

/** Per-editor mock-keyboard metrics. The two editors were drawn to different numbers. */
@Immutable
internal data class KeyStyle(
    /** Inset around each key. The symbol pages space keys this way; the numpad does not. */
    val keyPadding: Dp,
    /** Gap between rows and between keys in a row. The numpad spaces keys this way. */
    val keySpacing: Dp,
    val fontSize: TextUnit,
    val selectedBorder: Dp,
    val unselectedBorder: Dp,
)

/**
 * The symbol pages: a 1dp border is always reserved, so selecting a key tints it rather than
 * nudging its neighbours.
 */
internal val SymbolPageKeyStyle = KeyStyle(
    keyPadding = 1.dp,
    keySpacing = 0.dp,
    fontSize = 16.sp,
    selectedBorder = 1.dp,
    unselectedBorder = 1.dp,
)

/** The numpad: wider gaps, larger glyphs, and a border that appears only on selection. */
internal val NumpadKeyStyle = KeyStyle(
    keyPadding = 0.dp,
    keySpacing = 4.dp,
    fontSize = 18.sp,
    selectedBorder = 2.dp,
    unselectedBorder = 0.dp,
)

/** How an editor's palette gets its nine category lists. */
internal enum class PaletteLoading {
    /**
     * Resolved per tab as it is shown, on the composition thread, and the emoji category arrives
     * a page at a time as the grid is scrolled.
     */
    PER_TAB,

    /**
     * All nine categories snapshotted once on `Dispatchers.IO` — `getCategories` runs the whole
     * Emojibase parse plus seven `getStringArray` reads, and doing that inside composition janked
     * this screen's first frame. Emoji are capped rather than paged, and until the snapshot lands
     * the palette shows a "Loading symbols…" placeholder.
     */
    DEFERRED_SNAPSHOT,
}

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
 * The `load`/`save` pair is the dangerous part and is deliberately the *only* place a permutation
 * between on-screen position and storage index lives, because getting one backwards silently
 * scrambles every existing user's layout. `KeyLayoutEditorBehaviourTest` pins all three.
 */
@Immutable
internal class KeyEditorSpec(
    /** Distinguishes the three editors' ViewModels within one store. Not persisted. */
    val key: String,
    @StringRes val title: Int,
    val rows: List<List<Slot>>,
    @DimenRes val height: Int,
    val style: KeyStyle,
    /**
     * Which half of the keyboard strip the mock keyboard occupies: `null` to span the whole
     * width, or 0/1 for the left/right half (the slideboard follows the user's numpad side).
     */
    val half: (Context) -> Int?,
    val resets: List<Reset>,
    val load: (Context) -> List<String>,
    val save: (Context, List<String>) -> Unit,
    val paletteLoading: PaletteLoading,
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

private fun symbolPageSpec(
    isPkb: Boolean,
    rows: List<List<Slot>>,
    @DimenRes height: Int,
) = KeyEditorSpec(
    key = if (isPkb) "symbol_page_pkb" else "symbol_page_vkb",
    title = R.string.customize_symbol_page_title,
    rows = rows,
    height = height,
    style = SymbolPageKeyStyle,
    half = { null },
    resets = symbolPageResets(isPkb),
    load = { c -> CustomSymbolRepository(c).loadLayout(isPkb) },
    save = { c, layout -> CustomSymbolRepository(c).saveLayout(isPkb, layout) },
    paletteLoading = PaletteLoading.PER_TAB,
    clearsSymbolSelectionOnContextChange = false,
)

/** On-screen keyboard: 4 rows, 26 editable slots, the bottom row entirely fixed. */
internal val VkbSymbolPageSpec = symbolPageSpec(
    isPkb = false,
    height = R.dimen.config_symbol_page_height,
    rows = listOf(
        (0..9).map { Slot.Key(it) },
        listOf(Slot.Gap(0.5f)) + (10..18).map { Slot.Key(it) } + Slot.Gap(0.5f),
        listOf(Slot.Fixed(text = "1/2", weight = 3f)) +
            (19..25).map { Slot.Key(it, weight = 2f) } +
            Slot.Fixed(icon = R.drawable.ic_key_delete, weight = 3f),
        listOf(
            Slot.Fixed(text = "?123", weight = 3f),
            Slot.Fixed(icon = R.drawable.ic_key_voice, weight = 2f),
            Slot.Fixed(icon = R.drawable.ic_settings_space, weight = 10f),
            Slot.Fixed(text = "...", weight = 2f),
            Slot.Fixed(icon = R.drawable.ic_key_return, weight = 3f),
        ),
    ),
)

/** Physical keyboard: 3 rows, 28 editable slots. */
internal val PkbSymbolPageSpec = symbolPageSpec(
    isPkb = true,
    height = R.dimen.config_pkb_symbol_page_height,
    rows = listOf(
        (0..9).map { Slot.Key(it) },
        (10..18).map { Slot.Key(it) } + Slot.Fixed(icon = R.drawable.ic_key_delete),
        listOf(Slot.Fixed(text = "alt")) +
            (19..27).map { Slot.Key(it) } +
            Slot.Fixed(icon = R.drawable.ic_key_return),
    ),
)

// ============================================================================
// The slideboard numpad
// ============================================================================

private const val NUMPAD_PREF = "custom_slideboard_symbols"

/**
 * Editor position -> storage index for the slideboard's 4x5 numpad, determined empirically: the
 * keyboard's `TreeSet` sorts keys by row then column, but key parsing and the row structure leave
 * the stored order matching neither.
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
private val NUMPAD_KEYBOARD_READ_ORDER = intArrayOf(
    15, 16, 17, 4, 5,
    12, 13, 14, 2, 3,
    9, 10, 11, 19, 1,
    8, 7, 6, 18, 0,
)

private fun numpadDefaults(context: Context) =
    context.resources.getStringArray(R.array.slideboard_numpad_symbols).toList()

internal val SlideboardNumpadSpec = KeyEditorSpec(
    key = "slideboard_numpad",
    title = R.string.customize_slideboard_enable,
    rows = (0 until 4).map { row -> (0 until 5).map { col -> Slot.Key(row * 5 + col) } },
    height = R.dimen.config_symbol_page_height,
    style = NumpadKeyStyle,
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
    paletteLoading = PaletteLoading.DEFERRED_SNAPSHOT,
    clearsSymbolSelectionOnContextChange = true,
)
