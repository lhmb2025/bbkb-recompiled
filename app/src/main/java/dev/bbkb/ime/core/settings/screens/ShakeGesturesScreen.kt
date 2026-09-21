package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.R

/**
 * Shake Gestures Settings Screen
 * Configure shake gesture actions and sensitivity
 */
@Composable
fun ShakeGesturesScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_shake_title, onNavigateBack, listOf(
        Custom { ShakeIntro() },
        shakeAction("shake_x_action", R.string.settings_shake_x_axis_title,
            Modifier.settingsSearchAnchor("shake_x_action")),
        shakeAction("shake_y_action", R.string.settings_shake_y_axis_title,
            Modifier.settingsSearchAnchor("shake_y_action")),
        shakeAction("shake_z_action", R.string.settings_shake_z_axis_title,
            Modifier.settingsSearchAnchor("shake_z_action")),
        shakeAction("shake_fallback_action", R.string.settings_shake_fallback_title,
            Modifier.settingsSearchAnchor("shake_fallback_action")),
    ))
}

/** All four rows offer the same action list and fall back to its first entry. */
private fun shakeAction(key: String, titleRes: Int, anchor: Modifier) = Choice(
    store = stringPref(key, "0"),
    title = titleRes,
    options = ::shakeActionOptions,
    modifier = anchor,
)

/** R.array.prefs_shake_action_options / _values, kept parallel there. */
private fun shakeActionOptions(context: Context): List<ChoiceOption> {
    val labels = context.resources.getStringArray(R.array.prefs_shake_action_options)
    val values = context.resources.getStringArray(R.array.prefs_shake_action_values)
    return values.mapIndexed { index, value ->
        ChoiceOption(value, labels.getOrElse(index) { value })
    }
}

/**
 * Screen intro. This is a paragraph, not a setting, so it is a plain Text rather than a
 * PreferenceItem: PreferenceItem always lays out its title, and passing "" for it painted a blank
 * bodyLarge line above the summary (defect 10). Same shape as the intro on CustomizeMenuScreen.
 */
@Composable
private fun ShakeIntro() {
    val spacing = LocalSpacing.current
    Text(
        text = LocalContext.current.getString(R.string.settings_shake_info_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = spacing.large, vertical = spacing.medium)
    )
}
