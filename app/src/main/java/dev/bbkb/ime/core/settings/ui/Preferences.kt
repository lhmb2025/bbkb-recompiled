package dev.bbkb.ime.core.settings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.R

/** The base settings row every preference type builds on. */
@Composable
fun PreferenceItem(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    /** Alternative to [icon] for drawable art. */
    iconPainter: Painter? = null,
    /** Composed alternative to [icon] (e.g. a text badge), in the same 24dp column so titles align. */
    leading: (@Composable () -> Unit)? = null,
    iconSpaceReserved: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** Row background; defaults to the group card inside a [PreferenceGroup], else transparent. */
    containerColor: Color? = null,
    /**
     * Position in a lazy-list group, so edge rows paint the outer corners ([groupOuterCorners]).
     * Rows inside a [PreferenceGroup] keep [GroupEdge.Middle]; its clip rounds them.
     */
    groupEdge: GroupEdge = GroupEdge.Middle,
    onClick: (() -> Unit)? = null,
    /** A chip or tag after the title; wraps to its own line rather than cutting the title short. */
    titleAccessory: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val spacing = LocalSpacing.current
    val motion = LocalMotion.current
    val shapes = LocalShapes.current
    val inGroup = LocalInGroup.current
    val settingsColors = LocalSettingsColors.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .groupOuterCorners(groupEdge)
            .animateContentSize(animationSpec = motion.fastSpec())
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable { onClick() }
                } else Modifier
            ),
        // In a group: a card with small inner corners (the group's clip rounds the outer ones).
        shape = if (inGroup) shapes.row else RectangleShape,
        color = containerColor ?: if (inGroup) settingsColors.card else Color.Transparent
    ) {
        Row(
            // Caller's modifier inside the surface clip, so the search pulse takes the row's shape.
            modifier = modifier
                // Minimum height includes the padding, giving Material 3's 56dp one-line item.
                .heightIn(min = spacing.oneLineListItemHeight)
                .padding(
                    horizontal = spacing.listItemPadding,
                    vertical = spacing.listItemVerticalPadding
                )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                Spacer(
                    modifier = Modifier
                        .padding(end = spacing.iconTextGap)
                        .size(spacing.iconSize)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = if (trailing != null) spacing.iconTextGap else spacing.none)
            ) {
                TitleWithAccessory(accessory = titleAccessory) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }
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

            trailing?.invoke()
        }
    }
}

/** A title with an optional mark that flows beside it, or under it when there is no room. */
@Composable
fun TitleWithAccessory(
    accessory: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
) {
    if (accessory == null) {
        Box(modifier = modifier) { title() }
        return
    }
    val spacing = LocalSpacing.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.extraSmall),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        title()
        accessory()
    }
}

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
            // The row is the touch target, so lay the switch out at its drawn height; its 48dp
            // target would make switch rows taller than other rows.
            Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                Switch(
                    checked = checked,
                    onCheckedChange = if (enabled) onCheckedChange else null,
                    enabled = enabled
                )
            }
        }
    )
}

/** A row that opens a single-choice radio dialog. */
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
                // Capped near half the screen and scrollable: the dialog's own bound alone let long
                // lists clip their last rows into text-less bubbles on short screens.
                val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
                Column(modifier = Modifier.heightIn(max = maxListHeight).verticalScroll(rememberScrollState())) {
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
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = shapes.dialog
        )
    }
}

/** The label above a [PreferenceGroup]; the string carries its own (sentence) case. */
@Composable
fun PreferenceCategory(
    title: String,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = spacing.categoryStartPadding,
                end = spacing.listItemPadding,
                top = spacing.categoryTopPadding,
                bottom = spacing.categoryBottomPadding
            )
    )
}

/** True inside a [PreferenceGroup]; rows read it to paint themselves as cards. */
val LocalInGroup = compositionLocalOf { false }

/** Which outer edges of a lazy-list group a row is on. */
enum class GroupEdge { Middle, Top, Bottom, Only }

/** The [GroupEdge] of row [index] in a run of [count] rows. */
fun groupEdge(index: Int, count: Int): GroupEdge = when {
    count <= 1 -> GroupEdge.Only
    index == 0 -> GroupEdge.Top
    index == count - 1 -> GroupEdge.Bottom
    else -> GroupEdge.Middle
}

/**
 * Group outer corners for `LazyColumn` edge rows, painted in the page colour over the row rather
 * than clipped: a mixed-radius clip hit-tests via path ops, which Robolectric lacks, so taps missed.
 */
