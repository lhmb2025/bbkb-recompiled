package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.PrefStore
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.asRowIcon
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.R

/**
 * Key Press Feedback Settings Screen
 * Manages vibration, sound, and popup feedback for key presses
 *
 * The three switch defaults come from the same `config_default_*` resources SettingsManager
 * reads, or a toggle could disagree with what the keyboard is actually doing until it is first
 * touched. The vibration-duration and sound-volume rows are edited as strings but persisted as
 * ints with a "system default" sentinel each stores differently — see their stores below.
 */
@Composable
fun KeyPressFeedbackScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_key_press_feedback_title, onNavigateBack, listOf(
        Category(R.string.settings_category_vibrate),
        Toggle(
            store = boolPref("vibrate_on", R.bool.config_default_vibration_enabled),
            title = R.string.vibrate_on_keypress,
            summary = RowSummary.Res(R.string.vibrate_on_keypress_summary),
            icon = Icons.Default.Vibration.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("vibrate_on"),
        ),
        Choice(
            store = VIBRATION_DURATION,
            title = R.string.prefs_keypress_vibration_duration_settings,
            options = { VIBRATION_DURATIONS },
            summary = { _, value -> vibrationDurationLabel(value) },
            iconSpaceReserved = true,
            enabled = { it.bool("vibrate_on") },
            modifier = Modifier.settingsSearchAnchor("pref_vibration_duration_settings"),
        ),

        Category(R.string.settings_category_sound),
        Toggle(
            store = boolPref("sound_on", R.bool.config_default_sound_enabled),
            title = R.string.sound_on_keypress,
            summary = RowSummary.Res(R.string.sound_on_keypress_summary),
            icon = Icons.AutoMirrored.Filled.VolumeUp.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("sound_on"),
        ),
        Choice(
            store = SOUND_VOLUME,
            title = R.string.prefs_keypress_sound_volume_settings,
            options = { SOUND_VOLUMES },
            summary = { _, value -> soundVolumeLabel(value) },
            iconSpaceReserved = true,
            enabled = { it.bool("sound_on") },
            modifier = Modifier.settingsSearchAnchor("pref_keypress_sound_volume"),
        ),

        Category(R.string.settings_category_key_popup),
        Toggle(
            store = boolPref("popup_on", R.bool.config_default_key_preview_popup),
            title = R.string.popup_on_keypress,
            summary = RowSummary.Res(R.string.popup_on_keypress_summary),
            icon = Icons.Default.Visibility.asRowIcon(),
            modifier = Modifier.settingsSearchAnchor("popup_on"),
        ),
        Choice(
            store = stringPref("pref_key_preview_popup_dismiss_delay", ::defaultLingerTimeout),
            title = R.string.key_preview_popup_dismiss_delay,
            options = ::popupDismissDelays,
            summary = { _, value -> popupDismissDelayLabel(value) },
            iconSpaceReserved = true,
            enabled = { it.bool("popup_on") },
            modifier = Modifier.settingsSearchAnchor("pref_key_preview_popup_dismiss_delay"),
        ),
    ))
}

/** `-1` means "system default" and is written, not removed. */
private val VIBRATION_DURATION = PrefStore(
    "pref_vibration_duration_settings",
    { prefs, _ ->
        prefs.getInt("pref_vibration_duration_settings", -1)
            .let { if (it == -1) SYSTEM_DEFAULT else it.toString() }
    },
    { editor, value ->
        editor.putInt(
            "pref_vibration_duration_settings",
            if (value == SYSTEM_DEFAULT) -1 else value.toInt()
        )
    },
)

/**
 * "System default" here means *no* stored entry, so choosing it removes the key.
 *
 * Written as an int percent, but an entry the original APK's seek bar wrote is a 0..1 float, so
 * the read accepts both, as `SettingsManager.getKeypressSoundVolume` does.
 */
private val SOUND_VOLUME = PrefStore(
    "pref_keypress_sound_volume",
    { prefs, _ ->
        val percent = try {
            prefs.getInt("pref_keypress_sound_volume", -1)
        } catch (e: ClassCastException) {
            prefs.getFloat("pref_keypress_sound_volume", -1f)
                .let { if (it < 0f) -1 else Math.round(it * 100f) }
        }
        if (percent == -1) SYSTEM_DEFAULT else percent.toString()
    },
    { editor, value ->
        if (value == SYSTEM_DEFAULT) {
            editor.remove("pref_keypress_sound_volume")
        } else {
            editor.putInt("pref_keypress_sound_volume", value.toInt())
        }
    },
)

private const val SYSTEM_DEFAULT = "system_default"

private val VIBRATION_DURATIONS = listOf(
    ChoiceOption(SYSTEM_DEFAULT, "System default"),
    ChoiceOption("5", "5ms"), ChoiceOption("10", "10ms"), ChoiceOption("15", "15ms"),
    ChoiceOption("20", "20ms"), ChoiceOption("25", "25ms"), ChoiceOption("30", "30ms"),
    ChoiceOption("40", "40ms"), ChoiceOption("50", "50ms"), ChoiceOption("75", "75ms"),
    ChoiceOption("100", "100ms"),
)

/** Stored as a 0..100 percentage, shown as a 1..10 level. */
private val SOUND_VOLUMES = listOf(ChoiceOption(SYSTEM_DEFAULT, "System default")) +
    (1..10).map { ChoiceOption((it * 10).toString(), it.toString()) }

private fun defaultLingerTimeout(context: Context): String =
    context.resources.getInteger(R.integer.config_key_preview_linger_timeout).toString()

private fun popupDismissDelays(context: Context) = listOf(
    ChoiceOption(defaultLingerTimeout(context),
        context.getString(R.string.key_preview_popup_dismiss_default_delay)),
    ChoiceOption("0", context.getString(R.string.key_preview_popup_dismiss_no_delay)),
    ChoiceOption("70", "Short"),
    ChoiceOption("150", "Long"),
)

private fun vibrationDurationLabel(value: String): String = when (value) {
    SYSTEM_DEFAULT, "-1" -> "System default"
    else -> "${value}ms"
}

private fun soundVolumeLabel(value: String): String {
    return when (value) {
        SYSTEM_DEFAULT -> "System default"
        else -> {
            val level = value.toIntOrNull()?.div(10) ?: return value
            level.toString()
        }
    }
}

private fun popupDismissDelayLabel(value: String): String = when (value) {
    "0" -> "No delay"
    "70" -> "Short"
    "150" -> "Long"
    else -> "Default"
}
