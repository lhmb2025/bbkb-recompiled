package dev.bbkb.ime.core.settings.screens.keyeditor

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one tap-to-select / tap-to-swap key-layout editor, shared by the VKB symbol page, the PKB
 * symbol page and the slideboard numpad. A [KeyEditorSpec] supplies everything that differs.
 *
 * Layout, top to bottom: a nine-tab symbol palette, the palette grid itself (with an Add/Delete
 * bar on the Custom tab), and a mock keyboard built by walking `spec.rows`. Tap a palette symbol
 * then a key to assign it, tap two keys to swap them, use the app bar's trash to blank the
 * selected key, or the overflow to apply one of `spec.resets`. Every edit persists immediately.
 *
 * This is *not* the drag-reorder editor: `CustomizeMenuScreen` is a different interaction and
 * shares nothing useful with this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyLayoutEditorScreen(spec: KeyEditorSpec, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: KeyEditorViewModel = viewModel(
        key = spec.key,
        factory = KeyEditorViewModelFactory(context.applicationContext as Application, spec),
    )

    LaunchedEffect(Unit) { viewModel.initialize() }
    if (spec.paletteLoading == PaletteLoading.DEFERRED_SNAPSHOT) {
        LaunchedEffect(Unit) {
            viewModel.deferredCategories = withContext(Dispatchers.IO) { viewModel.readAllCategories() }
        }
    }

    val palette = KeyPalette(
        background = Color(KeyboardColorManager.backgroundColor),
        key = Color(KeyboardColorManager.keyColor),
        selected = Color(KeyboardColorManager.keyColorPressed),
        text = Color(KeyboardColorManager.textColor),
        hint = Color(KeyboardColorManager.hintColor),
    )

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = { EditorTopBar(spec, viewModel, onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            ScrollableTabRow(
                selectedTabIndex = viewModel.currentTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 0.dp
            ) {
                CATEGORY_TABS.forEachIndexed { index, titleRes ->
                    Tab(
                        selected = viewModel.currentTabIndex == index,
                        onClick = { viewModel.selectTab(index) },
                        text = { Text(stringResource(titleRes)) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                SymbolPalette(spec, viewModel, palette)
                if (viewModel.currentTabIndex == CUSTOM_TAB) {
                    CustomSymbolBar(viewModel, onAdd = { showAddDialog = true })
                }
            }

            MockKeyboard(spec, viewModel, palette)
        }

        if (showAddDialog) {
            AddSymbolDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { hex -> viewModel.addCustomSymbol(hex) }
            )
        }
    }
}

/** The live keyboard colours, read once per composition and threaded through the mock keyboard. */
private class KeyPalette(
    val background: Color,
    val key: Color,
    val selected: Color,
    val text: Color,
    val hint: Color,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(spec: KeyEditorSpec, viewModel: KeyEditorViewModel, onBack: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(spec.title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
        actions = {
            // The trash appears only while a key is selected.
            if (viewModel.selectedKeyIndex != null) {
                IconButton(onClick = { viewModel.clearSelectedKey() }) {
                    Icon(Icons.Default.Delete, "Clear selected key")
                }
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, "Menu")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                spec.resets.forEach { reset ->
                    DropdownMenuItem(
                        text = { Text(stringResource(reset.label)) },
                        onClick = { viewModel.applyReset(reset); showMenu = false }
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun SymbolPalette(spec: KeyEditorSpec, viewModel: KeyEditorViewModel, palette: KeyPalette) {
    // remember-ed: on the per-tab path this is `resources.getStringArray` plus two new lists, and
    // every key tap recomposes.
    val symbols = remember(
        viewModel.currentTabIndex,
        viewModel.customSymbols.size,
        viewModel.displayedEmojiCount,
        viewModel.deferredCategories,
    ) { viewModel.symbolsFor(viewModel.currentTabIndex) }

    val deferredAndStillLoading = spec.paletteLoading == PaletteLoading.DEFERRED_SNAPSHOT &&
        symbols.isEmpty() && viewModel.currentTabIndex != CUSTOM_TAB
    if (deferredAndStillLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading symbols...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 48.dp),
        contentPadding = PaddingValues(8.dp),
        state = rememberLazyGridState(),
        modifier = Modifier.fillMaxSize()
    ) {
        items(symbols) { symbol ->
            SymbolGridItem(
                symbol = symbol,
                isSelected = viewModel.selectedSymbol == symbol,
                textColor = palette.text,
                selectedColor = palette.selected,
                onClick = { viewModel.onSymbolTapped(symbol) }
            )
        }
        if (spec.paletteLoading == PaletteLoading.PER_TAB &&
            viewModel.currentTabIndex == EMOJI_TAB && viewModel.hasMoreEmojis
        ) {
            item {
                LaunchedEffect(Unit) { viewModel.loadMoreEmojis() }
            }
        }
    }
}

@Composable
private fun BoxScope.CustomSymbolBar(
    viewModel: KeyEditorViewModel,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, "Add")
        }
        IconButton(
            onClick = { viewModel.selectedSymbol?.let { viewModel.deleteCustomSymbol(it) } },
            enabled = viewModel.selectedSymbol != null
        ) {
            Icon(Icons.Default.Delete, "Delete")
        }
    }
}

@Composable
private fun SymbolGridItem(
    symbol: String,
    isSelected: Boolean,
    textColor: Color,
    selectedColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) selectedColor else Color.Transparent)
            .clickable(onClick = onClick)
            // Vestigial drag hook: it does nothing today, but it does consume the long-press
            // drag, so taking it out would change how the palette scrolls after a long press.
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { },
                    onDrag = { _, _ -> },
                    onDragEnd = { }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = symbol, color = textColor, fontSize = 20.sp)
    }
}

