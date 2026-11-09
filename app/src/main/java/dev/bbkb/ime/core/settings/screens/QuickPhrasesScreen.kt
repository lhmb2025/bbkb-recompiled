package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.ui.Custom
import dev.bbkb.ime.core.settings.ui.EditTextPreference
import dev.bbkb.ime.core.settings.ui.SettingsScreenHost

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
 * The five phrase keys in the order this screen lists them — what a layouts export and import
 * (`LayoutsBundle`) read and write, so the keys are still formed only here.
 */
internal val QUICK_PHRASE_KEYS: List<String> get() = QUICK_PHRASES.map { it.prefKey }

/**
 * The five phrases as the slideboard will insert them: the stored value, or the default
 * `SettingsValues` falls back to. Read here so every screen that names a phrase agrees.
 */
internal fun readQuickPhrases(context: Context, prefs: SharedPreferences): List<String> =
    QUICK_PHRASES.map { phrase ->
        context.getString(phrase.defaultRes).let { prefs.getString(phrase.prefKey, it) ?: it }
    }

/**
 * Quick Phrases Screen
 * Configure the 5 customizable quick phrases for slideboard
 */
@Composable
fun QuickPhrasesScreen(
    onNavigateBack: () -> Unit
) {
    SettingsScreenHost(
        R.string.settings_quick_phrases_screen_title,
        onNavigateBack,
        QUICK_PHRASES.map { phrase ->
            Custom(inGroup = true) { env -> QuickPhraseRow(env.prefs, phrase) }
        },
    )
}

@Composable
private fun QuickPhraseRow(prefs: SharedPreferences, phrase: QuickPhrase) {
    val default = stringResource(phrase.defaultRes)
    var value by remember {
        mutableStateOf(prefs.getString(phrase.prefKey, default) ?: default)
    }

    EditTextPreference(
        title = stringResource(phrase.titleRes),
        value = value,
        // A blank phrase would be an empty tile on the slideboard.
        allowBlank = false,
        capitalization = KeyboardCapitalization.Sentences,
        onValueChange = { newValue ->
            value = newValue
            prefs.edit().putString(phrase.prefKey, newValue).apply()
        }
    )
}
