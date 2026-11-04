package dev.bbkb.ime.core.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The mark of Material's list selection pattern: a filled circle with a check on a selected row,
 * and, as these rows have no avatar for the circle to take over, an empty ring on a row that can
 * be selected. The lists that select (Learned words, User dictionary, Text shortcuts) slide the
 * column holding it in when selection starts.
 */
@Composable
fun RoundCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Box(modifier = modifier.background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = colors.onPrimary, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(modifier = modifier.border(2.dp, colors.outline, CircleShape))
    }
}
