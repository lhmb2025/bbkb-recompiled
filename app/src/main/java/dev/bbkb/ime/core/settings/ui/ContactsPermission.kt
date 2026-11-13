package dev.bbkb.ime.core.settings.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.bbkb.ime.R

/** Quicker than anyone can read and answer Android's permission dialog. */
private const val INSTANT_DENIAL_MS = 400L

/**
 * Contacts permission requested from a switch, no separate permission row. [granted] is re-read
 * on resume. Once Android stops asking, [request] shows a dialog that opens the app's settings page.
 */
class ContactsPermissionState internal constructor(
    granted: Boolean,
    private val ask: () -> Unit,
) {
    var granted by mutableStateOf(granted)
        internal set

    fun request() = ask()
}

@Composable
fun rememberContactsPermission(onResult: (Boolean) -> Unit): ContactsPermissionState {
    val context = LocalContext.current
    var showBlocked by remember { mutableStateOf(false) }
    val askedAt = remember { longArrayOf(0L) }
    lateinit var state: ContactsPermissionState
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        state.granted = ok
        // An instant "no" means Android no longer shows its dialog; a real answer or backing out takes longer.
        val instant = SystemClock.elapsedRealtime() - askedAt[0] < INSTANT_DENIAL_MS
        if (!ok && instant) showBlocked = true
        onResult(ok)
    }
    state = remember {
        ContactsPermissionState(hasContacts(context)) {
            askedAt[0] = SystemClock.elapsedRealtime()
            launcher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.granted = hasContacts(context) }

    if (showBlocked) {
        AlertDialog(
            onDismissRequest = { showBlocked = false },
            title = { Text(stringResource(R.string.settings_pred_contacts_permission_title)) },
            text = { Text(stringResource(R.string.permission_blocked_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    showBlocked = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                    )
                }) { Text(stringResource(R.string.settings_update_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { showBlocked = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
    return state
}

private fun hasContacts(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