fun Modifier.groupOuterCorners(edge: GroupEdge): Modifier = composed {
    if (edge == GroupEdge.Middle) return@composed this
    val page = LocalSettingsColors.current.page
    val outer = LocalShapes.current.group.topStart
    val top = edge == GroupEdge.Top || edge == GroupEdge.Only
    val bottom = edge == GroupEdge.Bottom || edge == GroupEdge.Only
    drawWithContent {
        drawContent()
        val r = outer.toPx(size, this)
        if (r <= 0f) return@drawWithContent
        val w = size.width
        val h = size.height
        val caps = Path()
        if (top) {
            caps.moveTo(0f, 0f)
            caps.lineTo(r, 0f)
            caps.arcTo(Rect(0f, 0f, 2 * r, 2 * r), 270f, -90f, false)
            caps.close()
            caps.moveTo(w, 0f)
            caps.lineTo(w - r, 0f)
            caps.arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
            caps.close()
        }
        if (bottom) {
            caps.moveTo(0f, h)
            caps.lineTo(0f, h - r)
            caps.arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
            caps.close()
            caps.moveTo(w, h)
            caps.lineTo(w, h - r)
            caps.arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            caps.close()
        }
        drawPath(caps, page)
    }
}

/**
 * Android 16 Settings style group: inset, clipped to the outer radius, rows 2dp apart as their own
 * cards. The clip rounds whichever rows are first and last, so hidden rows need no bookkeeping.
 */
@Composable
fun PreferenceGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    val shapes = LocalShapes.current
    CompositionLocalProvider(LocalInGroup provides true) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.groupInset)
                .clip(shapes.group),
            verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
            content = content,
        )
    }
}

/** A row that navigates to a sub-screen. */
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

@Composable
private fun NavigateChevron(enabled: Boolean) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_chevron_right),
        contentDescription = stringResource(R.string.settings_common_navigate),
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        }
    )
}

@Composable
fun SimplePreference(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    PreferenceItem(
        title = title,
        summary = summary,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        modifier = modifier,
        onClick = onClick
    )
}

/** A row that edits text in a dialog. */
@Composable
fun EditTextPreference(
    title: String,
    summary: String? = null,
    /** Row title when it differs from the dialog heading ([title]). */
    rowTitle: String = title,
    icon: ImageVector? = null,
    iconSpaceReserved: Boolean = false,
    value: String,
    enabled: Boolean = true,
    /** False disables Save while the field is blank. */
    allowBlank: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    onValueChange: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    PreferenceItem(
        title = rowTitle,
        summary = summary ?: value,
        icon = icon,
        iconSpaceReserved = iconSpaceReserved,
        enabled = enabled,
        onClick = if (enabled) { { showDialog = true } } else null
    )

    if (showDialog) {
        val shapes = LocalShapes.current
        // Created with the dialog, so it opens on the current value with the cursor at the end.
        var text by remember {
            mutableStateOf(TextFieldValue(value, selection = TextRange(value.length)))
        }
        val canSave = allowBlank || text.text.isNotBlank()
        val save = {
            onValueChange(text.text)
            showDialog = false
        }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.settings_common_enter_text)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            },
            confirmButton = {
                TextButton(enabled = canSave, onClick = save) {
                    Text(stringResource(R.string.settings_common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = shapes.dialog
        )
    }
}

/** A row that sets a number with a slider dialog. */
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
    /** True shows the value ("400 ms") on the row and moves [summary] into the dialog. */
    valueOnRow: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    // Keyed on value so the dialog reopens on the current setting.
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }

    val displaySummary = when {
        valueOnRow -> sliderValueText(value, unit)
        else -> summary ?: stringResource(R.string.settings_common_current_value, "$value$unit")
    }
    
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
                    if (valueOnRow && summary != null) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(spacing.large))
                    }
                    Text(
                        text = stringResource(R.string.settings_common_slider_value, "${sliderValue.toInt()}$unit"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(spacing.large))
                    Slider(
                        value = sliderValue,
                        onValueChange = { newValue ->
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
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = shapes.dialog
        )
    }
}

/** Milliseconds go through the translated "%d ms". */
@Composable
private fun sliderValueText(value: Int, unit: String): String =
    if (unit == "ms") stringResource(R.string.settings_feedback_milliseconds, value) else "$value$unit"

/** A row that picks one symbol from a grid dialog. */
@Composable
fun GridPreference(
    title: String,
    summary: String,
    icon: ImageVector? = null,
    /** Composed leading art, e.g. the chosen symbol as text. */
    leading: (@Composable () -> Unit)? = null,
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
        leading = leading,
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
                    Text(stringResource(R.string.cancel))
                }
            },
            shape = shapes.dialog
        )
    }
}

/**
 * The explanatory paragraph above a screen's rows: plain text, not a card (a tinted card reads as
 * a warning), aligned with row text. [detail] is a smaller second line; [monospaceDetail] for
 * when characters like `%D` and `%d` must be told apart.
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
                start = spacing.categoryStartPadding,
                end = spacing.listItemPadding,
                top = spacing.medium,
                bottom = spacing.medium,
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

/** Short text in the icon slot (a language code, a macro tag), unbounded so titles stay aligned. */
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

/** A trailing word; 12dp end padding = (48dp IconButton - 24dp glyph) / 2, to align with icon buttons. */
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
 * A removable row's delete button, neutral (red is for the confirmation). Laid out at 24dp so the
 * row stays 56dp, with the 48dp touch target drawn unbounded around the glyph.
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
                imageVector = ImageVector.vectorResource(R.drawable.ic_settings_m_delete),
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
