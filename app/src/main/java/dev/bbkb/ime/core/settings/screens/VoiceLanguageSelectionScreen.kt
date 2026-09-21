package dev.bbkb.ime.core.settings.screens

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.util.SettingsManager
import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "VoiceLanguageSelection"

/** The preference both voice screens read and write. */
internal const val VOICE_LANGUAGE_PREF = "voice_input_language_list"

/**
 * A language code as the language itself names it — "Français (France)" for `fr-FR`. Falls back
 * to the raw code for anything [Locale] cannot parse.
 */
internal fun voiceLanguageDisplayName(code: String): String = try {
    val parts = code.replace("_", "-").split("-")
    val locale = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
    locale.getDisplayName(locale).replaceFirstChar { it.uppercase() }
} catch (e: Exception) {
    code
}

/**
 * Full-screen list of the voice-recognition languages this device offers.
 *
 * Where the device can say which languages work without a network the list is split into an
 * "Offline Ready" section and a "Requires Network" one; where it cannot — the pre-API-33
 * discovery path, and the built-in fallback list — there is one unsectioned list and no offline
 * markers. Both shapes come out of the same `sections` list below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLanguageSelectionScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    var selectedLanguage by remember {
        mutableStateOf(
            prefs.getString(VOICE_LANGUAGE_PREF, SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE)
                ?: SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE
        )
    }

    // Dynamically detected voice input languages
    var availableLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var offlineLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Detect available voice recognition languages using appropriate API
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fetchLanguagesModernForSelection(context) { supported, offline ->
                availableLanguages = supported.sorted()
                offlineLanguages = offline.sorted()
                isLoading = false
            }
        } else {
            fetchLanguagesLegacyForSelection(context) { languages ->
                availableLanguages = languages.sorted()
                offlineLanguages = emptyList()
                isLoading = false
            }
        }
    }

    fun isOfflineReady(code: String): Boolean = offlineLanguages.any {
        it.equals(code, ignoreCase = true) ||
            it.replace("_", "-").equals(code.replace("_", "-"), ignoreCase = true)
    }

    /** Section header (null for the unsectioned case) to the languages listed under it. */
    val sections: List<Pair<String?, List<String>>> =
        remember(availableLanguages, offlineLanguages) {
            if (offlineLanguages.isEmpty()) {
                listOf(null to availableLanguages)
            } else {
                listOf(
                    "Offline Ready" to availableLanguages.filter { isOfflineReady(it) },
                    "Requires Network" to availableLanguages.filterNot { isOfflineReady(it) },
                ).filter { it.second.isNotEmpty() }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_voice_input_lang_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        when {
            isLoading -> CenteredMessage(paddingValues) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Loading available languages...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            availableLanguages.isEmpty() -> CenteredMessage(paddingValues) {
                Text(
                    text = "No voice recognition languages available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Make sure Google app is installed and voice recognition is enabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                sections.forEach { (header, languages) ->
                    if (header != null) {
                        item { SectionHeader(header) }
                    }
                    items(languages) { language ->
                        LanguageListItem(
                            languageCode = language,
                            displayName = voiceLanguageDisplayName(language),
                            isSelected = language == selectedLanguage ||
                                language.replace("-", "_") == selectedLanguage.replace("-", "_"),
                            // Unknown, not "no", when the device could not tell us.
                            isOfflineReady = if (offlineLanguages.isEmpty()) null else isOfflineReady(language),
                            onClick = {
                                selectedLanguage = language
                                prefs.edit().putString(VOICE_LANGUAGE_PREF, language).apply()
                                onNavigateBack()
                            }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

/** The loading and empty states: a centred column below the app bar. */
@Composable
private fun CenteredMessage(
    paddingValues: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        content()
    }
}

/**
 * A section subhead, in the app's own [PreferenceCategory].
 *
 * The two sections used to be told apart by colour — the first primary, the second
 * onSurfaceVariant with a rule above it — which made one heading look like a heading and the other
 * like a divider caption. They are the same kind of thing, so they are drawn the same way, and
 * proximity does the separating: [PreferenceCategory]'s 24dp top padding is the gap.
 */
@Composable
private fun SectionHeader(title: String) {
    PreferenceCategory(title = title)
}

/**
 * One language, on [PreferenceItem] rather than a Row with 14dp vertical padding and a 36dp spacer
 * standing in for the missing tick.
 *
 * The tick sits in the 24dp leading column that every other settings row uses for its icon, and a
 * row without one reserves that column, so the names stay in one line down the screen. The selected
 * row no longer also changes its title's colour and weight: the tick says which one it is, and two
 * type ramps in one list is what made this screen look unlike the rest of settings.
 */
@Composable
private fun LanguageListItem(
    languageCode: String,
    displayName: String,
    isSelected: Boolean,
    isOfflineReady: Boolean?,
    onClick: () -> Unit
) {
    PreferenceItem(
        title = displayName,
        summary = languageCode,
        leading = if (isSelected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        iconSpaceReserved = true,
        modifier = Modifier.selectable(
            selected = isSelected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
        trailing = if (isOfflineReady == true) {
            {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = "Offline ready",
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        } else null,
    )
}

/**
 * Shared executor for recognition-support callbacks.
 *
 * A new single-thread executor used to be created per call and never shut down, and the call
 * happens on every entry to this screen - navigating in and out N times left N non-daemon
 * platform threads alive in the settings process. One daemon thread, reused, cannot accumulate.
 */
private val recognitionSupportExecutor: java.util.concurrent.ExecutorService by lazy {
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VoiceLanguageSupport").apply { isDaemon = true }
    }
}

/**
 * Fetch supported languages using modern API 33+ checkRecognitionSupport
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun fetchLanguagesModernForSelection(
    context: android.content.Context,
    onResult: (supported: List<String>, offline: List<String>) -> Unit
) {
    val fallbackLanguages = getDefaultLanguageList()

    var speechRecognizer: android.speech.SpeechRecognizer? = null
    try {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)

        recognizer.checkRecognitionSupport(intent, recognitionSupportExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installedLanguages = support.installedOnDeviceLanguages
                val supportedLanguages = support.supportedOnDeviceLanguages
                val allLanguages = (installedLanguages + supportedLanguages).distinct()

                if (BuildConfig.DEBUG) Log.d(TAG, "Modern API: Found ${installedLanguages.size} offline, ${supportedLanguages.size} supported")

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (allLanguages.isNotEmpty()) {
                        onResult(allLanguages, installedLanguages)
                    } else {
                        onResult(fallbackLanguages, emptyList())
                    }
                    recognizer.destroy()
                }
            }

            override fun onError(error: Int) {
                if (BuildConfig.DEBUG) Log.e(TAG, "checkRecognitionSupport error: $error")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    fetchLanguagesLegacyForSelection(context) { languages ->
                        onResult(languages, emptyList())
                    }
                    recognizer.destroy()
                }
            }
        })
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "Error initializing modern language fetch", e)
        // Neither callback will fire, so release the binding here rather than leaking it.
        speechRecognizer?.destroy()
        fetchLanguagesLegacyForSelection(context) { languages ->
            onResult(languages, emptyList())
        }
    }
}

/**
 * Fetch supported languages using legacy broadcast method (pre-API 33)
 */
private fun fetchLanguagesLegacyForSelection(
    context: android.content.Context,
    onResult: (languages: List<String>) -> Unit
) {
    val fallbackLanguages = getDefaultLanguageList()

    try {
        val detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        context.sendOrderedBroadcast(
            detailsIntent,
            null,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                    val extras = getResultExtras(true)
                    val languages = extras?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)

                    if (!languages.isNullOrEmpty()) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Legacy API: Found ${languages.size} languages")
                        onResult(languages.toList())
                    } else {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Legacy API: No languages found, using fallback")
                        onResult(fallbackLanguages)
                    }
                }
            },
            null,
            android.app.Activity.RESULT_OK,
            null,
            null
        )
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "Error fetching languages via broadcast", e)
        onResult(fallbackLanguages)
    }
}

private fun getDefaultLanguageList(): List<String> = listOf(
    "en-US", "en-GB", "en-AU", "en-IN", "en-CA",
    "es-ES", "es-MX", "es-US", "es-AR",
    "fr-FR", "fr-CA",
    "de-DE", "de-AT", "de-CH",
    "it-IT",
    "pt-BR", "pt-PT",
    "ja-JP",
    "ko-KR",
    "zh-CN", "zh-TW", "zh-HK",
    "ru-RU",
    "ar-SA", "ar-EG",
    "hi-IN",
    "nl-NL", "nl-BE",
    "pl-PL",
    "tr-TR",
    "vi-VN",
    "th-TH",
    "id-ID",
    "ms-MY"
)
