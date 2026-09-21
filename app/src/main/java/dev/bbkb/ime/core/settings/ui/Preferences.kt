package dev.bbkb.ime.core.settings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Base preference item composable - Foundation for all preference types
 * Follows Material 3 design guidelines with design system tokens
 */
@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    /** Alternative to [icon] for rows whose art is a drawable rather than a vector asset. */
    iconPainter: Painter? = null,
    /**
     * Alternative to [icon] for rows whose leading art is composed rather than drawn from an asset -
     * e.g. a short text badge. Laid out in the same 24dp column with the same gap, so titles still
     * align with icon-bearing rows.
     */
    leading: (@Composable () -> Unit)? = null,
    iconSpaceReserved: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /**
     * The row's own background. Defaults to the page's surface; a list with a selection mode
     * passes a tinted one for the rows that are selected, which is the only reason a settings row
     * is ever anything but flat.
     */
    containerColor: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = motion.fastSpec())
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable { onClick() }
                } else Modifier
            ),
        color = containerColor ?: MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                // The minimum applies to the WHOLE row, padding included. It used to sit inside the
                // 16dp vertical padding (min 48dp content), which made every single-line row
                // 16 + 48 + 16 = 80dp instead of Material 3's 56dp one-line list item. A 56dp row
                // still exceeds the 48dp touch target, which is what that minimum was for.
                .heightIn(min = spacing.oneLineListItemHeight)
                .padding(
                    horizontal = spacing.listItemPadding,
                    vertical = spacing.listItemVerticalPadding
                )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = spacing.iconTextGap)
                        .size(spacing.iconSize),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            } else if (iconPainter != null) {
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = spacing.iconTextGap)
                        .size(spacing.iconSize),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            } else if (leading != null) {
                Box(
                    modifier = Modifier
                        .padding(end = spacing.iconTextGap)
                        .size(spacing.iconSize),
                    contentAlignment = Alignment.Center
                ) {
                    leading()
                }
            } else if (iconSpaceReserved) {
                // Reserve space so text aligns with icon-bearing items
                Spacer(
                    modifier = Modifier
                        .padding(end = spacing.iconTextGap)
                        .size(spacing.iconSize)
                )
            }
            
            // Text Content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (trailing != null) spacing.iconTextGap else spacing.none)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,  // Standard for preference items
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
                if (summary != null) {
                    Spacer(modifier = Modifier.height(spacing.titleSummaryGap))
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
            }
            
            // Trailing content (switch, checkbox, chevron, etc.)
            trailing?.invoke()
        }
    }
}

/**
 * Switch preference composable - Material 3 switch with preference styling
 */
@Composable
fun SwitchPreference(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        modifier = modifier,
        onClick = if (enabled) { { onCheckedChange(!checked) } } else null,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled
            )
        }
    )
}

/**
 * List preference composable with Material 3 dialog
 * Displays a dialog with radio button selection
 */
@Composable
fun ListPreference(
    title: String,
    summary: String,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    entries: List<String>,
    entryValues: List<String>,
    value: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        modifier = modifier,
        onClick = if (enabled) { { showDialog = true } } else null
    )
    
    if (showDialog) {
        val spacing = LocalSpacing.current
        val shapes = LocalShapes.current
        
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                // Scrollable so long option lists (e.g. the multifunction-key picker) don't
                // overflow the dialog's max height and clip the last row into a text-less
                // "bubble". AlertDialog bounds the height; verticalScroll makes it reachable.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEachIndexed { index, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onValueChange(entryValues[index])
                                    showDialog = false
                                }
                                .padding(vertical = spacing.dialogListItemVerticalPadding),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == entryValues[index],
                                onClick = {
                                    onValueChange(entryValues[index])
                                    showDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(spacing.iconTextGap))
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = shapes.dialog
        )
    }
}

/**
 * Preference category header - Modern Android Material 3 styled section header
 */
