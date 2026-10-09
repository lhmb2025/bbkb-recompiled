package dev.bbkb.ime.core.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.ManagedToggle
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

/**
 * Device Compatibility Screen
 * Configuration loader, device meta state override, the BBKB Helper and, on phones whose profile
 * declares a touch surface over the keys, the Touch surface helper.
 * Accessed from Advanced settings.
 */
@Composable
fun DeviceCompatibilityScreen(
    onNavigateToDeviceConfiguration: () -> Unit,
    onNavigateToKeyboardHelper: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToTouchSurfaceHelper: () -> Unit = {},
) {
    // This one key is declared in strings.xml rather than inline, and is read back through the
    // same resource by SettingsValues — so it is resolved here rather than re-spelled.
    val metaStateKey = LocalContext.current.getString(R.string.pref_override_device_meta_state_key)
    val touchSurface = rememberTouchSurfaceState()

    SettingsScreenHost(R.string.settings_device_compatibility_title, onNavigateBack, listOf(
        Nav(
            title = R.string.settings_device_configuration_title,
            summary = R.string.settings_device_configuration_summary,
            icon = Icons.Default.PhoneAndroid.asRowIcon(),
            onClick = { onNavigateToDeviceConfiguration() },
        ),
        // Override Device Meta State. The default must match SettingsValues.overrideDeviceMetaState,
        // which reads this key with a default of true: with false here the switch rendered OFF on a
        // fresh install while the keyboard behaved as if it were ON.
        ManagedToggle(
            store = boolPref(metaStateKey, true),
            title = R.string.pref_override_device_meta_state,
            summary = RowSummary.Res(R.string.pref_override_device_meta_state_summary),
            icon = Icons.Default.KeyboardCapslock.asRowIcon(),
        ),
        Nav(
            title = R.string.settings_pkb_keyboard_helper_title,
            summary = R.string.settings_pkb_keyboard_helper_summary,
            icon = Icons.Default.Keyboard.asRowIcon(),
            onClick = { onNavigateToKeyboardHelper() },
        ),
        // Only where the profile describes a touch surface over the keys (the Unihertz Titans).
        // The summary is the live one-line status, re-read on every resume.
        Nav(
            title = R.string.touch_surface_helper_title,
            summary = touchSurface.statusLine,
            icon = Icons.Default.TouchApp.asRowIcon(),
            // Literal (== TouchSurfaceAnchors.HELPER): the search-index sync test scans for it.
            modifier = Modifier.settingsSearchAnchor("touch_surface_helper"),
            visible = { touchSurface.declared },
            onClick = { onNavigateToTouchSurfaceHelper() },
        ),
    ))
}
