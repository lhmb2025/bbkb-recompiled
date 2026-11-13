package dev.bbkb.ime.core.settings.screens

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import dev.bbkb.ime.R

/** Non-row blocks that settings screens draw through the spec's `Custom` escape hatch. */

/**
 * Swipe typing is unavailable on Chinese subtypes (a NuanceSDK limitation); screens with a
 * swipe-typing toggle show this modal the user must acknowledge.
 */
@Composable
fun ChineseLocaleSwipeWarning() {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(true) }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text(context.getString(R.string.settings_screen_type_by_swiping_unavailable)) },
            confirmButton = {
                Button(onClick = { showDialog = false }) {
                    Text(context.getString(R.string.user_dict_settings_add_dialog_confirm))
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        )
    }
}
