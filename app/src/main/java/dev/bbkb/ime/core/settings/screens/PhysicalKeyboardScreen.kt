package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.keyevent.AltSymShortcutHandler
import dev.bbkb.ime.core.keyevent.MultifunctionKeyHandler
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.Category
import dev.bbkb.ime.core.settings.ui.Choice
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.ManagedToggle
import dev.bbkb.ime.core.settings.ui.Nav
import dev.bbkb.ime.core.settings.ui.PrefStore
import dev.bbkb.ime.core.settings.ui.RowSummary
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost
import dev.bbkb.ime.core.settings.ui.Toggle
import dev.bbkb.ime.core.settings.ui.boolPref
import dev.bbkb.ime.core.settings.ui.stringPref
import dev.bbkb.ime.R

private const val CKB_GESTURES = "ckb_gestures_enabled"

/**
 * Physical Keyboard Settings Screen
 * Categories: Behavior, Capacitive Keyboard Gestures (conditional), Modifiers
 *
 * Note: "Override device meta state" and "BBKB Helper" have moved to
 * Advanced → Device Compatibility.
 */
@Composable
fun PhysicalKeyboardScreen(
    onNavigateBack: () -> Unit,
    onNavigateToCkbGestures: () -> Unit = {}
) {
    val context = LocalContext.current
    val hasTouchKeypad = remember { DeviceProfile.current()?.hasTouchKeypad() ?: false }
    val isChineseLocale = remember { LocaleUtils.isCurrentSubtypeChinese() }

    // Multifunction key action — only for devices whose config declares a MULTIFUNCTION key
    // (e.g. the Key2 mic key); hidden otherwise.
    val multifunctionKeyMapping = remember {
        DeviceProfile.current()?.getDeviceMapping()?.getMultifunctionKeyMapping()
    }
    val multifunctionDefault = MultifunctionKeyHandler.upgradeLegacyAction(multifunctionKeyMapping?.defaultAction)
        ?.takeIf { it.isNotEmpty() } ?: MultifunctionKeyHandler.DEFAULT_ACTION

    // Three keys are declared in strings.xml rather than inline; they are resolved through the
    // same resource the rest of the app reads them by.
    val modifierStatusIconKey = context.getString(R.string.pref_show_pkb_modifier_status_icon_key)
    val shiftDoubleTapLockKey = context.getString(R.string.pref_shift_double_tap_lock_key)
    val altDoubleTapLockKey = context.getString(R.string.pref_alt_double_tap_lock_key)

    SettingsScreenHost(R.string.settings_physical_keyboard_title, onNavigateBack, listOf(
        Category(R.string.settings_category_behavior),
        Toggle(
            store = boolPref("pref_voice_input_key", true),
            title = R.string.settings_pkb_dictation_key_title,
            summary = RowSummary.OnOff(
                R.string.settings_pkb_dictation_key_summary_on,
                R.string.settings_pkb_dictation_key_summary_off,
            ),
            modifier = Modifier.settingsSearchAnchor("pref_voice_input_key"),
        ),
        Choice(
            store = multifunctionStore(multifunctionDefault),
            title = R.string.settings_pkb_multifunction_key_title,
            options = ::multifunctionActions,
            // Emoji board: the fallback default action.
            fallbackIndex = 1,
            // Anchor kept as a string literal (== MultifunctionKeyHandler.PREF_KEY) because the
            // search-index sync test source-scans for literals.
            modifier = Modifier.settingsSearchAnchor("pref_multifunction_key_action"),
            visible = { multifunctionKeyMapping != null },
        ),
        Choice(
            store = stringPref("pref_alt_sym_shortcut_action", "disabled"),
            title = R.string.settings_pkb_alt_sym_shortcut_title,
            options = ::altSymActions,
            modifier = Modifier.settingsSearchAnchor("pref_alt_sym_shortcut_action"),
        ),
        Choice(
            store = stringPref("pref_pkb_hold_auto_commit", "off"),
            title = R.string.settings_pkb_hold_action_title,
            options = ::holdActions,
            // The row's summary uses a different resource from the dialog's label, so it is
            // spelled out rather than derived from the options.
            summary = ::holdActionSummary,
            modifier = Modifier.settingsSearchAnchor("pref_pkb_hold_auto_commit"),
        ),
        Choice(
            store = stringPref("control_mode", "2"),
            title = R.string.settings_pkb_ctrl_key_behavior_title,
            options = ::controlModes,
            fallbackIndex = 2,
            modifier = Modifier.settingsSearchAnchor("control_mode"),
        ),

        // ── CAPACITIVE KEYBOARD GESTURES (CKB devices only) ──────────────────────
        Category(R.string.settings_category_ckb_gestures, visible = { hasTouchKeypad }),
        ManagedToggle(
            store = boolPref("type_by_swiping_ckb", false),
            title = R.string.settings_screen_type_by_swiping,
            summary = RowSummary.Res(R.string.settings_screen_type_by_swiping_summary),
            enabled = { !isChineseLocale },
            visible = { hasTouchKeypad },
        ),
        Custom(visible = { hasTouchKeypad && isChineseLocale }) { ChineseLocaleSwipeWarning() },
        ManagedToggle(
            store = boolPref(CKB_GESTURES, true),
            title = R.string.ckb_gestures_enable_title,
            summary = RowSummary.Res(R.string.ckb_gestures_enable_summary),
            visible = { hasTouchKeypad },
        ),
        Nav(
            title = R.string.ckb_gestures_configure_title,
            summary = R.string.ckb_gestures_configure_summary,
            visible = { hasTouchKeypad && it.bool(CKB_GESTURES) },
            onClick = { onNavigateToCkbGestures() },
        ),

        Category(R.string.settings_category_modifiers),
        Toggle(
            store = boolPref(modifierStatusIconKey, true),
            title = R.string.pref_show_pkb_modifier_status_icon,
            summary = RowSummary.Res(R.string.pref_show_pkb_modifier_status_icon_summary),
            modifier = Modifier.settingsSearchAnchor("pref_show_pkb_modifier_status_icon"),
        ),
        Toggle(
            store = boolPref(shiftDoubleTapLockKey, true),
            title = R.string.pref_shift_double_tap_lock,
            summary = RowSummary.Res(R.string.pref_shift_double_tap_lock_summary),
            modifier = Modifier.settingsSearchAnchor("pref_shift_double_tap_lock"),
        ),
        Toggle(
            store = boolPref(altDoubleTapLockKey, true),
            title = R.string.pref_alt_double_tap_lock,
            summary = RowSummary.Res(R.string.pref_alt_double_tap_lock_summary),
            modifier = Modifier.settingsSearchAnchor("pref_alt_double_tap_lock"),
        ),
    ))
}

