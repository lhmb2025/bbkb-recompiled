package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import dev.bbkb.ime.R
import dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.gesture.arbiter.GestureAssignments
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.ListPreference
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
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
 * The debug flag that reveals this screen's DEVELOPER category.
 *
 * The sensor-map capture tools under it are instrumentation for whoever is working on the gesture
 * decoder — an export that writes a `<ckb-key-grid>` snippet to app files, and a visualizer that
 * spews to logcat — not settings. They are off by default and appear once this is switched on from
 * the debug screen. Nothing else on the screen is gated: the slots and the gesture-timing slider
 * are ordinary settings and always render.
 */
internal const val PREF_SHOW_CKB_DEVELOPER_SETTINGS = "pref_show_ckb_developer_settings"

/**
 * Per-gesture action assignments for the CKB gesture engine. Reached from Physical Keyboard
 * settings (Configure CKB gestures) once gestures are enabled. Flick/swipe **up** is reserved
 * (commit) and not shown. Writes the `ckb_gesture_<slot>` prefs the policy reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CkbGesturesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    // Read once when the screen first composes, as every other screen here reads its preferences.
    val showDeveloperSettings = remember {
        prefs.getBoolean(PREF_SHOW_CKB_DEVELOPER_SETTINGS, false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ckb_gestures_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceInfo(stringResource(R.string.ckb_gestures_reserved_note))

            val defaults = GestureAssignments()
            GestureSlot(R.string.ckb_gesture_slot_flick_left, GestureAssignments.KEY_FLICK_LEFT, defaults.flickLeft, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_flick_left"))
            GestureSlot(R.string.ckb_gesture_slot_flick_right, GestureAssignments.KEY_FLICK_RIGHT, defaults.flickRight, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_flick_right"))
            GestureSlot(R.string.ckb_gesture_slot_swipe_left, GestureAssignments.KEY_SWIPE_LEFT, defaults.swipeLeft, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_swipe_left"))
            GestureSlot(R.string.ckb_gesture_slot_swipe_right, GestureAssignments.KEY_SWIPE_RIGHT, defaults.swipeRight, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_swipe_right"))
            GestureSlot(R.string.ckb_gesture_slot_swipe_down, GestureAssignments.KEY_SWIPE_DOWN, defaults.swipeDown, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_swipe_down"))
            GestureSlot(R.string.ckb_gesture_slot_double_tap, GestureAssignments.KEY_DOUBLE_TAP, defaults.doubleTap, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_double_tap"))
            GestureSlot(R.string.ckb_gesture_slot_hold, GestureAssignments.KEY_HOLD, defaults.hold, prefs,
                Modifier.settingsSearchAnchor("ckb_gesture_hold"))

            // The "no gesture within N ms of a key press" window the arbiter honours through
            // KeyTypingGuard. It was exposed only on the debug screen and on Advanced Gesture
            // Parameters — neither is where someone whose gestures fire while typing looks — so
            // the single copy lives here, next to the slots it governs.
            PreferenceCategory(title = stringResource(R.string.settings_gesture_category_timing))
            var suppressionTimeout by remember {
                mutableStateOf(
                    prefs.getInt(
                        // Frozen user state, read on the engine side by
                        // SettingsValues.ckbGestureSuppressionTimeout.
                        "pref_CKB_gesture_suppression_timeout",
                        context.resources.getInteger(
                            R.integer.config_default_CKB_swipe_gesture_suppression_timeout
                        )
                    )
                )
            }
            SliderPreference(
                title = stringResource(R.string.prefs_ckb_gesture_activation_delay_title),
                summary = stringResource(R.string.prefs_ckb_gesture_activation_delay_summary),
                value = suppressionTimeout,
                valueRange = 0..500,
                stepSize = 10,
                unit = "ms",
                modifier = Modifier.settingsSearchAnchor("pref_CKB_gesture_suppression_timeout"),
                onValueChange = { newValue ->
                    suppressionTimeout = newValue
                    prefs.edit().putInt("pref_CKB_gesture_suppression_timeout", newValue).apply()
                }
            )

            // Instrumentation for the gesture decoder, not settings: behind the debug flag,
            // see [PREF_SHOW_CKB_DEVELOPER_SETTINGS]. Nothing here is deleted — switching the flag
            // on from the debug screen brings the whole category back.
            if (showDeveloperSettings) {
                PreferenceCategory(title = stringResource(R.string.ckb_gestures_developer_category))
                PreferenceScreen(
                    title = stringResource(R.string.ckb_gestures_export_title),
                    summary = stringResource(R.string.ckb_gestures_export_summary),
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
    }
}

@Composable
private fun GestureSlot(
    labelRes: Int,
    prefKey: String,
    default: GestureAction,
    prefs: android.content.SharedPreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val options = remember { GestureAction.assignableActions }
    var current by remember { mutableStateOf(GestureAction.fromKey(prefs.getString(prefKey, default.key))) }
    ListPreference(
        title = stringResource(labelRes),
        summary = stringResource(current.labelRes()),
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
