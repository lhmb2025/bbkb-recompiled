package dev.bbkb.ime.core.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.interceptor.KeyInterceptorManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/**
 * Keyboard Helper Settings Screen
 * Manages accessibility service settings for third-party physical keyboards.
 * 
 * Contains:
 * - Manage Accessibility Service link (navigates to system settings)
 * - Enable special key support toggle (moved from Advanced Settings)
 * - Pre-process all key events toggle (new feature)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardHelperScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Track accessibility service status
    var serviceEnabled by remember {
        mutableStateOf(KeyInterceptorManager.isServiceEnabled(context))
    }
    
    // Track preference states
    var specialKeyEnabled by remember {
        mutableStateOf(prefs.getBoolean(KeyInterceptorManager.PREF_KEY_INTERCEPTOR_ENABLED, false))
    }
    var preprocessAllKeys by remember {
        mutableStateOf(prefs.getBoolean(KeyInterceptorManager.PREF_PREPROCESS_ALL_KEYS, false))
    }
    var unifiedKeyMapping by remember {
        mutableStateOf(prefs.getBoolean(KeyInterceptorManager.PREF_USE_UNIFIED_KEY_MAPPING, false))
    }
    
    // Re-check service status when activity resumes (e.g., returning from accessibility settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = KeyInterceptorManager.isServiceEnabled(context)
                specialKeyEnabled = prefs.getBoolean(KeyInterceptorManager.PREF_KEY_INTERCEPTOR_ENABLED, false)
                preprocessAllKeys = prefs.getBoolean(KeyInterceptorManager.PREF_PREPROCESS_ALL_KEYS, false)
                unifiedKeyMapping = prefs.getBoolean(KeyInterceptorManager.PREF_USE_UNIFIED_KEY_MAPPING, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.keyboard_helper_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        val spacing = LocalSpacing.current
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Manage Accessibility Service link
            PreferenceScreen(
                title = context.getString(R.string.pref_manage_accessibility_service),
                summary = if (serviceEnabled) {
                    context.getString(R.string.pref_accessibility_service_enabled)
                } else {
                    context.getString(R.string.pref_accessibility_service_disabled)
                },
                icon = Icons.Default.Settings,
                onClick = {
                    KeyInterceptorManager.openAccessibilitySettings(context)
                }
            )
            
            // Enable special key support toggle
            SwitchPreference(
                title = context.getString(R.string.pref_key_interceptor_enabled),
                summary = context.getString(R.string.pref_key_interceptor_enabled_summary),
                icon = Icons.Default.Accessibility,
                checked = specialKeyEnabled && serviceEnabled,
                enabled = serviceEnabled,
                modifier = Modifier.settingsSearchAnchor("pref_key_interceptor_enabled"),
                onCheckedChange = { newValue ->
                    specialKeyEnabled = newValue
                    KeyInterceptorManager.setFeatureEnabled(context, newValue)
                }
            )
            
            // Pre-process all key events toggle
            SwitchPreference(
                title = context.getString(R.string.pref_preprocess_all_keys),
                summary = context.getString(R.string.pref_preprocess_all_keys_summary),
                icon = Icons.Default.Accessibility,
                checked = preprocessAllKeys && serviceEnabled,
                enabled = serviceEnabled,
                modifier = Modifier.settingsSearchAnchor("pref_preprocess_all_key_events"),
                onCheckedChange = { newValue ->
                    preprocessAllKeys = newValue
                    KeyInterceptorManager.setPreprocessAllKeysEnabled(context, newValue)
                }
            )
            
            // Unified key mapping pipeline toggle
            SwitchPreference(
                title = "Unified Key Mapping",
                summary = "Route special keys via XML config instead of hardcoded logic (experimental)",
                icon = Icons.Default.Settings,
                checked = unifiedKeyMapping && serviceEnabled,
                enabled = serviceEnabled && specialKeyEnabled,
                modifier = Modifier.settingsSearchAnchor("pref_use_unified_key_mapping"),
                onCheckedChange = { newValue ->
                    unifiedKeyMapping = newValue
                    KeyInterceptorManager.setUnifiedKeyMappingEnabled(context, newValue)
                }
            )
            
            // Bottom spacing for gesture navigation
            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}
