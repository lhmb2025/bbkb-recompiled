package dev.bbkb.ime.core.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.detection.HardwareProbe
import dev.bbkb.ime.core.device.touch.KeypadTouchSources
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SettingsEnv
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Simple
import dev.bbkb.ime.core.settings.ui.asRowIcon

/** Search anchors for the touch-surface rows; the screens spell them as literals for the sync test. */
object TouchSurfaceAnchors {
    /** The Touch surface helper row on Device compatibility. */
    const val HELPER = "touch_surface_helper"

    /** The Shizuku status row on the Touch surface helper. */
    const val SHIZUKU = "touch_surface_shizuku"
}

/**
 * Touch surface helper (Advanced > Device compatibility, on phones whose profile declares a touch
 * surface over the keys). Says which way the touch surface reaches BBKB and how that is going, and
 * links out to whatever setup that way needs: the phone's Scroll assistant (the built-in route),
 * or Shizuku. There is no choice to make here: the profile decides the route.
 *
 * Shaped like the BBKB Helper screen: everything is re-read on resume (coming back from the phone's
 * settings or from the Shizuku app) and kept no wizard state; Shizuku's answer to Grant access
 * arrives as a status change while the screen is up.
 */
@Composable
fun TouchSurfaceHelperScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberTouchSurfaceState()
    val mediaTek = remember { HardwareProbe.isMediaTek() }
    // Installing or removing Shizuku happens away from this screen, so it is looked up again
    // whenever the state is re-read (on every resume).
    val shizukuApp = remember(state) { TouchSurfaceLinks.shizukuApp(context) }
    val builtIn: (SettingsEnv) -> Boolean = { state.showBuiltIn }
    val shizuku: (SettingsEnv) -> Boolean = { state.showShizuku }

    SettingsScreenHost(R.string.touch_surface_helper_title, onNavigateBack, listOf(
        Custom { TouchSurfaceStatusHeader(state) },

        // ── Built-in: the phone hands the pad to BBKB itself ─────────────────
        Category(R.string.touch_surface_builtin_category, visible = builtIn),
        Custom(visible = builtIn) {
            PreferenceInfo(text = stringResource(R.string.touch_surface_builtin_intro))
        },
        Nav(
            title = R.string.touch_surface_open_scroll_assistant,
            icon = Icons.Default.Swipe.asRowIcon(),
            visible = builtIn,
            onClick = { TouchSurfaceLinks.open(it, TouchSurfaceLinks.scrollAssistant()) },
        ),
        Nav(
            title = R.string.touch_surface_cursor_assistant_title,
            summary = R.string.touch_surface_cursor_assistant_summary,
            icon = Icons.Default.TouchApp.asRowIcon(),
            visible = builtIn,
            onClick = { TouchSurfaceLinks.open(it, TouchSurfaceLinks.cursorAssistant()) },
        ),

        // ── Shizuku: BBKB reads the pad itself ───────────────────────────────
        Category(R.string.touch_surface_shizuku_category, visible = shizuku),
        Custom(visible = shizuku) {
            PreferenceInfo(text = stringResource(R.string.touch_surface_shizuku_intro))
        },
        Simple(
            title = R.string.touch_surface_shizuku_status_title,
            summary = state.shizukuStateLine,
            icon = Icons.Default.Info.asRowIcon(),
            // Literal (== TouchSurfaceAnchors.SHIZUKU): the search-index sync test scans for it.
            modifier = Modifier.settingsSearchAnchor("touch_surface_shizuku"),
            visible = shizuku,
        ),
        Nav(
            title = R.string.touch_surface_install_shizuku,
            summary = R.string.touch_surface_install_shizuku_summary,
            icon = Icons.Default.Download.asRowIcon(),
            visible = shizuku,
            onClick = { TouchSurfaceLinks.open(it, TouchSurfaceLinks.installShizuku()) },
        ),
        Nav(
            title = R.string.touch_surface_open_shizuku,
            summary = R.string.touch_surface_open_shizuku_summary,
            icon = Icons.AutoMirrored.Filled.OpenInNew.asRowIcon(),
            enabled = { shizukuApp != null },
            visible = shizuku,
            onClick = { ctx -> shizukuApp?.let { TouchSurfaceLinks.open(ctx, listOf(it)) } },
        ),
        Nav(
            title = R.string.touch_surface_grant_access,
            // The status row above already says whether access is granted; this row only has
            // something to add when Shizuku will not ask again.
            summary = if (state.shizukuAccessDenied) R.string.touch_surface_grant_access_denied
                else R.string.touch_surface_grant_access_summary,
            icon = Icons.Default.Key.asRowIcon(),
            enabled = { state.canRequestShizukuAccess },
            visible = shizuku,
            onClick = { KeypadTouchSources.shizukuEngine().requestPermission(it) },
        ),
        Category(R.string.touch_surface_shizuku_guide_category, visible = shizuku),
        Custom(visible = shizuku) {
            PreferenceInfo(text = stringResource(R.string.touch_surface_shizuku_guide_steps))
        },
        Custom(visible = { state.showShizuku && mediaTek }) {
            PreferenceInfo(text = stringResource(R.string.touch_surface_shizuku_mediatek))
        },
        Custom(visible = shizuku) {
            PreferenceInfo(text = stringResource(R.string.touch_surface_shizuku_reboot))
        },
    ))
}

/** Which way the touch surface reaches BBKB, and how that is going, in one line. */
@Composable
private fun TouchSurfaceStatusHeader(state: TouchSurfaceState) {
    PreferenceItem(
        title = stringResource(state.statusLine),
        icon = if (state.isWorking) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
    )
}
