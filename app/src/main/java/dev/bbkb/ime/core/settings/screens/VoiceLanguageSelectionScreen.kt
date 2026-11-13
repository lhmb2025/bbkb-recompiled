package dev.bbkb.ime.core.settings.screens

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.LocalInGroup
import dev.bbkb.ime.core.settings.ui.GroupEdge
import dev.bbkb.ime.core.settings.ui.groupEdge
import dev.bbkb.ime.core.settings.ui.LocalSpacing
import dev.bbkb.ime.core.settings.ui.PreferenceCategory
import dev.bbkb.ime.core.settings.ui.PreferenceItem
import dev.bbkb.ime.core.settings.ui.SearchableSettingsScaffold
import dev.bbkb.ime.core.settings.ui.rememberListSearchState
import dev.bbkb.ime.core.settings.util.SettingsManager
import android.view.inputmethod.InputMethodManager
import dev.bbkb.ime.keyboard.inputboard.voice.InstalledVoiceRecognizers
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguageTags
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceRecognizerChoice
import java.text.Collator
import java.util.Locale

private const val TAG = "VoiceLanguageSelection"

/** The preference both voice screens read and write. */
internal const val VOICE_LANGUAGE_PREF = "voice_input_language_list"

/** A language code in its own language ("Français (France)" for `fr-FR`); the raw code if unparseable. */
internal fun voiceLanguageDisplayName(code: String): String = try {
    val locale = Locale.forLanguageTag(code.replace("_", "-"))
    val name = locale.getDisplayName(locale)
    if (name.isBlank()) code else name.replaceFirstChar { it.uppercase() }
} catch (e: Exception) {
    code
}

/**
 * One picker row: recogniser tag, native name, the name in the phone's language when different
 * (second line and search key), and whether the recogniser has it offline.
 */
internal data class VoiceLanguageRow(
    val code: String,
    val name: String,
    val localName: String?,
    val offline: Boolean,
)

/** [code]'s name in the phone's language, or null when that is what [nativeName] already says. */
internal fun voiceLanguageLocalName(code: String, nativeName: String, phone: Locale = Locale.getDefault()): String? = try {
    Locale.forLanguageTag(code.replace("_", "-")).getDisplayName(phone)
        .replaceFirstChar { it.uppercase() }
        .takeIf { it.isNotBlank() && !it.equals(nativeName, ignoreCase = true) }
} catch (e: Exception) {
    null
}

private fun sameVoiceTag(a: String, b: String) = a.replace("_", "-").equals(b.replace("_", "-"), ignoreCase = true)

/**
 * The "Your languages" rows: the current choice, then the recogniser's tag for each enabled
 * keyboard in its own spelling (bare "fr" pins "fr-FR"). Tags the recogniser lacks are left out.
 */
internal fun pinnedVoiceLanguages(
    selected: String,
    keyboardTags: List<String>,
    available: List<String>,
): List<String> {
    val chosen = available.firstOrNull { sameVoiceTag(it, selected) }
    val keyboards = keyboardTags.mapNotNull { tag ->
        VoiceLanguageTags.bestMatch(tag, VoiceLanguageTags.likelyRegion(tag), available)
    }
    return (listOfNotNull(chosen) + keyboards).distinct()
}

/** The recogniser tags of the keyboards the user has switched on, without duplicates. */
internal fun enabledKeyboardLanguageTags(context: android.content.Context): List<String> = try {
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val self = imm?.inputMethodList?.firstOrNull { it.packageName == context.packageName }
    if (imm == null || self == null) emptyList()
    else imm.getEnabledInputMethodSubtypeList(self, true)
        .mapNotNull { VoiceLanguageTags.normalise(it.locale) }.distinct()
} catch (e: Exception) {
    emptyList()
}

