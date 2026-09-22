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
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SwitchPreference

/**
 * Voice Input Settings Screen
 * Configure voice input recognition settings
 * 
 * IMPLEMENTATION NOTE FOR VOICE RECOGNITION CODE:
 * When starting speech recognition, apply the "prefer offline" setting as follows:
 * 
 * ```java
 * Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
 * recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
 * 
 * // Force specific language if not using keyboard language
 * if (!useInputLanguage) {
 *     recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage); // e.g., "es-MX"
 *     recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLanguage);
 * }
 * 
 * // Apply offline preference
 * if (isOfflinePreferred) {
 *     recognizerIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
 * }
 * 
 * // Initialize SpeechRecognizer based on API level and offline preference
 * SpeechRecognizer speechRecognizer;
 * if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isOfflinePreferred) {
 *     // API 31+ (Android 12): Explicitly request on-device recognizer
 *     if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
 *         speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
 *     } else {
 *         // Fallback: On-device not available, use default cloud/hybrid
 *         speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
 *     }
 * } else {
 *     // Legacy devices or Online mode
 *     speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
 * }
 * 
 * speechRecognizer.setRecognitionListener(yourListener);
 * speechRecognizer.startListening(recognizerIntent);
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputSettingsScreen(
    onNavigateToLanguageSelection: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }
    
    // Enable built-in voice input vs system voice typing
    var builtInVoiceEnabled by remember {
        mutableStateOf(prefs.getBoolean("voice_input_enabled", true))
    }
    var useInputLanguage by remember {
        mutableStateOf(prefs.getBoolean("voice_input_use_input_language", true))
    }
    var preferOffline by remember {
        mutableStateOf(prefs.getBoolean("voice_input_prefer_offline", true))
    }
    var autoStartListening by remember {
        mutableStateOf(prefs.getBoolean("voice_input_auto_start", true))
    }
    var blockOffensive by remember {
        // Frozen key, and the engine reads it with
        // resources.getBoolean(R.bool.config_block_potentially_offensive) — now false — via
        // SettingsManager.isBlockOffensiveEnabled. Read the resource rather than hard-coding a
        // default here, so the row and the recogniser can never disagree about it.
        mutableStateOf(prefs.getBoolean("pref_key_block_potentially_offensive",
            context.resources.getBoolean(R.bool.config_block_potentially_offensive)))
    }
    // Read once: this screen only displays it; the picker is what changes it.
    val voiceLanguage = remember {
        prefs.getString(VOICE_LANGUAGE_PREF, SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE)
            ?: SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_voice_input_title)) },
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
            // Enable Built-in Voice Input
            SwitchPreference(
                title = context.getString(R.string.settings_voice_builtin_title),
                summary = context.getString(R.string.settings_voice_builtin_summary),
                checked = builtInVoiceEnabled,
                modifier = Modifier.settingsSearchAnchor("voice_input_enabled"),
                onCheckedChange = { newValue ->
                    builtInVoiceEnabled = newValue
                    prefs.edit().putBoolean("voice_input_enabled", newValue).apply()
                }
            )

            // Auto-start listening
            SwitchPreference(
                title = context.getString(R.string.settings_voice_auto_start_title),
                summary = if (autoStartListening)
                    context.getString(R.string.settings_voice_auto_start_summary_on)
                else
                    context.getString(R.string.settings_voice_auto_start_summary_off),
                checked = autoStartListening,
                enabled = builtInVoiceEnabled,
                modifier = Modifier.settingsSearchAnchor("voice_input_auto_start"),
                onCheckedChange = { newValue ->
                    autoStartListening = newValue
                    prefs.edit().putBoolean("voice_input_auto_start", newValue).apply()
                }
            )

            // Use Input Language
            SwitchPreference(
                title = context.getString(R.string.settings_voice_use_keyboard_lang_title),
                summary = context.getString(R.string.settings_voice_use_keyboard_lang_summary),
                checked = useInputLanguage,
                enabled = builtInVoiceEnabled,
                modifier = Modifier.settingsSearchAnchor("voice_input_use_input_language"),
                onCheckedChange = { newValue ->
                    useInputLanguage = newValue
                    prefs.edit().putBoolean("voice_input_use_input_language", newValue).apply()
                }
            )
            
            // Language Selection (when not using input language)
            if (builtInVoiceEnabled && !useInputLanguage) {
                PreferenceScreen(
                    title = context.getString(R.string.settings_voice_input_lang_title),
                    summary = voiceLanguageDisplayName(voiceLanguage),
                    icon = Icons.Default.Language,
                    onClick = onNavigateToLanguageSelection
                )
            }
            
            // Prefer Offline Recognition
            SwitchPreference(
                title = context.getString(R.string.settings_voice_prefer_offline_title),
                summary = context.getString(R.string.settings_voice_prefer_offline_summary),
                checked = preferOffline,
                enabled = builtInVoiceEnabled,
                modifier = Modifier.settingsSearchAnchor("voice_input_prefer_offline"),
                onCheckedChange = { newValue ->
                    preferOffline = newValue
                    prefs.edit().putBoolean("voice_input_prefer_offline", newValue).apply()
                }
            )

            // Block offensive words — the recogniser's EXTRA_MASK_OFFENSIVE_WORDS. It used to sit
            // on the Suggestions screen, among the prediction toggles, but dictation masking is
            // its only effect (VoiceRecognitionManager.startDictation), so it belongs here. The
            // preference key is unchanged, so nobody's setting moved with it.
            SwitchPreference(
                title = context.getString(R.string.settings_pred_block_offensive_title),
                summary = context.getString(R.string.settings_pred_block_offensive_summary),
                checked = blockOffensive,
                enabled = builtInVoiceEnabled,
                modifier = Modifier.settingsSearchAnchor("pref_key_block_potentially_offensive"),
                onCheckedChange = { newValue ->
                    blockOffensive = newValue
                    prefs.edit().putBoolean("pref_key_block_potentially_offensive", newValue).apply()
                }
            )

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}
