package dev.bbkb.ime.core.settings.screens

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import dev.bbkb.ime.R
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.settings.ui.ListPreference

internal fun GestureAction.labelRes(): Int = when (this) {
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
 * One gesture slot's action picker, shared by the capacitive and on-screen gesture pages.
 * Writes the action's key to [prefKey]; [default] is what an unset slot does.
 */
@Composable
internal fun GestureSlot(
    labelRes: Int,
    prefKey: String,
    default: GestureAction,
    prefs: SharedPreferences,
    modifier: Modifier = Modifier,
    /** The slot's icon; a slot without one keeps the icon column so the titles line up. */
    iconRes: Int? = null,
    options: List<GestureAction> = GestureAction.assignableActions,
) {
    val context = LocalContext.current
    var current by remember {
        val stored = GestureAction.fromKey(prefs.getString(prefKey, default.key))
        // A stored action this page does not offer reads as the slot's default.
        mutableStateOf(if (stored in options) stored else default)
    }
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
