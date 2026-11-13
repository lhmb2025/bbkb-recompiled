package dev.bbkb.ime.core.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.R

/** Material list-selection mark: a filled check circle when selected, an empty ring otherwise. */
@Composable
fun RoundCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    if (selected) {
        Box(modifier = modifier.background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(ImageVector.vectorResource(R.drawable.ic_settings_m_check_filled), contentDescription = stringResource(R.string.settings_common_selected), tint = colors.onPrimary, modifier = Modifier.size(16.dp))
        }
    } else {
        Box(modifier = modifier.border(2.dp, colors.outline, CircleShape))
    }
}
