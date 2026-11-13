package dev.bbkb.ime.core.settings.screens.keyeditor

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.KeyboardColorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The keyboard under the palette takes at most this share of the screen below the app bar. */
private const val MAX_KEYBOARD_SHARE = 0.5f

/**
 * Tap-to-select / tap-to-swap key-layout editor for any [KeyEditorSpec]: a symbol palette over
 * the board, drawn by the keyboard's own code ([KeyEditorPreviewView]). Tap a symbol then a key
 * to assign, two keys to swap; the trash blanks a key or deletes a custom symbol, the overflow
 * applies a reset. Edits persist immediately; reset, clear and delete can be undone.
 */
@Composable
internal fun KeyLayoutEditorScreen(spec: KeyEditorSpec, onBack: () -> Unit) {
    val context = LocalContext.current
    // Settings can open before the keyboard has started in this process; the board needs its
    // colours and current language set up.
    remember {
        KeyboardColorManager.ensureInitialized(context)
        SubtypeManager.ensureInitialized(context.applicationContext)
    }
    val viewModel: KeyEditorViewModel = viewModel(
        key = spec.key,
        factory = KeyEditorViewModelFactory(context.applicationContext as Application, spec),
    )

    var showAddDialog by remember { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }
    UndoSnackbar(viewModel, snackbars)

    SettingsScaffold(
        title = stringResource(spec.title),
        onNavigateBack = onBack,
        actions = { EditorTopBarActions(spec, viewModel) },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val maxKeyboardHeight = maxHeight * MAX_KEYBOARD_SHARE
            Column(modifier = Modifier.fillMaxSize()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = viewModel.currentTabIndex,
                    containerColor = LocalSettingsColors.current.page,
                    edgePadding = 0.dp,
                ) {
                    CATEGORY_TABS.forEachIndexed { index, titleRes ->
                        Tab(
                            selected = viewModel.currentTabIndex == index,
                            onClick = { viewModel.selectTab(index) },
                            text = { Text(stringResource(titleRes)) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    SymbolPalette(viewModel, onAdd = { showAddDialog = true })
                }

                Text(
                    text = stringResource(R.string.settings_keyeditor_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                )

                KeyboardPreview(spec, viewModel, maxKeyboardHeight)
            }
        }

        if (showAddDialog) {
            AddSymbolDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { hex -> viewModel.addCustomSymbol(hex) }
            )
        }
    }
}

/** Offers Undo for the edit the ViewModel is holding one for, until something else is edited. */
@Composable
private fun UndoSnackbar(viewModel: KeyEditorViewModel, snackbars: SnackbarHostState) {
    val pending = viewModel.pendingUndo
    val message = when (pending?.what) {
        EditorNotice.LAYOUT_RESET -> stringResource(R.string.settings_keyeditor_layout_reset)
        EditorNotice.KEY_CLEARED -> stringResource(R.string.settings_keyeditor_key_cleared)
        EditorNotice.SYMBOL_DELETED -> stringResource(R.string.settings_keyeditor_symbol_deleted)
        null -> null
    }
    val undo = stringResource(R.string.ckb_action_undo)
    // Keyed on the edit: another edit cancels this, which takes the stale snackbar down with it.
    LaunchedEffect(pending) {
        if (message != null) {
            val result = snackbars.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        }
    }
}

/** The top bar's action icons: the trash for what is selected, and the resets overflow. */
@Composable
private fun RowScope.EditorTopBarActions(spec: KeyEditorSpec, viewModel: KeyEditorViewModel) {
    var showMenu by remember { mutableStateOf(false) }
    val selectedCustomSymbol = viewModel.selectedSymbol?.takeIf {
        viewModel.currentTabIndex == CUSTOM_TAB && it in viewModel.customSymbols
    }
    // One trash, for whichever thing is selected: a key (blank it) or a custom symbol (delete it).
    // A key and a palette symbol are never selected together; the second tap assigns.
    if (viewModel.selectedKeyIndex != null) {
        IconButton(onClick = { viewModel.clearSelectedKey() }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_delete), stringResource(R.string.settings_keyeditor_clear_key))
        }
    } else if (selectedCustomSymbol != null) {
        IconButton(onClick = { viewModel.deleteCustomSymbol(selectedCustomSymbol) }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_delete), stringResource(R.string.clipboard_menu_delete))
        }
    }
    // Boxed with its button so the menu anchors at the overflow icon.
    Box {
        IconButton(onClick = { showMenu = true }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_more_vert), stringResource(R.string.settings_keyeditor_menu))
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            spec.resets.forEach { reset ->
                DropdownMenuItem(
                    text = { Text(stringResource(reset.label)) },
                    onClick = { viewModel.applyReset(reset); showMenu = false }
                )
            }
        }
    }
}

