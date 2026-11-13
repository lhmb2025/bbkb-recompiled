package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.zIndex
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSettingsColors
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.groupOuterCorners
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.settings.ui.Spacing
import dev.bbkb.ime.keyboard.inputboard.UimMenuOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Long-press-drag reordering of the input menu toggles. Positions 1 to 4 are the bar slots (the
 * fixed center key is not listed); items below the "not shown" marker are hidden. All cells share
 * one height so the drag arithmetic works in whole slots.
 */

/**
 * Height of every cell (toggles and marker alike), which the drag arithmetic relies on; Material 3's
 * one-line list item.
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
    // One slot is a cell plus the group gap below it.
    val rowHeightPx = with(LocalDensity.current) { (ROW_HEIGHT + spacing.groupGap).toPx() }

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

    SettingsScaffold(
        title = context.getString(R.string.settings_customize_menu_title),
        onNavigateBack = onNavigateBack,
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
            // One column, not two PreferenceGroups: a dragged row must keep its composition (and
            // gesture) across the marker, and a group's clip would cut it off.
            Column(verticalArrangement = Arrangement.spacedBy(spacing.groupGap)) {
                val shownCount = minOf(order.size, UimMenuOrder.SHOWN_COUNT)
                for (i in order.indices) {
                    if (i == UimMenuOrder.SHOWN_COUNT) {
                        HiddenSectionRow()
                    }
                    val id = order[i]
                    key(id) {
                        ToggleRow(
                            id = id,
                            position = if (i < UimMenuOrder.SHOWN_COUNT) i + 1 else null,
                            // Each section's first and last cards carry the group's outer corners.
                            edge = if (i < shownCount) {
                                groupEdge(i, shownCount)
                            } else {
                                groupEdge(i - shownCount, order.size - shownCount)
                            },
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
            }
            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

@Composable
private fun ToggleRow(
    id: String,
    position: Int?,
    edge: GroupEdge,
    isDragging: Boolean,
    offsetY: Float,
    onDragStart: () -> Unit,
    onDragBy: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val context = LocalContext.current
    val spacing = LocalSpacing.current
    val info = toggleInfo(id)
    val rowShape = LocalShapes.current.row
    Surface(
        shape = rowShape,
        // Opaque card colour, or a dragged row reads as a ghost sliding behind the others.
        color = if (isDragging) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            LocalSettingsColors.current.card
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .zIndex(if (isDragging) 1f else 0f)
            // The group inset, outside the layer, so the lifted row's shadow is the card's shape.
            .padding(horizontal = spacing.groupInset)
            .graphicsLayer {
                translationY = if (isDragging) offsetY else 0f
                shadowElevation = if (isDragging) 8f else 0f
                this.shape = rowShape
            }
            // Painted inside the layer so they travel with the row; a lifted row drops them.
            .groupOuterCorners(if (isDragging) GroupEdge.Middle else edge)
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
                imageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_drag_handle),
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
 * The boundary after the four bar slots: one whole [ROW_HEIGHT] cell (the drag arithmetic counts
 * it), no rule. The label is a sentence-case statement, not a category name.
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
                .padding(start = spacing.categoryStartPadding, end = spacing.listItemPadding)
        )
    }
}
