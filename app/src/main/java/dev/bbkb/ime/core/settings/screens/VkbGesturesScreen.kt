package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.R
import dev.bbkb.ime.core.gesture.arbiter.VkbGestureAssignments
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceInfo
import dev.bbkb.ime.core.settings.ui.SettingsScaffold

/**
 * What swipe left, right and down do on the on-screen keyboard (and the slideboard's number panel),
 * under the "Swipe gestures" switch. Swiping up is reserved for the prediction above a key.
 * Writes the `vkb_gesture_<slot>` prefs [VkbGestureAssignments] reads.
 */
@Composable
fun VkbGesturesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    // Device defaults (a profile may override a slot), so an unset slot shows what the keyboard does.
    val defaults = remember { VkbGestureAssignments.deviceDefaults() }
    val options = remember { VkbGestureAssignments.assignableActions }

    SettingsScaffold(
        title = stringResource(R.string.settings_vkb_swipe_gestures_title),
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceInfo(stringResource(R.string.vkb_gestures_reserved_note))
            PreferenceGroup {
                GestureSlot(R.string.ckb_gesture_slot_swipe_left, VkbGestureAssignments.KEY_SWIPE_LEFT, defaults.swipeLeft, prefs,
                    Modifier.settingsSearchAnchor("vkb_gesture_swipe_left"),
                    iconRes = R.drawable.ic_settings_gesture_swipe_left, options = options)
                GestureSlot(R.string.ckb_gesture_slot_swipe_right, VkbGestureAssignments.KEY_SWIPE_RIGHT, defaults.swipeRight, prefs,
                    Modifier.settingsSearchAnchor("vkb_gesture_swipe_right"),
                    iconRes = R.drawable.ic_settings_gesture_swipe_right, options = options)
                GestureSlot(R.string.ckb_gesture_slot_swipe_down, VkbGestureAssignments.KEY_SWIPE_DOWN, defaults.swipeDown, prefs,
                    Modifier.settingsSearchAnchor("vkb_gesture_swipe_down"),
                    iconRes = R.drawable.ic_settings_gesture_swipe_down, options = options)
            }
            // Same bottom room SettingsScreenHost adds.
            Spacer(modifier = Modifier.height(LocalSpacing.current.extraLarge))
        }
    }
}