@Composable
private fun SymbolPalette(viewModel: KeyEditorViewModel, onAdd: () -> Unit) {
    val tab = viewModel.currentTabIndex
    val isCustomTab = tab == CUSTOM_TAB
    // remember-ed: every key tap recomposes, and this filters the whole category.
    val symbols = remember(tab, viewModel.categories, viewModel.customSymbols.toList()) {
        viewModel.symbolsFor(tab)
    }

    if (!isCustomTab && viewModel.categories.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.settings_keyeditor_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(symbols) { symbol ->
            SymbolGridItem(
                symbol = symbol,
                isSelected = viewModel.selectedSymbol == symbol,
                onClick = { viewModel.onSymbolTapped(symbol) }
            )
        }
        if (isCustomTab) {
            item { AddSymbolCell(onAdd) }
        }
    }
}

private val PaletteCellShape = RoundedCornerShape(12.dp)

/** A palette symbol: a 48dp target, drawn in Settings' own colours (it sits on the Settings page). */
@Composable
private fun SymbolGridItem(symbol: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = isSelected, role = Role.Button, onClick = onClick)
            .padding(2.dp)
            .clip(PaletteCellShape)
            .background(if (isSelected) colors.secondaryContainer else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = if (isSelected) colors.onSecondaryContainer else colors.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** The Custom tab's last cell: add a symbol by its Unicode code. */
@Composable
private fun AddSymbolCell(onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onAdd)
            .padding(2.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, PaletteCellShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            ImageVector.vectorResource(R.drawable.ic_settings_m_add),
            stringResource(R.string.customized_symbol_page_add_button_description),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AddSymbolDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = { if (onAdd(text)) onDismiss() else isError = true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customized_symbol_page_add_button_description)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    // Hex digits only, up to a six-digit codepoint.
                    if (it.length <= 6 && it.all { c -> c.isDigit() || c in 'A'..'F' || c in 'a'..'f' }) {
                        text = it.uppercase()
                        isError = false
                    }
                },
                label = { Text(stringResource(R.string.settings_keyeditor_hex_code)) },
                isError = isError,
                supportingText = if (isError) {
                    { Text(stringResource(R.string.settings_keyeditor_invalid_code)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotEmpty(), onClick = submit) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * The keyboard's own view and layout with the ViewModel's layout on its editable keys. The
 * number pad takes its half of the strip, on the user's side.
 */
@Composable
private fun KeyboardPreview(spec: KeyEditorSpec, viewModel: KeyEditorViewModel, maxHeight: Dp) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val themed = remember(context) { EditorKeyboard.themedContext(context) }
    val half = remember { spec.half(context) }
    val heightPx = remember(maxHeight) {
        EditorKeyboard.heightPx(context, spec.board, with(density) { maxHeight.roundToPx() })
    }
    val selectionColor = MaterialTheme.colorScheme.primary.toArgb()
    val emptyKey = stringResource(R.string.settings_keyeditor_empty_key)

    // The preview may have drawn an unsaved layout on a cached keyboard; drop the cache on exit.
    DisposableEffect(Unit) {
        onDispose { KeyboardBuilder.onKeyboardThemeChanged() }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(density) { heightPx.toDp() })
            .background(Color(KeyboardColorManager.backgroundColor))
    ) {
        val widthPx = constraints.maxWidth
        // Layout XML parsing is slow, so it runs off the main thread.
        val board by produceState<EditorKeyboard?>(null, widthPx, heightPx) {
            value = withContext(Dispatchers.Default) {
                // A board that cannot be built leaves the strip empty rather than crashing Settings.
                runCatching { EditorKeyboard.build(themed, spec.board, widthPx, heightPx) }
                    .onFailure { Logger.error("KeyLayoutEditor", "Could not build the ${spec.board} board: $it") }
                    .getOrNull()
            }
        }
        val current = board ?: return@BoxWithConstraints
        // Read here so an edit or a selection recomposes this and runs `update` below.
        val layout = viewModel.layout.toList()
        val selected = viewModel.selectedKeyIndex ?: -1

        Row(modifier = Modifier.fillMaxSize()) {
            if (half == 1) Box(Modifier.weight(1f).fillMaxHeight())
            AndroidView(
                factory = {
                    val view = if (spec.board == PreviewBoard.NUMPAD) NumpadPreviewView(themed) else KeyEditorPreviewView(themed)
                    view.onSlotTapped = { slot -> viewModel.onKeyTapped(slot) }
                    view
                },
                update = { view ->
                    current.show(layout)
                    view.bind(current)
                    view.selectedSlot = selected
                    view.selectionColor = selectionColor
                    view.emptyKeyDescription = emptyKey
                },
            )
        }
    }
}
