package dev.bbkb.ime.core.settings.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.R

private const val SHOW_SWITCH_KEY = "pref_show_language_switch_key"

/** Same key and default as the Physical keyboard screen's CKB gestures master switch. */
private const val CKB_GESTURES = "ckb_gestures_enabled"

/** The Physical keyboard rows that can be set to switch language (highlighted together). */
private val PKB_SWITCH_ANCHORS = arrayOf("pref_multifunction_key_action", "pref_alt_sym_shortcut_action")

/**
 * Language Switching Settings Screen (Subpage of Languages & Input).
 *
 * Two sections, by keyboard. **On-screen keyboard**: every option here is about the touch
 * keyboard's language key and space bar. **Physical keyboard** (devices that have one): links to
 * where a physical key or a capacitive-keyboard gesture is set to switch language, rather than a
 * second copy of those settings — the shortcut link lands on Physical keyboard with the
 * multifunction key and Alt+Sym rows highlighted.
 */
@Composable
fun LanguageSwitchingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPhysicalKeyboard: () -> Unit = {},
    onNavigateToCkbGestures: () -> Unit = {},
) {
    val highlight = LocalSettingsHighlight.current
    val hasTouchKeypad = remember { DeviceProfile.current()?.hasTouchKeypad() ?: false }

    SettingsScreenHost(R.string.settings_language_switching_title, onNavigateBack, listOf(
        Category(R.string.settings_category_on_screen_keyboard),
        Toggle(
            store = boolPref(SHOW_SWITCH_KEY, true),
            title = R.string.settings_lang_switch_key_title,
            summary = RowSummary.Res(R.string.settings_lang_switch_key_summary),
            modifier = Modifier.settingsSearchAnchor("pref_show_language_switch_key"),
        ),
        Toggle(
            store = boolPref("pref_include_other_imes_in_language_switch_list", false),
            title = R.string.settings_lang_include_other_title,
            summary = RowSummary.Res(R.string.settings_lang_include_other_summary),
            enabled = { it.bool(SHOW_SWITCH_KEY) },
            modifier = Modifier.settingsSearchAnchor("pref_include_other_imes_in_language_switch_list"),
        ),
        Toggle(
            store = boolPref("pref_language_quick_switch_key", false),
            title = R.string.settings_lang_quick_switch_title,
            summary = RowSummary.Res(R.string.settings_lang_quick_switch_summary),
            modifier = Modifier.settingsSearchAnchor("pref_language_quick_switch_key"),
        ),
        Toggle(
            store = boolPref("pref_spacebar_language_switching", true),
            title = R.string.settings_lang_spacebar_switch_title,
            summary = RowSummary.Res(R.string.settings_lang_spacebar_switch_summary),
            modifier = Modifier.settingsSearchAnchor("pref_spacebar_language_switching"),
        ),

        Category(R.string.settings_category_physical_keyboard, visible = { it.hasPhysicalKeyboard }),
        Nav(
            title = R.string.settings_lang_assign_pkb_shortcut_title,
            summary = R.string.settings_lang_assign_pkb_shortcut_summary,
            visible = { it.hasPhysicalKeyboard },
            onClick = {
                highlight.request(*PKB_SWITCH_ANCHORS)
                onNavigateToPhysicalKeyboard()
            },
        ),
        // Read from prefs, not the host's state map: that map only tracks this screen's own rows.
        Nav(
            title = R.string.settings_lang_assign_ckb_gesture_title,
            summary = R.string.settings_lang_assign_ckb_gesture_summary,
            visible = { hasTouchKeypad && it.prefs.getBoolean(CKB_GESTURES, true) },
            onClick = { onNavigateToCkbGestures() },
        ),
    ))
}
