package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/**
 * Accessibility service settings for third-party physical keyboards.
 */
@Composable
fun KeyboardHelperScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    var serviceEnabled by remember {
        mutableStateOf(KeyInterceptorManager.isServiceEnabled(context))
    }

    var specialKeyEnabled by remember {
        mutableStateOf(prefs.getBoolean(KeyInterceptorManager.PREF_KEY_INTERCEPTOR_ENABLED, false))
    }
    var preprocessAllKeys by remember {
        mutableStateOf(prefs.getBoolean(KeyInterceptorManager.PREF_PREPROCESS_ALL_KEYS, false))
    }

    // Re-check on resume, e.g. returning from accessibility settings
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        serviceEnabled = KeyInterceptorManager.isServiceEnabled(context)
        specialKeyEnabled = prefs.getBoolean(KeyInterceptorManager.PREF_KEY_INTERCEPTOR_ENABLED, false)
        preprocessAllKeys = prefs.getBoolean(KeyInterceptorManager.PREF_PREPROCESS_ALL_KEYS, false)
    }
    
    SettingsScaffold(
        title = context.getString(R.string.keyboard_helper_title),
        onNavigateBack = onNavigateBack,
    ) { paddingValues ->
        val spacing = LocalSpacing.current
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            PreferenceGroup {
                PreferenceScreen(
                    title = context.getString(R.string.pref_manage_accessibility_service),
                    summary = if (serviceEnabled) {
                        context.getString(R.string.pref_accessibility_service_enabled)
                    } else {
                        context.getString(R.string.pref_accessibility_service_disabled)
                    },
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_accessibility),
                    onClick = {
                        KeyInterceptorManager.openAccessibilitySettings(context)
                    }
                )

                SwitchPreference(
                    title = context.getString(R.string.pref_key_interceptor_enabled),
                    summary = context.getString(R.string.pref_key_interceptor_enabled_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_multifunction_key),
                    checked = specialKeyEnabled && serviceEnabled,
                    enabled = serviceEnabled,
                    modifier = Modifier.settingsSearchAnchor("pref_key_interceptor_enabled"),
                    onCheckedChange = { newValue ->
                        specialKeyEnabled = newValue
                        KeyInterceptorManager.setFeatureEnabled(context, newValue)
                    }
                )

                SwitchPreference(
                    title = context.getString(R.string.pref_preprocess_all_keys),
                    summary = context.getString(R.string.pref_preprocess_all_keys_summary),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_keyboard),
                    checked = preprocessAllKeys && serviceEnabled,
                    enabled = serviceEnabled,
                    modifier = Modifier.settingsSearchAnchor("pref_preprocess_all_key_events"),
                    onCheckedChange = { newValue ->
                        preprocessAllKeys = newValue
                        KeyInterceptorManager.setPreprocessAllKeysEnabled(context, newValue)
                    }
                )
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}
