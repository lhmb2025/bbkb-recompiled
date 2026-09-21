package dev.bbkb.ime.core.settings.screens

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import dev.bbkb.ime.core.settings.ui.EditTextPreference
import dev.bbkb.ime.core.settings.ui.LocalSpacing

/**
 * One slideboard quick phrase.
 *
 * [prefKey] is formed here and nowhere else, so the row that reads a phrase is by construction the
 * row that writes it. These keys are persisted user state — `SettingsValues` reads them back by
 * name to fill the slideboard — so they move verbatim or not at all.
 *
 * [defaultRes] is what this screen shows for an unset key: the same `R.string.pref_quick_phrase_N_default`
 * that `SettingsValues` falls back to, so the phrase shown here is the phrase the slideboard inserts.
 */
private class QuickPhrase(index: Int, @StringRes val titleRes: Int, @StringRes val defaultRes: Int) {
    val prefKey: String = "quick_phrase_$index"
}

private val QUICK_PHRASES = listOf(
    QuickPhrase(1, R.string.settings_quick_phrase_1, R.string.pref_quick_phrase_1_default),
    QuickPhrase(2, R.string.settings_quick_phrase_2, R.string.pref_quick_phrase_2_default),
    QuickPhrase(3, R.string.settings_quick_phrase_3, R.string.pref_quick_phrase_3_default),
    QuickPhrase(4, R.string.settings_quick_phrase_4, R.string.pref_quick_phrase_4_default),
    QuickPhrase(5, R.string.settings_quick_phrase_5, R.string.pref_quick_phrase_5_default),
)

/**
 * Quick Phrases Screen
 * Configure the 5 customizable quick phrases for slideboard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPhrasesScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { PrefsManager.getPrefs(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_quick_phrases_screen_title)) },
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
            QUICK_PHRASES.forEach { phrase ->
                QuickPhraseRow(prefs, phrase, context.getString(phrase.titleRes), context.getString(phrase.defaultRes))
            }

            Spacer(modifier = Modifier.height(spacing.extraLarge))
        }
    }
}

@Composable
private fun QuickPhraseRow(
    prefs: SharedPreferences,
    phrase: QuickPhrase,
    title: String,
    default: String
) {
    var value by remember {
        mutableStateOf(prefs.getString(phrase.prefKey, default) ?: default)
    }

    EditTextPreference(
        title = title,
        value = value,
        onValueChange = { newValue ->
            value = newValue
            prefs.edit().putString(phrase.prefKey, newValue).apply()
        }
    )
}
