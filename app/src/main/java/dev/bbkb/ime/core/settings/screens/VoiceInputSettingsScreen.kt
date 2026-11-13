package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.os.Build
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
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.search.settingsSearchAnchor
import dev.bbkb.ime.core.settings.ui.ChoiceOption
import dev.bbkb.ime.core.settings.ui.ListPreference
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceGroup
import dev.bbkb.ime.core.settings.ui.PreferenceScreen
import dev.bbkb.ime.core.settings.ui.SettingsScaffold
import dev.bbkb.ime.core.settings.ui.SwitchPreference
import dev.bbkb.ime.keyboard.inputboard.voice.InstalledVoiceRecognizers
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguages
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceRecognizerChoice

@Composable
fun VoiceInputSettingsScreen(
    onNavigateToLanguageSelection: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    var builtInVoiceEnabled by remember {
        mutableStateOf(prefs.getBoolean("voice_input_enabled", true))
    }
    var useInputLanguage by remember {
        mutableStateOf(prefs.getBoolean("voice_input_use_input_language", true))
    }
    var preferOffline by remember {
        mutableStateOf(prefs.getBoolean("voice_input_prefer_offline", true))
    }
    var showPartialResults by remember {
        mutableStateOf(prefs.getBoolean("voice_input_show_partial_results", true))
    }
    var autoStartListening by remember {
        mutableStateOf(prefs.getBoolean("voice_input_auto_start", true))
    }
    var stopOnPause by remember {
        mutableStateOf(prefs.getBoolean("voice_input_stop_on_pause", true))
    }
    var downloadLanguages by remember {
        mutableStateOf(prefs.getBoolean(VoiceLanguages.PREF_AUTO_DOWNLOAD, true))
    }
    var blockOffensive by remember {
        // Default from the same resource SettingsManager.isBlockOffensiveEnabled reads, so the row
        // and the recogniser agree.
        mutableStateOf(prefs.getBoolean("pref_key_block_potentially_offensive",
            context.resources.getBoolean(R.bool.config_block_potentially_offensive)))
    }
    val recognizers = remember { InstalledVoiceRecognizers.query(context) }
    val onDeviceAvailable = remember { InstalledVoiceRecognizers.isOnDeviceAvailable(context) }
    var recognizer by remember {
        mutableStateOf(SettingsManager.getVoiceInputRecognizer(prefs))
    }
    val offlineLanguagesLink = remember(recognizer) { VoiceLanguages.settingsLink(context) }
    // Read once: only the picker changes it.
    val voiceLanguage = remember {
        prefs.getString(VOICE_LANGUAGE_PREF, SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE)
            ?: SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE
    }

    SettingsScaffold(
        title = context.getString(R.string.settings_voice_input_title),
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
                SwitchPreference(
                    title = context.getString(R.string.settings_voice_builtin_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_enable),
                    summary = context.getString(R.string.settings_voice_builtin_summary),
                    checked = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("voice_input_enabled"),
                    onCheckedChange = { newValue ->
                        builtInVoiceEnabled = newValue
                        prefs.edit().putBoolean("voice_input_enabled", newValue).apply()
                    }
                )

            }

            PreferenceCategory(context.getString(R.string.settings_voice_group_recognition))
            PreferenceGroup {
                // Shows what the setting resolves to now: an uninstalled choice reads as the system
                // default, which dictation falls back to.
                val recognizerOptions = voiceRecognizerOptions(context, recognizers, onDeviceAvailable)
                val resolvedRecognizer = VoiceRecognizerChoice
                    .select(recognizer, Build.VERSION.SDK_INT, onDeviceAvailable, recognizers)
                    .id()
                ListPreference(
                    title = context.getString(R.string.settings_voice_recognizer_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_recognizer),
                    summary = if (recognizers.isEmpty() && resolvedRecognizer == VoiceRecognizerChoice.SYSTEM_DEFAULT)
                        context.getString(R.string.settings_voice_recognizer_none_installed)
                    else
                        recognizerOptions.firstOrNull { it.value == resolvedRecognizer }?.label
                            ?: recognizerOptions.first().label,
                    entries = recognizerOptions.map { it.label },
                    entryValues = recognizerOptions.map { it.value },
                    value = resolvedRecognizer,
                    enabled = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("voice_input_recognizer"),
                    onValueChange = { newValue ->
                        recognizer = newValue
                        prefs.edit().putString("voice_input_recognizer", newValue).apply()
                    }
                )

                SwitchPreference(
                    title = context.getString(R.string.settings_voice_prefer_offline_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_offline),
                    summary = context.getString(R.string.settings_voice_prefer_offline_summary),
                    checked = preferOffline,
                    enabled = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("voice_input_prefer_offline"),
                    onCheckedChange = { newValue ->
                        preferOffline = newValue
                        prefs.edit().putBoolean("voice_input_prefer_offline", newValue).apply()
                    }
                )
            }

            PreferenceCategory(context.getString(R.string.settings_languages_title))
            PreferenceGroup {
                SwitchPreference(
                    title = context.getString(R.string.settings_voice_use_keyboard_lang_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_keyboard_alphabet),
                    summary = context.getString(R.string.settings_voice_use_keyboard_lang_summary),
                    checked = useInputLanguage,
                    enabled = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("voice_input_use_input_language"),
                    onCheckedChange = { newValue ->
                        useInputLanguage = newValue
                        prefs.edit().putBoolean("voice_input_use_input_language", newValue).apply()
                    }
                )

                // Always shown; greyed out while the switch above or voice input makes it moot.
                PreferenceScreen(
                    title = context.getString(R.string.settings_voice_input_lang_title),
                    summary = voiceLanguageDisplayName(voiceLanguage),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_m_language),
                    enabled = builtInVoiceEnabled && !useInputLanguage,
                    onClick = onNavigateToLanguageSelection
                )

                // Asks the recogniser for a voice language when a keyboard is added (Android 13+).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    SwitchPreference(
                        title = context.getString(R.string.settings_voice_download_languages_title),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_download),
                        summary = context.getString(R.string.settings_voice_download_languages_summary),
                        checked = downloadLanguages,
                        enabled = builtInVoiceEnabled,
                        modifier = Modifier.settingsSearchAnchor("voice_input_download_languages"),
                        onCheckedChange = { newValue ->
                            downloadLanguages = newValue
                            prefs.edit().putBoolean(VoiceLanguages.PREF_AUTO_DOWNLOAD, newValue).apply()
                        }
                    )
                }

                // The recogniser's own languages screen, only when it declares one we can open.
                offlineLanguagesLink?.let { (intent, appName) ->
                    PreferenceScreen(
                        title = context.getString(R.string.settings_voice_offline_languages_title),
                        summary = context.getString(R.string.settings_voice_offline_languages_summary, appName),
                        icon = ImageVector.vectorResource(R.drawable.ic_settings_m_open_in_new),
                        enabled = builtInVoiceEnabled,
                        onClick = {
                            try {
                                context.startActivity(intent)
                            } catch (e: RuntimeException) {
                                // Declared but not launchable after all: nothing to open.
                            }
                        }
                    )
                }
            }

            PreferenceCategory(context.getString(R.string.settings_voice_group_listening))
            PreferenceGroup {
                SwitchPreference(
                    title = context.getString(R.string.settings_voice_auto_start_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_auto_start),
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

                // Off is continuous dictation until the mic is tapped.
                SwitchPreference(
                    title = context.getString(R.string.settings_voice_stop_on_pause_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_stop_on_pause),
                    summary = if (stopOnPause)
                        context.getString(R.string.settings_voice_stop_on_pause_summary_on)
                    else
                        context.getString(R.string.settings_voice_stop_on_pause_summary_off),
                    checked = stopOnPause,
                    enabled = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("voice_input_stop_on_pause"),
                    onCheckedChange = { newValue ->
                        stopOnPause = newValue
                        prefs.edit().putBoolean("voice_input_stop_on_pause", newValue).apply()
                    }
                )
            }

            PreferenceCategory(context.getString(R.string.settings_voice_group_text))
            PreferenceGroup {
                // Partial results as composing text in the editor
                SwitchPreference(
                    title = context.getString(R.string.settings_voice_show_partial_results_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_partial_results),
                    summary = context.getString(R.string.settings_voice_show_partial_results_summary),
                    checked = showPartialResults,
                    enabled = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("voice_input_show_partial_results"),
                    onCheckedChange = { newValue ->
                        showPartialResults = newValue
                        prefs.edit().putBoolean("voice_input_show_partial_results", newValue).apply()
                    }
                )

                // EXTRA_MASK_OFFENSIVE_WORDS; dictation masking is its only effect.
                SwitchPreference(
                    title = context.getString(R.string.settings_pred_block_offensive_title),
                    icon = ImageVector.vectorResource(R.drawable.ic_settings_voice_block_offensive),
                    summary = context.getString(R.string.settings_pred_block_offensive_summary),
                    checked = blockOffensive,
                    enabled = builtInVoiceEnabled,
                    modifier = Modifier.settingsSearchAnchor("pref_key_block_potentially_offensive"),
                    onCheckedChange = { newValue ->
                        blockOffensive = newValue
                        prefs.edit().putBoolean("pref_key_block_potentially_offensive", newValue).apply()
                    }
                )
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

/**
 * "Speech recognizer" choices: the system default (named after its app when installed), each
 * installed recognition app, and the on-device recogniser where present.
 */
internal fun voiceRecognizerOptions(
    context: Context,
    recognizers: List<VoiceRecognizerChoice.Provider>,
    onDeviceAvailable: Boolean,
): List<ChoiceOption> {
    val systemDefault = VoiceRecognizerChoice.systemDefault(recognizers)
    return buildList {
        add(
            ChoiceOption(
                VoiceRecognizerChoice.SYSTEM_DEFAULT,
                if (systemDefault != null)
                    context.getString(R.string.settings_voice_recognizer_system_default_named, systemDefault.label)
                else
                    context.getString(R.string.settings_voice_recognizer_system_default),
            )
        )
        recognizers.forEach { add(ChoiceOption(it.component, it.label)) }
        if (onDeviceAvailable) {
            add(ChoiceOption(VoiceRecognizerChoice.ON_DEVICE, context.getString(R.string.settings_voice_recognizer_on_device)))
        }
    }
}
