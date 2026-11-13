package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.PrefsManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.bbkb.ime.R
import dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.gesture.arbiter.GestureAssignments
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.ListPreference
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.settings.ui.SliderPreference
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/** Human label resource for an assignable action. */
private fun GestureAction.labelRes(): Int = when (this) {
    GestureAction.NONE -> R.string.ckb_action_none
    GestureAction.DELETE_WORD -> R.string.ckb_action_delete_word
    GestureAction.ENTER_CURSOR_MODE -> R.string.ckb_action_cursor_mode
    GestureAction.NEXT_LANGUAGE -> R.string.ckb_action_next_language
    GestureAction.CYCLE_SYMBOLS -> R.string.ckb_action_cycle_symbols
    GestureAction.DISMISS_KEYBOARD -> R.string.ckb_action_dismiss
    GestureAction.UNDO -> R.string.ckb_action_undo
    GestureAction.EMOJI -> R.string.ckb_action_emoji
    GestureAction.COMMIT_SUGGESTION -> R.string.ckb_action_none // reserved; never listed
}

/**
 * Debug flag revealing the DEVELOPER category: decoder instrumentation (a `<ckb-key-grid>` export
 * to app files and a logcat visualizer). Nothing else on the screen is gated.
 */
internal const val PREF_SHOW_CKB_DEVELOPER_SETTINGS = "pref_show_ckb_developer_settings"

/**
 * Per-gesture action assignments for the CKB gesture engine. Flick/swipe up is reserved (commit)
 * and not shown. Writes the `ckb_gesture_<slot>` prefs the policy reads.
 */
