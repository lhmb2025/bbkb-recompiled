package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.Spacing
import dev.bbkb.ime.keyboard.inputboard.UimMenuOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Customize Menu screen: long-press-drag reordering of the Unified Input Menu toggles.
 *
 * Positions (1)-(4) are the four bar slots (the fixed center Keyboard/Settings key is
 * not listed — it cannot move); below the "not shown" marker, items are not shown on the
 * keyboard. All cells (toggles and that marker) share one height so the drag arithmetic
 * can work in whole visual slots.
 */

/**
 * Every cell — a toggle and the "not shown" marker alike — is exactly one of these, which is what
 * lets the drag arithmetic work in whole visual slots.
 *
 * Material 3's one-line list item, so a row here is the height of a row anywhere else in settings.
 * It was 64dp, chosen before [Spacing.oneLineListItemHeight] existed, which made this the only
 * screen whose rows did not line up with the screen it is reached from.
 */
private val ROW_HEIGHT = Spacing().oneLineListItemHeight

private data class UimToggleInfo(val labelRes: Int, val iconRes: Int)

private fun toggleInfo(id: String): UimToggleInfo = when (id) {
    UimMenuOrder.VOICE -> UimToggleInfo(R.string.settings_uim_toggle_voice, R.drawable.ic_auxbar_voice_enabled)
    UimMenuOrder.EMOJI -> UimToggleInfo(R.string.settings_uim_toggle_emoji, R.drawable.ic_auxbar_emoji_enabled)
    UimMenuOrder.FCC -> UimToggleInfo(R.string.settings_uim_toggle_fcc, R.drawable.ic_auxbar_fcc_enabled)
    UimMenuOrder.CLIPBOARD -> UimToggleInfo(R.string.settings_uim_toggle_clipboard, R.drawable.ic_auxbar_clipboard_enabled)
    else -> UimToggleInfo(R.string.settings_uim_toggle_numpad, R.drawable.ic_auxbar_numpad)
}

/** Visual slot of a toggle at order index i: the "not shown" divider sits after 4. */
private fun visualSlotOf(i: Int): Int =
    if (i < UimMenuOrder.SHOWN_COUNT) i else i + 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizeMenuScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    val order = remember { mutableStateListOf<String>().apply { addAll(UimMenuOrder.getOrder(prefs)) } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    fun onDragBy(id: String, dy: Float) {
        val i = order.indexOf(id)
        if (i < 0) return
        dragOffset += dy
        val fromSlot = visualSlotOf(i)
        val targetSlot = fromSlot + (dragOffset / rowHeightPx).roundToInt()
        var best = i
        var bestDist = Int.MAX_VALUE
        for (j in order.indices) {
            val d = abs(visualSlotOf(j) - targetSlot)
            if (d < bestDist) {
                bestDist = d
                best = j
            }
        }
        if (best != i) {
            order.add(best, order.removeAt(i))
            dragOffset -= (visualSlotOf(best) - fromSlot) * rowHeightPx
        }
    }

    fun onDragFinished() {
        draggingId = null
        dragOffset = 0f
        UimMenuOrder.saveOrder(prefs, order.toList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_customize_menu_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceInfo(
                text = context.getString(R.string.settings_customize_menu_instructions),
                modifier = Modifier.settingsSearchAnchor("customize_menu"),
            )
            for (i in order.indices) {
                if (i == UimMenuOrder.SHOWN_COUNT) {
                    HiddenSectionRow()
                }
                val id = order[i]
                key(id) {
                    ToggleRow(
                        id = id,
                        position = if (i < UimMenuOrder.SHOWN_COUNT) i + 1 else null,
                        isDragging = draggingId == id,
                        offsetY = dragOffset,
                        onDragStart = {
                            draggingId = id
                            dragOffset = 0f
                        },
                        onDragBy = { dy -> onDragBy(id, dy) },
                        onDragEnd = { onDragFinished() }
                    )
                }
            }
            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

@Composable
private fun ToggleRow(
    id: String,
    position: Int?,
    isDragging: Boolean,
    offsetY: Float,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val info = toggleInfo(id)
    Surface(
        // surface, not Color.Transparent: a dragged row lifts off the page, and the rows it passes
        // over have to be opaque or it reads as a ghost sliding behind them.
        color = if (isDragging) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (isDragging) offsetY else 0f
                shadowElevation = if (isDragging) 8f else 0f
            }
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragBy(dragAmount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() }
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.listItemPadding)
                .graphicsLayer { alpha = if (position == null) 0.6f else 1f }
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(spacing.iconTextGap))
            Icon(
                painter = painterResource(info.iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(spacing.iconSize)
            )
            Spacer(modifier = Modifier.width(spacing.iconTextGap))
            Text(
                text = context.getString(info.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (position != null) {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The boundary between the four bar slots and everything else.
 *
 * Still one whole [ROW_HEIGHT] cell, because the drag arithmetic counts it as a slot — but no rule
 * across it. The gap either side of the label is the separation, which is how every other section
 * boundary in settings is drawn. The label keeps its own wording and its own sentence case: it is a
 * statement about the rows under it ("Not shown on keyboard"), not a category name.
 */
@Composable
private fun HiddenSectionRow() {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
    ) {
        Text(
            text = context.getString(R.string.settings_customize_menu_hidden_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = spacing.listItemPadding)
        )
    }
}