@Composable
private fun AddSymbolDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customized_symbol_page_add_button_description)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        // Hex digits only, up to a six-digit codepoint.
                        if (it.length <= 6 && it.all { c -> c.isDigit() || c in 'A'..'F' || c in 'a'..'f' }) {
                            text = it.uppercase()
                        }
                    },
                    label = { Text("Hex Code (e.g. 1F600)") },
                    isError = isError,
                    singleLine = true
                )
                if (isError) {
                    Text(
                        "Invalid character code",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (onAdd(text)) onDismiss() else isError = true }) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

// ============================================================================
// The mock keyboard
// ============================================================================

@Composable
private fun MockKeyboard(spec: KeyEditorSpec, viewModel: KeyEditorViewModel, palette: KeyPalette) {
    val context = LocalContext.current
    val half = remember { spec.half(context) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimensionResource(spec.height))
            .background(palette.background)
    ) {
        if (half == null) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp)) {
                KeyGrid(spec, viewModel, palette)
            }
        } else {
            // The slideboard splits the strip in two and draws the pad on the user's chosen side.
            repeat(2) { side ->
                Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp)) {
                    if (side == half) KeyGrid(spec, viewModel, palette)
                }
            }
        }
    }
}

@Composable
private fun KeyGrid(spec: KeyEditorSpec, viewModel: KeyEditorViewModel, palette: KeyPalette) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(spec.style.keySpacing)
    ) {
        spec.rows.forEach { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spec.style.keySpacing)
            ) {
                row.forEach { slot ->
                    when (slot) {
                        is Slot.Gap -> Spacer(Modifier.weight(slot.weight))
                        is Slot.Fixed -> FixedKey(slot, spec.style, palette)
                        is Slot.Key -> EditableKey(slot, viewModel, spec.style, palette)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.EditableKey(
    slot: Slot.Key,
    viewModel: KeyEditorViewModel,
    style: KeyStyle,
    palette: KeyPalette,
) {
    val stored = viewModel.layout.getOrNull(slot.index) ?: ""
    val isSelected = viewModel.selectedKeyIndex == slot.index

    Box(
        modifier = Modifier
            .weight(slot.weight)
            .padding(style.keyPadding)
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) palette.selected else palette.key)
            .border(
                width = if (isSelected) style.selectedBorder else style.unselectedBorder,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { viewModel.onKeyTapped(slot.index) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            // Both blank markers render as nothing.
            text = if (stored == EMPTY_SLOT || stored == "null") "" else stored,
            color = palette.text,
            fontSize = style.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RowScope.FixedKey(slot: Slot.Fixed, style: KeyStyle, palette: KeyPalette) {
    Box(
        modifier = Modifier
            .weight(slot.weight)
            .padding(style.keyPadding)
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(palette.hint.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        if (slot.icon != null) {
            Icon(
                painter = painterResource(id = slot.icon),
                contentDescription = null,
                tint = palette.text,
                modifier = Modifier.size(24.dp)
            )
        } else if (slot.text != null) {
            Text(text = slot.text, color = palette.text, fontSize = 14.sp)
        }
    }
}
