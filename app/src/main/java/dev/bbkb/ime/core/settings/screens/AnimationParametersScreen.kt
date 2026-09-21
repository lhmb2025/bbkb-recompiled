package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Slide
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.intPref
import dev.bbkb.ime.core.settings.ui.percentPref
import dev.bbkb.ime.R

/** Every parameter row is live only while the custom-animation switch is on. */
private const val CUSTOM_ANIM = "pref_has_custom_key_preview_animation_params"

/**
 * Animation Parameters Screen
 * Detailed configuration for key preview animation parameters
 *
 * The four scale keys are stored as fractions and edited here as whole percent; [percentPref]
 * carries the conversion. An older version of this screen wrote them with putInt, so the engine's
 * getFloat threw, deleted the key and fell back to the resource fraction — the sliders never took
 * effect and wiped themselves on the next SettingsValues construction.
 */
@Composable
fun AnimationParametersScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(R.string.settings_animation_params_title, onNavigateBack, listOf(
        Toggle(boolPref(CUSTOM_ANIM, false), R.string.settings_anim_custom_preview_title,
            RowSummary.Res(R.string.settings_anim_custom_preview_summary)),

        Category(R.string.settings_anim_category_scale),
        Slide(percentPref("pref_key_preview_show_up_start_x_scale", R.fraction.config_key_preview_show_up_start_scale),
            R.string.settings_anim_show_start_x_title,
            R.string.settings_anim_show_start_x_summary, 0..200, step = 5, unit = "%",
            enabled = { it.bool(CUSTOM_ANIM) }),
        Slide(percentPref("pref_key_preview_show_up_start_y_scale", R.fraction.config_key_preview_show_up_start_scale),
            R.string.settings_anim_show_start_y_title,
            R.string.settings_anim_show_start_y_summary, 0..200, step = 5, unit = "%",
            enabled = { it.bool(CUSTOM_ANIM) }),
        Slide(percentPref("pref_key_preview_dismiss_end_x_scale", R.fraction.config_key_preview_dismiss_end_scale),
            R.string.settings_anim_dismiss_end_x_title,
            R.string.settings_anim_dismiss_end_x_summary, 0..200, step = 5, unit = "%",
            enabled = { it.bool(CUSTOM_ANIM) }),
        Slide(percentPref("pref_key_preview_dismiss_end_y_scale", R.fraction.config_key_preview_dismiss_end_scale),
            R.string.settings_anim_dismiss_end_y_title,
            R.string.settings_anim_dismiss_end_y_summary, 0..200, step = 5, unit = "%",
            enabled = { it.bool(CUSTOM_ANIM) }),

        Category(R.string.settings_anim_category_timing),
        Slide(intPref("pref_key_preview_show_up_duration", 60),
            R.string.settings_anim_show_duration_title,
            R.string.settings_anim_show_duration_summary, 0..200, step = 10, unit = "ms",
            enabled = { it.bool(CUSTOM_ANIM) }),
        Slide(intPref("pref_key_preview_dismiss_duration", 100),
            R.string.settings_anim_dismiss_duration_title,
            R.string.settings_anim_dismiss_duration_summary, 0..200, step = 10, unit = "ms",
            enabled = { it.bool(CUSTOM_ANIM) }),
    ))
}
