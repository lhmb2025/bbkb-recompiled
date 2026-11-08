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
 * The contacts permission as a switch asks for it, the way Google's own apps do: there is no
 * separate permission row. Turning a contacts switch on calls [request]; Android's own dialog
 * answers, and [granted] follows (it is also re-read when the screen resumes, so a grant made in
 * Android's app settings shows up on return).
 *
 * Once Android has stopped asking (the user said no twice, or "Don't ask again"), [request]
 * shows a short dialog instead that explains it and opens BBKB's page in Android's settings.
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
        // Android answers "no" at once, without showing its dialog, only once it has stopped
        // asking. A "no" the user gave in the dialog takes longer than this, and so does backing
        // out of it, so neither is mistaken for that.
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
