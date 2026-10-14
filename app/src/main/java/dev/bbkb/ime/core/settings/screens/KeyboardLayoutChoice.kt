package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import dev.bbkb.ime.R
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutChoice
import dev.bbkb.ime.core.settings.ui.LocalShapes
import dev.bbkb.ime.core.settings.ui.LocalSpacing

/**
 * One entry of a keyboard's Layout list on the Language screen: the layout's id (`""` for the
 * keyboard's own), what the row says, and which keyboards it changes.
 */
internal data class LayoutChoiceRow(val id: String, val title: String, val summary: String)

/** The row texts that come from resources, so [layoutChoiceRows] stays a plain function. */
internal class LayoutChoiceTexts(
    /** The keyboard's own layout's name: its layout set's (QWERTY, AZERTY…), else the keyboard's. */
    val ownTitle: String,
    /** "Uses the keyboard's own layout". */
    val ownSummary: String,
    /** "On-screen keyboard and physical keys". */
    val both: String,
    /** "On-screen keyboard". */
    val onScreen: String,
    /** "Physical keys only". */
    val physical: String,
)

/**
 * The rows for a keyboard's Layout list, one per [options] entry and in its order: the keyboard's
 * own layout, then each layout by its name, saying which keyboards it changes on this phone. A
 * layout draws the on-screen keyboard when [drawsOnScreen] says so (Colemak and Dvorak, which have
 * rows of their own); it changes the physical keys on a phone with them ([physicalKeys]) when it
 * is for the physical keyboard. A custom layout for the on-screen keyboard has no rows of its own
 * yet, so it reads "Physical keys only": the on-screen keyboard keeps its own layout.
 */
internal fun layoutChoiceRows(
    options: List<LetterLayoutChoice.Option>,
    texts: LayoutChoiceTexts,
    physicalKeys: Boolean,
    drawsOnScreen: (LetterLayout) -> Boolean,
): List<LayoutChoiceRow> = options.map { option ->
    val layout = option.layout ?: return@map LayoutChoiceRow(option.id, texts.ownTitle, texts.ownSummary)
    val onScreen = drawsOnScreen(layout)
    val physical = physicalKeys && LetterLayout.Target.PKB in layout.targets
    val summary = when {
        onScreen && physical -> texts.both
        onScreen -> texts.onScreen
        else -> texts.physical
    }
    LayoutChoiceRow(option.id, layout.name, summary)
}

/**
 * The row a keyboard's sheet shows for its layout: "Layout", the chosen layout's name under it.
 * Opens [KeyboardLayoutDialog].
 */
@Composable
internal fun KeyboardLayoutRow(current: LayoutChoiceRow, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.languages_hub_layout_title)) },
        supportingContent = { Text(current.title) },
        leadingContent = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * The Layout list itself: one radio row per layout, its name and which keyboards it changes.
 * Picking one applies it and closes the dialog, as the app's other single-choice lists do.
 */
@Composable
internal fun KeyboardLayoutDialog(
    rows: List<LayoutChoiceRow>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.languages_hub_layout_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                rows.forEach { row ->
                    val selected = row.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, role = Role.RadioButton) { onSelect(row.id) }
                            .padding(vertical = spacing.dialogListItemVerticalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(modifier = Modifier.width(spacing.iconTextGap))
                        Column {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                row.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        shape = LocalShapes.current.dialog,
    )
}