@Composable
fun PreferenceCategory(
    title: String,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    
    Text(
        text = title.uppercase(),  // Modern Android uses uppercase for categories
        style = MaterialTheme.typography.labelLarge,  // Modern Android uses labelLarge (12sp medium)
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = spacing.listItemPadding,
                end = spacing.listItemPadding,
                top = spacing.categoryTopPadding,
                bottom = spacing.categoryBottomPadding
            )
    )
}

/**
 * Preference screen navigation item - Navigates to sub-screen
 */
@Composable
fun PreferenceScreen(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        modifier = modifier,
        onClick = onClick,
        trailing = { NavigateChevron(enabled) }
    )
}

/**
 * Preference screen navigation item with Painter icon - Navigates to sub-screen
 *
 * Was a hand copy of [PreferenceItem] plus a chevron, existing only because [PreferenceItem]
 * took an [ImageVector]. It now delegates, and the two rows are the same row by construction
 * rather than by two people keeping the padding and disabled alphas in step.
 */
@Composable
fun PreferenceScreen(
    title: String,
    summary: String? = null,
    icon: Painter,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    PreferenceItem(
        title = title,
        summary = summary,
        iconPainter = icon,
        enabled = enabled,
        modifier = modifier,
        onClick = onClick,
        trailing = { NavigateChevron(enabled) }
    )
}

/** The trailing affordance every navigation row draws. */
@Composable
private fun NavigateChevron(enabled: Boolean) {
    Icon(
        imageVector = Icons.Default.ChevronRight,
        contentDescription = "Navigate",
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
    )
}

/**
 * Simple preference item for informational purposes
 */
@Composable
fun SimplePreference(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        onClick = onClick
    )
}

/**
 * Text input preference with dialog
 */
@Composable
fun EditTextPreference(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    // Keyed on the incoming value: an unkeyed remember captures it at the row's first composition,
    // so a programmatic change (a reset, a search-highlight scroll, a sibling write) left the
    // dialog showing the stale value.
    var textValue by remember(value) { mutableStateOf(value) }
    
    PreferenceItem(
        title = title,
        summary = summary ?: value,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        onClick = if (enabled) { { showDialog = true } } else null
    )
    
    if (showDialog) {
        val shapes = LocalShapes.current
        
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                TextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text("Enter text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(textValue)
                    showDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    textValue = value
                    showDialog = false 
                }) {
                    Text("Cancel")
                }
            },
            shape = shapes.dialog
        )
    }
}

/**
 * Slider preference for numeric values with dialog
 */
@Composable
fun SliderPreference(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    value: Int,
    valueRange: IntRange = 0..100,
    stepSize: Int? = null,
    unit: String = "",
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    // See EditTextPreference above: key on the incoming value so the dialog reopens on the
    // current setting rather than the one this row first composed with.
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }
    
    val displaySummary = summary ?: "Current: $value$unit"
    
    PreferenceItem(
        title = title,
        summary = displaySummary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        modifier = modifier,
        onClick = { if (enabled) showDialog = true }
    )
    
    if (showDialog) {
        val spacing = LocalSpacing.current
        val shapes = LocalShapes.current
        
        // Calculate steps based on stepSize or default to 1-unit steps
        val steps = if (stepSize != null && stepSize > 1) {
            ((valueRange.last - valueRange.first) / stepSize) - 1
        } else {
            valueRange.last - valueRange.first - 1
        }
        
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column {
                    Text(
                        text = "Value: ${sliderValue.toInt()}$unit",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(spacing.large))
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue ->
                            // Snap to step size if specified
                            sliderValue = if (stepSize != null && stepSize > 1) {
                                val snapped = ((newValue - valueRange.first) / stepSize).toInt() * stepSize + valueRange.first
                                snapped.toFloat()
                            } else {
                                newValue
                            }
                        },
                        valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                        steps = steps.coerceAtLeast(0)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(sliderValue.toInt())
                    showDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    sliderValue = value.toFloat()
                    showDialog = false 
                }) {
                    Text("Cancel")
                }
            },
            shape = shapes.dialog
        )
    }
}

