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

/**
 * Blocks a settings screen draws that are not preference rows, so they ride the spec's `Custom`
 * escape hatch rather than being contorted into a row type.
 */

/**
 * Swipe typing is unavailable on Chinese subtypes (a NuanceSDK limitation), and the two screens
 * that offer a swipe-typing toggle say so with a modal the user must acknowledge. One copy: the
 * two screens carried identical 15-line versions of this.
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