/**
 * The voice-recognition languages this device offers (often over a hundred). Searchable by native
 * name, phone-language name or tag; the current choice and the keyboards' languages repeat first
 * under "Your languages". Offline languages carry the Voice chip, except where the device cannot
 * tell (pre-API-33 discovery, the fallback list).
 */
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

    var availableLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var offlineLanguages by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    // Ask the recogniser dictation will use (the "Speech recognizer" setting), not always the default.
    val recognizer = remember { InstalledVoiceRecognizers.resolve(context) }
    val keyboardTags = remember { enabledKeyboardLanguageTags(context) }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fetchLanguagesModernForSelection(context, recognizer) { supported, offline ->
                availableLanguages = supported
                offlineLanguages = offline
                isLoading = false
            }
        } else {
            fetchLanguagesLegacyForSelection(context, recognizer) { languages ->
                availableLanguages = languages
                offlineLanguages = emptyList()
                isLoading = false
            }
        }
    }

    val rows: List<VoiceLanguageRow> = remember(availableLanguages, offlineLanguages) {
        val collator = Collator.getInstance()
        availableLanguages.distinct().map { code ->
            val name = voiceLanguageDisplayName(code)
            VoiceLanguageRow(
                code = code,
                name = name,
                localName = voiceLanguageLocalName(code, name),
                offline = offlineLanguages.any { sameVoiceTag(it, code) },
            )
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
    val pinned: List<VoiceLanguageRow> = remember(rows, keyboardTags) {
        // Read once: a choice here closes the screen, so the pins never move under a tap.
        pinnedVoiceLanguages(selectedLanguage, keyboardTags, rows.map { it.code })
            .mapNotNull { code -> rows.firstOrNull { it.code == code } }
    }

    val search = rememberListSearchState()
    val matching = rows.filter { search.matches(it.name, it.localName, it.code) }

    fun choose(row: VoiceLanguageRow) {
        selectedLanguage = row.code
        prefs.edit().putString(VOICE_LANGUAGE_PREF, row.code).apply()
        onNavigateBack()
    }

    SearchableSettingsScaffold(
        title = context.getString(R.string.settings_voice_input_lang_title),
        onNavigateBack = onNavigateBack,
        search = search,
        searchHint = stringResource(R.string.languages_hub_search),
    ) { paddingValues ->
        when {
            isLoading -> CenteredMessage(paddingValues) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_voice_lang_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            availableLanguages.isEmpty() -> CenteredMessage(paddingValues) {
                Text(
                    text = stringResource(R.string.settings_voice_lang_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_voice_lang_none_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            search.filtering && matching.isEmpty() -> CenteredMessage(paddingValues) {
                Text(
                    text = stringResource(R.string.settings_common_no_matches),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Each section is one group; rows paint themselves as cards with the group inset so the headers
            // keep page-level alignment.
            else -> CompositionLocalProvider(LocalInGroup provides true) {
                // While searching: the matches alone, no headers and no repeats.
                val sections: List<Triple<String, String?, List<VoiceLanguageRow>>> = if (search.filtering) {
                    listOf(Triple("found", null, matching))
                } else {
                    listOfNotNull(
                        Triple("yours", stringResource(R.string.settings_voice_lang_yours), pinned)
                            .takeIf { pinned.isNotEmpty() },
                        Triple("all", stringResource(R.string.settings_voice_lang_all), rows),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.groupGap)
                ) {
                    sections.forEach { (section, header, languages) ->
                        if (header != null) {
                            item(key = "$section-header") { SectionHeader(header) }
                        }
                        itemsIndexed(languages, key = { _, row -> section + "-" + row.code }) { index, row ->
                            LanguageListItem(
                                row = row,
                                isSelected = sameVoiceTag(row.code, selectedLanguage),
                                edge = groupEdge(index, languages.size),
                                onClick = { choose(row) },
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

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

@Composable
private fun SectionHeader(title: String) {
    PreferenceCategory(title = title)
}

/** One language on [PreferenceItem]: radio, native name, local name when different, Voice chip when offline. */
@Composable
private fun LanguageListItem(
    row: VoiceLanguageRow,
    isSelected: Boolean,
    edge: GroupEdge,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.padding(horizontal = LocalSpacing.current.groupInset)) {
        PreferenceItem(
            title = row.name,
            summary = row.localName,
            leading = { RadioButton(selected = isSelected, onClick = null) },
            titleAccessory = if (row.offline) {
                { OfflineVoiceChip() }
            } else null,
            modifier = Modifier.selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
            groupEdge = edge,
        )
    }
}

/**
 * API 33+ checkRecognitionSupport, asked of the recogniser the "Speech recognizer" setting chooses.
 * Callbacks run on the main thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun fetchLanguagesModernForSelection(
    context: android.content.Context,
    selection: VoiceRecognizerChoice.Selection,
    onResult: (supported: List<String>, offline: List<String>) -> Unit
) {
    val fallbackLanguages = fallbackLanguages(context, selection)

    var speechRecognizer: android.speech.SpeechRecognizer? = null
    try {
        val recognizer = InstalledVoiceRecognizers.create(context, selection)
        speechRecognizer = recognizer
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)

        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                val installedLanguages = support.installedOnDeviceLanguages
                val supportedLanguages = support.supportedOnDeviceLanguages
                // Include network languages, or a recogniser with few on-device models shows a short list.
                // Anything not installed lands under "Requires Network".
                val onlineLanguages = support.onlineLanguages
                val allLanguages = (installedLanguages + supportedLanguages + onlineLanguages).distinct()

                if (BuildConfig.DEBUG) Log.d(TAG, "Modern API: Found ${installedLanguages.size} offline, ${supportedLanguages.size} supported, ${onlineLanguages.size} online")

                if (allLanguages.isNotEmpty()) {
                    rememberLanguages(context, selection, allLanguages)
                    onResult(allLanguages, installedLanguages)
                } else {
                    onResult(fallbackLanguages, emptyList())
                }
                recognizer.destroy()
            }

            override fun onError(error: Int) {
                if (BuildConfig.DEBUG) Log.e(TAG, "checkRecognitionSupport error: $error")
                fetchLanguagesLegacyForSelection(context, selection) { languages ->
                    onResult(languages, emptyList())
                }
                recognizer.destroy()
            }
        })
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.e(TAG, "Error initializing modern language fetch", e)
        // Neither callback will fire, so release the binding here rather than leaking it.
        speechRecognizer?.destroy()
        fetchLanguagesLegacyForSelection(context, selection) { languages ->
            onResult(languages, emptyList())
        }
    }
}

/**
 * Pre-API-33 broadcast discovery. With an app chosen as the speech recognizer the broadcast goes
 * to that app alone.
 */
private fun fetchLanguagesLegacyForSelection(
    context: android.content.Context,
    selection: VoiceRecognizerChoice.Selection,
    onResult: (languages: List<String>) -> Unit
) {
    val fallbackLanguages = fallbackLanguages(context, selection)

    try {
        val detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
        if (selection.kind == VoiceRecognizerChoice.Kind.COMPONENT) {
            detailsIntent.setPackage(selection.packageName)
        }
        context.sendOrderedBroadcast(
            detailsIntent,
            null,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                    val extras = getResultExtras(true)
                    val languages = extras?.getStringArrayList(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES)

                    if (!languages.isNullOrEmpty()) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Legacy API: Found ${languages.size} languages")
                        rememberLanguages(context, selection, languages.toList())
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

/**
 * The list when discovery fails: this recogniser's last real answer or, before there is one, every
 * language this keyboard declares a subtype for plus the regional list below.
 */
internal fun fallbackLanguages(
    context: android.content.Context,
    selection: VoiceRecognizerChoice.Selection,
): List<String> = composeFallback(cachedLanguages(context, selection), keyboardLanguageTags(context))

/** Pure part of [fallbackLanguages], for tests: a remembered discovery wins outright. */
internal fun composeFallback(cached: List<String>, keyboard: List<String>): List<String> =
    if (cached.isNotEmpty()) cached else (keyboard + getDefaultLanguageList()).distinct().sorted()

/** Per-recogniser cache ([VoiceRecognizerChoice.languageCacheKey]), so a switch never shows the previous one's list. */
private fun cachedLanguages(
    context: android.content.Context,
    selection: VoiceRecognizerChoice.Selection,
): List<String> = try {
    PrefsManager.getPrefs(context).getString(VoiceRecognizerChoice.languageCacheKey(selection.id()), null)
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
} catch (e: Exception) {
    emptyList()
}

/** Remember a successful discovery so a later failure still shows this recogniser's real list. */
private fun rememberLanguages(
    context: android.content.Context,
    selection: VoiceRecognizerChoice.Selection,
    languages: List<String>,
) {
    if (languages.isEmpty()) return
    try {
        PrefsManager.getPrefs(context).edit()
            .putString(VoiceRecognizerChoice.languageCacheKey(selection.id()), languages.joinToString(","))
            .apply()
    } catch (e: Exception) {
        if (BuildConfig.DEBUG) Log.w(TAG, "Could not remember the voice language list", e)
    }
}

/** The recogniser tags of every keyboard this app declares, in method.xml order, without duplicates. */
internal fun keyboardLanguageTags(context: android.content.Context): List<String> = try {
    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val self = imm?.inputMethodList?.firstOrNull { it.packageName == context.packageName }
    if (self == null) emptyList()
    else (0 until self.subtypeCount).mapNotNull { VoiceLanguageTags.normalise(self.getSubtypeAt(it).locale) }.distinct()
} catch (e: Exception) {
    emptyList()
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
