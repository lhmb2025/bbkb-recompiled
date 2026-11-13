package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorComponent
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.ManagedToggle
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

/**
 * Configuration loader, meta state override, BBKB helper (greyed out where the profile never
 * needs it), custom keypad layouts (keypad phones only) and the Touch surface helper (only where
 * the profile declares a touch surface).
 */
@Composable
fun DeviceCompatibilityScreen(
    onNavigateToDeviceConfiguration: () -> Unit,
    onNavigateToKeyboardHelper: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToTouchSurfaceHelper: () -> Unit = {},
    onNavigateToPhysicalLayouts: () -> Unit = {},
) {
    val hasPhysicalKeyboard = remember { DeviceProfile.current()?.hasPhysicalKeyboard() ?: false }
    // Key declared in strings.xml and read through the same resource by SettingsValues.
    val metaStateKey = LocalContext.current.getString(R.string.pref_override_device_meta_state_key)
    val touchSurface = rememberTouchSurfaceState()
    // BlackBerry hardware: the helper's component is switched off, so the row greys out.
    val helperNotRequired = remember { KeyInterceptorComponent.isNotRequiredOnThisPhone() }

    SettingsScreenHost(R.string.settings_device_compatibility_title, onNavigateBack, listOf(
        Nav(
            title = R.string.settings_device_configuration_title,
            summary = R.string.settings_device_configuration_summary,
            icon = drawableIcon(R.drawable.ic_settings_m_phonelink_setup),
            onClick = { onNavigateToDeviceConfiguration() },
        ),
        // Default must match SettingsValues.overrideDeviceMetaState (true).
        ManagedToggle(
            store = boolPref(metaStateKey, true),
            title = R.string.pref_override_device_meta_state,
            summary = RowSummary.Res(R.string.pref_override_device_meta_state_summary),
            icon = drawableIcon(R.drawable.ic_settings_m_keyboard_capslock),
        ),
        // User letter layouts for the physical keys; which keyboard uses one is chosen on the
        // Language screen.
        Nav(
            title = R.string.settings_pkb_layouts_title,
            summary = R.string.settings_pkb_layouts_summary,
            icon = drawableIcon(R.drawable.ic_settings_custom_layouts),
            visible = { hasPhysicalKeyboard },
            onClick = { onNavigateToPhysicalLayouts() },
        ),
        Nav(
            title = R.string.settings_pkb_keyboard_helper_title,
            summary = if (helperNotRequired) R.string.settings_status_not_required
                else R.string.settings_pkb_keyboard_helper_summary,
            icon = drawableIcon(R.drawable.ic_settings_bbkb_helper),
            enabled = { !helperNotRequired },
            onClick = { onNavigateToKeyboardHelper() },
        ),
        // Only where the profile describes a touch surface (Unihertz Titans). Live status summary.
        Nav(
            title = R.string.touch_surface_helper_title,
            summary = touchSurface.statusLine,
            icon = drawableIcon(R.drawable.ic_settings_m_touch_app),
            // Literal (== TouchSurfaceAnchors.HELPER): the search-index sync test scans for it.
            modifier = Modifier.settingsSearchAnchor("touch_surface_helper"),
            visible = { touchSurface.declared },
            onClick = { onNavigateToTouchSurfaceHelper() },
        ),
    ))
}
