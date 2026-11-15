package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple

/**
 * First-run setup checklist. One screen of independent, skippable steps; each is a launcher into the
 * screen or system action that already performs it, so no step blocks another and the screen cannot
 * be got stuck halfway. Shown once on a brand-new settings database ([dev.bbkb.ime.core.settings.SetupState]).
 * The back action and the final row both mark setup done.
 *
 * The device device-input-config and the keyboard layout and language screens already exist; this
 * screen only chains them, plus the two system actions a device with no navigation bar cannot reach
 * on its own: enabling the keyboard and choosing it as the default.
 */
@Composable
fun SetupScreen(
    onNavigateToDeviceConfig: () -> Unit,
    onNavigateToKeylayout: () -> Unit,
    onNavigateToLanguage: () -> Unit,
    onNavigateToBackupRestore: () -> Unit,
    onDone: () -> Unit,
) {
    SettingsScreenHost(R.string.settings_setup_title, onDone, listOf(
        // Returning users restore first; the steps below are for a fresh install.
        Category(R.string.settings_setup_returning_header),
        Nav(
            title = R.string.settings_setup_restore_title,
            summary = R.string.settings_setup_restore_summary,
            onClick = { onNavigateToBackupRestore() },
        ),

        Category(R.string.settings_setup_steps_header),
        // Enable: the only in-app route to turn the IME on where no navigation bar exposes it.
        Nav(
            title = R.string.settings_setup_enable_title,
            summary = R.string.settings_setup_enable_summary,
            onClick = { context ->
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        ),
        // Set default: the system input-method picker (see also the Switch keyboard row in settings).
        Nav(
            title = R.string.settings_setup_set_default_title,
            summary = R.string.settings_setup_set_default_summary,
            onClick = { context ->
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showInputMethodPicker()
            },
        ),
        Nav(
            title = R.string.settings_setup_device_title,
            summary = R.string.settings_setup_device_summary,
            onClick = { onNavigateToDeviceConfig() },
        ),
        Nav(
            title = R.string.settings_setup_keylayout_title,
            summary = R.string.settings_setup_keylayout_summary,
            onClick = { onNavigateToKeylayout() },
        ),
        Nav(
            title = R.string.settings_setup_language_title,
            summary = R.string.settings_setup_language_summary,
            onClick = { onNavigateToLanguage() },
        ),

        // One tap to leave: marks setup done, same as the back action.
        Simple(
            title = R.string.settings_setup_skip_title,
            summary = R.string.settings_setup_skip_summary,
            onClick = { onDone() },
        ),
    ))
}