@Composable
fun CkbGesturesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    // Read once on first composition.
    val showDeveloperSettings = remember {
        prefs.getBoolean(PREF_SHOW_CKB_DEVELOPER_SETTINGS, false)
    }

    SettingsScaffold(
        title = stringResource(R.string.ckb_gestures_screen_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceInfo(stringResource(R.string.ckb_gestures_reserved_note))

            // Device defaults (a profile may override a slot), so an unset slot shows what the
            // arbiter will actually do.
            val defaults = remember { GestureAssignments.deviceDefaults() }
            PreferenceGroup {
                GestureSlot(R.string.ckb_gesture_slot_flick_left, GestureAssignments.KEY_FLICK_LEFT, defaults.flickLeft, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_flick_left"), iconRes = R.drawable.ic_settings_gesture_flick_left)
                GestureSlot(R.string.ckb_gesture_slot_flick_right, GestureAssignments.KEY_FLICK_RIGHT, defaults.flickRight, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_flick_right"), iconRes = R.drawable.ic_settings_gesture_flick_right)
                GestureSlot(R.string.ckb_gesture_slot_swipe_left, GestureAssignments.KEY_SWIPE_LEFT, defaults.swipeLeft, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_swipe_left"), iconRes = R.drawable.ic_settings_gesture_swipe_left)
                GestureSlot(R.string.ckb_gesture_slot_swipe_right, GestureAssignments.KEY_SWIPE_RIGHT, defaults.swipeRight, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_swipe_right"), iconRes = R.drawable.ic_settings_gesture_swipe_right)
                GestureSlot(R.string.ckb_gesture_slot_swipe_down, GestureAssignments.KEY_SWIPE_DOWN, defaults.swipeDown, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_swipe_down"), iconRes = R.drawable.ic_settings_gesture_swipe_down)
                GestureSlot(R.string.ckb_gesture_slot_double_tap, GestureAssignments.KEY_DOUBLE_TAP, defaults.doubleTap, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_double_tap"), iconRes = R.drawable.ic_settings_gesture_double_tap)
                GestureSlot(R.string.ckb_gesture_slot_hold, GestureAssignments.KEY_HOLD, defaults.hold, prefs,
                    Modifier.settingsSearchAnchor("ckb_gesture_hold"), iconRes = R.drawable.ic_settings_gesture_tap_hold)
            }

            // The "no gesture within N ms of a key press" window (KeyTypingGuard). Its only copy.
            PreferenceCategory(title = stringResource(R.string.settings_gesture_category_timing))
            var suppressionTimeout by remember {
                mutableStateOf(
                    prefs.getInt(
                        // Persisted key, read by SettingsValues.ckbGestureSuppressionTimeout.
                        "pref_CKB_gesture_suppression_timeout",
                        context.resources.getInteger(
                            R.integer.config_default_CKB_swipe_gesture_suppression_timeout
                        )
                    )
                )
            }
            PreferenceGroup {
                SliderPreference(
                    title = stringResource(R.string.prefs_ckb_gesture_activation_delay_title),
                    summary = stringResource(R.string.prefs_ckb_gesture_activation_delay_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_gesture_delay),
                    value = suppressionTimeout,
                    valueRange = 0..500,
                    stepSize = 10,
                    unit = "ms",
                    valueOnRow = true,
                    modifier = Modifier.settingsSearchAnchor("pref_CKB_gesture_suppression_timeout"),
                    onValueChange = { newValue ->
                        suppressionTimeout = newValue
                        prefs.edit().putInt("pref_CKB_gesture_suppression_timeout", newValue).apply()
                    }
                )
            }

            // See [PREF_SHOW_CKB_DEVELOPER_SETTINGS].
            if (showDeveloperSettings) {
                PreferenceCategory(title = stringResource(R.string.ckb_gestures_developer_category))
                PreferenceGroup {
                    PreferenceScreen(
                        title = stringResource(R.string.ckb_gestures_export_title),
                        summary = stringResource(R.string.ckb_gestures_export_summary),
                        iconSpaceReserved = true,
                        onClick = {
                            val file = CkbKeyGridCapture.exportXml(context)
                            val msg = if (file != null) context.getString(R.string.ckb_gestures_export_written, file.absolutePath)
                                      else context.getString(R.string.ckb_gestures_export_none)
                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                    )
                    var sensorVizOn by remember {
                        mutableStateOf(prefs.getBoolean(dev.bbkb.ime.core.gesture.replay.SensorViz.PREF_KEY, false))
                    }
                    SwitchPreference(
                        title = "Sensor Visualizer (debug)",
                        iconSpaceReserved = true,
                        summary = "ON, then focus any text field and sweep the physical keyboard. Plots every capacitive touch on the 1080x525 surface and dumps live/dead bands to logcat (tag XT9SENSOR) every ~800 samples — no overlay required.",
                        checked = sensorVizOn,
                        onCheckedChange = { nv ->
                            sensorVizOn = nv
                            prefs.edit().putBoolean(dev.bbkb.ime.core.gesture.replay.SensorViz.PREF_KEY, nv).apply()
                            dev.bbkb.ime.core.gesture.replay.SensorViz.active = nv
                            android.widget.Toast.makeText(
                                context,
                                if (nv) "Sensor Visualizer ON — focus a text field and sweep the keyboard"
                                else "Sensor Visualizer OFF",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                    PreferenceScreen(
                        title = "Export sensor map",
                        iconSpaceReserved = true,
                        summary = "Writes the captured sensor map to a file AND dumps the live/dead bands to logcat (tag XT9SENSOR). Sweep the keyboard first, then tap here.",
                        onClick = {
                            val f = dev.bbkb.ime.core.gesture.replay.SensorViz.export(context)
                            android.widget.Toast.makeText(
                                context,
                                if (f != null) "Saved: ${f.absolutePath} (also in logcat XT9SENSOR)"
                                else "No sensor data yet — switch on, focus a field, and sweep first",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }

            // Same bottom room SettingsScreenHost adds.
            Spacer(modifier = Modifier.height(LocalSpacing.current.extraLarge))
        }
    }
}

@Composable
private fun GestureSlot(
    labelRes: Int,
    prefKey: String,
    default: GestureAction,
    prefs: android.content.SharedPreferences,
    modifier: Modifier = Modifier,
    /** The slot's icon; a slot without one keeps the icon column so the titles line up. */
    iconRes: Int? = null,
) {
    val context = LocalContext.current
    val options = remember { GestureAction.assignableActions }
    var current by remember { mutableStateOf(GestureAction.fromKey(prefs.getString(prefKey, default.key))) }
    ListPreference(
        title = stringResource(labelRes),
        summary = stringResource(current.labelRes()),
        icon = iconRes?.let { ImageVector.vectorResource(it) },
        iconSpaceReserved = iconRes == null,
        entries = options.map { context.getString(it.labelRes()) },
        entryValues = options.map { it.key },
        value = current.key,
        modifier = modifier,
        onValueChange = { key ->
            current = GestureAction.fromKey(key)
            prefs.edit().putString(prefKey, key).apply()
        }
    )
}
