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
import dev.bbkb.ime.core.settings.ui.drawableIcon
import dev.bbkb.ime.R



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
            store = boolPref("pref_language_quick_switch_key", false),
            title = R.string.settings_lang_quick_switch_title,
            icon = drawableIcon(R.drawable.ic_settings_language_quick_switch),
            summary = RowSummary.Res(R.string.settings_lang_quick_switch_summary),
            modifier = Modifier.settingsSearchAnchor("pref_language_quick_switch_key"),
        ),
        Toggle(
            store = boolPref("pref_spacebar_language_switching", true),
            title = R.string.settings_lang_spacebar_switch_title,
            icon = drawableIcon(R.drawable.ic_settings_language_spacebar_swipe),
            summary = RowSummary.Res(R.string.settings_lang_spacebar_switch_summary),
            modifier = Modifier.settingsSearchAnchor("pref_spacebar_language_switching"),
        ),

        Category(R.string.settings_category_physical_keyboard, visible = { it.hasPhysicalKeyboard }),
        Nav(
            title = R.string.settings_lang_assign_pkb_shortcut_title,
            icon = drawableIcon(R.drawable.ic_settings_language_switch_shortcut),
            summary = R.string.settings_lang_assign_pkb_shortcut_summary,
            visible = { it.hasPhysicalKeyboard },
            onClick = {
                highlight.request(*PKB_SWITCH_ANCHORS)
                onNavigateToPhysicalKeyboard()
            },
        ),
        // Under the Physical keyboard header, so only where that header shows; and there whether
        // the gestures are on or off, since nothing is hidden because of a switch.
        Nav(
            title = R.string.settings_lang_assign_ckb_gesture_title,
            icon = drawableIcon(R.drawable.ic_settings_swipe_typing),
            summary = R.string.settings_lang_assign_ckb_gesture_summary,
            visible = { it.hasPhysicalKeyboard && hasTouchKeypad },
            onClick = { onNavigateToCkbGestures() },
        ),
    ))
}
