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
import androidx.compose.material.icons.outlined.ChevronRight
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
 * One entry of a keyboard's layout list on the Language screen: the layout's id (`""` for the
 * keyboard's default), what the row says, and a note under it where there is one.
 */
internal data class LayoutChoiceRow(val id: String, val title: String, val summary: String? = null)

/**
 * The rows for one of a keyboard's layout lists, one per [options] entry and in its order: the
 * keyboard's default ([ownTitle], with [ownSummary] under it) or, on the physical keys, the
 * keypad's own letters ([keypadLettersTitle]), then each layout under the name [name] gives it
 * (its own name unless the caller says otherwise).
 */
internal fun layoutChoiceRows(
    options: List<LetterLayoutChoice.Option>,
    ownTitle: String,
    ownSummary: String,
    keypadLettersTitle: String = "",
    name: (LetterLayout) -> String = { it.name },
): List<LayoutChoiceRow> = options.map { option ->
    val layout = option.layout ?: return@map when (option.id) {
        LetterLayoutChoice.KEYPAD_LETTERS -> LayoutChoiceRow(option.id, keypadLettersTitle)
        else -> LayoutChoiceRow(option.id, ownTitle, ownSummary)
    }
    LayoutChoiceRow(option.id, name(layout))
}

/**
 * The row a keyboard's sheet shows for one of its layouts: [title] ("On-screen keyboard" or
 * "Physical keyboard"), the chosen layout's name under it. Opens [KeyboardLayoutDialog].
 */
@Composable
internal fun KeyboardLayoutRow(title: String, current: LayoutChoiceRow, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(current.title) },
        leadingContent = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
        trailingContent = {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * One layout list itself: one radio row per layout, its name and its note. Picking one applies it
 * and closes the dialog, as the app's other single-choice lists do.
 */
@Composable
internal fun KeyboardLayoutDialog(
    title: String,
    rows: List<LayoutChoiceRow>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
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
                            row.summary?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