/**
 * Grid preference composable with Material 3 dialog
 * Displays a dialog with grid selection - ideal for currency symbols, emojis, etc.
 */
@Composable
fun GridPreference(
    title: String,
    summary: String,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    entries: List<String>,
    value: String,
    columns: Int = 5,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        modifier = modifier,
        onClick = if (enabled) { { showDialog = true } } else null
    )
    
    if (showDialog) {
        val shapes = LocalShapes.current
        
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    itemsIndexed(entries) { _, entry ->
                        val isSelected = value == entry
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    onValueChange(entry)
                                    showDialog = false
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = entry,
                                style = MaterialTheme.typography.titleLarge,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = shapes.dialog
        )
    }
}

// ============================================================================
// SHARED BLOCKS — the pieces screens used to hand-roll, one copy each
// ============================================================================

/**
 * The explanatory paragraph a screen puts above its rows.
 *
 * Five screens each carried a version of this and no two agreed: two drew an elevated card tinted
 * `primaryContainer`, one a `surfaceVariant` card with its own heading, two a bare [Text] with
 * their own padding and their own type ramp. Material 3 spends a card on a tappable *object*; a
 * tinted one reads as a warning the reader is supposed to act on. A sentence is neither.
 *
 * So this is the sentence, at the list's own metrics: the same 16dp gutter the rows use — so it
 * shares their left edge — in the same body style the rows give their summaries.
 *
 * [detail] is a second, smaller line for the screens that have one (a token list, a worked
 * example). [monospaceDetail] is for the one case where the characters themselves are the content
 * and the reader has to tell `%D` from `%d`.
 */
@Composable
fun PreferenceInfo(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    monospaceDetail: Boolean = false,
) {
    val spacing = LocalSpacing.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = spacing.listItemPadding,
                vertical = spacing.medium,
            )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail != null) {
            Spacer(modifier = Modifier.height(spacing.small))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (monospaceDetail) FontFamily.Monospace else null,
            )
        }
    }
}

/**
 * A row that states a condition and offers the one button that resolves it — the runtime-permission
 * prompts.
 *
 * These were tinted cards with a full-width button underneath, sitting in the middle of a column of
 * switches. What they describe is a property of the row below them, so they are drawn as a row: the
 * title and summary any preference has, with the action in the trailing slot — where the switch of
 * the row it gates will be.
 */
@Composable
fun PreferenceActionItem(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    modifier: Modifier = Modifier,
) {
    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        modifier = modifier,
        onClick = onAction,
        trailing = {
            TextButton(onClick = onAction) { Text(actionLabel) }
        },
    )
}

/**
 * Short text in a row's leading slot, where an icon would otherwise be — a language code, a macro
 * tag. Drawn unbounded, so a label wider than the 24dp icon column still centres on it and the
 * title stays at the same x as on every icon-bearing row of every other screen.
 */
@Composable
fun PreferenceLeadingBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.wrapContentWidth(align = Alignment.CenterHorizontally, unbounded = true),
    )
}

/**
 * A word in the trailing slot — "Preinstalled", a state, a count. The 12dp end padding is
 * (48dp IconButton − 24dp glyph) / 2, so the label's right edge meets the right edge of an icon
 * button on a neighbouring row.
 */
@Composable
fun PreferenceTrailingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 12.dp),
    )
}

/**
 * The bin a removable row carries.
 *
 * Neutral on purpose. Material 3 spends the error colour on error states and on destructive
 * *confirmation* — the dialog's Delete button stays red — not on the entry point, where a column of
 * red icons made deletion look like the screen's main activity.
 *
 * Laid out at the glyph's 24dp height, so a row holding it is the same 56dp as a row holding a
 * [PreferenceTrailingLabel]. The button keeps its full 48dp touch target: it is drawn unbounded,
 * centred on the glyph, and 48dp fits inside a 56dp row.
 */
@Composable
fun PreferenceDeleteButton(contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(LocalSpacing.current.iconSize),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.wrapContentHeight(unbounded = true),
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