/** An empty stored action means "unset" and falls back to the device mapping's default. */
private fun multifunctionStore(default: String) = PrefStore(
    MultifunctionKeyHandler.PREF_KEY,
    { prefs, _ ->
        MultifunctionKeyHandler.upgradeLegacyAction(prefs.getString(MultifunctionKeyHandler.PREF_KEY, ""))
            ?.takeIf { it.isNotEmpty() } ?: default
    },
    { editor, value -> editor.putString(MultifunctionKeyHandler.PREF_KEY, value) },
)

private fun controlModes(context: Context) = listOf(
    ChoiceOption("0", context.getString(R.string.control_key_setting_right_shift)),
    ChoiceOption("1", context.getString(R.string.control_key_setting_left_shift)),
    ChoiceOption("2", context.getString(R.string.control_key_setting_off)),
)

private fun multifunctionActions(context: Context) = listOf(
    ChoiceOption(MultifunctionKeyHandler.ACTION_CTRL, context.getString(R.string.settings_pkb_multifunction_action_ctrl)),
) + sharedShortcutActions(context)

/**
 * The actions the multifunction key and the Alt+Sym shortcut both offer — everything but Ctrl.
 * The two settings share action ids and labels.
 */
private fun sharedShortcutActions(context: Context) = listOf(
    MultifunctionKeyHandler.ACTION_EMOJI_BOARD to R.string.settings_pkb_multifunction_action_emoji,
    MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD to R.string.settings_pkb_multifunction_action_clipboard,
    MultifunctionKeyHandler.ACTION_FCC to R.string.settings_pkb_multifunction_action_fcc,
    MultifunctionKeyHandler.ACTION_NUMBER_PAD to R.string.settings_pkb_multifunction_action_number_pad,
    MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH to R.string.settings_pkb_multifunction_action_language_switch,
    MultifunctionKeyHandler.ACTION_SYMBOL_KEYBOARD to R.string.settings_pkb_multifunction_action_symbol,
).map { (value, label) -> ChoiceOption(value, context.getString(label)) }

private fun holdActions(context: Context) = listOf(
    ChoiceOption("off", context.getString(R.string.settings_pkb_hold_action_off)),
    ChoiceOption("uppercase", context.getString(R.string.settings_pkb_hold_action_uppercase)),
    ChoiceOption("symbol", context.getString(R.string.settings_pkb_hold_action_symbol)),
)

private fun holdActionSummary(context: Context, value: String): String = context.getString(
    when (value) {
        "uppercase" -> R.string.settings_pkb_hold_action_uppercase_summary
        "symbol" -> R.string.settings_pkb_hold_action_symbol_summary
        else -> R.string.settings_pkb_hold_action_off_summary
    }
)

private fun altSymActions(context: Context) = listOf(
    ChoiceOption(AltSymShortcutHandler.ACTION_DISABLED, context.getString(R.string.settings_pkb_alt_sym_shortcut_disabled)),
) + sharedShortcutActions(context)
